package com.raiji.rides.repository;

import java.util.Optional;
import java.util.UUID;

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
}
