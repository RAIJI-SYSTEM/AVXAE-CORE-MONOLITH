package com.raiji.deliveries.dto;

import java.math.BigDecimal;

import com.raiji.deliveries.DeliveryMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record DeliveryRequest(

        @NotNull(message = "Informe a modalidade da entrega.")
        DeliveryMode mode,

        @NotBlank(message = "Informe a cidade de origem.")
        @Size(max = 100)
        String originCity,

        @NotBlank(message = "Informe o estado de origem.")
        @Pattern(regexp = "^[A-Za-z]{2}$",
                message = "O estado de origem deve ter duas letras maiúsculas.")
        String originState,

        @NotBlank(message = "Informe o endereço de origem.")
        @Size(max = 255)
        String originAddress,

        @NotBlank(message = "Informe a cidade de destino.")
        @Size(max = 100)
        String destinationCity,

        @NotBlank(message = "Informe o estado de destino.")
        @Pattern(regexp = "^[A-Za-z]{2}$",
                message = "O estado de destino deve ter duas letras maiúsculas.")
        String destinationState,

        @NotBlank(message = "Informe o endereço de destino.")
        @Size(max = 255)
        String destinationAddress,

        @NotBlank(message = "Informe o nome do destinatário.")
        @Size(max = 150)
        String recipientName,

        @NotBlank(message = "Informe o contato do destinatário.")
        @Pattern(
                regexp = "^[0-9+()\\-\\s]{8,20}$",
                message = "O contato do destinatário é inválido."
        )
        String recipientContact,

        @NotBlank(message = "Informe a descrição do pacote.")
        @Size(max = 200)
        String description,

        @NotNull(message = "Informe o peso do pacote.")
        @Positive(message = "O peso deve ser maior que zero.")
        @DecimalMax(value = "500.00",
                message = "O peso máximo permitido é 500 kg.")
        @Digits(integer = 3, fraction = 2)
        BigDecimal weightKg,

        @NotNull(message = "Informe o comprimento.")
        @Positive
        @Max(value = 300, message = "O comprimento máximo é 300 cm.")
        Integer lengthCm,

        @NotNull(message = "Informe a largura.")
        @Positive
        @Max(value = 300, message = "A largura máxima é 300 cm.")
        Integer widthCm,

        @NotNull(message = "Informe a altura.")
        @Positive
        @Max(value = 300, message = "A altura máxima é 300 cm.")
        Integer heightCm

) {
}