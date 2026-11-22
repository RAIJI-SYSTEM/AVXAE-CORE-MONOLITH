package com.raiji.deliveries.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeliveryConfirmationRequest(

        @NotBlank(message = "O código de confirmação é obrigatório.")
        @Size(min = 6, max = 6, message = "O código deve possuir seis dígitos.")
        @Pattern(
                regexp = "\\d{6}",
                message = "O código deve conter exatamente seis números."
        )
        String confirmationCode,

        @NotBlank(message = "O nome de quem recebeu a encomenda é obrigatório.")
        @Size(max = 120, message = "O nome deve ter no máximo 120 caracteres.")
        String recipientName,

        @Size(max = 500, message = "A observação deve ter no máximo 500 caracteres.")
        String observation

) {
}
