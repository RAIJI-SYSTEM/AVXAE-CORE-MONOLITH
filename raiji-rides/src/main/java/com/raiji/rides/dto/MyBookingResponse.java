package com.raiji.rides.dto;

/** "Minhas viagens" do passageiro: o pedido e a carona. */
public record MyBookingResponse(RidePassengerResponse booking, RideResponse ride) {
}
