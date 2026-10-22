package com.raiji.rides.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.dto.RideRequest;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.dto.RideSearchFilter;
import com.raiji.rides.dto.RideSearchResponse;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.repository.RideSpecification;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RideService {

    private final RideRepository rideRepository;
    private final VehicleRepository vehicleRepository;

    public RideService(RideRepository rideRepository, VehicleRepository vehicleRepository) {
        this.rideRepository = rideRepository;
        this.vehicleRepository = vehicleRepository;
    }

    @Transactional
    public RideResponse create(RideRequest request, String driverSub, String driverName) {
        validarDepartureDate(request.departureDate());
        validarCargoDetails(request);

        Vehicle vehicle = vehicleDoMotorista(request.vehicleId(), driverSub);
        validarTotalSeats(request.totalSeats(), vehicle);

        Ride ride = new Ride();
        ride.setType(request.type());
        ride.setDriverSub(driverSub);
        ride.setDriverName(driverName);
        ride.setVehicleId(vehicle.getId());
        ride.setOriginCity(request.originCity().trim());
        ride.setOriginState(request.originState().toUpperCase());
        ride.setDestinationCity(request.destinationCity().trim());
        ride.setDestinationState(request.destinationState().toUpperCase());
        ride.setDepartureDate(request.departureDate());
        ride.setPricePerPassenger(request.pricePerPassenger());
        ride.setTotalSeats(request.totalSeats());
        ride.setAvailableSeats(request.totalSeats());
        ride.setStatus(RideStatus.PLANNED);
        ride.setCargoDetails(request.cargoDetails());

        return RideResponse.from(rideRepository.save(ride));
    }

    @Transactional
    public RideResponse updatePrice(UUID rideId, BigDecimal novoPreco, String driverSub) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Carona não encontrada."));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Carona pertence a outro motorista.");
        }

        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Só é possível alterar o preço em caronas PLANNED (RN08).");
        }

        if (novoPreco != null && novoPreco.signum() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "pricePerPassenger não pode ser negativo.");
        }

        ride.setPricePerPassenger(novoPreco);
        return RideResponse.from(rideRepository.save(ride));
    }

    @Transactional(readOnly = true)
    public RideSearchResponse search(RideSearchFilter filter, Pageable pageable) {
        Pageable sorted = withDefaultSort(pageable);
        Page<Ride> page = rideRepository.findAll(RideSpecification.search(filter), sorted);
        return RideSearchResponse.from(page);
    }

    private Pageable withDefaultSort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    Sort.by("departureDate").ascending());
        }
        return pageable;
    }

    private void validarDepartureDate(LocalDateTime departure) {
        LocalDateTime agora = LocalDateTime.now();

        if (departure.isBefore(agora.plusMinutes(5))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "departureDate deve estar pelo menos 5 minutos no futuro.");
        }

        if (departure.isAfter(agora.plusMonths(3))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "departureDate não pode exceder 3 meses (RN10).");
        }
    }

    private void validarCargoDetails(RideRequest request) {
        boolean ehCarga = request.type() == RideType.CARGO
                || request.type() == RideType.MIXED;

        if (ehCarga && (request.cargoDetails() == null
                || !request.cargoDetails().hasCargoCapacity())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "cargoDetails com capacidade > 0 é obrigatório para type CARGO/MIXED.");
        }
    }

    private Vehicle vehicleDoMotorista(UUID vehicleId, String driverSub) {
        return vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, driverSub)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Veículo não encontrado ou não pertence ao motorista."));
    }

    private void validarTotalSeats(Integer totalSeats, Vehicle vehicle) {
        int maxSeats = vehicle.getPassengerSeats() != null
                ? vehicle.getPassengerSeats()
                : 4;

        if (totalSeats > maxSeats) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "totalSeats não pode exceder os assentos do veículo (RN03).");
        }
    }
}
