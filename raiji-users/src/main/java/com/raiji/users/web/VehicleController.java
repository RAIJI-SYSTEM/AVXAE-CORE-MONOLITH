package com.raiji.users.web;

import java.util.List;
import java.util.UUID;

import com.raiji.users.dto.VehicleRequest;
import com.raiji.users.dto.VehicleResponse;
import com.raiji.users.service.VehicleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/vehicles")
@PreAuthorize("hasRole('DRIVER'")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService){
        this.vehicleService = vehicleService;
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VehicleRequest request) {

        VehicleResponse response = vehicleService.create(
                request,
                jwt.getSubject()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<VehicleResponse>> list(
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok(
                vehicleService.listByOwner((jwt.getSubject()))
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehicleResponse> findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt){

        return ResponseEntity.ok(
                vehicleService.findById(id, jwt.getSubject())
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<VehicleResponse> update(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VehicleRequest request) {

        return ResponseEntity.ok(
                vehicleService.update(id, jwt.getSubject(), request)
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        vehicleService.deactivate(id, jwt.getSubject());

        return ResponseEntity.noContent().build();
    }
}
