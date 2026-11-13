package com.raiji.rides.dto;

import java.util.List;

import com.raiji.rides.geo.LatLng;

/** Detalhe da carona com os pontos da rota (para desenhar no mapa). */
public record RideDetailResponse(RideResponse ride, List<LatLng> route) {
}
