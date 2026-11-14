package com.raiji.rides.cargo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Carga somada: peso (kg) e volume (litros). */
public record CargoLoad(BigDecimal weightKg, BigDecimal volumeLiters) {

    public static final CargoLoad NONE = new CargoLoad(BigDecimal.ZERO, BigDecimal.ZERO);

    public CargoLoad plus(CargoLoad other) {
        return new CargoLoad(weightKg.add(other.weightKg), volumeLiters.add(other.volumeLiters));
    }

    /** Um volume de dimensões em cm × quantidade, com peso unitário opcional. */
    public static CargoLoad of(int lengthCm, int widthCm, int heightCm, int quantity, BigDecimal unitWeightKg) {
        BigDecimal liters = BigDecimal.valueOf((long) lengthCm * widthCm * heightCm)
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(quantity));
        BigDecimal weight = unitWeightKg == null ? BigDecimal.ZERO
                : unitWeightKg.multiply(BigDecimal.valueOf(quantity));
        return new CargoLoad(weight, liters);
    }

    /** true se não for maior que a outra em nenhuma dimensão (peso e volume). */
    public boolean notGreaterThan(CargoLoad other) {
        return weightKg.compareTo(other.weightKg) <= 0 && volumeLiters.compareTo(other.volumeLiters) <= 0;
    }
}
