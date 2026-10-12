package com.raiji.users.service;

import com.raiji.users.domain.UserProfile;
import com.raiji.users.domain.UserProfileType;
import com.raiji.users.keycloak.KeycloakAdminService;
import com.raiji.users.repository.UserProfileRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProvisioningService {

    private final UserProfileRepository repository;
    private final KeycloakAdminService keycloakAdminService;

    public UserProvisioningService(UserProfileRepository repository,
                                   KeycloakAdminService keycloakAdminService) {
        this.repository = repository;
        this.keycloakAdminService = keycloakAdminService;
    }

    @Transactional
    public UserProfile getOrProvision(Jwt jwt) {
        String sub = resolveSubject(jwt);
        return repository.findByKeycloakSub(sub)
                .orElseGet(() -> provision(jwt, sub));
    }

    @Transactional
    public UserProfile setProfile(Jwt jwt, UserProfileType type) {
        String sub = resolveSubject(jwt);
        UserProfile profile = repository.findByKeycloakSub(sub)
                .orElseGet(() -> provision(jwt, sub));

        if (profile.getProfileType() == type) {
            return profile;
        }
        if (profile.getProfileType() != null) {
            keycloakAdminService.removeRealmRole(sub, profile.getProfileType().name());
        }
        keycloakAdminService.assignRealmRole(sub, type.name());

        profile.setProfileType(type);
        return repository.save(profile);
    }

    private UserProfile provision(Jwt jwt, String sub) {
        UserProfile profile = new UserProfile();
        profile.setKeycloakSub(sub);
        profile.setEmail(jwt.getClaimAsString("email"));
        profile.setFullName(jwt.getClaimAsString("name"));
        return repository.save(profile);
    }

    private String resolveSubject(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub != null && !sub.isBlank()) {
            return sub;
        }

        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isBlank()) {
            return username;
        }

        throw new IllegalStateException(
                "JWT não contém 'sub' nem 'preferred_username' — "
                        + "verifique os protocol mappers do client 'raiji-api' no realm 'raiji'");
    }
}