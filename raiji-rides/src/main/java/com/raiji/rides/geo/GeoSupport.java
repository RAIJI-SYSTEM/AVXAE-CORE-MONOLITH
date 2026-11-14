package com.raiji.rides.geo;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/** Pontos e rotas em WGS84 (SRID 4326, x = longitude, y = latitude). */
public final class GeoSupport {

    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    /** Limite de pontos aceitos numa rota (Mapbox costuma mandar algumas centenas). */
    public static final int MAX_ROUTE_POINTS = 5_000;

    private GeoSupport() {
    }

    public static Point point(double latitude, double longitude) {
        validate(latitude, longitude);
        Point p = FACTORY.createPoint(new Coordinate(longitude, latitude));
        p.setSRID(4326);
        return p;
    }

    /** Rota a partir de [latitude, longitude]; sem rota, linha reta entre origem e destino. */
    public static LineString route(List<LatLng> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("A rota precisa de pelo menos 2 pontos.");
        }
        if (points.size() > MAX_ROUTE_POINTS) {
            throw new IllegalArgumentException("A rota tem pontos demais (máximo " + MAX_ROUTE_POINTS + ").");
        }
        Coordinate[] coords = points.stream()
                .peek(p -> validate(p.latitude(), p.longitude()))
                .map(p -> new Coordinate(p.longitude(), p.latitude()))
                .toArray(Coordinate[]::new);
        LineString line = FACTORY.createLineString(coords);
        line.setSRID(4326);
        return line;
    }

    /** Comprimento aproximado da rota (soma das distâncias de grande círculo). */
    public static int lengthMeters(LineString line) {
        double total = 0;
        Coordinate[] c = line.getCoordinates();
        for (int i = 1; i < c.length; i++) {
            total += haversine(c[i - 1].y, c[i - 1].x, c[i].y, c[i].x);
        }
        return (int) Math.round(total);
    }

    public static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }

    public static void validate(double latitude, double longitude) {
        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude inválida.");
        }
        if (Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude inválida.");
        }
    }

    public static Double latitude(Point p) {
        return p == null ? null : p.getY();
    }

    public static Double longitude(Point p) {
        return p == null ? null : p.getX();
    }
}
