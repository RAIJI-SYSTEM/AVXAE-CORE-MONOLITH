package com.raiji.rides.payout;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.raiji.common.notification.NotificationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Repasse manual: quanto a plataforma deve a cada motorista e o registro dos Pix feitos.
 *
 * <p>Entra no saldo: vaga paga de viagem CONCLUÍDA e encomenda paga e ENTREGUE, menos a
 * comissão. Cancelado/estornado não entra. Quem faz o Pix é a plataforma (fora do app); aqui
 * ela só registra, e os itens ficam ligados ao repasse para não serem pagos duas vezes.
 */
@Service
public class DriverPayoutService {

    private static final Logger log = LoggerFactory.getLogger(DriverPayoutService.class);
    static final int MAX_PIX_KEY = 140;

    public record DriverSummary(String pixKey, long pendingCents, List<PayoutItem> pending, List<DriverPayout> payouts) {}

    public record DriverBalance(String driverSub, String driverName, String pixKey, long amountCents,
                                int itemCount, List<PayoutItem> items) {}

    private final PayoutRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DriverPayoutService(PayoutRepository repository, ApplicationEventPublisher events, ObjectProvider<Clock> clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    // ------------------------------------------------------------- motorista

    @Transactional(readOnly = true)
    public DriverSummary mine(String driverSub) {
        List<PayoutItem> pending = repository.pendingItems(driverSub, false);
        return new DriverSummary(
                repository.pixKey(driverSub).orElse(null),
                pending.stream().mapToLong(PayoutItem::driverCents).sum(),
                pending,
                repository.payouts(driverSub, 50));
    }

    @Transactional
    public String savePixKey(String driverSub, String pixKey) {
        String key = pixKey == null ? "" : pixKey.trim();
        if (key.isEmpty() || key.length() > MAX_PIX_KEY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma chave Pix válida.");
        }
        repository.savePixKey(driverSub, key);
        return key;
    }

    // ---------------------------------------------------------------- admin

    /** Saldo a repassar de cada motorista (só quem tem algo a receber) */
    @Transactional(readOnly = true)
    public List<DriverBalance> balances() {
        Map<String, List<PayoutItem>> byDriver = new LinkedHashMap<>();
        for (PayoutItem item : repository.pendingItems(null, false)) {
            byDriver.computeIfAbsent(item.driverSub(), k -> new ArrayList<>()).add(item);
        }
        Map<String, String> keys = repository.pixKeys();
        return byDriver.entrySet().stream()
                .map(e -> new DriverBalance(
                        e.getKey(),
                        e.getValue().get(0).driverName(),
                        keys.get(e.getKey()),
                        e.getValue().stream().mapToLong(PayoutItem::driverCents).sum(),
                        e.getValue().size(),
                        e.getValue()))
                .sorted((a, b) -> Long.compare(b.amountCents(), a.amountCents()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DriverPayout> history() {
        return repository.payouts(null, 100);
    }

    /**
     * Registra que a plataforma fez o Pix ao motorista. {@code expectedCents} é o valor que o
     * admin viu e pagou: se o saldo mudou nesse meio-tempo, nada é registrado (409).
     */
    @Transactional
    public DriverPayout registerPayout(String driverSub, long expectedCents, String note, String adminSub) {
        List<PayoutItem> items = repository.pendingItems(driverSub, true);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este motorista não tem nada a receber.");
        }
        long total = items.stream().mapToLong(PayoutItem::driverCents).sum();
        if (total != expectedCents) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O saldo deste motorista mudou. Atualize a lista e confira o valor antes de registrar.");
        }
        String trimmedNote = note == null || note.isBlank() ? null : note.trim();
        if (trimmedNote != null && trimmedNote.length() > 255) trimmedNote = trimmedNote.substring(0, 255);
        DriverPayout payout = new DriverPayout(UUID.randomUUID(), driverSub, total, items.size(),
                repository.pixKey(driverSub).orElse(null), trimmedNote, Instant.now(clock));
        repository.insertPayout(payout, adminSub);
        repository.markPaidOut(payout.id(), items);
        log.info("Repasse {} registrado: motorista {}, {} itens, {} centavos", payout.id(), driverSub, items.size(), total);

        events.publishEvent(new NotificationRequested(driverSub, "DRIVER", "DRIVER_PAYOUT_SENT", null, payout.id(),
                String.format("Repasse de R$ %d,%02d enviado por Pix.", total / 100, total % 100)));
        return payout;
    }
}
