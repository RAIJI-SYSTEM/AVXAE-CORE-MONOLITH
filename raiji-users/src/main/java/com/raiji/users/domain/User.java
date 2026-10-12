package com.raiji.users.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.enums.CNHCategory;
import com.raiji.common.enums.UserType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_keycloak", columnList = "keycloak_sub"),
        @Index(name = "idx_users_document", columnList = "document")
})
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "keycloak_sub", unique = true, nullable = false, length = 64)
    private String keycloakSub;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false)
    private UserType type;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(unique = true, nullable = false, length = 150)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(unique = true, nullable = false, length = 14)
    private String document;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "cnh_number", unique = true, length = 20)
    private String cnhNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "cnh_category", length = 5)
    private CNHCategory cnhCategory;

    @Column(name = "cnh_validity")
    private LocalDate cnhValidity;

    @Column(name = "is_driver_verified")
    private boolean driverVerified = false;

    public boolean canDrive() {
        return cnhNumber != null && cnhValidity != null
                && cnhValidity.isAfter(LocalDate.now()) && driverVerified;
    }

    public UUID getId() { return id; }
    public String getKeycloakSub() { return keycloakSub; }
    public void setKeycloakSub(String v) { this.keycloakSub = v; }
    public UserType getType() { return type; }
    public void setType(UserType v) { this.type = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getDocument() { return document; }
    public void setDocument(String v) { this.document = v; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String v) { this.photoUrl = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getCnhNumber() { return cnhNumber; }
    public void setCnhNumber(String v) { this.cnhNumber = v; }
    public CNHCategory getCnhCategory() { return cnhCategory; }
    public void setCnhCategory(CNHCategory v) { this.cnhCategory = v; }
    public LocalDate getCnhValidity() { return cnhValidity; }
    public void setCnhValidity(LocalDate v) { this.cnhValidity = v; }
    public boolean isDriverVerified() { return driverVerified; }
    public void setDriverVerified(boolean v) { this.driverVerified = v; }
}
