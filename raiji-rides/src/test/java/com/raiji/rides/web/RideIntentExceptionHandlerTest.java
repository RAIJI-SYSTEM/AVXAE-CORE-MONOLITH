package com.raiji.rides.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityNotFoundException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * [Regressão][Análise de Falhas] Contrato de erros das intenções de carona.
 *
 * <p>Esses três erros já causaram regressão no passado (404/400/409). O teste
 * fixa o mapeamento e o corpo devolvido ao app.
 */
@DisplayName("[Regressão][Análise de Falhas] RideIntentExceptionHandler")
class RideIntentExceptionHandlerTest {

    private final RideIntentExceptionHandler handler = new RideIntentExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rides/intents");

    private void assertBody(ResponseEntity<Map<String, Object>> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody())
                .containsEntry("status", status.value())
                .containsEntry("error", status.getReasonPhrase())
                .containsEntry("message", message)
                .containsEntry("path", "/api/rides/intents");
        assertThat((String) response.getBody().get("timestamp")).isNotBlank();
    }

    @Test
    @DisplayName("entidade inexistente => 404")
    void naoEncontrado() {
        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(
                new EntityNotFoundException("Intenção não encontrada"), request);

        assertBody(response, HttpStatus.NOT_FOUND, "Intenção não encontrada");
    }

    @Test
    @DisplayName("dados inválidos => 400, nunca 500")
    void dadosInvalidos() {
        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(
                new IllegalArgumentException("Origem e destino são obrigatórios"), request);

        assertBody(response, HttpStatus.BAD_REQUEST, "Origem e destino são obrigatórios");
    }

    @Test
    @DisplayName("conflito de estado => 409")
    void conflito() {
        ResponseEntity<Map<String, Object>> response = handler.handleConflict(
                new IllegalStateException("Intenção já aceita"), request);

        assertBody(response, HttpStatus.CONFLICT, "Intenção já aceita");
    }
}
