package com.raiji.users.driververification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Pedido para virar motorista (verificação da CNH).
 * O código de segurança da CNH NUNCA é gravado; a foto fica no armazenamento
 * só até a decisão (imageKey vira null quando ela é apagada).
 */
@Entity
@Table(name = "driver_applications")
public class DriverApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String sub;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DriverApplicationStatus status;

    /** Nome da conta no momento do envio (comparado com o da CNH) */
    @Column(name = "account_name", length = 200)
    private String accountName;

    @Column(name = "image_key", length = 200)
    private String imageKey;

    @Column(name = "cnh_name", length = 200)
    private String cnhName;

    @Column(length = 11)
    private String cpf;

    @Column(length = 20)
    private String registro;

    @Column(length = 10)
    private String categoria;

    private LocalDate validade;

    /** Motivo mostrado ao usuário (rejeição ou falha) */
    @Column(length = 500)
    private String reason;

    @Column(name = "ocr_code")
    private Integer ocrCode;

    @Column(name = "validation_code")
    private Integer validationCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public String getSub() { return sub; }
    public void setSub(String sub) { this.sub = sub; }

    public DriverApplicationStatus getStatus() { return status; }
    public void setStatus(DriverApplicationStatus status) { this.status = status; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public String getImageKey() { return imageKey; }
    public void setImageKey(String imageKey) { this.imageKey = imageKey; }

    public String getCnhName() { return cnhName; }
    public void setCnhName(String cnhName) { this.cnhName = cnhName; }

    public String getCpf() { return cpf; }
    public void setCpf(String cpf) { this.cpf = cpf; }

    public String getRegistro() { return registro; }
    public void setRegistro(String registro) { this.registro = registro; }

    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }

    public LocalDate getValidade() { return validade; }
    public void setValidade(LocalDate validade) { this.validade = validade; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Integer getOcrCode() { return ocrCode; }
    public void setOcrCode(Integer ocrCode) { this.ocrCode = ocrCode; }

    public Integer getValidationCode() { return validationCode; }
    public void setValidationCode(Integer validationCode) { this.validationCode = validationCode; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
