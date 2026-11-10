package com.raiji.users.service;

import java.util.List;
import java.util.Map;

import com.raiji.common.Roles;
import com.raiji.users.dto.UserProfileResponse;
import com.raiji.users.keycloak.KeycloakAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCapabilitiesServiceTest {

    private static final String SUB = "user-123";

    private KeycloakAdminService keycloak;
    private DriverActivityChecker activity;
    private UserCapabilitiesService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        keycloak = mock(KeycloakAdminService.class);
        activity = mock(DriverActivityChecker.class);
        ObjectProvider<DriverActivityChecker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(activity);
        service = new UserCapabilitiesService(keycloak, provider);
    }

    private static Jwt jwt(String... roles) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(SUB)
                .claim("email", "maria@raiji.com")
                .claim("name", "Maria")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
    }

    @Test
    @DisplayName("conta nova: passageira por padrão, ainda sem PF/PJ")
    void newAccountIsPassenger() {
        UserProfileResponse me = service.fromToken(jwt("default-roles-raiji", Roles.PASSENGER));

        assertThat(me.passenger()).isTrue();
        assertThat(me.driver()).isFalse();
        assertThat(me.userType()).isNull();
        assertThat(me.profileConfigured()).isFalse();
    }

    @Test
    @DisplayName("motorista E passageira ao mesmo tempo")
    void driverAndPassenger() {
        UserProfileResponse me = service.fromToken(jwt(Roles.PASSENGER, Roles.DRIVER, Roles.PF));

        assertThat(me.passenger()).isTrue();
        assertThat(me.driver()).isTrue();
        assertThat(me.userType()).isEqualTo(Roles.PF);
        assertThat(me.profileConfigured()).isTrue();
    }

    @Test
    @DisplayName("tornar-se motorista ADICIONA DRIVER e nunca remove PASSENGER")
    void becomeDriverIsAdditive() {
        when(keycloak.effectiveRealmRoles(SUB)).thenReturn(List.of(Roles.PASSENGER, Roles.DRIVER, Roles.PF));

        UserProfileResponse me = service.becomeDriver(jwt(Roles.PASSENGER, Roles.PF));

        verify(keycloak).assignRealmRole(SUB, Roles.DRIVER);
        verify(keycloak, never()).removeRealmRole(anyString(), anyString());
        assertThat(me.driver()).isTrue();
        assertThat(me.passenger()).isTrue();
    }

    @Test
    @DisplayName("deixar de ser motorista com carona ativa: 409 e nada muda")
    void stopBeingDriverWithActiveRides() {
        when(activity.hasActiveRidesAsDriver(SUB)).thenReturn(true);

        assertThatThrownBy(() -> service.stopBeingDriver(jwt(Roles.PASSENGER, Roles.DRIVER)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(keycloak, never()).removeRealmRole(anyString(), anyString());
    }

    @Test
    @DisplayName("deixar de ser motorista sem carona ativa: remove só DRIVER")
    void stopBeingDriver() {
        when(activity.hasActiveRidesAsDriver(SUB)).thenReturn(false);
        when(keycloak.effectiveRealmRoles(SUB)).thenReturn(List.of(Roles.PASSENGER));

        UserProfileResponse me = service.stopBeingDriver(jwt(Roles.PASSENGER, Roles.DRIVER));

        verify(keycloak).removeRealmRole(SUB, Roles.DRIVER);
        verify(keycloak, never()).removeRealmRole(eq(SUB), eq(Roles.PASSENGER));
        assertThat(me.driver()).isFalse();
        assertThat(me.passenger()).isTrue();
    }

    @Test
    @DisplayName("legado POST /profile com PASSENGER não tira DRIVER de quem já é motorista")
    void legacyPassengerKeepsDriver() {
        when(keycloak.effectiveRealmRoles(SUB)).thenReturn(List.of(Roles.PASSENGER, Roles.DRIVER, Roles.PJ));

        UserProfileResponse me = service.applyLegacyProfile(jwt(Roles.PASSENGER, Roles.DRIVER), Roles.PASSENGER, Roles.PJ);

        verify(keycloak).setUserType(SUB, Roles.PJ);
        verify(keycloak, never()).removeRealmRole(anyString(), anyString());
        verify(keycloak, never()).assignRealmRole(anyString(), anyString());
        assertThat(me.driver()).isTrue();
        assertThat(me.userType()).isEqualTo(Roles.PJ);
    }

    @Test
    @DisplayName("legado POST /profile com DRIVER acrescenta a role")
    void legacyDriverAdds() {
        when(keycloak.effectiveRealmRoles(SUB)).thenReturn(List.of(Roles.PASSENGER, Roles.DRIVER, Roles.PF));

        service.applyLegacyProfile(jwt(Roles.PASSENGER), Roles.DRIVER, Roles.PF);

        verify(keycloak).assignRealmRole(SUB, Roles.DRIVER);
        verify(keycloak, never()).removeRealmRole(anyString(), anyString());
    }
}
