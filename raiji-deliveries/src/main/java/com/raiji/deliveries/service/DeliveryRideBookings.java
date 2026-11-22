package com.raiji.deliveries.service;

import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.Delivery;
import com.raiji.deliveries.DeliveryStatusHistory;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.deliveries.repository.DeliveryStatusHistoryRepository;
import com.raiji.rides.cargo.CargoLoad;
import com.raiji.rides.cargo.RidePackageBookings;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** O módulo de encomendas responde ao de caronas: carga ocupada e o que fazer em cada momento da viagem. */
@Component
public class DeliveryRideBookings implements RidePackageBookings {

    private final DeliveryRepository deliveries;
    private final DeliveryStatusHistoryRepository history;
    private final DeliveryNotifier notifier;

    public DeliveryRideBookings(DeliveryRepository deliveries,
                                DeliveryStatusHistoryRepository history,
                                DeliveryNotifier notifier) {
        this.deliveries = deliveries;
        this.history = history;
        this.notifier = notifier;
    }

    @Override
    @Transactional(readOnly = true)
    public CargoLoad confirmedLoad(UUID rideId) {
        CargoLoad total = CargoLoad.NONE;
        for (Delivery d : deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId,
                List.of(DeliveryStatus.CONFIRMED, DeliveryStatus.IN_TRANSIT))) {
            total = total.plus(Packages.load(d.getPkg()));
        }
        return total;
    }

    @Override
    @Transactional
    public void rejectPending(UUID rideId, String reason) {
        closeAll(rideId, List.of(DeliveryStatus.PENDING), reason, "DELIVERY_REJECTED");
    }

    @Override
    @Transactional
    public void startConfirmed(UUID rideId, Instant startedAt) {
        for (Delivery d : deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(
                rideId,
                List.of(DeliveryStatus.CONFIRMED))) {

            DeliveryStatus previous = d.getStatus();

            d.startTransit(startedAt);
            d = deliveries.save(d);

            history.save(new DeliveryStatusHistory(
                    d.getId(),
                    previous,
                    DeliveryStatus.IN_TRANSIT,
                    null,
                    "Carona iniciada. Encomenda automaticamente colocada em trânsito."
            ));
        }
    }

    @Override
    @Transactional
    public void cancelNotPickedUp(UUID rideId, String reason) {
        closeAll(rideId, List.of(DeliveryStatus.CONFIRMED), reason, "DELIVERY_CANCELLED");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActive(UUID rideId) {
        return !deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId,
                List.of(DeliveryStatus.PENDING, DeliveryStatus.CONFIRMED, DeliveryStatus.IN_TRANSIT)).isEmpty();
    }

    private void closeAll(UUID rideId, List<DeliveryStatus> statuses, String reason, String kind) {
        for (Delivery d : deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId, statuses)) {
            DeliveryStatus previous = d.getStatus();

            d.cancel(reason);
            d = deliveries.save(d);

            history.save(new DeliveryStatusHistory(
                    d.getId(),
                    previous,
                    DeliveryStatus.CANCELLED,
                    null,
                    reason
            ));

            notifier.sender(d, kind, "Sua encomenda não vai nesta carona. Motivo: " + reason);
        }
    }
}
