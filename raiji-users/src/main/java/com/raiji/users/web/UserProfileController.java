package com.raiji.users.web;

import com.raiji.users.domain.UserProfile;
import com.raiji.users.dto.SetProfileRequest;
import com.raiji.users.dto.UserProfileResponse;
import com.raiji.users.service.UserProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserProfileController {

    private final UserProvisioningService provisioningService;

    public UserProfileController(UserProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @GetMapping
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return toResponse(provisioningService.getOrProvision(jwt));
    }

    @PostMapping("/profile")
    public ResponseEntity<UserProfileResponse> setProfile(@AuthenticationPrincipal Jwt jwt,
                                                          @Valid @RequestBody SetProfileRequest request) {
        return ResponseEntity.ok(toResponse(provisioningService.setProfile(jwt, request.role())));
    }

    private UserProfileResponse toResponse(UserProfile profile) {
        return new UserProfileResponse(
                profile.getId(),
                profile.getKeycloakSub(),
                profile.getEmail(),
                profile.getFullName(),
                profile.getProfileType(),
                profile.getProfileType() != null);
    }
}
