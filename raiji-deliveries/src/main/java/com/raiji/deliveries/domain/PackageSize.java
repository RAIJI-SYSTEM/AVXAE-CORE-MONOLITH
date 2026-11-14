package com.raiji.deliveries.domain;

import java.math.BigDecimal;

/**
 * Tamanho da encomenda, como o app mostra. Cada tamanho tem medidas padrão
 * (usadas quando a pessoa não informa as exatas) e um peso máximo.
 */
public enum PackageSize {
    ENVELOPE(35, 25, 3, "1"),
    SMALL(30, 20, 15, "5"),
    MEDIUM(50, 40, 30, "15"),
    LARGE(80, 60, 50, "30");

    public final int lengthCm;
    public final int widthCm;
    public final int heightCm;
    public final BigDecimal maxWeightKg;

    PackageSize(int lengthCm, int widthCm, int heightCm, String maxWeightKg) {
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.maxWeightKg = new BigDecimal(maxWeightKg);
    }
}
