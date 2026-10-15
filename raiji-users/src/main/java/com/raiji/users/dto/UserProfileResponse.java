package com.raiji.users.dto;

import java.util.List;

/**
 * Projeção do usuário, montada a partir do JWT + (opcional) leitura
 * do Keycloak. Não existe mais tabela local.
 */
public record UserProfileResponse(
        String sub,
        String email,
        String fullName,
        String userType,
        String role,
        List<String> realmRoles,
        boolean profileConfigured) {
}
