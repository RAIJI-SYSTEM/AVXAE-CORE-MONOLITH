package com.raiji.rides.dto;

import com.raiji.rides.domain.RideIntent;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentStatus;
import com.raiji.rides.domain.enums.RideIntentType;
import org.locationtech.jts.geom.Point;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * @param distanceMeters só na busca (/nearby): distância aproximada (em 100 m) entre o
 *                       ponto pesquisado e a origem da intenção; null nas próprias intenções
 */
public record RideIntentResponse(
        UUID id,
        String userSub,
        RideIntentType type,
        RideIntentMode mode,
        RideIntentStatus status,
        String originCity,
        String originState,
        Double originLatitude,
        Double originLongitude,
        String destinationCity,
        String destinationState,
        Double destinationLatitude,
        Double destinationLongitude,
        OffsetDateTime desiredAt,
        OffsetDateTime expiresAt,
        Double distanceMeters
) {
    /** 2 casas decimais (~1,1 km): suficiente para o mapa e a busca, sem revelar o endereço exato */
    private static final double APPROXIMATION_FACTOR = 100.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    /** Visão completa — para as intenções do próprio usuário */
    public static RideIntentResponse from(RideIntent intent) {
        return new RideIntentResponse(
                intent.getId(),
                intent.getUserSub(),
                intent.getType(),
                intent.getMode(),
                intent.getStatus(),
                intent.getOriginCity(),
                intent.getOriginState(),
                lat(intent.getOriginLocation()),
                lng(intent.getOriginLocation()),
                intent.getDestinationCity(),
                intent.getDestinationState(),
                lat(intent.getDestinationLocation()),
                lng(intent.getDestinationLocation()),
                intent.getDesiredAt(),
                intent.getExpiresAt(),
                null
        );
    }

    /**
     * Visão para OUTRAS pessoas (busca): coordenadas arredondadas (~1 km) e a distância
     * até quem pesquisou. O ponto exato só deve ser combinado depois do aceite.
     */
    public static RideIntentResponse approximate(RideIntent intent, double fromLatitude, double fromLongitude) {
        Point origin = intent.getOriginLocation();
        Double distance = origin == null ? null
                : Math.round(haversine(fromLatitude, fromLongitude, origin.getY(), origin.getX()) / 100.0) * 100.0;
        return new RideIntentResponse(
                intent.getId(),
                intent.getUserSub(),
                intent.getType(),
                intent.getMode(),
                intent.getStatus(),
                intent.getOriginCity(),
                intent.getOriginState(),
                round(lat(origin)),
                round(lng(origin)),
                intent.getDestinationCity(),
                intent.getDestinationState(),
                round(lat(intent.getDestinationLocation())),
                round(lng(intent.getDestinationLocation())),
                intent.getDesiredAt(),
                intent.getExpiresAt(),
                distance
        );
    }

    private static Double lat(Point p) {
        return p == null ? null : p.getY();
    }

    private static Double lng(Point p) {
        return p == null ? null : p.getX();
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * APPROXIMATION_FACTOR) / APPROXIMATION_FACTOR;
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }
}
