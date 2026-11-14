package com.raiji.users.mail;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resend — {@code POST https://api.resend.com/emails} com a chave no cabeçalho.
 * Usa o cabeçalho Idempotency-Key para não duplicar envios.
 */
@Component
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final MailProperties.Resend config;
    private final RestClient restClient;

    public ResendEmailSender(MailProperties properties) {
        this.config = properties.resend();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl() == null || config.baseUrl().isBlank() ? "https://api.resend.com" : config.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public void send(EmailMessage message) {
        if (!config.configured()) {
            log.info("Resend sem chave (RESEND_API_KEY): e-mail \"{}\" não enviado", message.subject());
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", config.from());
        body.put("to", List.of(message.to()));
        body.put("subject", message.subject());
        body.put("html", message.html());
        body.put("text", message.text());

        restClient.post()
                .uri("/emails")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .header("Idempotency-Key", message.idempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        // Sem o endereço no log (dado pessoal)
        log.info("E-mail \"{}\" enviado pelo Resend", message.subject());
    }
}
