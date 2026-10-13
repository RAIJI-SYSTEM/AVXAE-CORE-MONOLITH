package com.raiji.users.web;

import java.util.List;
import java.util.Map;

import com.raiji.users.dto.SetProfileRequest;
import com.raiji.users.dto.UserProfileResponse;
import com.raiji.users.keycloak.KeycloakAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfil do usuário — 100% baseado no Keycloak.
 * Nada é persistido em banco local; as "roles" DRIVER/PASSENGER e PF/PJ
 * vivem como realm roles e são lidas do próprio JWT.
 */
@RestController
@RequestMapping("/api/users/me")
public class UserProfileController {

    private final KeycloakAdminService keycloakAdminService;

    public UserProfileController(KeycloakAdminService keycloakAdminService) {
        this.keycloakAdminService = keycloakAdminService;
    }

    @GetMapping
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return toResponse(jwt);
    }

    @PostMapping("/profile")
    public ResponseEntity<UserProfileResponse> setProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SetProfileRequest request) {

        String sub = jwt.getSubject();

        keycloakAdminService.setUserType(sub, request.userType());

        String opposite = "DRIVER".equals(request.role()) ? "PASSENGER" : "DRIVER";
        try {
            keycloakAdminService.removeRealmRole(sub, opposite);
        } catch (Exception ignored) {
            // não estava atribuída
        }
        keycloakAdminService.assignRealmRole(sub, request.role());

        return ResponseEntity.ok(new UserProfileResponse(
                sub,
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                request.userType(),
                request.role(),
                List.of(request.userType(), request.role()),
                true));
    }

    @SuppressWarnings("unchecked")
    private UserProfileResponse toResponse(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        List<String> roles = realmAccess == null
                ? List.of()
                : (List<String>) realmAccess.getOrDefault("roles", List.of());

        String userType = roles.stream()
                .filter(r -> r.equals("PF") || r.equals("PJ"))
                .findFirst().orElse(null);

        String role = roles.stream()
                .filter(r -> r.equals("DRIVER") || r.equals("PASSENGER"))
                .findFirst().orElse(null);

        return new UserProfileResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                userType,
                role,
                roles,
                userType != null && role != null);
    }
}
