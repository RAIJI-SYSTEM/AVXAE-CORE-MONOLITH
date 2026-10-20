
package com.raiji.users.web;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raiji.users.dto.VehicleRequest;
import com.raiji.users.dto.VehicleResponse;
import com.raiji.users.service.VehicleService;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VehicleController.class)
@AutoConfigureMockMvc
@Import({
        VehicleController.class,
        VehicleControllerTest.TestConfig.class
})
class VehicleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VehicleService vehicleService;

    private final UUID vehicleId =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private VehicleRequest validRequest() {
        return new VehicleRequest(
                "ABC1D23",
                "CARRO",
                "Toyota Corolla",
                "Prata",
                2024,
                4,
                400
        );
    }

    private VehicleResponse vehicleResponse() {
        return new VehicleResponse(
                vehicleId,
                "driver-123",
                "ABC1D23",
                "CARRO",
                "Toyota Corolla",
                "Prata",
                2024,
                4,
                400,
                true
        );
    }

    @Test
    void shouldCreateVehicleAndReturn201() throws Exception {
        when(vehicleService.create(
                any(VehicleRequest.class),
                eq("driver-123")
        )).thenReturn(vehicleResponse());

        mockMvc.perform(post("/api/vehicles")
                        .with(jwt()
                                .jwt(token -> token.subject("driver-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_DRIVER")
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(vehicleId.toString()))
                .andExpect(jsonPath("$.ownerSub").value("driver-123"))
                .andExpect(jsonPath("$.plate").value("ABC1D23"))
                .andExpect(jsonPath("$.active").value(true));

        verify(vehicleService).create(
                any(VehicleRequest.class),
                eq("driver-123")
        );
    }

    @Test
    void shouldReturn400WhenRequestIsInvalid() throws Exception {
        String invalidJson = """
                {
                  "plate": "",
                  "type": "",
                  "model": "",
                  "color": "",
                  "year": 0,
                  "passengerSeats": 0,
                  "cargoVolumeLiters": -1
                }
                """;

        mockMvc.perform(post("/api/vehicles")
                        .with(jwt()
                                .jwt(token -> token.subject("driver-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_DRIVER")
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn403ForPassenger() throws Exception {
        mockMvc.perform(get("/api/vehicles")
                        .with(jwt()
                                .jwt(token -> token.subject("passenger-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_PASSENGER")
                                )))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldListVehiclesForDriver() throws Exception {
        when(vehicleService.listByOwner("driver-123"))
                .thenReturn(List.of(vehicleResponse()));

        mockMvc.perform(get("/api/vehicles")
                        .with(jwt()
                                .jwt(token -> token.subject("driver-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_DRIVER")
                                )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(vehicleId.toString()))
                .andExpect(jsonPath("$[0].ownerSub").value("driver-123"))
                .andExpect(jsonPath("$[0].plate").value("ABC1D23"))
                .andExpect(jsonPath("$[0].active").value(true));

        verify(vehicleService).listByOwner("driver-123");
    }

    @Test
    void shouldReturnVehicleById() throws Exception {
        when(vehicleService.findById(vehicleId, "driver-123"))
                .thenReturn(vehicleResponse());

        mockMvc.perform(get("/api/vehicles/" + vehicleId)
                        .with(jwt()
                                .jwt(token -> token.subject("driver-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_DRIVER")
                                )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vehicleId.toString()))
                .andExpect(jsonPath("$.plate").value("ABC1D23"))
                .andExpect(jsonPath("$.ownerSub").value("driver-123"));

        verify(vehicleService).findById(vehicleId, "driver-123");
    }

    @Test
    void shouldUpdateVehicle() throws Exception {
        when(vehicleService.update(
                eq(vehicleId),
                eq("driver-123"),
                any(VehicleRequest.class)
        )).thenReturn(vehicleResponse());

        mockMvc.perform(put("/api/vehicles/" + vehicleId)
                        .with(jwt()
                                .jwt(token -> token.subject("driver-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_DRIVER")
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plate").value("ABC1D23"))
                .andExpect(jsonPath("$.ownerSub").value("driver-123"));

        verify(vehicleService).update(
                eq(vehicleId),
                eq("driver-123"),
                any(VehicleRequest.class)
        );
    }

    @Test
    void shouldDeleteVehicle() throws Exception {
        mockMvc.perform(delete("/api/vehicles/" + vehicleId)
                        .with(jwt()
                                .jwt(token -> token.subject("driver-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_DRIVER")
                                )))
                .andExpect(status().isNoContent());

        verify(vehicleService).deactivate(vehicleId, "driver-123");
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                        "O JwtDecoder não deve ser chamado neste teste"
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
                    .oauth2ResourceServer(oauth2 -> oauth2
                            .jwt(jwt -> jwt
                                    .jwtAuthenticationConverter(
                                            new org.springframework.security.oauth2.server.resource.authentication
                                                    .JwtAuthenticationConverter()
                                    )))
                    .build();
        }
    }
}
