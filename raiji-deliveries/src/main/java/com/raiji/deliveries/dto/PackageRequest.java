package com.raiji.deliveries.dto;

import java.math.BigDecimal;

import com.raiji.deliveries.domain.PackageSize;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * O pacote e quem recebe. Medidas opcionais: sem elas, valem as do tamanho
 * (ENVELOPE 35×25×3, SMALL 30×20×15, MEDIUM 50×40×30, LARGE 80×60×50 cm).
 * Peso obrigatório, até o máximo do tamanho (1, 5, 15, 30 kg).
 */
public record PackageRequest(
        @NotNull(message = "Escolha o tamanho (ENVELOPE, SMALL, MEDIUM ou LARGE).") PackageSize size,
        @NotBlank(message = "Descreva o que vai no pacote.") @Size(max = 200) String description,
        @NotNull(message = "Informe o peso aproximado (kg).") @Positive @Digits(integer = 6, fraction = 2) BigDecimal weightKg,
        @Positive Integer lengthCm,
        @Positive Integer widthCm,
        @Positive Integer heightCm,
        boolean fragile,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal declaredValue,
        @NotBlank(message = "Informe o nome de quem recebe.") @Size(max = 150) String recipientName,
        @NotBlank(message = "Informe o telefone de quem recebe.")
        @Pattern(regexp = "^[0-9+()\\-\\s]{8,20}$", message = "Telefone inválido.") String recipientPhone
) {
}
