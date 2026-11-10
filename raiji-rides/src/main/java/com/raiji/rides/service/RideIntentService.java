
package com.raiji.rides.service;

import com.raiji.rides.domain.RideIntent;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentStatus;
import com.raiji.rides.domain.enums.RideIntentType;
import com.raiji.rides.repository.RideIntentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class RideIntentService {

    private static final int PASSENGER_EXPIRATION_MINUTES = 30;
    private static final int DRIVER_EXPIRATION_MINUTES = 60;

    private final RideIntentRepository repository;

    private final GeometryFactory geometryFactory =
            new GeometryFactory(new PrecisionModel(), 4326);

    public RideIntentService(RideIntentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RideIntent create(
            String userSub,
            RideIntentType type,
            RideIntentMode mode,
            String originCity,
            String originState,
            double originLatitude,
            double originLongitude,
            String destinationCity,
            String destinationState,
            Double destinationLatitude,
            Double destinationLongitude,
            OffsetDateTime desiredAt
    ) {
        validateCreation(type, mode, originLatitude, originLongitude,
                destinationLatitude, destinationLongitude);

        RideIntent intent = new RideIntent();

        intent.setUserSub(userSub);
        intent.setType(type);
        intent.setMode(mode);
        intent.setStatus(RideIntentStatus.ACTIVE);

        intent.setOriginCity(originCity);
        intent.setOriginState(originState);
        intent.setOriginLocation(createPoint(originLatitude, originLongitude));

        if (mode == RideIntentMode.ROUTE) {
            intent.setDestinationCity(destinationCity);
            intent.setDestinationState(destinationState);
            intent.setDestinationLocation(
                    createPoint(destinationLatitude, destinationLongitude));
        }

        intent.setDesiredAt(desiredAt);

        int expirationMinutes = type == RideIntentType.PASSENGER_REQUEST
                ? PASSENGER_EXPIRATION_MINUTES
                : DRIVER_EXPIRATION_MINUTES;

        intent.setExpiresAt(
                OffsetDateTime.now().plusMinutes(expirationMinutes));

        return repository.save(intent);
    }

    @Transactional(readOnly = true)
    public List<RideIntent> findNearby(
            String userSub,
            RideIntentType type,
            RideIntentMode mode,
            double latitude,
            double longitude,
            double radiusMeters
    ) {
        validateCoordinates(latitude, longitude);

        if (radiusMeters <= 0 || radiusMeters > 100_000) {
            throw new IllegalArgumentException(
                    "O raio deve ser maior que zero e no máximo 100 km.");
        }

        return repository.findNearby(
                        type.name(),
                        mode.name(),
                        latitude,
                        longitude,
                        radiusMeters)
                .stream()
                .filter(intent -> !intent.getUserSub().equals(userSub))
                .toList();
    }

    @Transactional
    public void cancel(String userSub, UUID intentId) {
        RideIntent intent = repository.findById(intentId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Intenção de carona não encontrada."));

        if (!intent.getUserSub().equals(userSub)) {
            throw new IllegalArgumentException(
                    "Você não pode cancelar uma intenção de outro usuário.");
        }

        if (intent.getStatus() != RideIntentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Somente intenções ativas podem ser canceladas.");
        }

        intent.setStatus(RideIntentStatus.CANCELLED);
        repository.save(intent);
    }

    @Transactional(readOnly = true)
    public List<RideIntent> findMyIntents(String userSub) {
        return repository.findByUserSubAndStatus(
                userSub, RideIntentStatus.ACTIVE);
    }

    private Point createPoint(double latitude, double longitude) {
        Point point = geometryFactory.createPoint(
                new Coordinate(longitude, latitude));
        point.setSRID(4326);
        return point;
    }

    private void validateCreation(
            RideIntentType type,
            RideIntentMode mode,
            double originLatitude,
            double originLongitude,
            Double destinationLatitude,
            Double destinationLongitude
    ) {
        if (type == null || mode == null) {
            throw new IllegalArgumentException(
                    "Tipo e modo da intenção são obrigatórios.");
        }

        validateCoordinates(originLatitude, originLongitude);

        if (mode == RideIntentMode.FREE
                && type != RideIntentType.DRIVER_OFFER) {
            throw new IllegalArgumentException(
                    "O modo FREE é permitido somente para motoristas.");
        }

        if (mode == RideIntentMode.ROUTE) {
            if (destinationLatitude == null
                    || destinationLongitude == null) {
                throw new IllegalArgumentException(
                        "Destino é obrigatório no modo ROUTE.");
            }

            validateCoordinates(
                    destinationLatitude, destinationLongitude);
        }
    }

    private void validateCoordinates(double latitude, double longitude) {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude inválida.");
        }

        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude inválida.");
        }
    }
}
