package com.raiji.users.dto;

import java.util.UUID;

import com.raiji.users.domain.UserProfileType;

public record UserProfileResponse(
        UUID id,
        String keycloakSub,
        String email,
        String fullName,
        UserProfileType profileType,
        boolean profileConfigured) {
}
