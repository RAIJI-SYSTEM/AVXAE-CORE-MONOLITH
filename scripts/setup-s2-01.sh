#!/usr/bin/env bash
# =============================================================================
# setup-s2-01.sh — S2-01 · Declaração de bagagem na solicitação de vaga (RF18)
# =============================================================================
# Script ÚNICO e autocontido que implementa toda a issue no projeto:
#
#   NOVOS:
#   * raiji-rides/.../domain/BaggageType.java        (enum mochila|sacola|mala|caixa)
#   * raiji-rides/.../domain/Baggage.java            (entidade, FK ride_passengers)
#   * raiji-rides/.../dto/BaggageItemRequest.java    (record + validação)
#   * raiji-rides/.../dto/BaggageItemResponse.java   (record)
#   * raiji-rides/.../db/migration/V8__create_baggage.sql
#   * raiji-app/.../it/BaggageApiIT.java             (critérios de aceite)
#   * tests/s2-01/*                                  (suíte de verificação)
#
#   ATUALIZADOS (contrato S1-06):
#   * RidePassenger        -> @OneToMany Baggage (cascade, mesma transação)
#   * RideRequestCreateRequest -> baggage @NotEmpty @Valid (obrigatório)
#   * RidePassengerResponse    -> expõe baggage (visão do motorista)
#   * RidePassengerService     -> persiste bagagem junto com a solicitação
#   * RideRequestController    -> repassa baggage
#   * RideRequestApiIT / RideRequestControllerTest / RidePassengerServiceTest
#                            -> payloads/assinaturas com bagagem
#   * docs/API.md          -> contrato atualizado
#
# Uso:
#   bash scripts/setup-s2-01.sh           # implementa tudo (idempotente)
#   bash scripts/setup-s2-01.sh --check   # implementa e roda a verificação
# =============================================================================
set -euo pipefail

MODE="apply"
if [[ "${1:-}" == "--check" ]]; then MODE="check"; fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Detecta a RAIZ DO PROJETO com segurança ----------------------------------
ROOT=""
GIT_TOP="$(cd "$SCRIPT_DIR" && git rev-parse --show-toplevel 2>/dev/null || true)"
for cand in "$SCRIPT_DIR" "$SCRIPT_DIR/.." "$GIT_TOP" "$PWD"; do
  [[ -n "$cand" ]] || continue
  if [[ -f "$cand/pom.xml" && -f "$cand/docker-compose.yml" ]]; then
    ROOT="$(cd "$cand" && pwd)"
    break
  fi
done
if [[ -z "$ROOT" ]]; then
  echo "[setup-s2-01] ERRO: raiz do projeto não encontrada (pom.xml + docker-compose.yml)." >&2
  exit 1
fi
cd "$ROOT"
echo "[setup-s2-01] raiz do projeto: $ROOT"

SELF="$(readlink -f "${BASH_SOURCE[0]}")"
CANONICAL="$ROOT/scripts/setup-s2-01.sh"
mkdir -p scripts
if [[ "$SELF" != "$CANONICAL" ]]; then
  cp "$SELF" "$CANONICAL"
  echo "[setup-s2-01] gerador instalado em scripts/setup-s2-01.sh (local canônico)"
fi

GRN=$'\033[0;32m'; NC=$'\033[0m'
log() { printf '%s[setup-s2-01]%s %s\n' "$GRN" "$NC" "$*"; }

write_file() {
  mkdir -p "$(dirname "$1")"
  cat > "$1"
  log "gravado: $1"
}

RIDES_MAIN="raiji-rides/src/main/java/com/raiji/rides"

# =============================================================================
# 1) BaggageType — enum com códigos minúsculos no JSON (contrato da issue)
# =============================================================================
write_file "$RIDES_MAIN/domain/BaggageType.java" <<'@@BAGGAGE_TYPE@@'
package com.raiji.rides.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * S2-01 / RF18 — Tipos de bagagem aceitos na declaração.
 *
 * <p>O contrato da API usa códigos em minúsculas ({@code mochila}, {@code sacola},
 * {@code mala}, {@code caixa}); o banco armazena o {@code name()} do enum
 * (VARCHAR). Valor fora do enum na requisição => erro de desserialização => 400.
 */
public enum BaggageType {

    MOCHILA,
    SACOLA,
    MALA,
    CAIXA;

    @JsonValue
    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static BaggageType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BaggageType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Tipo de bagagem inválido: '" + code
                        + "'. Valores aceitos: mochila, sacola, mala, caixa."
        );
    }
}
@@BAGGAGE_TYPE@@

# =============================================================================
# 2) Baggage — entidade (tabela única com coluna type VARCHAR; sem tabela por tipo)
# =============================================================================
write_file "$RIDES_MAIN/domain/Baggage.java" <<'@@BAGGAGE_ENTITY@@'
package com.raiji.rides.domain;

import java.util.UUID;

import com.raiji.common.base.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * S2-01 / RF18 — Item de bagagem declarado na solicitação de vaga.
 *
 * <p>Tabela única com coluna {@code type} VARCHAR (sem tabela separada por
 * tipo). A soma de peso/volume para validação contra a capacidade da carona
 * será implementada em S2-03.
 */
@Entity
@Table(name = "baggage", indexes = {
        @Index(name = "idx_baggage_ride_passenger", columnList = "ride_passenger_id")
})
public class Baggage extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ride_passenger_id", nullable = false)
    private RidePassenger ridePassenger;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private BaggageType type;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "length_cm", nullable = false)
    private Integer lengthCm;

    @Column(name = "width_cm", nullable = false)
    private Integer widthCm;

    @Column(name = "height_cm", nullable = false)
    private Integer heightCm;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public UUID getId() { return id; }

    public RidePassenger getRidePassenger() { return ridePassenger; }
    public void setRidePassenger(RidePassenger v) { this.ridePassenger = v; }

    public BaggageType getType() { return type; }
    public void setType(BaggageType v) { this.type = v; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer v) { this.quantity = v; }

    public Integer getLengthCm() { return lengthCm; }
    public void setLengthCm(Integer v) { this.lengthCm = v; }

    public Integer getWidthCm() { return widthCm; }
    public void setWidthCm(Integer v) { this.widthCm = v; }

    public Integer getHeightCm() { return heightCm; }
    public void setHeightCm(Integer v) { this.heightCm = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
}
@@BAGGAGE_ENTITY@@

# =============================================================================
# 3) BaggageItemRequest / BaggageItemResponse (records)
# =============================================================================
write_file "$RIDES_MAIN/dto/BaggageItemRequest.java" <<'@@BAGGAGE_ITEM_REQUEST@@'
package com.raiji.rides.dto;

import com.raiji.rides.domain.BaggageType;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * S2-01 / RF18 — Item de bagagem no payload da solicitação de vaga.
 *
 * Regras (critérios de aceite):
 * - type ∈ {mochila, sacola, mala, caixa}  -> fora do enum => 400
 * - quantity >= 1                          -> <= 0         => 400
 * - lengthCm/widthCm/heightCm > 0          -> <= 0         => 400
 */
public record BaggageItemRequest(

        @NotNull(message = "O tipo da bagagem é obrigatório (mochila, sacola, mala, caixa).")
        BaggageType type,

        @NotNull(message = "A quantidade é obrigatória.")
        @Min(value = 1, message = "A quantidade deve ser maior ou igual a 1.")
        Integer quantity,

        @NotNull(message = "O comprimento (lengthCm) é obrigatório.")
        @Positive(message = "O comprimento deve ser maior que zero.")
        Integer lengthCm,

        @NotNull(message = "A largura (widthCm) é obrigatória.")
        @Positive(message = "A largura deve ser maior que zero.")
        Integer widthCm,

        @NotNull(message = "A altura (heightCm) é obrigatória.")
        @Positive(message = "A altura deve ser maior que zero.")
        Integer heightCm,

        @Size(max = 500, message = "A descrição deve ter no máximo 500 caracteres.")
        String description

) {}
@@BAGGAGE_ITEM_REQUEST@@

write_file "$RIDES_MAIN/dto/BaggageItemResponse.java" <<'@@BAGGAGE_ITEM_RESPONSE@@'
package com.raiji.rides.dto;

import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.BaggageType;

/**
 * S2-01 / RF18 — Item de bagagem retornado ao motorista
 * (GET /api/rides/{rideId}/requests) e ao solicitante (POST .../requests).
 */
public record BaggageItemResponse(
        BaggageType type,
        Integer quantity,
        Integer lengthCm,
        Integer widthCm,
        Integer heightCm,
        String description
) {

    public static BaggageItemResponse from(Baggage baggage) {
        return new BaggageItemResponse(
                baggage.getType(),
                baggage.getQuantity(),
                baggage.getLengthCm(),
                baggage.getWidthCm(),
                baggage.getHeightCm(),
                baggage.getDescription()
        );
    }
}
@@BAGGAGE_ITEM_RESPONSE@@

# =============================================================================
# 4) Migração V8__create_baggage.sql
# =============================================================================
write_file "raiji-rides/src/main/resources/db/migration/V8__create_baggage.sql" <<'@@V8_SQL@@'
-- S2-01 / RF18: bagagem declarada na solicitação de vaga.
-- Tabela ÚNICA com coluna "type" VARCHAR (sem tabela separada por tipo).
-- CHECKs espelham as regras de negócio: quantity >= 1 e dimensões > 0.

CREATE TABLE IF NOT EXISTS baggage (
    id UUID PRIMARY KEY,
    ride_passenger_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    quantity INT NOT NULL,
    length_cm INT NOT NULL,
    width_cm INT NOT NULL,
    height_cm INT NOT NULL,
    description TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT DEFAULT 0,
    CONSTRAINT fk_baggage_ride_passengers
        FOREIGN KEY (ride_passenger_id) REFERENCES ride_passengers(id),
    CONSTRAINT chk_baggage_type
        CHECK (type IN ('MOCHILA', 'SACOLA', 'MALA', 'CAIXA')),
    CONSTRAINT chk_baggage_quantity
        CHECK (quantity >= 1),
    CONSTRAINT chk_baggage_dimensions
        CHECK (length_cm > 0 AND width_cm > 0 AND height_cm > 0)
);

CREATE INDEX IF NOT EXISTS idx_baggage_ride_passenger ON baggage(ride_passenger_id);
@@V8_SQL@@

# =============================================================================
# 5) RidePassenger — @OneToMany Baggage (cascade ALL + orphanRemoval)
# =============================================================================
write_file "$RIDES_MAIN/domain/RidePassenger.java" <<'@@RIDE_PASSENGER@@'
package com.raiji.rides.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.enums.RidePassengerStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "ride_passengers", indexes = {
        @Index(name = "idx_ride_pass_ride", columnList = "ride_id"),
        @Index(name = "idx_ride_pass_passenger_sub", columnList = "passenger_sub")
})
public class RidePassenger extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    /** Sub do passageiro no Keycloak. */
    @Column(name = "passenger_sub", nullable = false, length = 64)
    private String passengerSub;

    @Column(name = "passenger_name", length = 150)
    private String passengerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RidePassengerStatus status = RidePassengerStatus.PENDING;

    @Column(name = "joined_at")
    private Instant joinedAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    /**
     * S2-01 / RF18 — bagagem declarada na solicitação.
     * Cascade ALL: persistida junto com a solicitação na MESMA transação.
     */
    @OneToMany(
            mappedBy = "ridePassenger",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<Baggage> baggage = new ArrayList<>();

    public void confirm() {
        this.status = RidePassengerStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }

    public void cancel(String reason) {
        this.status = RidePassengerStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    /** Adiciona um item de bagagem mantendo os dois lados do relacionamento. */
    public void addBaggage(Baggage item) {
        item.setRidePassenger(this);
        this.baggage.add(item);
    }

    public UUID getId() { return id; }
    public UUID getRideId() { return rideId; }
    public void setRideId(UUID v) { this.rideId = v; }
    public String getPassengerSub() { return passengerSub; }
    public void setPassengerSub(String v) { this.passengerSub = v; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String v) { this.passengerName = v; }
    public RidePassengerStatus getStatus() { return status; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public String getCancellationReason() { return cancellationReason; }
    public List<Baggage> getBaggage() { return baggage; }
}
@@RIDE_PASSENGER@@

# =============================================================================
# 6) RideRequestCreateRequest — baggage obrigatório (@NotEmpty + @Valid)
# =============================================================================
write_file "$RIDES_MAIN/dto/RideRequestCreateRequest.java" <<'@@CREATE_REQUEST@@'
package com.raiji.rides.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Payload do POST /api/rides/{rideId}/requests.
 *
 * S2-01 / RF18: a declaração de bagagem passa a ser OBRIGATÓRIA
 * (mínimo 1 item). Sem baggage ou com baggage vazio => 400.
 */
public record RideRequestCreateRequest(

        @NotBlank(message = "O nome do passageiro é obrigatório.")
        @Size(max = 150, message = "O nome deve ter no máximo 150 caracteres.")
        String passengerName,

        @Valid
        @NotEmpty(message = "A declaração de bagagem é obrigatória (mínimo 1 item).")
        List<BaggageItemRequest> baggage

) {}
@@CREATE_REQUEST@@

# =============================================================================
# 7) RidePassengerResponse — expõe a bagagem (visão do motorista)
# =============================================================================
write_file "$RIDES_MAIN/dto/RidePassengerResponse.java" <<'@@PASSENGER_RESPONSE@@'
package com.raiji.rides.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raiji.rides.domain.RidePassenger;
import com.raiji.common.enums.RidePassengerStatus;

/**
 * Resposta de solicitação de vaga.
 * S2-01 / RF18: inclui a bagagem declarada — o motorista a vê ANTES de
 * aceitar/recusar (GET /api/rides/{rideId}/requests).
 */
public record RidePassengerResponse(
        UUID id,
        UUID rideId,
        String passengerSub,
        String passengerName,
        RidePassengerStatus status,
        Instant joinedAt,
        List<BaggageItemResponse> baggage
) {

    public static RidePassengerResponse from(RidePassenger passenger) {
        List<BaggageItemResponse> baggage = passenger.getBaggage() == null
                ? List.of()
                : passenger.getBaggage().stream()
                        .map(BaggageItemResponse::from)
                        .toList();

        return new RidePassengerResponse(
                passenger.getId(),
                passenger.getRideId(),
                passenger.getPassengerSub(),
                passenger.getPassengerName(),
                passenger.getStatus(),
                passenger.getJoinedAt(),
                baggage
        );
    }
}
@@PASSENGER_RESPONSE@@

# =============================================================================
# 8) RidePassengerService — persiste a bagagem na MESMA transação
# =============================================================================
write_file "$RIDES_MAIN/service/RidePassengerService.java" <<'@@SERVICE@@'
package com.raiji.rides.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.event.RideRequestCreatedEvent;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RidePassengerService {

    private static final List<RidePassengerStatus> ACTIVE_STATUSES =
            List.of(
                    RidePassengerStatus.PENDING,
                    RidePassengerStatus.CONFIRMED
            );

    private final RideRepository rideRepository;
    private final RidePassengerRepository ridePassengerRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RidePassengerService(
            RideRepository rideRepository,
            RidePassengerRepository ridePassengerRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.rideRepository = rideRepository;
        this.ridePassengerRepository = ridePassengerRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * S2-01 / RF18: a solicitação de vaga exige a declaração de bagagem.
     * Solicitação + bagagem são persistidas na MESMA transação
     * (cascade ALL em RidePassenger.baggage).
     */
    @Transactional
    public RidePassengerResponse request(
            UUID rideId,
            String passengerSub,
            String passengerName,
            List<BaggageItemRequest> baggage
    ) {
        if (baggage == null || baggage.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A declaração de bagagem é obrigatória (mínimo 1 item)."
            );
        }

        Ride ride = rideRepository.findById(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta carona não está disponível para solicitações."
            );
        }

        if (ride.getDepartureDate() == null
                || !ride.getDepartureDate().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não é possível solicitar uma carona com partida passada."
            );
        }

        boolean alreadyRequested =
                ridePassengerRepository
                        .existsByRideIdAndPassengerSubAndStatusInAndDeletedAtIsNull(
                                rideId,
                                passengerSub,
                                ACTIVE_STATUSES
                        );

        if (alreadyRequested) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Você já possui uma solicitação ativa para esta carona."
            );
        }

        long activeRequests =
                ridePassengerRepository
                        .countByRideIdAndStatusInAndDeletedAtIsNull(
                                rideId,
                                ACTIVE_STATUSES
                        );

        if (ride.getAvailableSeats() == null
                || ride.getAvailableSeats() <= 0
                || activeRequests >= ride.getTotalSeats()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não há vagas disponíveis nesta carona."
            );
        }

        RidePassenger passenger = new RidePassenger();
        passenger.setRideId(rideId);
        passenger.setPassengerSub(passengerSub);
        passenger.setPassengerName(passengerName.trim());

        for (BaggageItemRequest item : baggage) {
            Baggage baggageEntity = new Baggage();
            baggageEntity.setType(item.type());
            baggageEntity.setQuantity(item.quantity());
            baggageEntity.setLengthCm(item.lengthCm());
            baggageEntity.setWidthCm(item.widthCm());
            baggageEntity.setHeightCm(item.heightCm());
            baggageEntity.setDescription(item.description());
            passenger.addBaggage(baggageEntity);
        }

        // cascade ALL: solicitação + bagagem persistidas juntas (mesma transação)
        RidePassenger saved = ridePassengerRepository.save(passenger);

        eventPublisher.publishEvent(
                new RideRequestCreatedEvent(
                        ride.getId(),
                        ride.getDriverSub(),
                        ride.getDriverName(),
                        saved.getPassengerSub(),
                        saved.getPassengerName(),
                        saved.getJoinedAt()
                )
        );

        return RidePassengerResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<RidePassengerResponse> listForDriver(
            UUID rideId,
            String driverSub
    ) {
        Ride ride = rideRepository.findById(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não tem permissão para visualizar as solicitações desta carona."
            );
        }

        return ridePassengerRepository
                .findByRideIdAndDeletedAtIsNull(rideId)
                .stream()
                .map(RidePassengerResponse::from)
                .toList();
    }
}
@@SERVICE@@

# =============================================================================
# 9) RideRequestController — repassa a bagagem
# =============================================================================
write_file "$RIDES_MAIN/web/RideRequestController.java" <<'@@CONTROLLER@@'
package com.raiji.rides.web;

import java.util.List;
import java.util.UUID;

import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.dto.RideRequestCreateRequest;
import com.raiji.rides.service.RidePassengerService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rides/{rideId}/requests")
public class RideRequestController {

    private final RidePassengerService ridePassengerService;

    public RideRequestController(
            RidePassengerService ridePassengerService
    ) {
        this.ridePassengerService = ridePassengerService;
    }

    /** S2-01: payload exige passengerName + baggage (>= 1 item). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PASSENGER')")
    public RidePassengerResponse request(
            @PathVariable UUID rideId,
            @Valid @RequestBody RideRequestCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ridePassengerService.request(
                rideId,
                jwt.getSubject(),
                request.passengerName(),
                request.baggage()
        );
    }

    /** S2-01: visão do motorista — inclui a bagagem antes de aceitar/recusar. */
    @GetMapping
    @PreAuthorize("hasRole('DRIVER')")
    public List<RidePassengerResponse> listRequests(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ridePassengerService.listForDriver(
                rideId,
                jwt.getSubject()
        );
    }
}
@@CONTROLLER@@

# =============================================================================
# 10) BaggageApiIT — critérios de aceite da issue
# =============================================================================
write_file "raiji-app/src/test/java/com/raiji/it/BaggageApiIT.java" <<'@@BAGGAGE_IT@@'
package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S2-01 / RF18 — Declaração de bagagem na solicitação de vaga.
 *
 * Cobre os critérios de aceite:
 *  - solicitação SEM baggage            -> 400
 *  - solicitação com baggage VAZIO      -> 400
 *  - quantity <= 0                      -> 400
 *  - dimensões <= 0                     -> 400
 *  - type fora do enum                  -> 400
 *  - bagagem persiste e é recuperada no detalhe da solicitação
 *  - motorista vê a bagagem ANTES de aceitar/recusar
 */
@DisplayName("Testes de integração — declaração de bagagem (S2-01/RF18)")
class BaggageApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record UserSetup(String token, String sub) {}

    private record DriverSetup(String token, UUID vehicleId, String sub) {}

    // -------------------------------------------------------------------------
    // Setup (mesmo padrão de RideRequestApiIT)
    // -------------------------------------------------------------------------

    private UserSetup criarUsuarioComRole(String prefixo, String role) {
        String username = prefixo + "-" + System.nanoTime();
        String userId = criarUsuario(username);

        if (role != null) {
            atribuirRoleRealm(userId, role);
        }

        String token = obterToken(username, SENHA_NOVOS_USUARIOS);

        String sub = given()
                .auth().oauth2(token)
                .when().get(app("/api/users/me"))
                .then().statusCode(200)
                .extract().path("sub");

        return new UserSetup(token, sub);
    }

    private DriverSetup criarMotorista() {
        UserSetup user = criarUsuarioComRole("baggage-driver", "DRIVER");

        String plate = "B" + String.format(
                "%06d",
                ThreadLocalRandom.current().nextInt(0, 1_000_000)
        );

        String vehicleId = given()
                .auth().oauth2(user.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "plate", plate,
                        "type", "CARRO",
                        "model", "Honda Civic",
                        "color", "Preto",
                        "year", 2023,
                        "passengerSeats", 4,
                        "cargoVolumeLiters", 400
                ))
                .when().post(app("/api/vehicles"))
                .then().statusCode(201)
                .extract().path("id");

        return new DriverSetup(
                user.token(),
                UUID.fromString(vehicleId),
                user.sub()
        );
    }

    private String criarCarona(DriverSetup driver) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", driver.vehicleId().toString());
        body.put("type", "PASSENGER");
        body.put("originCity", "Fortaleza");
        body.put("originState", "CE");
        body.put("destinationCity", "Sobral");
        body.put("destinationState", "CE");
        body.put("departureDate", LocalDateTime.now()
                .plusDays(1).format(ISO));
        body.put("totalSeats", 3);

        return given()
                .auth().oauth2(driver.token())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .extract().path("id");
    }

    private UserSetup criarPassageiro() {
        return criarUsuarioComRole("baggage-passenger", "PASSENGER");
    }

    // -------------------------------------------------------------------------
    // Helpers de payload
    // -------------------------------------------------------------------------

    private static Map<String, Object> item(
            String type, int quantity, int lengthCm, int widthCm, int heightCm,
            String description
    ) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", type);
        item.put("quantity", quantity);
        item.put("lengthCm", lengthCm);
        item.put("widthCm", widthCm);
        item.put("heightCm", heightCm);
        item.put("description", description);
        return item;
    }

    private static Map<String, Object> itemValido() {
        return item("mala", 1, 60, 40, 30, "Mala de bordo");
    }

    private static Map<String, Object> payload(String nome, List<Map<String, Object>> baggage) {
        Map<String, Object> body = new HashMap<>();
        body.put("passengerName", nome);
        if (baggage != null) {
            body.put("baggage", baggage);
        }
        return body;
    }

    // -------------------------------------------------------------------------
    // Critérios de aceite — casos de 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("S2-01: solicitação SEM baggage retorna 400")
    void solicitacaoSemBaggageRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", null))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(400);
    }

    @Test
    @DisplayName("S2-01: solicitação com baggage VAZIO retorna 400")
    void solicitacaoComBaggageVazioRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", List.of()))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(400);
    }

    @Test
    @DisplayName("S2-01: quantity <= 0 retorna 400")
    void quantityInvalidaRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        for (int quantity : new int[]{0, -1}) {
            given()
                    .auth().oauth2(passenger.token())
                    .contentType(ContentType.JSON)
                    .body(payload("Maria Silva",
                            List.of(item("mala", quantity, 60, 40, 30, "Mala"))))
                    .when().post(app("/api/rides/" + rideId + "/requests"))
                    .then().statusCode(400);
        }
    }

    @Test
    @DisplayName("S2-01: dimensões <= 0 retornam 400")
    void dimensoesInvalidasRetornam400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        List<Map<String, Object>> casosInvalidos = List.of(
                item("mala", 1, 0, 40, 30, "comprimento zero"),
                item("mala", 1, 60, -5, 30, "largura negativa"),
                item("mala", 1, 60, 40, 0, "altura zero")
        );

        for (Map<String, Object> caso : casosInvalidos) {
            given()
                    .auth().oauth2(passenger.token())
                    .contentType(ContentType.JSON)
                    .body(payload("Maria Silva", List.of(caso)))
                    .when().post(app("/api/rides/" + rideId + "/requests"))
                    .then().statusCode(400);
        }
    }

    @Test
    @DisplayName("S2-01: type fora do enum retorna 400")
    void typeForaDoEnumRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva",
                        List.of(item("geladeira", 1, 60, 40, 30, "Fora do enum"))))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(400);
    }

    // -------------------------------------------------------------------------
    // Critérios de aceite — persistência e visão do motorista
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("S2-01: bagagem persiste e é retornada na solicitação")
    void bagagemPersisteEhRetornada() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", List.of(itemValido())))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("PENDING"))
                .body("baggage", hasSize(1))
                .body("baggage[0].type", equalTo("mala"))
                .body("baggage[0].quantity", equalTo(1))
                .body("baggage[0].lengthCm", equalTo(60))
                .body("baggage[0].widthCm", equalTo(40))
                .body("baggage[0].heightCm", equalTo(30))
                .body("baggage[0].description", equalTo("Mala de bordo"));

        // persistência real: tabela baggage com FK para ride_passengers
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baggage b "
                        + "JOIN ride_passengers rp ON b.ride_passenger_id = rp.id "
                        + "WHERE rp.ride_id = CAST(? AS uuid)",
                Integer.class, rideId);
        org.junit.jupiter.api.Assertions.assertEquals(1, total);

        Map<String, Object> linha = jdbcTemplate.queryForMap(
                "SELECT b.type, b.quantity, b.length_cm, b.width_cm, b.height_cm, b.description "
                        + "FROM baggage b "
                        + "JOIN ride_passengers rp ON b.ride_passenger_id = rp.id "
                        + "WHERE rp.ride_id = CAST(? AS uuid)",
                rideId);
        org.junit.jupiter.api.Assertions.assertEquals("MALA", linha.get("type"));
        org.junit.jupiter.api.Assertions.assertEquals(1, linha.get("quantity"));
        org.junit.jupiter.api.Assertions.assertEquals(60, linha.get("length_cm"));
        org.junit.jupiter.api.Assertions.assertEquals(40, linha.get("width_cm"));
        org.junit.jupiter.api.Assertions.assertEquals(30, linha.get("height_cm"));
        org.junit.jupiter.api.Assertions.assertEquals("Mala de bordo", linha.get("description"));
    }

    @Test
    @DisplayName("S2-01: motorista vê a bagagem ANTES de aceitar/recusar")
    void motoristaVeBagagemAntesDeDecidir() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", List.of(itemValido())))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201);

        // solicitação ainda PENDING (motorista não decidiu) e a bagagem visível
        given()
                .auth().oauth2(driver.token())
                .when().get(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].status", equalTo("PENDING"))
                .body("[0].passengerName", equalTo("Maria Silva"))
                .body("[0].baggage", hasSize(1))
                .body("[0].baggage[0].type", equalTo("mala"))
                .body("[0].baggage[0].quantity", equalTo(1))
                .body("[0].baggage[0].lengthCm", equalTo(60))
                .body("[0].baggage[0].widthCm", equalTo(40))
                .body("[0].baggage[0].heightCm", equalTo(30))
                .body("[0].baggage[0].description", equalTo("Mala de bordo"));
    }

    @Test
    @DisplayName("S2-01: múltiplos itens de bagagem persistem")
    void multiplosItensPersistem() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        List<Map<String, Object>> itens = new ArrayList<>();
        itens.add(item("mochila", 2, 45, 30, 20, "Mochila escolar"));
        itens.add(item("caixa", 1, 50, 40, 40, "Caixa fechada"));

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", itens))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("baggage", hasSize(2));

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baggage b "
                        + "JOIN ride_passengers rp ON b.ride_passenger_id = rp.id "
                        + "WHERE rp.ride_id = CAST(? AS uuid)",
                Integer.class, rideId);
        org.junit.jupiter.api.Assertions.assertEquals(2, total);
    }

    @Test
    @DisplayName("S2-01: type é case-insensitive na entrada e minúsculo na saída")
    void typeCaseInsensitiveEntradaMinusculaSaida() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva",
                        List.of(item("SACOLA", 1, 35, 25, 15, "Sacola reforçada"))))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("baggage[0].type", equalTo("sacola"));
    }
}
@@BAGGAGE_IT@@

# =============================================================================
# 11) PATCHES cirúrgicos nos testes existentes + docs/API.md (idempotentes)
# =============================================================================
log "aplicando patches em testes existentes e docs/API.md ..."
python3 - <<'@@PYPATCH@@'
import re
import sys

def load(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

def save(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)

def patch(path, guard, patches, must_apply_all=True):
    src = load(path)
    if guard in src:
        print(f"  [skip] {path} (já patcheado)")
        return
    for old, new, count in patches:
        found = src.count(old)
        if found == 0:
            if must_apply_all:
                sys.exit(f"ERRO: anchor não encontrado em {path}:\n---\n{old[:300]}\n---")
            continue
        if count is not None and found != count:
            sys.exit(f"ERRO: anchor {found}x (esperado {count}x) em {path}:\n{old[:200]}")
        src = src.replace(old, new)
    save(path, src)
    print(f"  [ok]   {path}")

# ---------------------------------------------------------------------------
# A) RideRequestApiIT — payloads passam a incluir bagagem válida
# ---------------------------------------------------------------------------
IT = "raiji-app/src/test/java/com/raiji/it/RideRequestApiIT.java"

helpers = '''
    private static Map<String, Object> itemBagagem() {
        Map<String, Object> item = new HashMap<>();
        item.put("type", "mala");
        item.put("quantity", 1);
        item.put("lengthCm", 60);
        item.put("widthCm", 40);
        item.put("heightCm", 30);
        item.put("description", "Mala de bordo");
        return item;
    }

    private static Map<String, Object> payloadSolicitacao(String nome) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("passengerName", nome);
        payload.put("baggage", List.of(itemBagagem()));
        return payload;
    }
'''

anchor_solicitar = '''    private void solicitar(String token, String rideId, String nome) {
        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(payloadSolicitacao(nome))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201);
    }
'''

patch(IT, "payloadSolicitacao", [
    # import List
    ("import java.util.HashMap;\nimport java.util.Map;",
     "import java.util.HashMap;\nimport java.util.List;\nimport java.util.Map;", 1),
    # helper solicitar()
    ('''    private void solicitar(String token, String rideId, String nome) {
        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", nome))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201);
    }
''', anchor_solicitar + helpers, 1),
    # demais payloads
    ('.body(Map.of("passengerName", "Maria Silva"))',
     '.body(payloadSolicitacao("Maria Silva"))', 6),
    ('.body(Map.of("passengerName", " "))',
     '.body(payloadSolicitacao(" "))', 1),
    # motorista passa a ver a bagagem na listagem
    ('''.body("[0].passengerName", equalTo("Maria Silva"))
                .body("[0].status", equalTo("PENDING"));''',
     '''.body("[0].passengerName", equalTo("Maria Silva"))
                .body("[0].status", equalTo("PENDING"))
                .body("[0].baggage", hasSize(1))
                .body("[0].baggage[0].type", equalTo("mala"));''', 1),
])

# ---------------------------------------------------------------------------
# B) RideRequestControllerTest — DTO com bagagem, stub 4-args, novos 400
# ---------------------------------------------------------------------------
CT = "raiji-rides/src/test/java/com/raiji/rides/web/RideRequestControllerTest.java"

json_sem_bagagem = '''.content("""
                                {
                                    "passengerName": "João Silva"
                                }
                                """))'''

json_com_bagagem = '''.content("""
                                {
                                    "passengerName": "João Silva",
                                    "baggage": [
                                        {
                                            "type": "mala",
                                            "quantity": 1,
                                            "lengthCm": 60,
                                            "widthCm": 40,
                                            "heightCm": 30,
                                            "description": "Mala de bordo"
                                        }
                                    ]
                                }
                                """))'''

json_nome_vazio_old = '''.content("""
                                {
                                    "passengerName": ""
                                }
                                """))'''

json_nome_vazio_new = '''.content("""
                                {
                                    "passengerName": "",
                                    "baggage": [
                                        {
                                            "type": "mala",
                                            "quantity": 1,
                                            "lengthCm": 60,
                                            "widthCm": 40,
                                            "heightCm": 30
                                        }
                                    ]
                                }
                                """))'''

novos_testes = '''    @Test
    void baggageAusenteRetorna400() throws Exception {

        mockMvc.perform(post("/api/rides/{rideId}/requests", rideId)
                        .with(jwt()
                                .jwt(token -> token.subject("passenger-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_PASSENGER")))
                        .contentType("application/json")
                        .content("""
                                {
                                    "passengerName": "João Silva"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void baggageVazioRetorna400() throws Exception {

        mockMvc.perform(post("/api/rides/{rideId}/requests", rideId)
                        .with(jwt()
                                .jwt(token -> token.subject("passenger-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_PASSENGER")))
                        .contentType("application/json")
                        .content("""
                                {
                                    "passengerName": "João Silva",
                                    "baggage": []
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration'''

patch(CT, "baggageAusenteRetorna400", [
    # imports
    ("import com.raiji.rides.dto.RidePassengerResponse;",
     "import com.raiji.rides.domain.BaggageType;\nimport com.raiji.rides.dto.BaggageItemResponse;\nimport com.raiji.rides.dto.RidePassengerResponse;", 1),
    ("import static org.mockito.ArgumentMatchers.eq;",
     "import static org.mockito.ArgumentMatchers.any;\nimport static org.mockito.ArgumentMatchers.eq;", 1),
    # response() com bagagem
    ('''                RidePassengerStatus.PENDING,
                Instant.parse("2026-11-01T12:00:00Z")
        );''',
     '''                RidePassengerStatus.PENDING,
                Instant.parse("2026-11-01T12:00:00Z"),
                List.of(new BaggageItemResponse(
                        BaggageType.MALA, 1, 60, 40, 30, "Mala de bordo"))
        );''', 1),
    # stub do service com 4 args
    ('''        when(ridePassengerService.request(
                eq(rideId),
                eq("passenger-123"),
                eq("João Silva")
        )).thenReturn(response());''',
     '''        when(ridePassengerService.request(
                eq(rideId),
                eq("passenger-123"),
                eq("João Silva"),
                any()
        )).thenReturn(response());''', 1),
    # JSONs existentes ganham bagagem (2 ocorrências: create + driver-cannot)
    (json_sem_bagagem, json_com_bagagem, 2),
    (json_nome_vazio_old, json_nome_vazio_new, 1),
    # asserções de bagagem nas respostas
    ('.andExpect(jsonPath("$.status").value("PENDING"));',
     '''.andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.baggage[0].type").value("mala"))
                .andExpect(jsonPath("$.baggage[0].quantity").value(1));''', 1),
    ('.andExpect(jsonPath("$[0].status").value("PENDING"));',
     '''.andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].baggage[0].type").value("mala"));''', 1),
    # novos testes 400 (inseridos ANTES de TestConfiguration)
    ("    @TestConfiguration", novos_testes, 1),
])

# ---------------------------------------------------------------------------
# C) RidePassengerServiceTest — assinatura 4 args + testes de bagagem
# ---------------------------------------------------------------------------
ST = "raiji-rides/src/test/java/com/raiji/rides/service/RidePassengerServiceTest.java"

novos_testes_st = '''    @Test
    void devePersistirBagagemNaMesmaTransacao() {
        RidePassengerResponse response = service.request(
                rideId, PASSENGER_SUB, "João Silva", bagagemValida());

        ArgumentCaptor<RidePassenger> captor =
                ArgumentCaptor.forClass(RidePassenger.class);
        verify(passengerRepository).save(captor.capture());

        RidePassenger salvo = captor.getValue();
        assertEquals(1, salvo.getBaggage().size());
        assertEquals(BaggageType.MALA, salvo.getBaggage().get(0).getType());
        assertEquals(1, salvo.getBaggage().get(0).getQuantity());
        assertEquals(60, salvo.getBaggage().get(0).getLengthCm());
        assertSame(salvo, salvo.getBaggage().get(0).getRidePassenger());

        assertEquals(1, response.baggage().size());
        assertEquals(BaggageType.MALA, response.baggage().get(0).type());
        assertEquals("Mala de bordo", response.baggage().get(0).description());
    }

    @Test
    void deveRejeitarBagagemAusenteOuVazia() {
        ResponseStatusException semBagagem = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João", null)
        );
        assertEquals(HttpStatus.BAD_REQUEST, semBagagem.getStatusCode());

        ResponseStatusException bagagemVazia = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João", List.of())
        );
        assertEquals(HttpStatus.BAD_REQUEST, bagagemVazia.getStatusCode());

        verify(passengerRepository, never()).save(any());
    }

    private static List<BaggageItemRequest> bagagemValida() {
        return List.of(new BaggageItemRequest(
                BaggageType.MALA, 1, 60, 40, 30, "Mala de bordo"));
    }

    @Test
    void deveRejeitarListagemPorOutroMotorista() {'''

patch(ST, "bagagemValida", [
    # imports
    ("import com.raiji.rides.domain.Ride;\nimport com.raiji.rides.domain.RidePassenger;",
     "import com.raiji.rides.domain.BaggageType;\nimport com.raiji.rides.domain.Ride;\nimport com.raiji.rides.domain.RidePassenger;", 1),
    ("import com.raiji.rides.dto.RidePassengerResponse;",
     "import com.raiji.rides.dto.BaggageItemRequest;\nimport com.raiji.rides.dto.RidePassengerResponse;", 1),
    ("import org.junit.jupiter.api.BeforeEach;",
     "import org.junit.jupiter.api.BeforeEach;\nimport org.mockito.ArgumentCaptor;", 1),
    # assinatura 4 args (7 ocorrências no total)
    ('''        RidePassengerResponse response = service.request(
                rideId, PASSENGER_SUB, "João Silva");''',
     '''        RidePassengerResponse response = service.request(
                rideId, PASSENGER_SUB, "João Silva", bagagemValida());''', 1),
    ('service.request(rideId, PASSENGER_SUB, "João")',
     'service.request(rideId, PASSENGER_SUB, "João", bagagemValida())', 6),
    # novos testes + helper (inseridos antes de deveRejeitarListagem...)
    ("    @Test\n    void deveRejeitarListagemPorOutroMotorista() {",
     novos_testes_st, 1),
])

# ---------------------------------------------------------------------------
# D) docs/API.md — contrato atualizado (S1-06 + S2-01)
# ---------------------------------------------------------------------------
API = "docs/API.md"

secao = '''

---

## Solicitacao de vaga (S1-06 + S2-01)

### POST /api/rides/{rideId}/requests

Autenticacao: Bearer token com role `PASSENGER`.

A partir da S2-01 (RF18) o payload EXIGE a declaracao de bagagem
(`baggage` com pelo menos 1 item):

```json
{
  "passengerName": "João Silva",
  "baggage": [
    {
      "type": "mala",
      "quantity": 1,
      "lengthCm": 60,
      "widthCm": 40,
      "heightCm": 30,
      "description": "Mala de bordo"
    }
  ]
}
```

| Campo | Tipo | Obrigatorio | Regras |
|---|---|---:|---|
| passengerName | string | sim | 1..150 caracteres |
| baggage | array | sim | minimo 1 item |
| baggage[].type | string | sim | `mochila` \\| `sacola` \\| `mala` \\| `caixa` |
| baggage[].quantity | int | sim | >= 1 |
| baggage[].lengthCm | int | sim | > 0 |
| baggage[].widthCm | int | sim | > 0 |
| baggage[].heightCm | int | sim | > 0 |
| baggage[].description | string | nao | <= 500 caracteres |

Erros:

- `400` baggage ausente/vazio, quantity <= 0, dimensao <= 0, type fora do enum, nome invalido
- `401` sem token · `403` sem role PASSENGER
- `404` carona inexistente
- `409` solicitacao duplicada, sem vagas, carona nao PLANNED ou partida passada

A resposta `201` ecoa a solicitacao com `status: PENDING` e a `baggage` declarada.

### GET /api/rides/{rideId}/requests (visao do motorista)

Autenticacao: Bearer token com role `DRIVER` (dono da carona).

Retorna as solicitacoes com a bagagem declarada — o motorista ve a bagagem
ANTES de aceitar/recusar:

```json
[
  {
    "id": "…",
    "rideId": "…",
    "passengerSub": "…",
    "passengerName": "Maria Silva",
    "status": "PENDING",
    "joinedAt": "…",
    "baggage": [
      { "type": "mala", "quantity": 1, "lengthCm": 60, "widthCm": 40, "heightCm": 30, "description": "Mala de bordo" }
    ]
  }
]
```

Observacoes:

- `type` e aceito em qualquer caixa na entrada e sempre devolvido em minusculas.
- A soma de peso/volume contra a capacidade da carona sera validada na S2-03.
'''

src = load(API)
if "Solicitacao de vaga (S1-06 + S2-01)" in src:
    print(f"  [skip] {API} (já patcheado)")
else:
    save(API, src.rstrip("\n") + "\n" + secao)
    print(f"  [ok]   {API}")

print("patches aplicados com sucesso.")
@@PYPATCH@@

# =============================================================================
# 12) tests/s2-01 — suíte de verificação da implementação
# =============================================================================
write_file "tests/s2-01/test_s2_01.py" <<'@@PYTEST@@'
#!/usr/bin/env python3
"""Verificação estrutural da S2-01 (RF18) — declaração de bagagem.

Roda SEM Maven/Docker: valida que o gerador criou/patcheou todos os
arquivos exigidos pela issue e que os critérios de aceite estão cobertos
por testes. Para a validação real (compilação + testes + IT), use:

    bash tests/s2-01/run-maven.sh
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RIDES_MAIN = ROOT / "raiji-rides/src/main/java/com/raiji/rides"
RIDES_TEST = ROOT / "raiji-rides/src/test/java/com/raiji/rides"
APP_IT = ROOT / "raiji-app/src/test/java/com/raiji/it"
MIG = ROOT / "raiji-rides/src/main/resources/db/migration"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


class TestNovosArquivos(unittest.TestCase):
    def test_arquivos_exigem_existir(self):
        esperados = [
            RIDES_MAIN / "domain/BaggageType.java",
            RIDES_MAIN / "domain/Baggage.java",
            RIDES_MAIN / "dto/BaggageItemRequest.java",
            RIDES_MAIN / "dto/BaggageItemResponse.java",
            MIG / "V8__create_baggage.sql",
            APP_IT / "BaggageApiIT.java",
            ROOT / "scripts/setup-s2-01.sh",
            ROOT / "tests/s2-01/run-all.sh",
            ROOT / "tests/s2-01/run-maven.sh",
            ROOT / "tests/s2-01/test_s2_01.py",
        ]
        for p in esperados:
            self.assertTrue(p.is_file(), f"ausente: {p}")


class TestBaggageType(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "domain/BaggageType.java")

    def test_enum_com_quatro_tipos(self):
        for t in ("MOCHILA", "SACOLA", "MALA", "CAIXA"):
            self.assertRegex(self.src, rf"\b{t}\b")

    def test_json_minúsculo(self):
        self.assertIn("@JsonValue", self.src)
        self.assertIn("toLowerCase", self.src)

    def test_json_creator_case_insensitive(self):
        self.assertIn("@JsonCreator", self.src)


class TestEntidadeBaggage(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "domain/Baggage.java")

    def test_tabela_e_fk(self):
        self.assertIn('@Table(name = "baggage"', self.src)
        self.assertIn("@ManyToOne", self.src)
        self.assertIn('@JoinColumn(name = "ride_passenger_id"', self.src)

    def test_enum_string_e_auditoria(self):
        self.assertIn("@Enumerated(EnumType.STRING)", self.src)
        self.assertIn("extends AuditableEntity", self.src)

    def test_campos_obrigatórios(self):
        for campo in ("quantity", "lengthCm", "widthCm", "heightCm", "description"):
            self.assertIn(campo, self.src)


class TestDtos(unittest.TestCase):
    def test_request_validado(self):
        src = read(RIDES_MAIN / "dto/BaggageItemRequest.java")
        self.assertIn("record BaggageItemRequest", src)
        self.assertIn("@NotNull", src)
        self.assertIn("@Min(value = 1", src)
        self.assertEqual(src.count("@Positive"), 3, "3 dimensões > 0")

    def test_response_com_from(self):
        src = read(RIDES_MAIN / "dto/BaggageItemResponse.java")
        self.assertIn("record BaggageItemResponse", src)
        self.assertIn("static BaggageItemResponse from(Baggage", src)

    def test_create_request_baggage_obrigatório(self):
        src = read(RIDES_MAIN / "dto/RideRequestCreateRequest.java")
        self.assertIn("@NotEmpty", src)
        self.assertIn("@Valid", src)
        self.assertIn("List<BaggageItemRequest> baggage", src)

    def test_response_expõe_bagagem(self):
        src = read(RIDES_MAIN / "dto/RidePassengerResponse.java")
        self.assertIn("List<BaggageItemResponse> baggage", src)
        self.assertIn("BaggageItemResponse::from", src)


class TestMigracaoV8(unittest.TestCase):
    def setUp(self):
        self.src = read(MIG / "V8__create_baggage.sql")

    def test_cria_apenas_baggage(self):
        tabelas = re.findall(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+)", self.src, re.I)
        self.assertEqual([t.lower() for t in tabelas], ["baggage"],
                         "S2-01: tabela única (sem tabela por tipo)")

    def test_fk_para_ride_passengers(self):
        self.assertRegex(self.src, r"REFERENCES\s+ride_passengers\s*\(id\)")

    def test_colunas_de_auditoria(self):
        for col in ("created_by", "created_at", "updated_by", "updated_at",
                    "deleted_at", "version"):
            self.assertIn(col, self.src)

    def test_checks_de_domínio(self):
        low = self.src.lower()
        for tipo in ("'mochila'", "'sacola'", "'mala'", "'caixa'"):
            self.assertIn(tipo, low)
        self.assertRegex(low, r"check\s*\(quantity\s*>=\s*1\)")
        self.assertRegex(low, r"check\s*\(length_cm\s*>\s*0")

    def test_v2_nao_foi_tocada(self):
        v2 = [p for p in MIG.glob("V2__*.sql")]
        self.assertTrue(v2, "V2 deve existir")
        self.assertNotIn("baggage", read(v2[0]).lower())


class TestDominioETransacao(unittest.TestCase):
    def test_one_to_many_cascade(self):
        src = read(RIDES_MAIN / "domain/RidePassenger.java")
        self.assertIn("@OneToMany", src)
        self.assertIn('mappedBy = "ridePassenger"', src)
        self.assertIn("CascadeType.ALL", src)
        self.assertIn("orphanRemoval = true", src)
        self.assertIn("public void addBaggage(Baggage", src)
        self.assertIn("item.setRidePassenger(this)", src)

    def test_service_persiste_na_mesma_transacao(self):
        src = read(RIDES_MAIN / "service/RidePassengerService.java")
        self.assertIn("List<BaggageItemRequest> baggage", src)
        self.assertIn("@Transactional", src)
        self.assertIn("passenger.addBaggage(", src)
        self.assertIn("ridePassengerRepository.save(passenger)", src)
        # 400 defensivo ANTES de qualquer consulta
        idx_bag = src.index("baggage == null || baggage.isEmpty()")
        idx_ride = src.index("rideRepository.findById(rideId)")
        self.assertLess(idx_bag, idx_ride,
                        "validação de bagagem deve preceder o findById")

    def test_controller_repassa_bagagem(self):
        src = read(RIDES_MAIN / "web/RideRequestController.java")
        self.assertIn("request.baggage()", src)
        self.assertIn("@Valid @RequestBody RideRequestCreateRequest", src)


class TestBaggageApiIT(unittest.TestCase):
    def setUp(self):
        self.src = read(APP_IT / "BaggageApiIT.java")

    def test_critérios_de_aceite_cobertos(self):
        for metodo in (
            "solicitacaoSemBaggageRetorna400",
            "solicitacaoComBaggageVazioRetorna400",
            "quantityInvalidaRetorna400",
            "dimensoesInvalidasRetornam400",
            "typeForaDoEnumRetorna400",
            "bagagemPersisteEhRetornada",
            "motoristaVeBagagemAntesDeDecidir",
            "multiplosItensPersistem",
            "typeCaseInsensitiveEntradaMinusculaSaida",
        ):
            self.assertIn(metodo, self.src, f"teste ausente: {metodo}")

    def test_usa_jdbcTemplate_para_persistencia(self):
        self.assertIn("JdbcTemplate", self.src)
        self.assertIn("FROM baggage", self.src)
        self.assertIn("ride_passenger_id", self.src)

    def test_motorista_vê_bagagem_com_status_pending(self):
        self.assertIn('equalTo("PENDING")', self.src)
        self.assertIn("[0].baggage", self.src)


class TestesExistentesPatcheados(unittest.TestCase):
    def test_ride_request_api_it(self):
        src = read(APP_IT / "RideRequestApiIT.java")
        self.assertIn("payloadSolicitacao(", src)
        self.assertIn('put("baggage"', src)
        self.assertNotIn('Map.of("passengerName", "Maria Silva")', src,
                         "payloads antigos sem bagagem devem ter sido substituídos")
        self.assertIn('[0].baggage", hasSize(1)', src)

    def test_controller_test(self):
        src = read(RIDES_TEST / "web/RideRequestControllerTest.java")
        self.assertIn("baggageAusenteRetorna400", src)
        self.assertIn("baggageVazioRetorna400", src)
        self.assertIn("BaggageItemResponse", src)
        self.assertIn("any()", src)
        self.assertEqual(src.count('"baggage": ['), 4,
                         "3 payloads com bagagem válida + 1 array vazio")
        self.assertIn('jsonPath("$.baggage[0].type").value("mala")', src)

    def test_service_test(self):
        src = read(RIDES_TEST / "service/RidePassengerServiceTest.java")
        self.assertIn("bagagemValida()", src)
        self.assertIn("devePersistirBagagemNaMesmaTransacao", src)
        self.assertIn("deveRejeitarBagagemAusenteOuVazia", src)
        self.assertIn("ArgumentCaptor", src)
        self.assertIn("assertSame", src)
        self.assertNotIn('PASSENGER_SUB, "João")', src,
                         "nenhuma chamada 3-args pode restar")


class TestDocumentacao(unittest.TestCase):
    def test_api_md_atualizado(self):
        src = read(ROOT / "docs/API.md")
        self.assertIn("Solicitacao de vaga (S1-06 + S2-01)", src)
        for item in ("POST /api/rides/{rideId}/requests",
                     "GET /api/rides/{rideId}/requests",
                     "mochila", "sacola", "mala", "caixa",
                     "lengthCm", "widthCm", "heightCm"):
            self.assertIn(item, src)
        self.assertIn("S2-03", src, "nota sobre peso/volume adiados")


if __name__ == "__main__":
    unittest.main(verbosity=2)
@@PYTEST@@

write_file "tests/s2-01/run-maven.sh" <<'@@RUNMVN@@'
#!/usr/bin/env bash
# =============================================================================
# run-maven.sh — validação REAL da S2-01 (compilação, testes unitários e IT)
# Requer: JDK 17+, Maven e Docker (Testcontainers/Jakarta para as ITs).
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

command -v mvn >/dev/null || { echo "[run-maven] mvn não encontrado." >&2; exit 1; }

echo "[run-maven] 1/2 — testes unitários do raiji-rides (+ deps)..."
mvn -q -pl raiji-rides -am test -Djacoco.skip=true

echo "[run-maven] 2/2 — integração BaggageApiIT no raiji-app..."
if docker info >/dev/null 2>&1; then
  mvn -q -pl raiji-app -am verify \
      -Dit.test=BaggageApiIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
      -Djacoco.skip=true
else
  echo "[run-maven] AVISO: docker indisponível — pulando ITs." >&2
  echo "[run-maven] Rode depois: mvn -pl raiji-app -am verify -Dit.test=BaggageApiIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false" >&2
fi

echo "[run-maven] cobertura agregada (gate 80% do raiji-app cobre os ≥70% do raiji-rides):"
echo "  mvn -q verify   # roda tudo com JaCoCo"
echo "[run-maven] OK"
@@RUNMVN@@

write_file "tests/s2-01/run-all.sh" <<'@@RUNALL@@'
#!/usr/bin/env bash
# =============================================================================
# run-all.sh — verificação completa da S2-01
#   1. sintaxe dos scripts
#   2. testes estruturais (python, sem Maven)
#   3. idempotência do gerador (2ª execução não altera nada)
#   4. opcional: --with-maven → compilação + testes reais
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

GRN=$'\033[0;32m'; RED=$'\033[0;31m'; NC=$'\033[0m'
ok()   { printf '%s[PASS]%s %s\n' "$GRN" "$NC" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "$RED" "$NC" "$*"; exit 1; }

echo "== 1/4 sintaxe dos scripts =="
for s in scripts/setup-s2-01.sh tests/s2-01/run-maven.sh tests/s2-01/run-all.sh; do
  bash -n "$s" || fail "sintaxe: $s"
done
ok "bash -n em 3 scripts"

echo "== 2/4 testes estruturais =="
python3 "$HERE/test_s2_01.py" || fail "testes estruturais"
ok "test_s2_01.py"

echo "== 3/4 idempotência do gerador =="
ALVO=$(find raiji-rides raiji-app docs scripts -type f \
        \( -name '*.java' -o -name '*.sql' -o -name '*.md' -o -name '*.sh' \) \
        | sort | xargs sha256sum | sha256sum)
bash scripts/setup-s2-01.sh >/dev/null 2>&1 || fail "re-execução do gerador"
ALVO2=$(find raiji-rides raiji-app docs scripts -type f \
        \( -name '*.java' -o -name '*.sql' -o -name '*.md' -o -name '*.sh' \) \
        | sort | xargs sha256sum | sha256sum)
[[ "$ALVO" == "$ALVO2" ]] || fail "gerador não idempotente (2ª execução alterou arquivos)"
ok "2ª execução do gerador: árvore idêntica"

echo "== 4/4 validação real (opcional) =="
if [[ "${1:-}" == "--with-maven" ]]; then
  bash "$HERE/run-maven.sh" || fail "run-maven.sh"
  ok "Maven: unitários + BaggageApiIT"
else
  echo "  (pulada — use: bash tests/s2-01/run-all.sh --with-maven)"
fi

echo
ok "S2-01 verificada com sucesso."
@@RUNALL@@

chmod +x scripts/setup-s2-01.sh tests/s2-01/run-all.sh tests/s2-01/run-maven.sh

log "implementação da S2-01 concluída."
cat <<RESUMO
  Novos:  BaggageType/Baggage (domain), BaggageItemRequest/Response (dto),
          V8__create_baggage.sql, BaggageApiIT, tests/s2-01/
  Atual.: RidePassenger, RideRequestCreateRequest, RidePassengerResponse,
          RidePassengerService, RideRequestController, RideRequestApiIT,
          RideRequestControllerTest, RidePassengerServiceTest, docs/API.md
  Verificar:  bash tests/s2-01/run-all.sh              (estrutural)
              bash tests/s2-01/run-all.sh --with-maven (real: mvn + docker)
RESUMO

if [[ "$MODE" == "check" ]]; then
  exec bash tests/s2-01/run-all.sh
fi
