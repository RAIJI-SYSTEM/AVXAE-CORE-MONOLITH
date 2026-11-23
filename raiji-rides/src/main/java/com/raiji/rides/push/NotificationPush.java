package com.raiji.rides.push;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Toda notificação do app também vai por push. O título vem do assunto; o texto é a
 * própria notificação. Ao tocar, o app abre a caixa de notificações do modo certo.
 */
@Component
public class NotificationPush {

    private final PushService push;

    public NotificationPush(PushService push) {
        this.push = push;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onSaved(NotificationSaved n) {
        if (n.recipientSub() == null) return;
        Map<String, String> data = new HashMap<>();
        data.put("type", "notification");
        if (n.audience() != null) data.put("audience", n.audience());
        if (n.kind() != null) data.put("kind", n.kind());
        if (n.rideId() != null) data.put("rideId", n.rideId().toString());
        if (n.referenceId() != null) data.put("referenceId", n.referenceId().toString());
        push.send(List.of(n.recipientSub()), new PushMessage(titleOf(n.kind()), n.message(), data));
    }

    static String titleOf(String kind) {
        if (kind == null) return "Raiji";
        if (kind.equals("RIDE_REQUEST")) return "Novo pedido de reserva";
        if (kind.equals("RIDE_REQUEST_ACCEPTED")) return "Reserva aceita";
        if (kind.equals("RIDE_REQUEST_REJECTED")) return "Reserva recusada";
        if (kind.equals("RIDE_PAYMENT_RECEIVED")) return "Pagamento recebido";
        if (kind.equals("DRIVER_PAYOUT_SENT")) return "Repasse enviado";
        if (kind.contains("CANCEL")) return "Cancelamento";
        if (kind.startsWith("DELIVERY")) return "Encomenda";
        if (kind.startsWith("FREIGHT") || kind.startsWith("COMPANY")) return "Empresa";
        if (kind.startsWith("BOARD")) return "Mural da região";
        return "Raiji";
    }
}
