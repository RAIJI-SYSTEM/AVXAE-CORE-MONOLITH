package com.raiji.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountTypeRequest(
        @NotBlank
        @Pattern(regexp = "PF|PJ", message = "userType deve ser PF ou PJ")
        String userType
) {
}
