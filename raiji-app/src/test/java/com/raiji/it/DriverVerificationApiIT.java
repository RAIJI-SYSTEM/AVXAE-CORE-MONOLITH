package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verificação da CNH ponta a ponta (API real + Keycloak real), com a
 * Infosimples FALSA ({@link FakeInfosimples}) e o armazenamento em memória.
 */
@DisplayName("Verificação da CNH para virar motorista (Infosimples simulada)")
class DriverVerificationApiIT extends RaijiTestSupport {

    private static final byte[] FOTO = "foto-de-cnh".getBytes(StandardCharsets.UTF_8);

    private record Conta(String username, String token, String sub) {}

    private Conta novaConta(String prefixo) {
        String username = prefixo + "-" + System.nanoTime();
        criarUsuario(username);
        String token = obterToken(username, SENHA_NOVOS_USUARIOS);
        String sub = given().auth().oauth2(token).when().get(app("/api/users/me"))
                .then().statusCode(200).extract().path("sub");
        return new Conta(username, token, sub);
    }

    private ValidatableResponse enviar(Conta conta, byte[] foto, String mime, String codigo) {
        return given().auth().oauth2(conta.token())
                .multiPart("image", "cnh.png", foto, mime)
                .multiPart("securityCode", codigo)
                .when().post(app("/api/users/me/driver/cnh"))
                .then();
    }

    /** Envia e espera a verificação (em segundo plano) terminar */
    private Map<String, Object> verificar(Conta conta, byte[] foto, String codigo) {
        enviar(conta, foto, "image/png", codigo).statusCode(202).body("status", equalTo("PROCESSING"));
        for (int i = 0; i < 100; i++) {
            Map<String, Object> status = given().auth().oauth2(conta.token())
                    .when().get(app("/api/users/me/driver/cnh"))
                    .then().statusCode(200).extract().as(Map.class);
            if (!"PROCESSING".equals(status.get("status"))) return status;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("a verificação não terminou");
    }

    private void semFotoGuardada(Conta conta) {
        assertThat(InMemoryCnhImageStorage.FILES.keySet())
                .noneMatch(key -> key.startsWith("cnh/" + conta.sub() + "/"));
    }

    @Test
    @DisplayName("CNH válida: APPROVED, a conta vira motorista (e passageira), a foto é apagada")
    void aprovada() {
        Conta conta = novaConta("cnh-ok");

        Map<String, Object> status = verificar(conta, FOTO, FakeInfosimples.CNH_VALIDA);

        assertThat(status).containsEntry("status", "APPROVED").containsEntry("categoria", "B");
        assertThat(status.get("validade")).isNotNull();
        assertThat(status).doesNotContainKeys("cpf", "registro");
        semFotoGuardada(conta);

        String novoToken = obterToken(conta.username(), SENHA_NOVOS_USUARIOS);
        given().auth().oauth2(novoToken).when().get(app("/api/users/me"))
                .then().statusCode(200)
                .body("driver", is(true))
                .body("passenger", is(true))
                .body("realmRoles", hasItem("DRIVER"));

        // E-mail de aprovação enviado (Resend FALSO) para o e-mail da conta, uma vez por pedido
        String email = conta.username() + "@raiji.com";
        java.util.List<Map<String, String>> enviados = FakeResend.EMAILS.stream()
                .filter(e -> e.get("body").contains("\"to\":[\"" + email + "\"]"))
                .toList();
        assertThat(enviados).hasSize(1);
        assertThat(enviados.get(0).get("path")).isEqualTo("/emails");
        assertThat(enviados.get(0).get("auth")).isEqualTo("Bearer " + FakeResend.API_KEY);
        assertThat(enviados.get(0).get("idempotencyKey")).startsWith("driver-approved-");
        assertThat(enviados.get(0).get("body"))
                .contains("Seu cadastro de motorista no Raiji foi aprovado")
                .contains("Olá, Usuario!");

        // Chegou na Infosimples (falsa) com o token falso e sem login gov.br configurado
        assertThat(FakeInfosimples.CALLS).anySatisfy(call -> {
            assertThat(call).containsEntry("_path", "/api/v2/consultas/senatran/validar-cnh")
                    .containsEntry("token", FakeInfosimples.TOKEN)
                    .containsEntry("codigo_seguranca", FakeInfosimples.CNH_VALIDA)
                    .containsEntry("cpf", "12345678909")
                    .doesNotContainKeys("login_cpf", "login_senha");
        });
    }

    @Test
    @DisplayName("CNH não encontrada no SENATRAN: REJECTED com motivo; não vira motorista")
    void naoEncontrada() {
        Conta conta = novaConta("cnh-612");

        Map<String, Object> status = verificar(conta, FOTO, FakeInfosimples.CNH_NAO_ENCONTRADA);

        assertThat(status).containsEntry("status", "REJECTED");
        assertThat((String) status.get("reason")).contains("Não encontramos");
        semFotoGuardada(conta);
        assertThat(FakeResend.EMAILS)
                .as("rejeitado não recebe e-mail de aprovação")
                .noneMatch(e -> e.get("body").contains(conta.username() + "@raiji.com"));
        given().auth().oauth2(obterToken(conta.username(), SENHA_NOVOS_USUARIOS))
                .when().get(app("/api/users/me"))
                .then().body("driver", is(false)).body("realmRoles", not(hasItem("DRIVER")));
    }

    @Test
    @DisplayName("CNH suspensa: REJECTED mostrando a situação")
    void suspensa() {
        Map<String, Object> status = verificar(novaConta("cnh-susp"), FOTO, FakeInfosimples.CNH_SUSPENSA);

        assertThat(status).containsEntry("status", "REJECTED");
        assertThat((String) status.get("reason")).contains("suspensa");
    }

    @Test
    @DisplayName("nome da CNH diferente do da conta: REJECTED")
    void nomeDiferente() {
        Map<String, Object> status = verificar(novaConta("cnh-nome"), FOTO, FakeInfosimples.NOME_DIFERENTE);

        assertThat(status).containsEntry("status", "REJECTED");
        assertThat((String) status.get("reason")).contains("nome");
    }

    @Test
    @DisplayName("SENATRAN fora do ar: ERROR (pode tentar de novo depois)")
    void indisponivel() {
        Conta conta = novaConta("cnh-615");

        Map<String, Object> status = verificar(conta, FOTO, FakeInfosimples.FONTE_INDISPONIVEL);

        assertThat(status).containsEntry("status", "ERROR");
        semFotoGuardada(conta);
    }

    @Test
    @DisplayName("foto ilegível: REJECTED sem consultar o SENATRAN")
    void fotoIlegivel() {
        String codigoUnico = "66666666666";

        Map<String, Object> status = verificar(novaConta("cnh-ileg"),
                "ILEGIVEL".getBytes(StandardCharsets.UTF_8), codigoUnico);

        assertThat(status).containsEntry("status", "REJECTED");
        assertThat((String) status.get("reason")).contains("foto nítida");
        assertThat(FakeInfosimples.CALLS).noneMatch(call -> codigoUnico.equals(call.get("codigo_seguranca")));
    }

    @Test
    @DisplayName("validação do envio: sem foto 400, formato 415, código inválido 400, sem token 401")
    void validacoesDoEnvio() {
        Conta conta = novaConta("cnh-val");

        given().auth().oauth2(conta.token()).multiPart("securityCode", FakeInfosimples.CNH_VALIDA)
                .when().post(app("/api/users/me/driver/cnh")).then().statusCode(400);
        enviar(conta, FOTO, "application/pdf", FakeInfosimples.CNH_VALIDA).statusCode(415);
        enviar(conta, FOTO, "image/png", "abc").statusCode(400);
        given().multiPart("image", "cnh.png", FOTO, "image/png").multiPart("securityCode", "11111111111")
                .when().post(app("/api/users/me/driver/cnh")).then().statusCode(401);
    }

    @Test
    @DisplayName("sem nunca ter enviado: status NONE")
    void nunca() {
        given().auth().oauth2(novaConta("cnh-none").token())
                .when().get(app("/api/users/me/driver/cnh"))
                .then().statusCode(200).body("status", equalTo("NONE"));
    }

    @Test
    @DisplayName("POST /driver sem CNH verificada -> 409; com CNH aprovada volta a ser motorista sem reenviar")
    void reativarMotorista() {
        Conta conta = novaConta("cnh-reat");

        given().auth().oauth2(conta.token()).when().post(app("/api/users/me/driver"))
                .then().statusCode(409).body("message", containsString("CNH"));

        assertThat(verificar(conta, FOTO, FakeInfosimples.CNH_VALIDA)).containsEntry("status", "APPROVED");

        given().auth().oauth2(conta.token()).when().delete(app("/api/users/me/driver"))
                .then().statusCode(200).body("driver", is(false)).body("passenger", is(true));
        given().auth().oauth2(conta.token()).when().post(app("/api/users/me/driver"))
                .then().statusCode(200).body("driver", is(true));
    }
}
