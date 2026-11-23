package com.raiji.deliveries.board;

/** OPEN -> ACCEPTED (o dono escolheu uma oferta) | CANCELLED. EXPIRED não é gravado: OPEN com o horário vencido. */
public enum BoardStatus { OPEN, ACCEPTED, CANCELLED, EXPIRED }
