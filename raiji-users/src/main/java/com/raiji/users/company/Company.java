package com.raiji.users.company;

import java.time.Instant;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "companies")
public class Company extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 14, unique = true)
    private String cnpj;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "trade_name", length = 200)
    private String tradeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanyKind kind;

    @Column(name = "registration_status", nullable = false, length = 40)
    private String registrationStatus;

    @Column(name = "main_activity", length = 255)
    private String mainActivity;

    @Column(length = 100)
    private String city;

    @Column(length = 2)
    private String state;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    /** Nome para mostrar: o fantasia, se houver; senão a razão social. */
    public String displayName() {
        return tradeName == null || tradeName.isBlank() ? legalName : tradeName;
    }

    public UUID getId() { return id; }
    public String getCnpj() { return cnpj; }
    public void setCnpj(String v) { this.cnpj = v; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String v) { this.legalName = v; }
    public String getTradeName() { return tradeName; }
    public void setTradeName(String v) { this.tradeName = v; }
    public CompanyKind getKind() { return kind; }
    public void setKind(CompanyKind v) { this.kind = v; }
    public String getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(String v) { this.registrationStatus = v; }
    public String getMainActivity() { return mainActivity; }
    public void setMainActivity(String v) { this.mainActivity = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant v) { this.verifiedAt = v; }
}
