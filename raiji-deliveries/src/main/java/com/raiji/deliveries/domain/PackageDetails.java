package com.raiji.deliveries.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** O pacote e o destinatário (comum à encomenda e à proposta de frete). */
@Embeddable
public class PackageDetails {

    @Enumerated(EnumType.STRING)
    @Column(name = "package_size", nullable = false, length = 20)
    private PackageSize size;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "weight_kg", nullable = false, precision = 8, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "length_cm", nullable = false)
    private Integer lengthCm;

    @Column(name = "width_cm", nullable = false)
    private Integer widthCm;

    @Column(name = "height_cm", nullable = false)
    private Integer heightCm;

    @Column(nullable = false)
    private boolean fragile;

    @Column(name = "declared_value", precision = 12, scale = 2)
    private BigDecimal declaredValue;

    @Column(name = "recipient_name", nullable = false, length = 150)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    public PackageDetails copy() {
        PackageDetails c = new PackageDetails();
        c.size = size; c.description = description; c.weightKg = weightKg;
        c.lengthCm = lengthCm; c.widthCm = widthCm; c.heightCm = heightCm;
        c.fragile = fragile; c.declaredValue = declaredValue;
        c.recipientName = recipientName; c.recipientPhone = recipientPhone;
        return c;
    }

    public PackageSize getSize() { return size; }
    public void setSize(PackageSize v) { this.size = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal v) { this.weightKg = v; }
    public Integer getLengthCm() { return lengthCm; }
    public void setLengthCm(Integer v) { this.lengthCm = v; }
    public Integer getWidthCm() { return widthCm; }
    public void setWidthCm(Integer v) { this.widthCm = v; }
    public Integer getHeightCm() { return heightCm; }
    public void setHeightCm(Integer v) { this.heightCm = v; }
    public boolean isFragile() { return fragile; }
    public void setFragile(boolean v) { this.fragile = v; }
    public BigDecimal getDeclaredValue() { return declaredValue; }
    public void setDeclaredValue(BigDecimal v) { this.declaredValue = v; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String v) { this.recipientName = v; }
    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String v) { this.recipientPhone = v; }
}
