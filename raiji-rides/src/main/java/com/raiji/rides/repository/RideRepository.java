package com.raiji.rides.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Ride;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideRepository extends JpaRepository<Ride, UUID>, JpaSpecificationExecutor<Ride> {

    /**
     * S2-03 / RN03 — trava pessimista ({@code SELECT ... FOR UPDATE}) na linha
     * da carona: serializa decisões concorrentes. Dois aceites simultâneos
     * sobre a última vaga => um confirma e o outro, ao reler a carona após o
     * commit do primeiro, recebe 409. Estratégia documentada em
     * docs/ARQUITETURA.md (o {@code @Version} herdado de AuditableEntity
     * permanece como rede de segurança).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Ride r where r.id = :id")
    Optional<Ride> findByIdForUpdate(@Param("id") UUID id);

    /**
     * S2-06 / RF10 + RN05 — caronas PLANNED cujo {@code departureDate} já foi
     * atingido ({@code <= cutoff}). Usada pelo {@code RideStatusScheduler} para
     * promover a {@code IN_PROGRESS}. O filtro por {@code PLANNED} torna a
     * execução idempotente (uma carona já promovida não aparece de novo).
     */
    List<Ride> findByStatusAndDepartureDateLessThanEqualAndDeletedAtIsNull(
            RideStatus status, LocalDateTime cutoff);

    /** O motorista tem carona em algum destes status? (ex.: PLANNED, IN_PROGRESS) */
    boolean existsByDriverSubAndStatusInAndDeletedAtIsNull(
            String driverSub, List<RideStatus> statuses);
}
