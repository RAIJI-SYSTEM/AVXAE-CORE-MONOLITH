package com.raiji.users.driververification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.raiji.users.mail.DriverApprovalMailer;
import com.raiji.users.service.UserCapabilitiesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verificação da CNH para virar motorista:
 * <ol>
 *   <li>recebe a foto + código de segurança; guarda a foto (temporária) — 202</li>
 *   <li>em segundo plano: OCR da foto -> validação no SENATRAN -> regras ({@link CnhDecision})</li>
 *   <li>aprovado: a conta recebe a role DRIVER e um e-mail (Resend). Em qualquer
 *       desfecho a foto é apagada.</li>
 * </ol>
 */
@Service
public class DriverVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DriverVerificationService.class);

    /** Verificação "em andamento" há mais tempo que isso é considerada travada */
    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    static final Set<String> ACCEPTED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Pattern SECURITY_CODE = Pattern.compile("\\d{6,15}");

    private final DriverApplicationRepository repository;
    private final CnhImageStorage storage;
    private final InfosimplesClient infosimples;
    private final UserCapabilitiesService capabilities;
    private final ApplicationEventPublisher events;
    private final DriverApprovalMailer approvalMailer;
    private final Clock clock;

    public DriverVerificationService(
            DriverApplicationRepository repository,
            CnhImageStorage storage,
            InfosimplesClient infosimples,
            UserCapabilitiesService capabilities,
            ApplicationEventPublisher events,
            DriverApprovalMailer approvalMailer,
            ObjectProvider<Clock> clock) {
        this.repository = repository;
        this.storage = storage;
        this.infosimples = infosimples;
        this.capabilities = capabilities;
        this.events = events;
        this.approvalMailer = approvalMailer;
        this.clock = clock.getIfAvailable(Clock::systemDefaultZone);
    }

    @Transactional
    public DriverApplicationResponse submit(Jwt jwt, MultipartFile image, String securityCode) {
        if (!infosimples.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A verificação de CNH está indisponível no momento.");
        }
        if (image == null || image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie a foto da CNH (campo \"image\").");
        }
        String contentType = image.getContentType() == null ? "" : image.getContentType().toLowerCase();
        if (!ACCEPTED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Formato não aceito: envie a foto em JPG, PNG ou WebP.");
        }
        String code = securityCode == null ? "" : securityCode.replaceAll("\\s", "");
        if (!SECURITY_CODE.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Código de segurança da CNH inválido: informe apenas os números.");
        }

        String sub = jwt.getSubject();
        if (repository.existsBySubAndStatusAndCreatedAtAfter(
                sub, DriverApplicationStatus.PROCESSING, Instant.now(clock).minus(STALE_AFTER))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já existe uma verificação de CNH em andamento. Aguarde o resultado.");
        }

        DriverApplication application = new DriverApplication();
        application.setSub(sub);
        application.setStatus(DriverApplicationStatus.PROCESSING);
        application.setAccountName(accountName(jwt));
        application.setImageKey("cnh/" + sub + "/" + UUID.randomUUID());
        repository.save(application);

        try {
            storage.put(application.getImageKey(), image.getBytes(), contentType);
        } catch (Exception e) {
            log.warn("Falha ao guardar a foto da CNH (pedido {}): {}", application.getId(), e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível receber a foto agora. Tente novamente.");
        }

        // Processado depois do commit, em segundo plano (DriverApplicationProcessor)
        events.publishEvent(new DriverApplicationSubmittedEvent(application.getId(), code));
        return DriverApplicationResponse.from(application);
    }

    /** Executado em segundo plano. Nunca relança: todo desfecho vira um status. */
    public void process(UUID applicationId, String securityCode) {
        DriverApplication application = repository.findById(applicationId).orElse(null);
        if (application == null || application.getStatus() != DriverApplicationStatus.PROCESSING) {
            return;
        }
        CnhDecision.Result result;
        try {
            result = verify(application, securityCode);
            if (result.outcome() == CnhDecision.Outcome.APPROVED) {
                capabilities.grantVerifiedDriver(application.getSub());
            }
        } catch (Exception e) {
            log.warn("Verificação da CNH falhou (pedido {}): {}", applicationId, e.getClass().getSimpleName());
            result = CnhDecision.Result.error(CnhDecision.UNAVAILABLE);
        }

        application.setStatus(switch (result.outcome()) {
            case APPROVED -> DriverApplicationStatus.APPROVED;
            case REJECTED -> DriverApplicationStatus.REJECTED;
            case ERROR -> DriverApplicationStatus.ERROR;
        });
        application.setReason(result.reason());
        if (result.categoria() != null) application.setCategoria(result.categoria());
        if (result.validade() != null) application.setValidade(result.validade());
        if (result.cnhName() != null) application.setCnhName(result.cnhName());
        application.setDecidedAt(Instant.now(clock));
        deleteImage(application);
        repository.save(application);
        log.info("Verificação da CNH (pedido {}): {}", applicationId, application.getStatus());

        if (application.getStatus() == DriverApplicationStatus.APPROVED) {
            try {
                approvalMailer.sendApproved(application.getSub(), application.getId(),
                        application.getCategoria(), application.getValidade());
            } catch (Exception e) {
                // O cadastro já está aprovado: falha no e-mail não desfaz nada
                log.warn("Não foi possível enviar o e-mail de aprovação (pedido {}): {}",
                        applicationId, e.getClass().getSimpleName());
            }
        }
    }

    private CnhDecision.Result verify(DriverApplication application, String securityCode) {
        byte[] image = storage.get(application.getImageKey());

        InfosimplesClient.Response ocr = infosimples.ocrCnh(image);
        application.setOcrCode(ocr.code());
        CnhDecision.OcrOutcome read = CnhDecision.readOcr(ocr);
        if (!read.ok()) {
            return read.failure();
        }
        CnhDecision.OcrData data = read.data();
        application.setCpf(data.cpf());
        application.setRegistro(data.registro());
        application.setCnhName(data.nome());

        InfosimplesClient.Response validation =
                infosimples.validarCnh(data.cpf(), data.registro(), securityCode, data.nome());
        application.setValidationCode(validation.code());
        return CnhDecision.decide(validation, data, application.getAccountName(), LocalDate.now(clock));
    }

    /** A foto é temporária: sai do armazenamento em qualquer desfecho */
    private void deleteImage(DriverApplication application) {
        if (application.getImageKey() == null) return;
        try {
            storage.delete(application.getImageKey());
            application.setImageKey(null);
        } catch (Exception e) {
            // A regra de expiração do bucket (1 dia) apaga depois
            log.warn("Não foi possível apagar a foto da CNH (pedido {})", application.getId());
        }
    }

    @Transactional
    public DriverApplicationResponse latest(String sub) {
        return repository.findTopBySubOrderByCreatedAtDesc(sub)
                .map(application -> {
                    if (application.getStatus() == DriverApplicationStatus.PROCESSING
                            && application.getCreatedAt().isBefore(Instant.now(clock).minus(STALE_AFTER))) {
                        application.setStatus(DriverApplicationStatus.ERROR);
                        application.setReason(CnhDecision.UNAVAILABLE);
                        application.setDecidedAt(Instant.now(clock));
                        repository.save(application);
                    }
                    return DriverApplicationResponse.from(application);
                })
                .orElseGet(DriverApplicationResponse::none);
    }

    private static String accountName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) return name;
        String given = jwt.getClaimAsString("given_name");
        String family = jwt.getClaimAsString("family_name");
        return ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
    }
}
