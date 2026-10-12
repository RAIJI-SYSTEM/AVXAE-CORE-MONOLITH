package com.raiji.users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.raiji.users.domain.UserProfile;
import com.raiji.users.domain.UserProfileType;
import com.raiji.users.keycloak.KeycloakAdminService;
import com.raiji.users.repository.UserProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes unitários (§4) — regras de perfil e provisionamento")
class UserProvisioningServiceTest {

    @Mock private UserProfileRepository repositorio;
    @Mock private KeycloakAdminService keycloak;
    @InjectMocks private UserProvisioningService servico;

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("token-de-teste")
                .header("alg", "RS256")
                .claim("sub", sub)
                .claim("email", sub + "@raiji.com")
                .claim("name", "Usuário " + sub)
                .build();
    }

    @Test
    @DisplayName("CT-U01 (RN20): 1º acesso provisiona perfil com sub/e-mail/nome e sem tipo")
    void provisionaNoPrimeiroAcesso() {
        when(repositorio.findByKeycloakSub("sub-1")).thenReturn(Optional.empty());
        when(repositorio.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfile perfil = servico.getOrProvision(jwt("sub-1"));

        assertThat(perfil.getKeycloakSub()).isEqualTo("sub-1");
        assertThat(perfil.getEmail()).isEqualTo("sub-1@raiji.com");
        assertThat(perfil.getFullName()).isEqualTo("Usuário sub-1");
        assertThat(perfil.getProfileType()).isNull();
        verify(repositorio).save(any(UserProfile.class));
    }

    @Test
    @DisplayName("CT-U02 (RN20): usuário já provisionado é reaproveitado (sem novo save)")
    void naoReprovisiona() {
        UserProfile existente = new UserProfile();
        when(repositorio.findByKeycloakSub("sub-1")).thenReturn(Optional.of(existente));

        assertThat(servico.getOrProvision(jwt("sub-1"))).isSameAs(existente);
        verify(repositorio, never()).save(any());
    }

    @Test
    @DisplayName("CT-U03 (RN01/RF34): definir o 1º perfil atribui a realm role e persiste")
    void definePrimeiroPerfil() {
        UserProfile perfil = new UserProfile();
        when(repositorio.findByKeycloakSub("sub-1")).thenReturn(Optional.of(perfil));
        when(repositorio.save(perfil)).thenReturn(perfil);

        servico.setProfile(jwt("sub-1"), UserProfileType.DRIVER);

        verify(keycloak).assignRealmRole("sub-1", "DRIVER");
        verify(keycloak, never()).removeRealmRole(anyString(), anyString());
        assertThat(perfil.getProfileType()).isEqualTo(UserProfileType.DRIVER);
    }

    @Test
    @DisplayName("CT-U04 (RF34): trocar de perfil remove a role antiga e atribui a nova")
    void trocaDePerfil() {
        UserProfile perfil = new UserProfile();
        perfil.setProfileType(UserProfileType.PASSENGER);
        when(repositorio.findByKeycloakSub("sub-1")).thenReturn(Optional.of(perfil));
        when(repositorio.save(perfil)).thenReturn(perfil);

        servico.setProfile(jwt("sub-1"), UserProfileType.DRIVER);

        verify(keycloak).removeRealmRole("sub-1", "PASSENGER");
        verify(keycloak).assignRealmRole("sub-1", "DRIVER");
        assertThat(perfil.getProfileType()).isEqualTo(UserProfileType.DRIVER);
    }

    @Test
    @DisplayName("CT-U05: reenviar o mesmo perfil é idempotente (sem chamadas ao Keycloak)")
    void reenvioEIdempotente() {
        UserProfile perfil = new UserProfile();
        perfil.setProfileType(UserProfileType.DRIVER);
        when(repositorio.findByKeycloakSub("sub-1")).thenReturn(Optional.of(perfil));

        servico.setProfile(jwt("sub-1"), UserProfileType.DRIVER);

        verifyNoInteractions(keycloak);
        verify(repositorio, never()).save(any());
    }
}