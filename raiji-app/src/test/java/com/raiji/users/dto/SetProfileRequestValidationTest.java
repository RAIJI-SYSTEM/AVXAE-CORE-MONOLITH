package com.raiji.users.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.raiji.users.domain.UserProfileType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testes unitários (§4) — validações do SetProfileRequest")
class SetProfileRequestValidationTest {

    private final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("CT-U09: role nula viola @NotNull")
    void roleNulaEInvalida() {
        var violacoes = validador.validate(new SetProfileRequest(null));
        assertThat(violacoes).hasSize(1);
        assertThat(violacoes.iterator().next().getPropertyPath().toString()).isEqualTo("role");
    }

    @Test
    @DisplayName("CT-U10: role válida não gera violações")
    void roleValidaPassa() {
        assertThat(validador.validate(new SetProfileRequest(UserProfileType.DRIVER))).isEmpty();
    }
}
