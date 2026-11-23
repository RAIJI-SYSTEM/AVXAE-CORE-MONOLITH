package com.raiji.rides.drivercode;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.repository.RideRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Achar o motorista pelo código de 8 dígitos e ver as viagens que ele publicou.
 * <ul>
 *   <li>{@code GET /api/rides/drivers/me/code}: o código do próprio motorista (para divulgar);</li>
 *   <li>{@code GET /api/rides/drivers/by-code/{code}}: o motorista e as próximas viagens públicas dele.</li>
 * </ul>
 */
@RestController
public class DriverCodeController {

    public record DriverByCodeResponse(String driverSub, String driverName, String code, List<RideResponse> rides) {}

    private final RideRepository rides;
    private final Clock clock;

    public DriverCodeController(RideRepository rides, Clock clock) {
        this.rides = rides;
        this.clock = clock;
    }

    @GetMapping("/api/rides/drivers/me/code")
    @PreAuthorize("hasRole('DRIVER')")
    public Map<String, String> myCode(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("code", DriverCodes.of(jwt.getSubject()));
    }

    @GetMapping("/api/rides/drivers/by-code/{code}")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public DriverByCodeResponse byCode(@PathVariable String code) {
        String wanted = DriverCodes.normalize(code);
        if (wanted.length() != DriverCodes.LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O código tem 8 números.");
        }
        String driverSub = rides.findDistinctDriverSubs().stream()
                .filter(sub -> DriverCodes.of(sub).equals(wanted))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenhum motorista com esse código."));

        LocalDateTime now = LocalDateTime.now(clock);
        List<Ride> all = rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                driverSub, List.of(RideStatus.PLANNED, RideStatus.COMPLETED, RideStatus.IN_PROGRESS, RideStatus.CANCELLED));
        String name = all.stream().map(Ride::getDriverName).filter(n -> n != null && !n.isBlank()).findFirst().orElse(null);
        List<RideResponse> upcoming = all.stream()
                .filter(r -> r.getStatus() == RideStatus.PLANNED)
                .filter(r -> r.getDepartureDate().isAfter(now))
                .filter(r -> r.getVisibility() == RideVisibility.PUBLIC && !r.isBookingsClosed())
                .map(RideResponse::from)
                .toList();
        return new DriverByCodeResponse(driverSub, name, wanted, upcoming);
    }
}
