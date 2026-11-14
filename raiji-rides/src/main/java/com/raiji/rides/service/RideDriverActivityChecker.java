package com.raiji.rides.service;

import java.util.List;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.repository.RideRepository;
import com.raiji.users.service.DriverActivityChecker;
import org.springframework.stereotype.Component;

/**
 * Responde ao módulo de usuários se o motorista ainda tem compromisso:
 * carona PLANNED (com passageiros contando com ela) ou IN_PROGRESS.
 */
@Component
public class RideDriverActivityChecker implements DriverActivityChecker {

    private static final List<RideStatus> ACTIVE = List.of(RideStatus.PLANNED, RideStatus.IN_PROGRESS);

    private final RideRepository rideRepository;

    public RideDriverActivityChecker(RideRepository rideRepository) {
        this.rideRepository = rideRepository;
    }

    @Override
    public boolean hasActiveRidesAsDriver(String driverSub) {
        return rideRepository.existsByDriverSubAndStatusInAndDeletedAtIsNull(driverSub, ACTIVE);
    }
}
