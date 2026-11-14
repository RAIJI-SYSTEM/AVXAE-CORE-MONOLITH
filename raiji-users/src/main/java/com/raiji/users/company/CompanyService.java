package com.raiji.users.company;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.raiji.users.company.CompanyDtos.CnpjPreview;
import com.raiji.users.company.CompanyDtos.CompanyDetailResponse;
import com.raiji.users.company.CompanyDtos.CompanyResponse;
import com.raiji.users.company.CompanyDtos.InviteResponse;
import com.raiji.users.company.CompanyDtos.MemberResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Empresas (PJ):
 * <ul>
 *   <li>Cadastro pelo CNPJ: consulta a Receita (BrasilAPI); só CNPJ ATIVO. Quem cadastra vira OWNER.</li>
 *   <li>Vários gestores: qualquer membro ativo convida por e-mail; a pessoa aceita no app
 *       entrando com aquele e-mail (verificado).</li>
 *   <li>Qualquer membro ativo cria pedidos e aprova pagamentos; o OWNER também remove membros.</li>
 *   <li>A empresa não tem frota/funcionários motoristas: quem dirige é sempre um motorista da plataforma.</li>
 * </ul>
 */
@Service
public class CompanyService {

    private static final List<MemberStatus> OPEN = List.of(MemberStatus.INVITED, MemberStatus.ACTIVE);

    private final CompanyRepository companies;
    private final CompanyMemberRepository members;
    private final CnpjLookup cnpjLookup;
    private final CompanyInviteMailer inviteMailer;

    public CompanyService(CompanyRepository companies, CompanyMemberRepository members,
                          CnpjLookup cnpjLookup, CompanyInviteMailer inviteMailer) {
        this.companies = companies;
        this.members = members;
        this.cnpjLookup = cnpjLookup;
        this.inviteMailer = inviteMailer;
    }

    /** Prévia: o que a Receita diz desse CNPJ (para a pessoa confirmar antes de cadastrar). */
    @Transactional(readOnly = true)
    public CnpjPreview preview(String rawCnpj) {
        String cnpj = validCnpj(rawCnpj);
        CnpjInfo info = consultar(cnpj);
        return new CnpjPreview(cnpj, Cnpj.format(cnpj), info.legalName(), info.tradeName(),
                info.mei() ? CompanyKind.MEI : CompanyKind.EMPRESA, info.registrationStatus(), info.active(),
                info.mainActivity(), info.city(), info.state(),
                companies.findByCnpjAndDeletedAtIsNull(cnpj).isPresent());
    }

    @Transactional
    public CompanyResponse register(Jwt jwt, String rawCnpj) {
        String cnpj = validCnpj(rawCnpj);
        if (companies.findByCnpjAndDeletedAtIsNull(cnpj).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta empresa já está cadastrada no Raiji. Peça a um dos gestores para convidar você.");
        }
        String email = verifiedEmail(jwt);
        CnpjInfo info = consultar(cnpj);
        if (!info.active()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "O CNPJ não está ativo na Receita Federal (situação: "
                            + (info.registrationStatus() == null ? "desconhecida" : info.registrationStatus()) + ").");
        }

        Company company = new Company();
        company.setCnpj(cnpj);
        company.setLegalName(info.legalName() == null ? "Empresa " + Cnpj.format(cnpj) : info.legalName());
        company.setTradeName(info.tradeName());
        company.setKind(info.mei() ? CompanyKind.MEI : CompanyKind.EMPRESA);
        company.setRegistrationStatus(info.registrationStatus());
        company.setMainActivity(truncate(info.mainActivity(), 255));
        company.setCity(info.city());
        company.setState(info.state() == null ? null : info.state().toUpperCase(Locale.ROOT));
        company.setVerifiedAt(Instant.now());
        company = companies.save(company);

        CompanyMember owner = new CompanyMember();
        owner.setCompanyId(company.getId());
        owner.setUserSub(jwt.getSubject());
        owner.setEmail(email);
        owner.setDisplayName(jwt.getClaimAsString("name"));
        owner.setRole(MemberRole.OWNER);
        owner.setStatus(MemberStatus.ACTIVE);
        owner.setJoinedAt(Instant.now());
        members.save(owner);

        return CompanyResponse.of(company, MemberRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<CompanyResponse> mine(String userSub) {
        List<CompanyMember> minhas = members.findByUserSubAndStatus(userSub, MemberStatus.ACTIVE);
        Map<UUID, Company> porId = companies.findAllById(minhas.stream().map(CompanyMember::getCompanyId).toList())
                .stream().filter(c -> c.getDeletedAt() == null)
                .collect(Collectors.toMap(Company::getId, Function.identity()));
        return minhas.stream().filter(m -> porId.containsKey(m.getCompanyId()))
                .map(m -> CompanyResponse.of(porId.get(m.getCompanyId()), m.getRole()))
                .sorted(Comparator.comparing(CompanyResponse::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyDetailResponse get(UUID companyId, String userSub) {
        Company company = companies.findById(companyId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);
        CompanyMember eu = members.findByCompanyIdAndUserSubAndStatus(companyId, userSub, MemberStatus.ACTIVE)
                .orElseThrow(this::naoEncontrada);
        List<MemberResponse> lista = members.findByCompanyIdAndStatusIn(companyId, OPEN).stream()
                .sorted(Comparator.comparing((CompanyMember m) -> m.getRole() != MemberRole.OWNER)
                        .thenComparing(m -> m.getStatus() != MemberStatus.ACTIVE)
                        .thenComparing(CompanyMember::getEmail))
                .map(m -> MemberResponse.of(m, userSub)).toList();
        return new CompanyDetailResponse(CompanyResponse.of(company, eu.getRole()), lista);
    }

    /** Qualquer membro ativo convida alguém por e-mail. */
    @Transactional
    public MemberResponse invite(UUID companyId, Jwt jwt, String rawEmail, String name) {
        Company company = companies.findById(companyId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);
        if (!members.existsByCompanyIdAndUserSubAndStatus(companyId, jwt.getSubject(), MemberStatus.ACTIVE)) {
            throw naoEncontrada();
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (members.findOpenByEmail(companyId, email, OPEN).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta pessoa já é membro ou já foi convidada.");
        }
        CompanyMember invite = new CompanyMember();
        invite.setCompanyId(companyId);
        invite.setEmail(email);
        invite.setDisplayName(name == null || name.isBlank() ? null : name.trim());
        invite.setRole(MemberRole.MEMBER);
        invite.setStatus(MemberStatus.INVITED);
        invite.setInvitedBy(jwt.getSubject());
        invite.setInvitedAt(Instant.now());
        invite = members.save(invite);
        inviteMailer.sendInvite(invite, company, jwt.getClaimAsString("name"));
        return MemberResponse.of(invite, jwt.getSubject());
    }

    /** Convites pendentes para o e-mail (verificado) de quem está logado. */
    @Transactional(readOnly = true)
    public List<InviteResponse> myInvites(Jwt jwt) {
        String email = emailOrNull(jwt);
        if (email == null) return List.of();
        return members.findByEmailAndStatus(email, MemberStatus.INVITED).stream()
                .map(m -> companies.findById(m.getCompanyId()).filter(c -> c.getDeletedAt() == null)
                        .map(c -> new InviteResponse(m.getId(), c.getId(), c.displayName(), Cnpj.format(c.getCnpj()),
                                c.getKind(), m.getInvitedAt()))
                        .orElse(null))
                .filter(i -> i != null).toList();
    }

    @Transactional
    public CompanyResponse acceptInvite(UUID memberId, Jwt jwt) {
        CompanyMember invite = meuConvite(memberId, jwt);
        if (members.existsByCompanyIdAndUserSubAndStatus(invite.getCompanyId(), jwt.getSubject(), MemberStatus.ACTIVE)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já é membro desta empresa.");
        }
        invite.setUserSub(jwt.getSubject());
        if (invite.getDisplayName() == null) invite.setDisplayName(jwt.getClaimAsString("name"));
        invite.setStatus(MemberStatus.ACTIVE);
        invite.setJoinedAt(Instant.now());
        members.save(invite);
        Company company = companies.findById(invite.getCompanyId()).orElseThrow(this::naoEncontrada);
        return CompanyResponse.of(company, invite.getRole());
    }

    @Transactional
    public void declineInvite(UUID memberId, Jwt jwt) {
        CompanyMember invite = meuConvite(memberId, jwt);
        invite.setStatus(MemberStatus.DECLINED);
        members.save(invite);
    }

    /**
     * Remove um membro (ou cancela um convite). O dono remove qualquer um; um
     * membro só pode sair (remover a si mesmo). O dono não sai da empresa.
     */
    @Transactional
    public void removeMember(UUID companyId, UUID memberId, String userSub) {
        CompanyMember eu = members.findByCompanyIdAndUserSubAndStatus(companyId, userSub, MemberStatus.ACTIVE)
                .orElseThrow(this::naoEncontrada);
        CompanyMember alvo = members.findById(memberId)
                .filter(m -> m.getCompanyId().equals(companyId) && OPEN.contains(m.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membro não encontrado."));
        boolean souEu = alvo.getId().equals(eu.getId());
        if (souEu && eu.getRole() == MemberRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O dono não pode sair da empresa.");
        }
        if (!souEu && eu.getRole() != MemberRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só o dono da empresa remove outros membros.");
        }
        alvo.setStatus(MemberStatus.REMOVED);
        members.save(alvo);
    }

    // ------------------------------------------------------------------ apoio

    private CompanyMember meuConvite(UUID memberId, Jwt jwt) {
        String email = verifiedEmail(jwt);
        return members.findById(memberId)
                .filter(m -> m.getStatus() == MemberStatus.INVITED && m.getEmail().equalsIgnoreCase(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado."));
    }

    private CnpjInfo consultar(String cnpj) {
        try {
            return cnpjLookup.find(cnpj).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "CNPJ não encontrado na Receita Federal."));
        } catch (CnpjLookup.CnpjLookupUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A consulta de CNPJ está fora do ar. Tente de novo em alguns minutos.");
        }
    }

    private static String validCnpj(String raw) {
        String cnpj = Cnpj.normalize(raw);
        if (!Cnpj.isValid(cnpj)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CNPJ inválido.");
        }
        return cnpj;
    }

    private static String emailOrNull(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        Boolean verified = jwt.getClaimAsBoolean("email_verified");
        if (email == null || email.isBlank() || !Boolean.TRUE.equals(verified)) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String verifiedEmail(Jwt jwt) {
        String email = emailOrNull(jwt);
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Confirme o seu e-mail antes de gerenciar empresas.");
        }
        return email;
    }

    private ResponseStatusException naoEncontrada() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
