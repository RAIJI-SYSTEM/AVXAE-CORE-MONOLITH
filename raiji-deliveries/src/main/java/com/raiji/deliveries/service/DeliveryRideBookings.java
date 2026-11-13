package com.raiji.deliveries.service;

import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.rides.cargo.CargoLoad;
import com.raiji.rides.cargo.RidePackageBookings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** O módulo de encomendas responde ao de caronas: carga ocupada e o que fazer em cada momento da viagem. */
@Component
public class DeliveryRideBookings implements RidePackageBookings {

    private final DeliveryRepository deliveries;
    private final DeliveryNotifier notifier;

    public DeliveryRideBookings(DeliveryRepository deliveries, DeliveryNotifier notifier) {
        this.deliveries = deliveries;
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
            d.cancel(reason);
            deliveries.save(d);
            notifier.sender(d, kind, "Sua encomenda não vai nesta carona. Motivo: " + reason);
        }
    }
}
