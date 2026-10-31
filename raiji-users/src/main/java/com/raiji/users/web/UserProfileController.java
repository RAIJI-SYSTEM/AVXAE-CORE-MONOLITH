
package com.raiji.users.web;

import java.util.List;
import java.util.Map;

import com.raiji.users.dto.SetProfileRequest;
import com.raiji.users.dto.UpdateProfileRequest;
import com.raiji.users.dto.UserProfileResponse;
import com.raiji.users.dto.UpdateProfileResponse;
import com.raiji.users.domain.UserBusinessProfile;
import com.raiji.users.keycloak.KeycloakAdminService;
import com.raiji.users.service.UserBusinessProfileService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users/me")
public class UserProfileController {

    private final KeycloakAdminService keycloakAdminService;
    private final UserBusinessProfileService businessProfileService;

    public UserProfileController(
            KeycloakAdminService keycloakAdminService,
            UserBusinessProfileService businessProfileService) {
        this.keycloakAdminService = keycloakAdminService;
        this.businessProfileService = businessProfileService;
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

        String opposite = "DRIVER".equals(request.role())
                ? "PASSENGER" : "DRIVER";

        try {
            keycloakAdminService.removeRealmRole(sub, opposite);
        } catch (Exception ignored) {
            // A role que não estava atribuída não precisa ser removida.
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

    @PatchMapping
    public ResponseEntity<UpdateProfileResponse> updateBusinessProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request) {

        if (request.phone() == null && request.photoUrl() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Informe pelo menos phone ou photoUrl.");
        }

        String sub = jwt.getSubject();

        UserBusinessProfile profile = businessProfileService.upsert(
                sub,
                request.phone(),
                request.photoUrl());

        UpdateProfileResponse response = new UpdateProfileResponse(
                sub,
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                profile.getPhone(),
                profile.getPhotoUrl());

        return ResponseEntity.ok(response);
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
