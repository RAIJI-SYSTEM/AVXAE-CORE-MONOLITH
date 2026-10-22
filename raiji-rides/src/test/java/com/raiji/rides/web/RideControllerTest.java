package com.raiji.rides.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.rides.dto.RideRequest;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.service.RideService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RideController.class)
@AutoConfigureMockMvc
@Import({RideController.class, RideControllerTest.TestConfig.class})
class RideControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean RideService rideService;

    private final UUID rideId    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private final UUID vehicleId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private RideRequest request() {
        return new RideRequest(
                vehicleId, RideType.PASSENGER,
                "Fortaleza", "CE", "Sobral", "CE",
                LocalDateTime.now().plusDays(1),
                new BigDecimal("50.00"), 4, null);
    }

    private RideResponse response() {
        return new RideResponse(
                rideId, "driver-123", "Fulano",
                vehicleId, RideType.PASSENGER,
                "Fortaleza", "CE", "Sobral", "CE",
                LocalDateTime.now().plusDays(1),
                new BigDecimal("50.00"), 4, 4,
                RideStatus.PLANNED, null, null, null);
    }

    @Test
    void deveCriarCaronaRetornando201() throws Exception {
        when(rideService.create(any(RideRequest.class), eq("driver-123"), any()))
                .thenReturn(response());

        mockMvc.perform(post("/api/rides")
                        .with(jwt()
                                .jwt(t -> t.subject("driver-123").claim("name", "Fulano"))
                                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(rideId.toString()))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.availableSeats").value(4));
    }

    @Test
    void deveRetornar400ComPayloadInvalido() throws Exception {
        String json = """
                {
                  "vehicleId": null,
                  "type": null,
                  "originCity": "",
                  "originState": "C",
                  "destinationCity": "",
                  "destinationState": "X",
                  "departureDate": null,
                  "totalSeats": 0
                }
                """;

        mockMvc.perform(post("/api/rides")
                        .with(jwt()
                                .jwt(t -> t.subject("driver-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deveRetornar403QuandoNaoForDriver() throws Exception {
        mockMvc.perform(post("/api/rides")
                        .with(jwt()
                                .jwt(t -> t.subject("passenger-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_PASSENGER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    void deveAtualizarPreco() throws Exception {
        when(rideService.updatePrice(eq(rideId), any(), eq("driver-123")))
                .thenReturn(response());

        mockMvc.perform(patch("/api/rides/" + rideId + "/price")
                        .with(jwt()
                                .jwt(t -> t.subject("driver-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerPassenger\": 50.00}"))
                .andExpect(status().isOk());
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> { throw new UnsupportedOperationException("não chamar"); };
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http
                    .csrf(c -> c.disable())
                    .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .oauth2ResourceServer(o -> o.jwt())
                    .build();
        }
    }
}
