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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class RideIntentService {

    private static final int PASSENGER_EXPIRATION_MINUTES = 30;
    private static final int DRIVER_EXPIRATION_MINUTES = 60;
    private static final int FUTURE_EXPIRATION_TOLERANCE_MINUTES = 60;
    private static final int IMMEDIATE_WINDOW_MINUTES = 60;
    private static final double MAX_RADIUS_METERS = 100_000;
    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

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
                destinationLatitude, destinationLongitude, desiredAt);

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

        OffsetDateTime now = OffsetDateTime.now();

        if (desiredAt != null && desiredAt.isAfter(now)) {
            intent.setExpiresAt(desiredAt.plusMinutes(
                    FUTURE_EXPIRATION_TOLERANCE_MINUTES));
        } else {
            intent.setExpiresAt(now.plusMinutes(expirationMinutes));
        }

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
        validateSearch(latitude, longitude, radiusMeters);

        return repository.findNearby(
                        type.name(), mode.name(),
                        latitude, longitude, radiusMeters)
                .stream()
                .filter(intent -> !intent.getUserSub().equals(userSub))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RideIntent> findBoard(
            String userSub,
            RideIntentType type,
            RideIntentMode mode,
            double latitude,
            double longitude,
            double radiusMeters,
            LocalDate date
    ) {
        validateSearch(latitude, longitude, radiusMeters);

        // O "dia" do mural é o dia no Brasil, não o do servidor (UTC no Docker:
        // o "hoje" começaria às 21h de Brasília)
        ZoneId zone = BRAZIL;
        if (date == null || date.isBefore(LocalDate.now(zone))) {
            throw new IllegalArgumentException(
                    "A data do mural deve ser hoje ou uma data futura.");
        }

        OffsetDateTime startAt = date.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime endAt = date.plusDays(1)
                .atStartOfDay(zone).toOffsetDateTime();

        return repository.findBoard(
                        type.name(), mode.name(),
                        latitude, longitude, radiusMeters,
                        startAt, endAt)
                .stream()
                .filter(intent -> !intent.getUserSub().equals(userSub))
                .toList();
    }

    @Transactional
    public void cancel(String userSub, UUID intentId) {
        RideIntent intent = repository.findById(intentId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Intenção de carona não encontrada."));

        // Intenção de outra pessoa: responde como "não encontrada" (404), para não
        // confirmar a quem testa IDs que aquela intenção existe
        if (!intent.getUserSub().equals(userSub)) {
            throw new EntityNotFoundException("Intenção de carona não encontrada.");
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
                        userSub, RideIntentStatus.ACTIVE)
                .stream()
                .filter(intent -> intent.getExpiresAt().isAfter(
                        OffsetDateTime.now()))
                .toList();
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
            Double destinationLongitude,
            OffsetDateTime desiredAt
    ) {
        if (type == null || mode == null) {
            throw new IllegalArgumentException(
                    "Tipo e modo da intenção são obrigatórios.");
        }

        validateCoordinates(originLatitude, originLongitude);

        if (desiredAt != null && desiredAt.isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException(
                    "O horário desejado não pode estar no passado.");
        }

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

    private void validateSearch(
            double latitude,
            double longitude,
            double radiusMeters
    ) {
        validateCoordinates(latitude, longitude);

        if (radiusMeters <= 0 || radiusMeters > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException(
                    "O raio deve ser maior que zero e no máximo 100 km.");
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
