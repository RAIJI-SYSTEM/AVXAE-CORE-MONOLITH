package com.raiji.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * Infosimples FALSA para os testes de integração (servidor HTTP local).
 * Configurada para TODOS os testes em {@link RaijiTestSupport}: nenhum teste
 * chama a API de verdade (sem custo, sem token real, sem gastar o limite do gov.br).
 *
 * <p>O cenário é escolhido pelo código de segurança enviado:
 * <pre>
 * 11111111111  CNH válida (categoria B, vence em 3 anos)  -> APPROVED
 * 22222222222  612: CNH não encontrada no SENATRAN       -> REJECTED
 * 33333333333  situação "suspensa"                        -> REJECTED
 * 44444444444  615: fonte indisponível                    -> ERROR
 * 55555555555  nome no SENATRAN diferente da conta        -> REJECTED
 * </pre>
 * Imagem com o conteúdo "ILEGIVEL" -> OCR 611 (não deu para ler).
 * O nome devolvido ("USUARIO TESTE DA SILVA") bate com o dos usuários de teste ("Usuario Teste").
 */
public final class FakeInfosimples {

    public static final String TOKEN = "token-falso-de-teste";
    public static final String CNH_VALIDA = "11111111111";
    public static final String CNH_NAO_ENCONTRADA = "22222222222";
    public static final String CNH_SUSPENSA = "33333333333";
    public static final String FONTE_INDISPONIVEL = "44444444444";
    public static final String NOME_DIFERENTE = "55555555555";

    /** Chamadas recebidas (caminho + parâmetros), para os testes conferirem */
    public static final List<Map<String, String>> CALLS = new CopyOnWriteArrayList<>();

    private static HttpServer server;

    private FakeInfosimples() {
    }

    public static synchronized String start() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String path = exchange.getRequestURI().getPath();
                Map<String, String> call = new HashMap<>(form);
                call.put("_path", path);
                call.remove("image_base64");
                CALLS.add(call);

                String body = TOKEN.equals(form.get("token"))
                        ? (path.endsWith("/ocr/cnh") ? ocr(form) : validar(form))
                        : "{\"code\":601,\"code_message\":\"token inválido\",\"data\":[],\"errors\":[]}";
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
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

    private static String ocr(Map<String, String> form) {
        String image = new String(Base64.getDecoder().decode(form.getOrDefault("image_base64", "")), StandardCharsets.UTF_8);
        if (image.contains("ILEGIVEL")) {
            return "{\"code\":611,\"code_message\":\"dados incompletos\",\"header\":{\"billable\":true},\"data\":[],\"errors\":[]}";
        }
        return """
                {"code":200,"code_message":"ok","header":{"billable":true},"data":[{"tipo":"cnh_frente","campos":{
                  "nome":{"valor":"USUARIO TESTE DA SILVA","score":0.95},
                  "cpf":{"valor":"123.456.789-09","score":0.95},
                  "registro":{"valor":"01234567890","score":0.95},
                  "categoria":{"valor":"B","score":0.9}}}],"errors":[]}""";
    }

    private static String validar(Map<String, String> form) {
        String codigo = form.getOrDefault("codigo_seguranca", "");
        String validade = LocalDate.now().plusYears(3).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        return switch (codigo) {
            case CNH_NAO_ENCONTRADA -> erro(612, true);
            case FONTE_INDISPONIVEL -> erro(615, false);
            case CNH_SUSPENSA -> cnh("suspensa", validade, "USUARIO TESTE DA SILVA");
            case NOME_DIFERENTE -> cnh("válida", validade, "FULANO DE TAL");
            default -> cnh("válida", validade, "USUARIO TESTE DA SILVA");
        };
    }

    private static String cnh(String situacao, String validade, String nome) {
        return """
                {"code":200,"code_message":"ok","header":{"billable":true},"data":[{
                  "categoria":"B","situacao":"%s","validade_data":"%s","nome":"%s",
                  "nome_condutor_identico_ao_informado":true}],"errors":[]}""".formatted(situacao, validade, nome);
    }

    private static String erro(int code, boolean billable) {
        return "{\"code\":%d,\"code_message\":\"erro simulado\",\"header\":{\"billable\":%s},\"data\":[],\"errors\":[]}"
                .formatted(code, billable);
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }
}
