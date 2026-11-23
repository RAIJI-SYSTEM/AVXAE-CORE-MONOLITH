package com.raiji.rides.push;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Envia pelo Firebase Cloud Messaging (API HTTP v1), autenticado com a conta de serviço do
 * projeto Firebase (arquivo JSON em {@code raiji.push.firebase-credentials-file}).
 * Sem o arquivo, o push fica desligado e nada é enviado.
 */
@Component
public class FcmSender {

    /** Resultado do envio para um token */
    public enum Result { SENT, INVALID_TOKEN, FAILED }

    private static final Logger log = LoggerFactory.getLogger(FcmSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    /** Canal criado pelo app Android (som e prioridade alta) */
    static final String ANDROID_CHANNEL = "raiji";

    private final GoogleCredentials credentials;
    private final String projectId;
    private final RestClient http;

    public FcmSender(@Value("${raiji.push.firebase-credentials-file:}") String credentialsFile, ObjectMapper json) {
        GoogleCredentials creds = null;
        String project = null;
        if (credentialsFile != null && !credentialsFile.isBlank()) {
            Path path = Path.of(credentialsFile);
            if (Files.isRegularFile(path)) {
                try (InputStream in = new FileInputStream(path.toFile())) {
                    JsonNode node = json.readTree(Files.readAllBytes(path));
                    project = node.path("project_id").asText(null);
                    creds = GoogleCredentials.fromStream(in).createScoped(List.of(SCOPE));
                } catch (IOException | RuntimeException e) {
                    log.error("Push desligado: não foi possível ler a conta de serviço do Firebase ({})", e.getMessage());
                    creds = null;
                }
            } else {
                log.warn("Push desligado: arquivo da conta de serviço do Firebase não encontrado em {}", credentialsFile);
            }
        }
        this.credentials = creds;
        this.projectId = project;
        this.http = RestClient.create("https://fcm.googleapis.com");
        if (enabled()) log.info("Push ligado (Firebase, projeto {})", projectId);
    }

    public boolean enabled() {
        return credentials != null && projectId != null;
    }

    public Result send(String token, PushMessage message) {
        if (!enabled()) return Result.FAILED;
        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();
            Map<String, Object> body = Map.of("message", Map.of(
                    "token", token,
                    "notification", Map.of("title", message.title(), "body", message.body()),
                    "data", message.data(),
                    "android", Map.of("priority", "high",
                            "notification", Map.of("channel_id", ANDROID_CHANNEL, "sound", "default"))));
            return http.post()
                    .uri("/v1/projects/{project}/messages:send", projectId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> classify(response.getStatusCode(), response.bodyTo(String.class)));
        } catch (IOException | RuntimeException e) {
            log.warn("Falha ao enviar push: {}", e.getMessage());
            return Result.FAILED;
        }
    }

    /** 404 UNREGISTERED / 400 com token inválido: o aparelho não recebe mais */
    static Result classify(HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) return Result.SENT;
        String text = body == null ? "" : body;
        if (status.value() == 404 || text.contains("UNREGISTERED")
                || (status.value() == 400 && text.contains("registration token"))) {
            return Result.INVALID_TOKEN;
        }
        log.warn("Firebase recusou o push ({}): {}", status.value(), text.length() > 300 ? text.substring(0, 300) : text);
        return Result.FAILED;
    }
}
