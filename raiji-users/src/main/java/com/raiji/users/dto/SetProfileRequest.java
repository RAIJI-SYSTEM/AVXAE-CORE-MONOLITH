package com.raiji.users.dto;

import com.raiji.users.domain.UserProfileType;
import jakarta.validation.constraints.NotNull;

public record SetProfileRequest(@NotNull UserProfileType role) {
}
