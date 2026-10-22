package com.raiji.rides.repository;

import java.util.ArrayList;
import java.util.List;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.dto.RideSearchFilter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * Specifications para busca de caronas (S1-05 / RF04).
 *
 * Regras fixas:
 * - status = PLANNED
 * - availableSeats > 0
 * - deleted_at IS NULL
 */
public final class RideSpecification {

    private RideSpecification() {
    }

    public static Specification<Ride> search(RideSearchFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("status"), RideStatus.PLANNED));
            predicates.add(cb.greaterThan(root.get("availableSeats"), 0));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (filter != null) {
                if (filter.originCity() != null && !filter.originCity().isBlank()) {
                    predicates.add(cb.like(
                            cb.lower(root.get("originCity")),
                            "%" + filter.originCity().trim().toLowerCase() + "%"));
                }

                if (filter.destinationCity() != null && !filter.destinationCity().isBlank()) {
                    predicates.add(cb.like(
                            cb.lower(root.get("destinationCity")),
                            "%" + filter.destinationCity().trim().toLowerCase() + "%"));
                }

                if (filter.departureFrom() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(
                            root.get("departureDate"),
                            filter.departureFrom()));
                }

                if (filter.departureTo() != null) {
                    predicates.add(cb.lessThanOrEqualTo(
                            root.get("departureDate"),
                            filter.departureTo()));
                }

                if (filter.maxPrice() != null) {
                    predicates.add(cb.lessThanOrEqualTo(
                            root.get("pricePerPassenger"),
                            filter.maxPrice()));
                }

                if (Boolean.TRUE.equals(filter.freeOnly())) {
                    predicates.add(cb.isNull(root.get("pricePerPassenger")));
                }

                if (filter.minSeats() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(
                            root.get("availableSeats"),
                            filter.minSeats()));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
