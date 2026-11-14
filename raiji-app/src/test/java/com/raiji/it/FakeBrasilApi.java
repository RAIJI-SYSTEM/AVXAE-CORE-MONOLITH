package com.raiji.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import com.sun.net.httpserver.HttpServer;

/**
 * BrasilAPI FALSA (consulta de CNPJ) para os testes de integração: nenhum teste
 * consulta a API real. O cenário sai dos 2 primeiros dígitos do CNPJ:
 * <pre>
 * 99...  situação BAIXADA          98...  404 (não existe na Receita)
 * 97...  500 (fora do ar)          96...  ATIVA, MEI
 * outros ATIVA, empresa
 * </pre>
 * Use {@link #cnpj(String)} para gerar um CNPJ válido (dígitos verificadores) e único.
 */
public final class FakeBrasilApi {

    public static final List<String> CONSULTAS = new CopyOnWriteArrayList<>();
    private static HttpServer server;

    private FakeBrasilApi() {
    }

    public static synchronized String start() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/api/cnpj/v1/", exchange -> {
                String cnpj = exchange.getRequestURI().getPath().substring("/api/cnpj/v1/".length());
                CONSULTAS.add(cnpj);
                int status;
                String body;
                if (cnpj.startsWith("98")) {
                    status = 404;
                    body = "{\"message\":\"CNPJ " + cnpj + " não encontrado.\"}";
                } else if (cnpj.startsWith("97")) {
                    status = 500;
                    body = "{\"message\":\"erro\"}";
                } else {
                    status = 200;
                    String situacao = cnpj.startsWith("99") ? "BAIXADA" : "ATIVA";
                    boolean mei = cnpj.startsWith("96");
                    body = """
                            {"cnpj":"%s","razao_social":"EMPRESA TESTE %s LTDA","nome_fantasia":"%s",
                             "descricao_situacao_cadastral":"%s","opcao_pelo_mei":%s,
                             "cnae_fiscal_descricao":"Transporte rodoviário de carga","municipio":"SAO PAULO","uf":"SP"}
                            """.formatted(cnpj, cnpj.substring(0, 4), mei ? "" : "Loja Teste " + cnpj.substring(0, 4),
                            situacao, mei);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** CNPJ válido e aleatório começando com {@code prefixo} (2 dígitos). */
    public static String cnpj(String prefixo) {
        StringBuilder base = new StringBuilder(prefixo);
        while (base.length() < 8) base.append(ThreadLocalRandom.current().nextInt(10));
        base.append("0001");
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        base.append(dv(base.toString(), w1));
        base.append(dv(base.toString(), w2));
        return base.toString();
    }

    private static int dv(String d, int[] w) {
        int sum = 0;
        for (int i = 0; i < w.length; i++) sum += (d.charAt(i) - '0') * w[i];
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }
}
