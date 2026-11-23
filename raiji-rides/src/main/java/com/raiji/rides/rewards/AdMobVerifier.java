package com.raiji.rides.rewards;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Verificação do callback de anúncio premiado do AdMob (SSV, server-side verification).
 * https://developers.google.com/admob/android/ssv
 *
 * <p>O Google chama a URL com a recompensa na query e uma assinatura ECDSA (P-256/SHA-256)
 * sobre tudo que vem antes de {@code &signature=}. Só callback com assinatura válida credita:
 * ninguém ganha cupom chamando a API direto.
 */
@Component
public class AdMobVerifier {

    public record Callback(String adUnit, String transactionId, String userId, String customData) {
    }

    private static final Logger log = LoggerFactory.getLogger(AdMobVerifier.class);
    private static final String KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";
    private static final Duration KEYS_TTL = Duration.ofHours(24);

    private final RestClient http = RestClient.create();
    private final ObjectMapper json;
    private final Clock clock;
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    public AdMobVerifier(ObjectMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    /** @param rawQuery a query exatamente como chegou (sem decodificar) */
    public Optional<Callback> verify(String rawQuery) {
        if (rawQuery == null) return Optional.empty();
        int cut = rawQuery.indexOf("&signature=");
        if (cut < 0) return Optional.empty();
        Map<String, String> params = parse(rawQuery);
        String signature = params.get("signature");
        String keyId = params.get("key_id");
        if (signature == null || keyId == null) return Optional.empty();
        try {
            PublicKey key = keyFor(keyId);
            if (key == null) return Optional.empty();
            Signature verifier = Signature.getInstance("SHA256withECDSA"); // assinatura em DER, como o AdMob manda
            verifier.initVerify(key);
            verifier.update(rawQuery.substring(0, cut).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(decode(signature))) return Optional.empty();
        } catch (Exception e) {
            log.warn("Callback do AdMob recusado: {}", e.getMessage());
            return Optional.empty();
        }
        return Optional.of(new Callback(params.get("ad_unit"), params.get("transaction_id"), params.get("user_id"),
                params.get("custom_data")));
    }

    private PublicKey keyFor(String keyId) throws Exception {
        boolean stale = fetchedAt.plus(KEYS_TTL).isBefore(Instant.now(clock));
        if (stale || !keys.containsKey(keyId)) refreshKeys(); // o Google pode ter trocado as chaves
        return keys.get(keyId);
    }

    private synchronized void refreshKeys() throws Exception {
        String body = http.get().uri(KEYS_URL).retrieve().body(String.class);
        JsonNode root = json.readTree(body);
        Map<String, PublicKey> fresh = new HashMap<>();
        KeyFactory factory = KeyFactory.getInstance("EC");
        for (JsonNode k : root.path("keys")) {
            fresh.put(k.path("keyId").asText(), factory.generatePublic(new X509EncodedKeySpec(decode(k.path("base64").asText()))));
        }
        keys = fresh;
        fetchedAt = Instant.now(clock);
    }

    static byte[] decode(String base64) {
        String normalized = base64.replace('-', '+').replace('_', '/');
        while (normalized.length() % 4 != 0) normalized += "=";
        return Base64.getDecoder().decode(normalized);
    }

    static Map<String, String> parse(String query) {
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
