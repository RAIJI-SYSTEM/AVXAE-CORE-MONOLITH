package com.raiji.rides.push;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O app registra o token do aparelho ao entrar e o remove ao sair da conta.
 * PUT/DELETE /api/push/devices {token, platform}
 */
@RestController
@RequestMapping("/api/push/devices")
public class PushDeviceController {

    public record DeviceRequest(
            @NotBlank @Size(max = 512) String token,
            @Pattern(regexp = "android|ios|web") String platform) {
    }

    private final PushDeviceRepository devices;
    private final FcmSender fcm;

    public PushDeviceController(PushDeviceRepository devices, FcmSender fcm) {
        this.devices = devices;
        this.fcm = fcm;
    }

    /** O app pergunta antes de pedir permissão: sem Firebase no servidor, nem pede */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", fcm.enabled());
    }

    @PutMapping
    public ResponseEntity<Void> register(@Valid @RequestBody DeviceRequest request, @AuthenticationPrincipal Jwt jwt) {
        devices.save(request.token(), jwt.getSubject(), request.platform() == null ? "android" : request.platform());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unregister(@Valid @RequestBody DeviceRequest request, @AuthenticationPrincipal Jwt jwt) {
        devices.delete(request.token(), jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
