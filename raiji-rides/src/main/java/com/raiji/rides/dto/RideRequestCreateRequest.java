package com.raiji.rides.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RideRequestCreateRequest(

        @NotBlank(message = "O nome do passageiro é obrigatório.")
        @Size(max = 150, message = "O nome deve ter no máximo 150 caracteres.")
        String passengerName

) {}