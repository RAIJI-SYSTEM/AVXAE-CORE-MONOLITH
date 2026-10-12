package com.raiji.web;

import java.util.HashMap;   // ← ADICIONAR

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");

        Map<String, Object> resposta = new HashMap<>();
        resposta.put("sub", jwt.getSubject());
        resposta.put("email", jwt.getClaimAsString("email"));   // pode ser null
        resposta.put("name", jwt.getClaimAsString("name"));     // pode ser null
        resposta.put("realmRoles", realmAccess == null
                ? List.of()
                : realmAccess.getOrDefault("roles", List.of()));

        return resposta;
    }

    @GetMapping("/admin/ping")
    @PreAuthorize("hasRole('admin')")
    public Map<String, String> adminPing() {
        return Map.of("message", "Voce tem a role admin do realm raiji");
    }
}
