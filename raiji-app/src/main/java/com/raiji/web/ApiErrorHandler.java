package com.raiji.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Devolve ao app o MOTIVO dos erros de negócio (ResponseStatusException), que
 * são mensagens escritas para o usuário — ex.: "Para ser motorista, envie sua CNH...".
 * O Spring Boot, por padrão, omite esse texto.
 *
 * Só trata ResponseStatusException: erros inesperados (500) continuam com a
 * resposta genérica do Spring, sem expor detalhes internos.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", ex.getStatusCode().value());
        body.put("error", status != null ? status.getReasonPhrase() : "Error");
        body.put("message", ex.getReason());
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(ex.getStatusCode()).headers(ex.getHeaders()).body(body);
    }
}
