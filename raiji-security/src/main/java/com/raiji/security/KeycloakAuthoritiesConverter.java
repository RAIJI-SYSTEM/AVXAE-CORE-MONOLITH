package com.raiji.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public final class KeycloakAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLE_PREFIX = "ROLE_";
    private final String apiClientId;

    public KeycloakAuthoritiesConverter(String apiClientId) {
        this.apiClientId = apiClientId;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        addRoles(authorities, jwt.getClaimAsMap("realm_access"));

        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess != null && resourceAccess.get(apiClientId) instanceof Map<?, ?> clientAccess) {
            addRoles(authorities, castToMap(clientAccess));
        }
        return authorities;
    }

    private void addRoles(Set<GrantedAuthority> authorities, Map<String, Object> access) {
        if (access == null || !(access.get("roles") instanceof Collection<?> roles)) {
            return;
        }
        roles.stream()
             .map(String::valueOf)
             .map(role -> ROLE_PREFIX + role)
             .map(SimpleGrantedAuthority::new)
             .forEach(authorities::add);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
