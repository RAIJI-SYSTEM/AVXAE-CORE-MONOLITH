package com.raiji.users.mail;

/** Envio de e-mail transacional (implementação: Resend). */
public interface EmailSender {

    /**
     * @param idempotencyKey mesma chave = o provedor não envia duas vezes (ex.: em reprocessamento)
     */
    record EmailMessage(String to, String subject, String html, String text, String idempotencyKey) {
    }

    void send(EmailMessage message);
}
