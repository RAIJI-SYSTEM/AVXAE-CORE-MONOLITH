package com.raiji.users.driververification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverApplicationRepository extends JpaRepository<DriverApplication, UUID> {

    Optional<DriverApplication> findTopBySubOrderByCreatedAtDesc(String sub);

    /** Há verificação em andamento (recente)? Uma mais antiga que o corte é considerada travada. */
    boolean existsBySubAndStatusAndCreatedAtAfter(String sub, DriverApplicationStatus status, Instant cutoff);

    /** CNH aprovada e ainda dentro da validade */
    boolean existsBySubAndStatusAndValidadeGreaterThanEqual(String sub, DriverApplicationStatus status, LocalDate date);
}
