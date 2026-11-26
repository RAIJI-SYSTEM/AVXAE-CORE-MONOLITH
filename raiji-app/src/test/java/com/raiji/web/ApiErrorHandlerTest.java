package com.raiji.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

class ApiErrorHandlerTest {

    private final ApiErrorHandler handler = new ApiErrorHandler();

    private final MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/users/me");

    @Test
    @SuppressWarnings("unchecked")
    void devolveAMensagemDeNegocioComOStatus() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.CONFLICT, "Placa já cadastrada.");

        ResponseEntity<Map<String, Object>> response = handler.handle(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("status", 409)
                .containsEntry("error", "Conflict")
                .containsEntry("message", "Placa já cadastrada.")
                .containsEntry("path", "/api/users/me");
        assertThat(body.get("timestamp")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void repassaOsCabecalhosDoErro() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "60");
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Calma.") {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };

        ResponseEntity<Map<String, Object>> response = handler.handle(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(response.getBody()).containsEntry("error", "Too Many Requests");
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusDesconhecidoUsaRotuloGenerico() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatusCode.valueOf(599), "Estranho.");
        ResponseEntity<Map<String, Object>> response = handler.handle(ex, request);
        assertThat(response.getBody()).containsEntry("error", "Error");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validacaoDevolveMensagemDoPrimeiroCampoETodosOsCampos() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "body");
        binding.addError(new FieldError("body", "plate", "Placa inválida."));
        binding.addError(new FieldError("body", "plate", "Outra mensagem."));
        binding.addError(new FieldError("body", "model", "Informe o modelo."));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter(), binding);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("status", 400)
                .containsEntry("error", "Bad Request")
                .containsEntry("message", "Placa inválida.")
                .containsEntry("path", "/api/users/me");
        assertThat((Map<String, String>) body.get("fields"))
                .containsEntry("plate", "Placa inválida.")
                .containsEntry("model", "Informe o modelo.")
                .hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validacaoSemCamposUsaMensagemPadrao() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "body");
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter(), binding);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex, request);

        assertThat(response.getBody()).containsEntry("message", "Dados inválidos.");
        assertThat((Map<String, String>) response.getBody().get("fields")).isEmpty();
    }

    private static MethodParameter methodParameter() throws NoSuchMethodException {
        Method method = String.class.getMethod("length");
        return new MethodParameter(method, -1);
    }
}
