package com.raiji.users.keycloak;

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

        // CORREÇÃO: O método delete() padrão não aceita body.
        // Usamos method(HttpMethod.DELETE) para obter um RequestBodySpec que aceita corpo.
        restClient.method(HttpMethod.DELETE)
                .uri(adminBaseUrl() + "/users/" + userId + "/role-mappings/realm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(role))
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> fetchRealmRole(String roleName, String adminToken) {
        return restClient.get()
                .uri(adminBaseUrl() + "/roles/" + roleName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    private String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.adminClientId());
        form.add("client_secret", properties.adminClientSecret());

        Map<String, Object> response = restClient.post()
                .uri(properties.baseUrl() + "/realms/" + properties.realm() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        return (String) response.get("access_token");
    }

    private String adminBaseUrl() {
        return properties.baseUrl() + "/admin/realms/" + properties.realm();
    }
}
