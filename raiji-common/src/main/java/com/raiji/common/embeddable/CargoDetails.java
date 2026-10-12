package com.raiji.common.embeddable;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class CargoDetails {

    @Column(name = "cargo_max_weight_kg", precision = 10, scale = 2)
    private BigDecimal maxWeightKg;

    @Column(name = "cargo_max_length_cm", precision = 8, scale = 2)
    private BigDecimal maxLengthCm;

    @Column(name = "cargo_max_width_cm", precision = 8, scale = 2)
    private BigDecimal maxWidthCm;

    @Column(name = "cargo_max_height_cm", precision = 8, scale = 2)
    private BigDecimal maxHeightCm;

    @Column(name = "cargo_observation", columnDefinition = "TEXT")
    private String observation;

    public boolean hasCargoCapacity() {
        return maxWeightKg != null && maxWeightKg.compareTo(BigDecimal.ZERO) > 0;
    }

    public BigDecimal getMaxWeightKg() { return maxWeightKg; }
    public void setMaxWeightKg(BigDecimal v) { this.maxWeightKg = v; }
    public BigDecimal getMaxLengthCm() { return maxLengthCm; }
    public void setMaxLengthCm(BigDecimal v) { this.maxLengthCm = v; }
    public BigDecimal getMaxWidthCm() { return maxWidthCm; }
    public void setMaxWidthCm(BigDecimal v) { this.maxWidthCm = v; }
    public BigDecimal getMaxHeightCm() { return maxHeightCm; }
    public void setMaxHeightCm(BigDecimal v) { this.maxHeightCm = v; }
    public String getObservation() { return observation; }
    public void setObservation(String v) { this.observation = v; }
}
