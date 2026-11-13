package com.raiji.rides.cargo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Trava de capacidade de carga da carona (RN12), compartilhada entre a bagagem
 * dos passageiros e as encomendas.
 *
 * <p>Ocupam espaço: a bagagem em vigor dos passageiros CONFIRMED e as encomendas
 * aceitas. Pedidos pendentes e alterações esperando aprovação não ocupam.
 * Limites (só quando declarados): peso máximo da carona (cargoDetails.maxWeightKg)
 * e volume do porta-malas do veículo (cargoVolumeLiters).
 */
@Service
public class CargoCapacityService {

    private final RidePassengerRepository ridePassengerRepository;
    private final VehicleRepository vehicleRepository;
    private final List<RidePackageBookings> packageBookings;

    public CargoCapacityService(RidePassengerRepository ridePassengerRepository,
                                VehicleRepository vehicleRepository,
                                List<RidePackageBookings> packageBookings) {
        this.ridePassengerRepository = ridePassengerRepository;
        this.vehicleRepository = vehicleRepository;
        this.packageBookings = packageBookings;
    }

    /** Carga que já ocupa a carona, sem contar o pedido {@code excludeBookingId}. */
    public CargoLoad currentLoad(UUID rideId, UUID excludeBookingId) {
        CargoLoad total = CargoLoad.NONE;
        for (RidePassenger p : ridePassengerRepository.findByRideIdAndDeletedAtIsNull(rideId)) {
            if (p.getStatus() != RidePassengerStatus.CONFIRMED) continue;
            if (excludeBookingId != null && excludeBookingId.equals(p.getId())) continue;
            total = total.plus(loadOf(p.activeBaggage()));
        }
        for (RidePackageBookings packages : packageBookings) {
            total = total.plus(packages.confirmedLoad(rideId));
        }
        return total;
    }

    public static CargoLoad loadOf(List<Baggage> items) {
        CargoLoad total = CargoLoad.NONE;
        for (Baggage b : items) {
            total = total.plus(CargoLoad.of(b.getLengthCm(), b.getWidthCm(), b.getHeightCm(),
                    b.getQuantity(), b.getWeightKg()));
        }
        return total;
    }

    /**
     * Garante que {@code extra} cabe na carona além do que já está ocupado
     * (sem contar {@code excludeBookingId}, que está sendo substituído). Excedeu => 400.
     */
    public void assertFits(Ride ride, CargoLoad extra, UUID excludeBookingId) {
        CargoLoad total = currentLoad(ride.getId(), excludeBookingId).plus(extra);

        CargoDetails cargo = ride.getCargoDetails();
        if (cargo != null && cargo.hasCargoCapacity()
                && total.weightKg().compareTo(cargo.getMaxWeightKg()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Peso total das bagagens (" + plain(total.weightKg())
                            + " kg) excede o limite da carona (" + plain(cargo.getMaxWeightKg()) + " kg).");
        }

        Vehicle vehicle = vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(ride.getVehicleId(), ride.getDriverSub())
                .orElse(null);
        if (vehicle != null && vehicle.getCargoVolumeLiters() != null && vehicle.getCargoVolumeLiters() > 0
                && total.volumeLiters().compareTo(BigDecimal.valueOf(vehicle.getCargoVolumeLiters())) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Volume total das bagagens (" + plain(total.volumeLiters())
                            + " L) excede a capacidade do veículo (" + vehicle.getCargoVolumeLiters() + " L).");
        }
    }

    /** Um volume maior que as medidas máximas aceitas pela carona não cabe, seja qual for o espaço livre. */
    public void assertItemDimensions(Ride ride, int lengthCm, int widthCm, int heightCm) {
        CargoDetails cargo = ride.getCargoDetails();
        if (cargo == null) return;
        if (exceeds(lengthCm, cargo.getMaxLengthCm()) || exceeds(widthCm, cargo.getMaxWidthCm())
                || exceeds(heightCm, cargo.getMaxHeightCm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O volume (" + lengthCm + "×" + widthCm + "×" + heightCm
                            + " cm) é maior que as medidas aceitas nesta carona.");
        }
    }

    private static boolean exceeds(int value, BigDecimal max) {
        return max != null && max.signum() > 0 && BigDecimal.valueOf(value).compareTo(max) > 0;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
