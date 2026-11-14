package com.raiji.users.company;

import java.util.Optional;

/**
 * Consulta de CNPJ na Receita. Implementação padrão: BrasilAPI (dados abertos da
 * Receita, grátis). Dá para trocar por Serpro (API oficial paga) ou Infosimples
 * implementando esta interface.
 */
public interface CnpjLookup {

    /** Vazio = CNPJ não existe na Receita. Fora do ar => {@link CnpjLookupUnavailableException}. */
    Optional<CnpjInfo> find(String cnpjDigits);

    class CnpjLookupUnavailableException extends RuntimeException {
        public CnpjLookupUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
