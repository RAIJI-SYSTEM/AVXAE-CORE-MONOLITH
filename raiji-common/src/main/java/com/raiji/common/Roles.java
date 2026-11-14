package com.raiji.common;

public final class Roles {
    public static final String ADMIN = "admin";
    /** Acumulável com PASSENGER: a mesma conta pode ser motorista e passageira */
    public static final String DRIVER = "DRIVER";
    /** Toda conta tem (faz parte das roles padrão do realm) */
    public static final String PASSENGER = "PASSENGER";
    /** Tipo da conta — exclusivos entre si */
    public static final String PF = "PF";
    public static final String PJ = "PJ";

    private Roles() {
    }
}
