package com.raiji.users.dto;

import java.util.List;

/**
 * Perfil do usuário como CAPACIDADES (não um papel único):
 * a mesma conta pode ser passageira e motorista ao mesmo tempo.
 *
 * @param userType          PF ou PJ (null enquanto não escolhido)
 * @param passenger         pode pedir vaga em caronas (toda conta, por padrão)
 * @param driver            pode cadastrar veículo e publicar caronas
 * @param realmRoles        roles do Keycloak (para depuração/compatibilidade)
 * @param profileConfigured onboarding concluído (tipo de conta escolhido)
 */
public record UserProfileResponse(
        String sub,
        String email,
        String fullName,
        String userType,
        boolean passenger,
        boolean driver,
        List<String> realmRoles,
        boolean profileConfigured) {
}
