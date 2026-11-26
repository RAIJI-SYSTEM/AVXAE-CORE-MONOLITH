package com.raiji.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

/**
 * [Regressão][Análise de Falhas] Contrato de erros da API.
 *
 * <p>Garante que o app continue recebendo status, mensagem e campos no formato
 * esperado. Também documenta o diagnóstico disponível: o motivo de negócio não
 * é engolido e o caminho da requisição acompanha a resposta.
 */
@DisplayName("[Regressão][Análise de Falhas] ApiErrorHandler — contrato de erros")
class ApiErrorHandlerTest {

    private final ApiErrorHandler handler = new ApiErrorHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rides/1");

    @Test
    @DisplayName("ResponseStatusException preserva status, motivo e caminho")
    void preservaErroDeNegocio() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.CONFLICT, "Já existe uma reserva para esta carona.");

        ResponseEntity<Map<String, Object>> response = handler.handle(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("status", 409)
                .containsEntry("error", "Conflict")
                .containsEntry("message", "Já existe uma reserva para esta carona.")
                .containsEntry("path", "/api/rides/1");
        assertThat(response.getBody().get("timestamp")).isInstanceOf(String.class);
        assertThat((String) response.getBody().get("timestamp")).isNotBlank();
    }

    @Test
    @DisplayName("erro de validação vira 400 com a primeira mensagem e todos os campos")
    void agregaErrosDeValidacao() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "cpf", "CPF é obrigatório"));
        binding.addError(new FieldError("request", "cpf", "CPF duplicado"));
        binding.addError(new FieldError("request", "name", "Nome é obrigatório"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("status", 400)
                .containsEntry("error", "Bad Request")
                .containsEntry("message", "CPF é obrigatório")
                .containsEntry("path", "/api/rides/1");

        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) response.getBody().get("fields");
        assertThat(fields)
                .containsEntry("cpf", "CPF é obrigatório")
                .containsEntry("name", "Nome é obrigatório");
    }

    @Test
    @DisplayName("validação sem campos usa mensagem genérica")
    void validacaoSemCampos() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex, request);

        assertThat(response.getBody()).containsEntry("message", "Dados inválidos.");
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) response.getBody().get("fields");
        assertThat(fields).isEmpty();
    }
}
