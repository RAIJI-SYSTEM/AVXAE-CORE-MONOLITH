package com.raiji.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Requisição para definir o perfil. role = DRIVER/PASSENGER, userType = PF/PJ.
 * Ambos são realm roles no Keycloak.
 */
public record SetProfileRequest(
        @NotBlank
        @Pattern(regexp = "DRIVER|PASSENGER", message = "role deve ser DRIVER ou PASSENGER")
        String role,

        @NotBlank
        @Pattern(regexp = "PF|PJ", message = "userType deve ser PF ou PJ")
        String userType
) {
}
