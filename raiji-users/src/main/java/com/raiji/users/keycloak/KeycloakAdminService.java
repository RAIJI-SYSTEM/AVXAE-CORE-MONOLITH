package com.raiji.users.keycloak;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class KeycloakAdminService {

    private final KeycloakAdminProperties properties;
    private final RestClient restClient;

    public KeycloakAdminService(KeycloakAdminProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    // -----------------------------------------------------------------------
    // Realm roles
    // -----------------------------------------------------------------------

    public void assignRealmRole(String userId, String roleName) {
        String token = adminToken();
        Map<String, Object> role = fetchRealmRole(roleName, token);

        restClient.post()
                .uri(adminBaseUrl() + "/users/" + userId + "/role-mappings/realm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(role))
                .retrieve()
                .toBodilessEntity();
    }

    public void removeRealmRole(String userId, String roleName) {
        String token = adminToken();
        Map<String, Object> role = fetchRealmRole(roleName, token);

        restClient.method(HttpMethod.DELETE)
                .uri(adminBaseUrl() + "/users/" + userId + "/role-mappings/realm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(role))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Troca o tipo PF/PJ: remove as duas e adiciona a nova.
     */
    public void setUserType(String userId, String userType) {
        if (userType == null) return;
        removeRealmRoleQuietly(userId, "PF");
        removeRealmRoleQuietly(userId, "PJ");
        assignRealmRole(userId, userType);
    }

    /**
     * Mescla (merge) atributos no usuário do Keycloak.
     * Ex.: Map.of("cnh_number", List.of("123"), "cnh_category", List.of("AB"))
     */
    public void updateUserAttributes(String userId, Map<String, List<String>> attributes) {
        if (attributes == null || attributes.isEmpty()) return;

        String token = adminToken();

        Map<String, Object> user = restClient.get()
                .uri(adminBaseUrl() + "/users/" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (user == null) return;

        @SuppressWarnings("unchecked")
        Map<String, List<String>> merged =
                user.get("attributes") instanceof Map<?, ?> existing
                        ? new HashMap<>((Map<String, List<String>>) existing)
                        : new HashMap<>();
        merged.putAll(attributes);
        user.put("attributes", merged);

        restClient.put()
                .uri(adminBaseUrl() + "/users/" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void removeRealmRoleQuietly(String userId, String roleName) {
        try {
            removeRealmRole(userId, roleName);
        } catch (Exception ignored) {
            // Role não existia ou já não estava atribuída — segue o jogo.
        }
    }

    private Map<String, Object> fetchRealmRole(String roleName, String adminToken) {
        return restClient.get()
                .uri(adminBaseUrl() + "/roles/" + roleName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    private String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.adminClientId());
        form.add("client_secret", properties.adminClientSecret());

        Map<String, Object> response = restClient.post()
                .uri(properties.baseUrl() + "/realms/" + properties.realm()
                        + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        return (String) response.get("access_token");
    }

    private String adminBaseUrl() {
        return properties.baseUrl() + "/admin/realms/" + properties.realm();
    }
}
