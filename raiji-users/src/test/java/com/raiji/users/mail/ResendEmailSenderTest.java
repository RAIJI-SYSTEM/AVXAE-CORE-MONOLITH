package com.raiji.users.mail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Contra um servidor HTTP LOCAL que imita o Resend: nenhum e-mail real é enviado. */
class ResendEmailSenderTest {

    private HttpServer server;
    private final List<Map<String, String>> requests = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(Map.of(
                    "path", exchange.getRequestURI().getPath(),
                    "auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "idem", String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = "{\"id\":\"email-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ResendEmailSender sender(String key) {
        return new ResendEmailSender(new MailProperties(new MailProperties.Resend(
                key, "http://127.0.0.1:" + server.getAddress().getPort(), "Raiji <nao-responda@raiji.test>")));
    }

    @Test
    @DisplayName("envia para /emails com a chave, Idempotency-Key, remetente e destinatário")
    void envia() {
        sender("re_falsa").send(new EmailSender.EmailMessage(
                "maria@exemplo.com", "Assunto", "<p>oi</p>", "oi", "driver-approved-1"));

        assertThat(requests).hasSize(1);
        Map<String, String> r = requests.get(0);
        assertThat(r.get("path")).isEqualTo("/emails");
        assertThat(r.get("auth")).isEqualTo("Bearer re_falsa");
        assertThat(r.get("idem")).isEqualTo("driver-approved-1");
        assertThat(r.get("body"))
                .contains("\"from\":\"Raiji <nao-responda@raiji.test>\"")
                .contains("\"to\":[\"maria@exemplo.com\"]")
                .contains("\"subject\":\"Assunto\"");
    }

    @Test
    @DisplayName("sem chave: não chama o Resend")
    void semChave() {
        sender("").send(new EmailSender.EmailMessage("maria@exemplo.com", "Assunto", "h", "t", "k"));

        assertThat(requests).isEmpty();
    }
}
