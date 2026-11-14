
package com.raiji.users.web;

import com.raiji.users.dto.AccountTypeRequest;
import com.raiji.users.dto.SetProfileRequest;
import com.raiji.users.dto.UpdateProfileRequest;
import com.raiji.users.dto.UserProfileResponse;
import com.raiji.users.dto.UpdateProfileResponse;
import com.raiji.users.domain.UserBusinessProfile;
import com.raiji.users.service.UserBusinessProfileService;
import com.raiji.users.service.UserCapabilitiesService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Perfil do usuário. Passageiro e motorista são CAPACIDADES acumuláveis:
 * toda conta é passageira; tornar-se motorista acrescenta, não substitui.
 *
 * <pre>
 * GET    /api/users/me               perfil (do token)
 * PUT    /api/users/me/account-type  { "userType": "PF" | "PJ" }
 * POST   /api/users/me/driver        torna-se motorista (idempotente)
 * DELETE /api/users/me/driver        deixa de ser motorista (409 com carona ativa)
 * POST   /api/users/me/profile       legado: PF/PJ + DRIVER (aditivo)
 * PATCH  /api/users/me               telefone / foto
 * </pre>
 *
 * Após PUT/POST/DELETE o app deve renovar o token para ver as roles novas.
 */
@RestController
@RequestMapping("/api/users/me")
public class UserProfileController {

    private final UserCapabilitiesService capabilitiesService;
    private final UserBusinessProfileService businessProfileService;

    public UserProfileController(
            UserCapabilitiesService capabilitiesService,
            UserBusinessProfileService businessProfileService) {
        this.capabilitiesService = capabilitiesService;
        this.businessProfileService = businessProfileService;
    }

    @GetMapping
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return capabilitiesService.fromToken(jwt);
    }

    @PutMapping("/account-type")
    public UserProfileResponse setAccountType(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AccountTypeRequest request) {
        return capabilitiesService.setAccountType(jwt, request.userType());
    }

    @PostMapping("/driver")
    public UserProfileResponse becomeDriver(@AuthenticationPrincipal Jwt jwt) {
        return capabilitiesService.becomeDriver(jwt);
    }

    @DeleteMapping("/driver")
    public UserProfileResponse stopBeingDriver(@AuthenticationPrincipal Jwt jwt) {
        return capabilitiesService.stopBeingDriver(jwt);
    }

    /**
     * Legado (mantido por compatibilidade): define PF/PJ e, com role=DRIVER,
     * ACRESCENTA a capacidade de motorista. Não remove mais a outra role.
     * Prefira PUT /account-type e POST /driver.
     */
    @PostMapping("/profile")
    public ResponseEntity<UserProfileResponse> setProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SetProfileRequest request) {
        return ResponseEntity.ok(
                capabilitiesService.applyLegacyProfile(jwt, request.role(), request.userType()));
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
}
