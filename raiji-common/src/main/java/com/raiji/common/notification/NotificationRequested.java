package com.raiji.common.notification;

import java.util.UUID;

/**
 * Evento "avise esta pessoa". Qualquer módulo publica (ApplicationEventPublisher)
 * e o módulo de caronas grava a notificação, sem os módulos dependerem entre si.
 *
 * @param audience    PASSENGER, DRIVER ou COMPANY (em qual caixa de entrada do app aparece)
 * @param kind        assunto, para o app abrir a tela certa (ex.: DELIVERY_ACCEPTED)
 * @param rideId      carona relacionada (pode ser null)
 * @param referenceId id do que a notificação fala (pode ser null)
 */
public record NotificationRequested(
        String recipientSub,
        String audience,
        String kind,
        UUID rideId,
        UUID referenceId,
        String message
) {
}
