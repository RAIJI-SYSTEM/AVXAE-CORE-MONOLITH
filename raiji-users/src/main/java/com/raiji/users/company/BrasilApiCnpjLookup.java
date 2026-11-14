package com.raiji.users.company;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * BrasilAPI: GET /api/cnpj/v1/{cnpj}. Espelho gratuito dos dados abertos da
 * Receita Federal (podem ter alguns dias de atraso em relação à Receita).
 */
@Component
public class BrasilApiCnpjLookup implements CnpjLookup {

    private static final Logger log = LoggerFactory.getLogger(BrasilApiCnpjLookup.class);

    private final RestClient restClient;

    public BrasilApiCnpjLookup(CompanyProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.cnpjBaseUrl())
                .requestFactory(factory)
                .defaultHeader("User-Agent", "raiji-api")
                .build();
    }

    @Override
    public Optional<CnpjInfo> find(String cnpj) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/cnpj/v1/{cnpj}", cnpj)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (body == null) {
                return Optional.empty();
            }
            return Optional.of(new CnpjInfo(
                    cnpj,
                    str(body.get("razao_social")),
                    str(body.get("nome_fantasia")),
                    str(body.get("descricao_situacao_cadastral")),
                    Boolean.TRUE.equals(body.get("opcao_pelo_mei")),
                    str(body.get("cnae_fiscal_descricao")),
                    str(body.get("municipio")),
                    str(body.get("uf"))));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                return Optional.empty();
            }
            log.warn("BrasilAPI respondeu {} na consulta de CNPJ", e.getStatusCode());
            throw new CnpjLookupUnavailableException("Consulta de CNPJ indisponível.", e);
        } catch (RestClientException e) {
            log.warn("BrasilAPI indisponível: {}", e.getMessage());
            throw new CnpjLookupUnavailableException("Consulta de CNPJ indisponível.", e);
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
