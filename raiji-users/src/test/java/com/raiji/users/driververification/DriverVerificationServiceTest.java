package com.raiji.users.driververification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.raiji.users.service.UserCapabilitiesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Infosimples e armazenamento simulados (mocks): nenhuma chamada real. */
class DriverVerificationServiceTest {

    private static final String SUB = "user-1";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneId.of("UTC"));

    private DriverApplicationRepository repository;
    private CnhImageStorage storage;
    private InfosimplesClient infosimples;
    private UserCapabilitiesService capabilities;
    private ApplicationEventPublisher events;
    private DriverVerificationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(DriverApplicationRepository.class);
        storage = mock(CnhImageStorage.class);
        infosimples = mock(InfosimplesClient.class);
        capabilities = mock(UserCapabilitiesService.class);
        events = mock(ApplicationEventPublisher.class);
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any())).thenReturn(CLOCK);
        when(infosimples.configured()).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> {
            DriverApplication app = inv.getArgument(0);
            if (app.getId() == null) ReflectionTestUtils.setField(app, "id", UUID.randomUUID());
            return app;
        });
        service = new DriverVerificationService(repository, storage, infosimples, capabilities, events, clock);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(SUB).claim("name", "Maria Souza").build();
    }

    private static MockMultipartFile foto(String contentType) {
        return new MockMultipartFile("image", "cnh.jpg", contentType, new byte[] {1, 2, 3});
    }

    private DriverApplication emAnalise() {
        DriverApplication app = new DriverApplication();
        ReflectionTestUtils.setField(app, "id", UUID.randomUUID());
        app.setSub(SUB);
        app.setStatus(DriverApplicationStatus.PROCESSING);
        app.setAccountName("Maria Souza");
        app.setImageKey("cnh/user-1/abc");
        when(repository.findById(app.getId())).thenReturn(Optional.of(app));
        when(storage.get("cnh/user-1/abc")).thenReturn(new byte[] {1, 2, 3});
        return app;
    }

    private static InfosimplesClient.Response ocrOk() {
        return new InfosimplesClient.Response(200, "ok", List.of(Map.of("tipo", "cnh_frente", "campos", Map.of(
                "nome", Map.of("valor", "MARIA DA SILVA SOUZA", "score", 0.9),
                "cpf", Map.of("valor", "123.456.789-01", "score", 0.9),
                "registro", Map.of("valor", "98765432100", "score", 0.9)))), List.of(), true);
    }

    private static InfosimplesClient.Response validacao(int code, String situacao) {
        return new InfosimplesClient.Response(code, "msg", code != 200 ? List.of() : List.of(Map.of(
                "situacao", situacao, "validade_data", "10/11/2030", "categoria", "B",
                "nome", "MARIA DA SILVA SOUZA")), List.of(), true);
    }

    // ---------------------------------------------------------------- envio

    @Test
    @DisplayName("envio: grava pedido PROCESSING, guarda a foto e publica o evento (sem gravar o código)")
    void envio() {
        DriverApplicationResponse r = service.submit(jwt(), foto("image/jpeg"), "123 456 789 01");

        assertThat(r.status()).isEqualTo("PROCESSING");
        verify(storage).put(org.mockito.ArgumentMatchers.startsWith("cnh/user-1/"), any(), eq("image/jpeg"));
        ArgumentCaptor<DriverApplicationSubmittedEvent> evento = ArgumentCaptor.forClass(DriverApplicationSubmittedEvent.class);
        verify(events).publishEvent(evento.capture());
        assertThat(evento.getValue().securityCode()).isEqualTo("12345678901");
        assertThat(evento.getValue().toString()).doesNotContain("12345678901");
    }

    @Test
    @DisplayName("envio: sem token da Infosimples -> 503; sem foto -> 400; formato -> 415; código -> 400")
    void validacoesDeEnvio() {
        when(infosimples.configured()).thenReturn(false);
        assertStatus(() -> service.submit(jwt(), foto("image/jpeg"), "12345678901"), HttpStatus.SERVICE_UNAVAILABLE);

        when(infosimples.configured()).thenReturn(true);
        assertStatus(() -> service.submit(jwt(), null, "12345678901"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.submit(jwt(), foto("application/pdf"), "12345678901"), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertStatus(() -> service.submit(jwt(), foto("image/png"), "abc"), HttpStatus.BAD_REQUEST);
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("envio com verificação já em andamento -> 409")
    void emAndamento() {
        when(repository.existsBySubAndStatusAndCreatedAtAfter(eq(SUB), eq(DriverApplicationStatus.PROCESSING), any()))
                .thenReturn(true);

        assertStatus(() -> service.submit(jwt(), foto("image/png"), "12345678901"), HttpStatus.CONFLICT);
    }

    // ---------------------------------------------------------- processamento

    @Test
    @DisplayName("aprovado: dá a role DRIVER, guarda categoria/validade e APAGA a foto")
    void aprovado() {
        DriverApplication app = emAnalise();
        when(infosimples.ocrCnh(any())).thenReturn(ocrOk());
        when(infosimples.validarCnh("12345678901", "98765432100", "55566677788", "MARIA DA SILVA SOUZA"))
                .thenReturn(validacao(200, "válida"));

        service.process(app.getId(), "55566677788");

        assertThat(app.getStatus()).isEqualTo(DriverApplicationStatus.APPROVED);
        assertThat(app.getCategoria()).isEqualTo("B");
        assertThat(app.getImageKey()).isNull();
        verify(capabilities).grantVerifiedDriver(SUB);
        verify(storage).delete("cnh/user-1/abc");
    }

    @Test
    @DisplayName("CNH não encontrada no SENATRAN: REJECTED, sem role, foto apagada")
    void rejeitado() {
        DriverApplication app = emAnalise();
        when(infosimples.ocrCnh(any())).thenReturn(ocrOk());
        when(infosimples.validarCnh(anyString(), anyString(), anyString(), anyString())).thenReturn(validacao(612, null));

        service.process(app.getId(), "55566677788");

        assertThat(app.getStatus()).isEqualTo(DriverApplicationStatus.REJECTED);
        assertThat(app.getReason()).contains("Não encontramos");
        verify(capabilities, never()).grantVerifiedDriver(anyString());
        verify(storage).delete("cnh/user-1/abc");
    }

    @Test
    @DisplayName("foto ilegível: REJECTED sem consultar o SENATRAN (economiza a consulta)")
    void fotoIlegivel() {
        DriverApplication app = emAnalise();
        when(infosimples.ocrCnh(any())).thenReturn(new InfosimplesClient.Response(611, "x", List.of(), List.of(), true));

        service.process(app.getId(), "55566677788");

        assertThat(app.getStatus()).isEqualTo(DriverApplicationStatus.REJECTED);
        verify(infosimples, never()).validarCnh(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("falha inesperada (rede, Keycloak...): ERROR e a foto é apagada mesmo assim")
    void falhaInesperada() {
        DriverApplication app = emAnalise();
        when(infosimples.ocrCnh(any())).thenReturn(ocrOk());
        when(infosimples.validarCnh(anyString(), anyString(), anyString(), anyString())).thenReturn(validacao(200, "válida"));
        doThrow(new RuntimeException("keycloak fora")).when(capabilities).grantVerifiedDriver(SUB);

        service.process(app.getId(), "55566677788");

        assertThat(app.getStatus()).isEqualTo(DriverApplicationStatus.ERROR);
        verify(storage).delete("cnh/user-1/abc");
    }

    @Test
    @DisplayName("pedido que não está em análise não é processado de novo")
    void naoReprocessa() {
        DriverApplication app = emAnalise();
        app.setStatus(DriverApplicationStatus.APPROVED);

        service.process(app.getId(), "55566677788");

        verify(infosimples, never()).ocrCnh(any());
    }

    // ---------------------------------------------------------------- status

    @Test
    @DisplayName("status: NONE quando nunca enviou; em análise há muito tempo vira ERROR")
    void status() {
        when(repository.findTopBySubOrderByCreatedAtDesc(SUB)).thenReturn(Optional.empty());
        assertThat(service.latest(SUB).status()).isEqualTo("NONE");

        DriverApplication travado = new DriverApplication();
        ReflectionTestUtils.setField(travado, "id", UUID.randomUUID());
        travado.setStatus(DriverApplicationStatus.PROCESSING);
        ReflectionTestUtils.setField(travado, "createdAt", Instant.parse("2026-11-10T11:00:00Z"));
        when(repository.findTopBySubOrderByCreatedAtDesc(SUB)).thenReturn(Optional.of(travado));

        assertThat(service.latest(SUB).status()).isEqualTo("ERROR");
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(status);
    }
}
