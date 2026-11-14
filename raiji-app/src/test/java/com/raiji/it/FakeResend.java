package com.raiji.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * Resend FALSO para os testes de integração (servidor HTTP local).
 * Configurado em {@link RaijiTestSupport} para TODOS os testes: nenhum e-mail real sai.
 */
public final class FakeResend {

    public static final String API_KEY = "re_chave_falsa_de_teste";

    /** E-mails "enviados": cabeçalhos relevantes + corpo JSON */
    public static final List<Map<String, String>> EMAILS = new CopyOnWriteArrayList<>();

    private static HttpServer server;

    private FakeResend() {
    }

    public static synchronized String start() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                EMAILS.add(Map.of(
                        "path", exchange.getRequestURI().getPath(),
                        "auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                        "idempotencyKey", String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                        "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] out = "{\"id\":\"fake-email\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
