package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Documentação viva — catálogo de todas as requisições da API")
class ApiCatalogIT extends RaijiTestSupport {

    //private static final String SENHA_NOVOS_USUARIOS_NOVOS_USUARIOS = "Catalogo@Raiji123";
    private static final int LIMITE_CORPO = 280;

    private record Endpoint(String metodo, String rota, String autenticacao,
                            String responsabilidade, Supplier<Response> chamada) {}

    @Test
    @DisplayName("CT-DOC: executa todas as requisições e registra o retorno de sucesso de cada uma")
    void catalogoVivoDaApi() throws IOException {
        String usuario = "catalogo-" + System.currentTimeMillis();
        String userId = criarUsuario(usuario);
        String token = obterToken(usuario, SENHA_NOVOS_USUARIOS);

        List<Endpoint> endpoints = List.of(
            new Endpoint("POST", "/realms/raiji/protocol/openid-connect/token",
                "pública (credenciais no corpo)",
                "LOGIN — troca usuário/SENHA_NOVOS_USUARIOS por access_token (JWT RS256) e refresh_token (RN21)",
                () -> given().contentType(ContentType.URLENC)
                        .formParam("grant_type", "password")
                        .formParam("client_id", CLIENTE_API)
                        .formParam("username", usuario)
                        .formParam("password", SENHA_NOVOS_USUARIOS)
                        .when().post(urlToken())),

            new Endpoint("GET", "/realms/raiji",
                "pública",
                "METADADOS DO REALM — emissor dos tokens; recursos habilitados (registro e recuperação de SENHA_NOVOS_USUARIOS — RF35)",
                () -> given().when().get(kc("/realms/raiji"))),

            new Endpoint("GET", "/realms/raiji/protocol/openid-connect/certs",
                "pública",
                "JWKS — chaves públicas com que o monolito valida a assinatura de todo JWT localmente (RN21)",
                () -> given().when().get(kc("/realms/raiji/protocol/openid-connect/certs"))),

            new Endpoint("GET", "/actuator/health",
                "pública",
                "HEALTHCHECK da aplicação — endereço usado pelo Docker Compose e por orquestradores",
                () -> given().when().get(app("/actuator/health"))),

            new Endpoint("GET", "/api/me",
                "Bearer token (JWT)",
                "IDENTIDADE — sub, e-mail, nome e realmRoles do usuário autenticado, lidos do token",
                () -> given().auth().oauth2(token).when().get(app("/api/me"))),

            new Endpoint("GET", "/api/users/me",
                "Bearer token (JWT)",
                "PERFIL DE NEGÓCIO — consulta e, no 1º acesso, provisiona o registro no banco (RN20)",
                () -> given().auth().oauth2(token).when().get(app("/api/users/me"))),

            new Endpoint("POST", "/api/users/me/profile",
                "Bearer token (JWT)",
                "DEFINIR O PERFIL — DRIVER/PASSENGER; grava no banco e sincroniza a realm role no Keycloak (RN01/RF34)",
                () -> given().auth().oauth2(token).contentType(ContentType.JSON)
                        .body("{\"role\":\"PASSENGER\"}")
                        .when().post(app("/api/users/me/profile"))),

            new Endpoint("GET", "/api/admin/ping",
                "Bearer token (JWT) + realm role admin",
                "ÁREA ADMINISTRATIVA — protegida por autorização; sem a role admin responde 403 (RN22)",
                () -> {
                    atribuirRoleRealm(userId, "admin");
                    return given().auth().oauth2(obterToken(usuario, SENHA_NOVOS_USUARIOS))
                            .when().get(app("/api/admin/ping"));
                })
        );

        StringBuilder saida = new StringBuilder();
        saida.append("""
                ================================================================================
                RAIJI — CATÁLOGO VIVO DA API — %s
                Gerado pelos testes (JUnit 5 + REST Assured + Testcontainers) contra a
                aplicação REAL. Cada bloco traz a RESPONSABILIDADE do endpoint e a
                resposta observada quando bem-sucedida.
                ================================================================================

                """.formatted(LocalDateTime.now()));

        List<String> falhas = new ArrayList<>();
        int sucesso = 0;
        int n = 1;

        for (Endpoint e : endpoints) {
            Response resposta = e.chamada().get();
            int status = resposta.getStatusCode();
            if (status >= 200 && status < 300) sucesso++;
            else falhas.add("%s %s → HTTP %d".formatted(e.metodo(), e.rota(), status));

            saida.append("[%d] %s %s%n".formatted(n++, e.metodo(), e.rota()));
            saida.append("    autenticação ....: %s%n".formatted(e.autenticacao()));
            saida.append("    responsável por .: %s%n".formatted(e.responsabilidade()));
            saida.append("    sucesso .........: HTTP %d%n".formatted(status));
            saida.append("    resposta ........: %s%n%n".formatted(corpoResumido(resposta)));
        }

        saida.append("-".repeat(80)).append(System.lineSeparator());
        saida.append("TOTAL: %d endpoints | com sucesso (2xx): %d | sem sucesso: %d%n"
                .formatted(endpoints.size(), sucesso, falhas.size()));

        System.out.println(saida);
        Files.writeString(Path.of("target", "api-catalog.txt"), saida, StandardCharsets.UTF_8);

        assertThat(falhas).as("endpoints que não responderam com sucesso (2xx)").isEmpty();
    }

    private static String corpoResumido(Response resposta) {
        String corpo = resposta.getBody().asString()
                .replaceAll("(\"(?:access_token|refresh_token|id_token)\":\")[^\"]*\"",
                        "$1***mascarado***\"");
        return corpo.length() <= LIMITE_CORPO ? corpo : corpo.substring(0, LIMITE_CORPO) + " …(truncado)";
    }
}
