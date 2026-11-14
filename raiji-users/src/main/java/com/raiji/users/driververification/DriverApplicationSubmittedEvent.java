package com.raiji.users.driververification;

import java.util.UUID;

/**
 * Publicado ao receber a CNH; processado depois do commit, em segundo plano.
 * O código de segurança só existe em memória (nunca é gravado nem logado).
 */
public record DriverApplicationSubmittedEvent(UUID applicationId, String securityCode) {

    @Override
    public String toString() {
        return "DriverApplicationSubmittedEvent[applicationId=" + applicationId + ", securityCode=***]";
    }
}
