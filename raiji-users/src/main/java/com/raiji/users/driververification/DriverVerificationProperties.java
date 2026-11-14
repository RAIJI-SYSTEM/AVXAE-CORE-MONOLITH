package com.raiji.users.driververification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Verificação da CNH para virar motorista.
 *
 * <pre>
 * raiji.driver-verification.infosimples.*  token, base-url, login gov.br, timeout
 * raiji.driver-verification.storage.*      onde a foto fica durante a verificação
 * </pre>
 */
@ConfigurationProperties(prefix = "raiji.driver-verification")
public record DriverVerificationProperties(Infosimples infosimples, Storage storage) {

    public record Infosimples(
            String baseUrl,
            String token,
            String govbrCpf,
            String govbrPassword,
            Integer timeoutSeconds) {

        public boolean configured() {
            return token != null && !token.isBlank();
        }

        public boolean hasGovbrLogin() {
            return govbrCpf != null && !govbrCpf.isBlank()
                    && govbrPassword != null && !govbrPassword.isBlank();
        }

        public int timeout() {
            return timeoutSeconds == null ? 120 : Math.max(15, Math.min(timeoutSeconds, 600));
        }
    }

    /** endpoint/chaves vazios = S3 da AWS com as credenciais padrão (role da EC2) */
    public record Storage(
            String bucket,
            String region,
            String endpoint,
            Boolean pathStyle,
            String accessKey,
            String secretKey) {
    }
}
