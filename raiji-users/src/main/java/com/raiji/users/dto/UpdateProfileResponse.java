package com.raiji.users.dto;

public record UpdateProfileResponse(
        String sub,
        String email,
        String fullName,
        String phone,
        String photoUrl
) {
}
