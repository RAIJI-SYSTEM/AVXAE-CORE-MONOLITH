
package com.raiji.rides.web;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.service.RidePassengerService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;

import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = RideRequestController.class)
@AutoConfigureMockMvc
@Import({
        RideRequestController.class,
        RideRequestControllerTest.TestConfig.class
})
class RideRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RidePassengerService ridePassengerService;

    private final UUID rideId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    private RidePassengerResponse response() {
        return new RidePassengerResponse(
                requestId,
                rideId,
                "passenger-123",
                "João Silva",
                RidePassengerStatus.PENDING,
                Instant.parse("2026-11-01T12:00:00Z")
        );
    }

    @Test
    void passengerCanCreateRideRequest() throws Exception {

        when(ridePassengerService.request(
                eq(rideId),
                eq("passenger-123"),
                eq("João Silva")
        )).thenReturn(response());

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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.rideId").value(rideId.toString()))
                .andExpect(jsonPath("$.passengerSub").value("passenger-123"))
                .andExpect(jsonPath("$.passengerName").value("João Silva"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void driverCannotCreateRideRequest() throws Exception {

        mockMvc.perform(post("/api/rides/{rideId}/requests", rideId)
                        .with(jwt()
                                .jwt(token -> token.subject("driver-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER")))
                        .contentType("application/json")
                        .content("""
                                {
                                    "passengerName": "João Silva"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void passengerNameIsRequired() throws Exception {

        mockMvc.perform(post("/api/rides/{rideId}/requests", rideId)
                        .with(jwt()
                                .jwt(token -> token.subject("passenger-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_PASSENGER")))
                        .contentType("application/json")
                        .content("""
                                {
                                    "passengerName": ""
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void driverCanListRideRequests() throws Exception {

        when(ridePassengerService.listForDriver(rideId, "driver-123"))
                .thenReturn(List.of(response()));

        mockMvc.perform(get("/api/rides/{rideId}/requests", rideId)
                        .with(jwt()
                                .jwt(token -> token.subject("driver-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId.toString()))
                .andExpect(jsonPath("$[0].passengerName").value("João Silva"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void passengerCannotListRideRequests() throws Exception {

        mockMvc.perform(get("/api/rides/{rideId}/requests", rideId)
                        .with(jwt()
                                .jwt(token -> token.subject("passenger-123"))
                                .authorities(new SimpleGrantedAuthority("ROLE_PASSENGER"))))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                        "JWT decoder não deve ser chamado neste teste."
                );
            };
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http)
                throws Exception {

            return http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                    .build();
        }
    }
}
