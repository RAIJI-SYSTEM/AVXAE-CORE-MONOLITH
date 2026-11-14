package com.raiji.users.driververification;

/**
 * Guarda a foto da CNH só enquanto a verificação acontece (apagada na decisão;
 * o bucket ainda tem uma regra de expiração de 1 dia como garantia).
 */
public interface CnhImageStorage {

    void put(String key, byte[] content, String contentType);

    byte[] get(String key);

    void delete(String key);
}
