package com.raiji.rides.domain;

/**
 * Em qual "modo" do app a notificação aparece. A mesma pessoa pode ser
 * passageira numa carona e motorista em outra: o app separa as caixas de entrada.
 */
public enum NotificationAudience {
    /** Ex.: "sua solicitação foi aceita" */
    PASSENGER,
    /** Ex.: "nova solicitação na sua carona", "reserva cancelada" */
    DRIVER,
    /** Avisos de empresa (convites, propostas e ofertas de frete) */
    COMPANY
}
