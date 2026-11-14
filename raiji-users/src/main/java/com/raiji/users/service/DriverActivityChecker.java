package com.raiji.users.service;

/**
 * Porta implementada pelo módulo de caronas (raiji-rides): o módulo de usuários
 * pergunta "este motorista tem carona em andamento?" sem depender de raiji-rides
 * (raiji-rides é que depende de raiji-users).
 */
public interface DriverActivityChecker {

    /** true se o usuário tem carona como motorista planejada ou em andamento */
    boolean hasActiveRidesAsDriver(String driverSub);
}
