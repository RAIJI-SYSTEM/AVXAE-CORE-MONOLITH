package com.raiji.it;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Map;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.restassured.http.ContentType;
import io.restassured.common.mapper.TypeRef;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class RaijiTestSupport {

    protected static final String REALM = "raiji";
    protected static final String CLIENTE_API = "raiji-api";
    protected static final String USUARIO_TESTE = "teste";
    protected static final String SENHA_TESTE = "teste123";
    protected static final String SENHA_NOVOS_USUARIOS = "Raiji@It123";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("raiji_app")
                    .withUsername("raiji")
                    .withPassword("raiji123");

    // VERSÃO ATUALIZADA PARA 26.0 (Alinhada com docker-compose.yml e Dockerfile.keycloak)
    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.0.5")
                    .withAdminUsername("admin")
                    .withAdminPassword("admin")
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    .withFeaturesDisabled("organization") 
                    .withRealmImportFile("raiji-realm.json");

    // O bloco static garante que os containers subam e tenham suas portas mapeadas
    // ANTES de qualquer contexto do Spring Boot tentar ler as propriedades dinâmicas.
    static {
        POSTGRES.start();
        KEYCLOAK.start();
    }

    @LocalServerPort
    protected int porta;

    @DynamicPropertySource
    static void registrarPropriedades(DynamicPropertyRegistry registro) {
        // Não é mais necessário chamar .start() aqui, pois o bloco static já cuidou disso.
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
        registro.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM
                        + "/protocol/openid-connect/certs");
        registro.add("raiji.keycloak.base-url", KEYCLOAK::getAuthServerUrl);
        registro.add("raiji.keycloak.admin-client-id", () -> "raiji-admin");
        registro.add("raiji.keycloak.admin-client-secret", () -> "raiji-admin-secret");
        registro.add("raiji.keycloak.realm", () -> REALM);
    }

    protected String app(String caminho) { return "http://localhost:" + porta + caminho; }
    protected static String kc(String caminho) { return KEYCLOAK.getAuthServerUrl() + caminho; }
    protected static String urlToken() { return kc("/realms/" + REALM + "/protocol/openid-connect/token"); }
    protected static String urlAdmin() { return kc("/admin/realms/" + REALM); }

    protected static String obterToken(String usuario, String senha) {
        return given()
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "password")
                .formParam("client_id", CLIENTE_API)
                .formParam("username", usuario)
                .formParam("password", senha)
                .when().post(urlToken())
                .then().statusCode(200)
                .extract().path("access_token");
    }

    protected static String tokenAdmin() {
        return given()
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "password")
                .formParam("client_id", "admin-cli")
                .formParam("username", KEYCLOAK.getAdminUsername())
                .formParam("password", KEYCLOAK.getAdminPassword())
                .when().post(kc("/realms/master/protocol/openid-connect/token"))
                .then().statusCode(200)
                .extract().path("access_token");
    }

    protected static String criarUsuario(String username) {
        String admin = tokenAdmin();

        String location = given()
                .auth().oauth2(admin)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "username", username,
                        "enabled", true,
                        "email", username + "@raiji.com",
                        "emailVerified", true,
                        "firstName", "Usuario",
                        "lastName", "Teste"))
                .when().post(urlAdmin() + "/users")
                .then().statusCode(201)
                .extract().header("Location");

        String userId = location.substring(location.lastIndexOf('/') + 1);

        given()
                .auth().oauth2(admin)
                .contentType(ContentType.JSON)
                .body(Map.of("type", "password", "value", SENHA_NOVOS_USUARIOS, "temporary", false))
                .when().put(urlAdmin() + "/users/" + userId + "/reset-password")
                .then().statusCode(204);

        return userId;
    }

    @SuppressWarnings("unchecked")
    protected static void atribuirRoleRealm(String userId, String role) {
        String admin = tokenAdmin();

        Map<String, Object> roleRep = given()
                .auth().oauth2(admin)
                .when().get(urlAdmin() + "/roles/" + role)
                .then().statusCode(200)
                .extract().as(Map.class);

        given()
                .auth().oauth2(admin)
                .contentType(ContentType.JSON)
                .body(List.of(roleRep))
                .when().post(urlAdmin() + "/users/" + userId + "/role-mappings/realm")
                .then().statusCode(204);
    }

    private static void habilitarServiceAccountAdmin() {
        String admin = tokenAdmin();

        given().contentType(ContentType.URLENC)
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", "raiji-admin")
                .formParam("client_secret", "raiji-admin-secret")
                .when().post(urlToken())
                .then().statusCode(200);

        String serviceAccountId = given().auth().oauth2(admin)
                .queryParam("username", "service-account-raiji-admin")
                .queryParam("exact", true)
                .when().get(urlAdmin() + "/users")
                .then().statusCode(200)
                .extract().path("[0].id");

        String realmManagementId = given().auth().oauth2(admin)
                .queryParam("clientId", "realm-management")
                .when().get(urlAdmin() + "/clients")
                .then().statusCode(200)
                .extract().path("[0].id");

        List<Map<String, Object>> roles = given().auth().oauth2(admin)
                .when().get(urlAdmin() + "/clients/" + realmManagementId + "/roles")
                .then().statusCode(200)
                .extract().as(new TypeRef<>() {});

        List<Map<String, Object>> desejadas = roles.stream()
                .filter(r -> List.of("manage-users", "view-users", "query-users")
                        .contains(r.get("name")))
                .toList();

        given().auth().oauth2(admin)
                .contentType(ContentType.JSON)
                .body(desejadas)
                .when().post(urlAdmin() + "/users/" + serviceAccountId
                        + "/role-mappings/clients/" + realmManagementId)
                .then().statusCode(204);
    }

    protected static String adulterarAssinatura(String token) {
        char[] caracteres = token.toCharArray();
        int i = caracteres.length - 3;
        caracteres[i] = (caracteres[i] == 'X') ? 'Y' : 'X';
        return new String(caracteres);
    }
}