package com.raiji.users.driververification;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Cliente da API da Infosimples (só o back-end chama: o token nunca vai ao app).
 *
 * <ul>
 *   <li>OCR / CNH: {@code POST /api/v2/imagens/ocr/cnh} — lê os dados da foto</li>
 *   <li>SENATRAN / Validar CNH: {@code POST /api/v2/consultas/senatran/validar-cnh}
 *       — confere na base RENACH, usando o login gov.br da empresa</li>
 * </ul>
 *
 * A API responde HTTP 200 com o resultado real no campo {@code code}
 * (200 = sucesso; 6xx = erros, alguns cobrados). Ver {@link CnhDecision}.
 */
@Component
public class InfosimplesClient {

    private static final Logger log = LoggerFactory.getLogger(InfosimplesClient.class);

    private final DriverVerificationProperties.Infosimples config;
    private final RestClient restClient;

    public InfosimplesClient(DriverVerificationProperties properties) {
        this.config = properties.infosimples();
        // Timeout do cliente HTTP maior que o "timeout" pedido à Infosimples (recomendação deles)
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
        factory.setReadTimeout(Duration.ofSeconds(config.timeout() + 30L));
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl() == null ? "https://api.infosimples.com" : config.baseUrl())
                .requestFactory(factory)
                .build();
    }

    public boolean configured() {
        return config.configured();
    }

    /** Lê os dados da CNH na imagem (frente, verso ou os dois lados). */
    public Response ocrCnh(byte[] image) {
        MultiValueMap<String, String> form = baseForm();
        form.add("image_base64", Base64.getEncoder().encodeToString(image));
        return post("/api/v2/imagens/ocr/cnh", form);
    }

    /** Confere a CNH na base do SENATRAN. */
    public Response validarCnh(String cpf, String registro, String codigoSeguranca, String nomeCondutor) {
        MultiValueMap<String, String> form = baseForm();
        form.add("cpf", cpf);
        form.add("registro", registro);
        form.add("codigo_seguranca", codigoSeguranca);
        if (nomeCondutor != null && !nomeCondutor.isBlank()) form.add("nome_condutor", nomeCondutor);
        if (config.hasGovbrLogin()) {
            form.add("login_cpf", config.govbrCpf());
            form.add("login_senha", config.govbrPassword());
        }
        return post("/api/v2/consultas/senatran/validar-cnh", form);
    }

    private MultiValueMap<String, String> baseForm() {
        if (!config.configured()) {
            throw new IllegalStateException("Infosimples sem token configurado");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", config.token());
        form.add("timeout", String.valueOf(config.timeout()));
        return form;
    }

    @SuppressWarnings("unchecked")
    private Response post(String path, MultiValueMap<String, String> form) {
        Map<String, Object> body = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (body == null) {
            return new Response(600, "resposta vazia", List.of(), List.of(), false);
        }
        int code = body.get("code") instanceof Number n ? n.intValue() : 600;
        Map<String, Object> header = body.get("header") instanceof Map<?, ?> h ? (Map<String, Object>) h : Map.of();
        Response response = new Response(
                code,
                String.valueOf(body.getOrDefault("code_message", "")),
                body.get("data") instanceof List<?> d ? (List<Map<String, Object>>) d : List.of(),
                body.get("errors") instanceof List<?> e ? (List<String>) e : List.of(),
                Boolean.TRUE.equals(header.get("billable")));
        // Nunca loga dados pessoais nem o token: só serviço, código e cobrança
        log.info("Infosimples {} -> code={} ({}), cobrado={}", path, code, response.codeMessage(), response.billable());
        if (code != 200 && !response.errors().isEmpty()) {
            log.debug("Infosimples {} errors={}", path, response.errors());
        }
        return response;
    }

    /**
     * @param errors mensagens técnicas da Infosimples — NÃO devem ser mostradas ao usuário
     */
    public record Response(
            int code,
            String codeMessage,
            List<Map<String, Object>> data,
            List<String> errors,
            boolean billable) {

        public Map<String, Object> first() {
            return data.isEmpty() ? Map.of() : data.get(0);
        }
    }
}
