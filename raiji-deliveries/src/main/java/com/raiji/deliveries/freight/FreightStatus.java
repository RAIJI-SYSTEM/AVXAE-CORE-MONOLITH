package com.raiji.deliveries.freight;

/** OPEN (esperando motorista) -> ACCEPTED (virou viagem + encomenda) | DECLINED (direta recusada) | CANCELLED. */
public enum FreightStatus { OPEN, ACCEPTED, DECLINED, CANCELLED }
