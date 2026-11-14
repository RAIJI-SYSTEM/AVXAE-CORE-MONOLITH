package com.raiji.users.company;

/** Dados do CNPJ na Receita Federal. */
public record CnpjInfo(
        String cnpj,
        String legalName,
        String tradeName,
        /** Ex.: ATIVA, BAIXADA, SUSPENSA, INAPTA, NULA */
        String registrationStatus,
        boolean mei,
        String mainActivity,
        String city,
        String state
) {
    public boolean active() {
        return "ATIVA".equalsIgnoreCase(registrationStatus);
    }
}
