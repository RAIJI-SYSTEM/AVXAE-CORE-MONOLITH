package com.raiji.users.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * E-mails transacionais (Resend). A chave vem do .env da raiz (RESEND_API_KEY).
 * Sem chave, nada é enviado (só registrado no log).
 *
 * @param from remetente, ex.: "Raiji &lt;nao-responda@seudominio.com.br&gt;". O domínio precisa
 *             estar verificado no Resend; o padrão onboarding@resend.dev só entrega
 *             para o e-mail do dono da conta Resend (bom para testar).
 */
@ConfigurationProperties(prefix = "raiji.mail")
public record MailProperties(Resend resend) {

    public record Resend(String apiKey, String baseUrl, String from) {

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
