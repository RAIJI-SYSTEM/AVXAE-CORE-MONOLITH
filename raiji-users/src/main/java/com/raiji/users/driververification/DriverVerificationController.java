package com.raiji.users.driververification;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Verificação da CNH para virar motorista.
 *
 * <pre>
 * POST /api/users/me/driver/cnh   multipart: image (foto da CNH) + securityCode
 *                                 -> 202 { status: "PROCESSING" }
 * GET  /api/users/me/driver/cnh   -> { status: NONE | PROCESSING | APPROVED | REJECTED | ERROR, reason, ... }
 * </pre>
 *
 * Aprovado: a conta recebe a role DRIVER (o app deve renovar o token).
 */
@RestController
@RequestMapping("/api/users/me/driver/cnh")
public class DriverVerificationController {

    private final DriverVerificationService service;

    public DriverVerificationController(DriverVerificationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DriverApplicationResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "securityCode", required = false) String securityCode) {
        return ResponseEntity.accepted().body(service.submit(jwt, image, securityCode));
    }

    @GetMapping
    public DriverApplicationResponse status(@AuthenticationPrincipal Jwt jwt) {
        return service.latest(jwt.getSubject());
    }
}
