package com.raiji.users.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;


public record UpdateProfileRequest(
        @Pattern(
                regexp = "^\\+[1-9]\\d{1,14}$",
                message = "Telefone deve estar no formato E.164"
        )
        String phone,

        @URL(message = "A foto deve conter uma URL válida")
        @Size(
                max = 500,
                message = "A URL da foto deve ter no máximo 500 caracteres"
        )
        String photoUrl
){
    @AssertTrue(message = "Informe pelo menos telefone ou foto")
    public boolean hasAtLeastOneField(){
        return phone != null || photoUrl != null;
    }
}
