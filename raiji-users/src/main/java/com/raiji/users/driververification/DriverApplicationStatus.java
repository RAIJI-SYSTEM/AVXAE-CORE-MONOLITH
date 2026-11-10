package com.raiji.users.driververification;

public enum DriverApplicationStatus {
    /** Foto recebida; consultando a Infosimples */
    PROCESSING,
    /** CNH válida e conferida: a conta recebeu a role DRIVER */
    APPROVED,
    /** Dados não conferem (motivo em "reason"); o usuário pode reenviar */
    REJECTED,
    /** Falha técnica/indisponibilidade; o usuário pode reenviar mais tarde */
    ERROR
}
