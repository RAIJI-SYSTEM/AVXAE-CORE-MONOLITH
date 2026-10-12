package com.raiji.users.domain;

import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "vehicles", indexes = {
        @Index(name = "idx_vehicles_plate", columnList = "plate"),
        @Index(name = "idx_vehicles_owner", columnList = "owner_id")
})
public class Vehicle extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "plate", unique = true, nullable = false, length = 7)
    private String plate;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(length = 50)
    private String color;

    @Column
    private Integer year;

    @Column(name = "passenger_seats")
    private Integer passengerSeats = 4;

    @Column(name = "cargo_volume_liters")
    private Integer cargoVolumeLiters;

    @Column(nullable = false)
    private boolean active = true;

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID v) { this.ownerId = v; }
    public String getPlate() { return plate; }
    public void setPlate(String v) { this.plate = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getColor() { return color; }
    public void setColor(String v) { this.color = v; }
    public Integer getYear() { return year; }
    public void setYear(Integer v) { this.year = v; }
    public Integer getPassengerSeats() { return passengerSeats; }
    public void setPassengerSeats(Integer v) { this.passengerSeats = v; }
    public Integer getCargoVolumeLiters() { return cargoVolumeLiters; }
    public void setCargoVolumeLiters(Integer v) { this.cargoVolumeLiters = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
}
