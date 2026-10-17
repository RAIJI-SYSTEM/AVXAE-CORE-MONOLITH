
package com.raiji.users.service;

import java.util.List;
import java.util.UUID;

import com.raiji.users.domain.Vehicle;
import com.raiji.users.dto.VehicleRequest;
import com.raiji.users.dto.VehicleResponse;
import com.raiji.users.repository.VehicleRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    public VehicleService(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    @Transactional
    public VehicleResponse create(VehicleRequest request, String ownerSub) {
        String plate = normalizePlate(request.plate());

        if (vehicleRepository.existsByPlate(plate)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe um veículo cadastrado com essa placa."
            );
        }

        Vehicle vehicle = new Vehicle();
        vehicle.setOwnerSub(ownerSub);
        vehicle.setPlate(plate);
        applyRequest(vehicle, request);

        return toResponse(vehicleRepository.save(vehicle));
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> listByOwner(String ownerSub) {
        return vehicleRepository
                .findByOwnerSubAndDeletedAtIsNull(ownerSub)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public VehicleResponse findById(UUID id, String ownerSub) {
        return toResponse(findOwnedVehicle(id, ownerSub));
    }

    @Transactional
    public VehicleResponse update(
            UUID id,
            String ownerSub,
            VehicleRequest request
    ) {
        Vehicle vehicle = findOwnedVehicle(id, ownerSub);
        String plate = normalizePlate(request.plate());

        if (vehicleRepository.existsByPlateAndIdNot(plate, id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe um veículo cadastrado com essa placa."
            );
        }

        vehicle.setPlate(plate);
        applyRequest(vehicle, request);

        return toResponse(vehicleRepository.save(vehicle));
    }

    @Transactional
    public void deactivate(UUID id, String ownerSub) {
        Vehicle vehicle = findOwnedVehicle(id, ownerSub);
        vehicle.softDelete();
        vehicle.setActive(false);
        vehicleRepository.save(vehicle);
    }

    private Vehicle findOwnedVehicle(UUID id, String ownerSub) {
        return vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, ownerSub)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Veículo não encontrado."
                ));
    }

    private void applyRequest(Vehicle vehicle, VehicleRequest request) {
        vehicle.setType(request.type().trim());
        vehicle.setModel(request.model().trim());
        vehicle.setColor(
                request.color() == null ? null : request.color().trim()
        );
        vehicle.setYear(request.year());
        vehicle.setPassengerSeats(request.passengerSeats());
        vehicle.setCargoVolumeLiters(request.cargoVolumeLiters());
    }

    private String normalizePlate(String plate) {
        return plate.trim().toUpperCase();
    }

    private VehicleResponse toResponse(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getOwnerSub(),
                vehicle.getPlate(),
                vehicle.getType(),
                vehicle.getModel(),
                vehicle.getColor(),
                vehicle.getYear(),
                vehicle.getPassengerSeats(),
                vehicle.getCargoVolumeLiters(),
                vehicle.isActive()
        );
    }
}
