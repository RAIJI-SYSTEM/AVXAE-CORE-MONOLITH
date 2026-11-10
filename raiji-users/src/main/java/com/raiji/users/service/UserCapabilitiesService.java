package com.raiji.users.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.raiji.common.Roles;
import com.raiji.users.dto.UserProfileResponse;
import com.raiji.users.keycloak.KeycloakAdminService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capacidades da conta. Regra: PASSAGEIRO e MOTORISTA são acumuláveis.
 *
 * <ul>
 *   <li>Toda conta é passageira: PASSENGER faz parte das roles padrão do realm
 *       (default-roles-raiji), inclusive para contas que já existiam.</li>
 *   <li>Tornar-se motorista ADICIONA a role DRIVER; nunca remove PASSENGER.</li>
 *   <li>PF/PJ continua exclusivo (é o tipo da conta, outro assunto).</li>
 * </ul>
 *
 * As roles chegam ao app pelo token: depois de uma mudança aqui o app precisa
 * renovar o token para enxergá-la (a resposta destas operações já vem atualizada).
 */
@Service
public class UserCapabilitiesService {

    private final KeycloakAdminService keycloakAdminService;
    private final ObjectProvider<DriverActivityChecker> driverActivityChecker;

    public UserCapabilitiesService(
            KeycloakAdminService keycloakAdminService,
            ObjectProvider<DriverActivityChecker> driverActivityChecker) {
        this.keycloakAdminService = keycloakAdminService;
        this.driverActivityChecker = driverActivityChecker;
    }

    /** Perfil a partir do token (sem chamar o Keycloak). */
    public UserProfileResponse fromToken(Jwt jwt) {
        return build(jwt, tokenRoles(jwt));
    }

    public UserProfileResponse setAccountType(Jwt jwt, String userType) {
        keycloakAdminService.setUserType(jwt.getSubject(), userType);
        return current(jwt);
    }

    /** Idempotente: quem já é motorista continua motorista. */
    public UserProfileResponse becomeDriver(Jwt jwt) {
        keycloakAdminService.assignRealmRole(jwt.getSubject(), Roles.DRIVER);
        return current(jwt);
    }

    /** Recusado (409) se houver carona como motorista planejada ou em andamento. */
    public UserProfileResponse stopBeingDriver(Jwt jwt) {
        DriverActivityChecker checker = driverActivityChecker.getIfAvailable();
        if (checker != null && checker.hasActiveRidesAsDriver(jwt.getSubject())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Você tem caronas planejadas ou em andamento como motorista. "
                            + "Cancele ou conclua antes de deixar de ser motorista.");
        }
        keycloakAdminService.removeRealmRole(jwt.getSubject(), Roles.DRIVER);
        return current(jwt);
    }

    /**
     * Compatibilidade com o antigo POST /profile: define PF/PJ e, se pedir
     * DRIVER, ADICIONA a role. Nunca remove a outra (PASSENGER continua).
     */
    public UserProfileResponse applyLegacyProfile(Jwt jwt, String role, String userType) {
        keycloakAdminService.setUserType(jwt.getSubject(), userType);
        if (Roles.DRIVER.equals(role)) {
            keycloakAdminService.assignRealmRole(jwt.getSubject(), Roles.DRIVER);
        }
        return current(jwt);
    }

    /** Estado real no Keycloak, já com a mudança (o token do pedido ainda é o antigo). */
    private UserProfileResponse current(Jwt jwt) {
        return build(jwt, keycloakAdminService.effectiveRealmRoles(jwt.getSubject()));
    }

    private static UserProfileResponse build(Jwt jwt, Collection<String> roles) {
        List<String> realmRoles = List.copyOf(roles);
        String userType = realmRoles.contains(Roles.PJ) ? Roles.PJ
                : realmRoles.contains(Roles.PF) ? Roles.PF
                : null;
        return new UserProfileResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                userType,
                realmRoles.contains(Roles.PASSENGER),
                realmRoles.contains(Roles.DRIVER),
                realmRoles,
                userType != null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> tokenRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return (List<String>) roles;
    }
}
