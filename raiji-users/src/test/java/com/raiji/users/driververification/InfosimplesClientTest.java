package com.raiji.users.driververification;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testa o cliente contra um servidor HTTP LOCAL que imita a Infosimples.
 * Nenhuma chamada sai da máquina (sem custo, sem token real).
 */
class InfosimplesClientTest {

    private HttpServer server;
    private final Map<String, Map<String, String>> recebido = new HashMap<>();
    private String resposta = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recebido.put(exchange.getRequestURI().getPath(), parseForm(body));
            byte[] out = resposta.getBytes(StandardCharsets.UTF_8);
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

    private InfosimplesClient client(String token, String govbrCpf, String govbrSenha) {
        var props = new DriverVerificationProperties(
                new DriverVerificationProperties.Infosimples(
                        "http://127.0.0.1:" + server.getAddress().getPort(), token, govbrCpf, govbrSenha, 60),
                null);
        return new InfosimplesClient(props);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    @Test
    @DisplayName("OCR: envia token e a imagem em Base64; lê code, data e cobrança")
    void ocr() {
        resposta = """
                {"code":200,"code_message":"ok","header":{"billable":true},
                 "data":[{"tipo":"cnh_frente","campos":{"nome":{"valor":"MARIA","score":0.9}}}],
                 "errors":[]}""";
        byte[] imagem = {1, 2, 3, 4};

        InfosimplesClient.Response r = client("tok-123", null, null).ocrCnh(imagem);

        Map<String, String> form = recebido.get("/api/v2/imagens/ocr/cnh");
        assertThat(form).containsEntry("token", "tok-123");
        assertThat(form).containsEntry("image_base64", Base64.getEncoder().encodeToString(imagem));
        assertThat(r.code()).isEqualTo(200);
        assertThat(r.billable()).isTrue();
        assertThat(r.first()).containsEntry("tipo", "cnh_frente");
    }

    @Test
    @DisplayName("Validar CNH: envia CPF, registro, código e o login gov.br quando configurado")
    void validarComLogin() {
        resposta = """
                {"code":612,"code_message":"sem dados","header":{"billable":true},"data":[],"errors":["x"]}""";

        InfosimplesClient.Response r = client("tok", "11122233344", "senha")
                .validarCnh("12345678901", "98765432100", "55566677788", "MARIA");

        Map<String, String> form = recebido.get("/api/v2/consultas/senatran/validar-cnh");
        assertThat(form).containsEntry("cpf", "12345678901")
                .containsEntry("registro", "98765432100")
                .containsEntry("codigo_seguranca", "55566677788")
                .containsEntry("nome_condutor", "MARIA")
                .containsEntry("login_cpf", "11122233344")
                .containsEntry("login_senha", "senha")
                .containsKey("timeout");
        assertThat(r.code()).isEqualTo(612);
        assertThat(r.data()).isEmpty();
    }

    @Test
    @DisplayName("Validar CNH sem login gov.br configurado: não envia login_cpf/login_senha")
    void validarSemLogin() {
        resposta = "{\"code\":200,\"data\":[{}]}";

        client("tok", "", "").validarCnh("1", "2", "3", null);

        assertThat(recebido.get("/api/v2/consultas/senatran/validar-cnh"))
                .doesNotContainKeys("login_cpf", "login_senha", "nome_condutor");
    }

    @Test
    @DisplayName("sem token: não chama nada")
    void semToken() {
        InfosimplesClient c = client("", null, null);

        assertThat(c.configured()).isFalse();
        assertThatThrownBy(() -> c.ocrCnh(new byte[] {1})).isInstanceOf(IllegalStateException.class);
        assertThat(recebido).isEmpty();
    }
}
