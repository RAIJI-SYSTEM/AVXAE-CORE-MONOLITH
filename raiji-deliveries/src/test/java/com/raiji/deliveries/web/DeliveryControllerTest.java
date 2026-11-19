
package com.raiji.deliveries.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.DeliveryMode;
import com.raiji.deliveries.dto.AvailableDeliveryResponse;
import com.raiji.deliveries.dto.DeliveryRequest;
import com.raiji.deliveries.dto.DeliveryResponse;
import com.raiji.deliveries.dto.DeliverySummaryResponse;
import com.raiji.deliveries.service.DeliveryService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DeliveryController.class)
@AutoConfigureMockMvc
@Import({DeliveryController.class, DeliveryControllerTest.TestConfig.class})
class DeliveryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    DeliveryService deliveryService;

    private final UUID deliveryId =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private DeliveryRequest request() {
        return new DeliveryRequest(
                DeliveryMode.ON_ROUTE,
                "Fortaleza",
                "CE",
                "Terminal Rodoviário, box 12",
                "Sobral",
                "CE",
                "Rua das Flores, 100",
                "Maria Souza",
                "+5585999990000",
                "Caixa com livros",
                new BigDecimal("4.50"),
                40,
                30,
                25
        );
    }

    private DeliverySummaryResponse summaryResponse() {
        return new DeliverySummaryResponse(
                deliveryId,
                "RM-ABC234-XY",
                DeliveryStatus.PENDING,
                "ON_ROUTE",
                null,
                null,
                Instant.parse("2026-11-18T12:00:00Z")
        );
    }

    @Test
    void deveCriarEntregaIndependenteRetornando201() throws Exception {

        when(deliveryService.create(
                eq("sender-123"),
                eq("Maria"),
                any(DeliveryRequest.class)
        )).thenReturn(summaryResponse());

        mockMvc.perform(post("/api/deliveries")
                        .with(jwt()
                                .jwt(t -> t
                                        .subject("sender-123")
                                        .claim("name", "Maria"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_PASSENGER")
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(deliveryId.toString()))
                .andExpect(jsonPath("$.trackingCode").value("RM-ABC234-XY"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.mode").value("ON_ROUTE"));
    }

    @Test
    void deveRetornar400QuandoPayloadForInvalido() throws Exception {

        String json = """
                {
                  "mode": null,
                  "originCity": "",
                  "originState": "C",
                  "originAddress": "",
                  "destinationCity": "",
                  "destinationState": "X",
                  "destinationAddress": "",
                  "recipientName": "",
                  "recipientContact": "abc",
                  "description": "",
                  "weightKg": 0,
                  "lengthCm": 0,
                  "widthCm": 0,
                  "heightCm": 0
                }
                """;

        mockMvc.perform(post("/api/deliveries")
                        .with(jwt()
                                .jwt(t -> t.subject("sender-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_PASSENGER")
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    /**
     * Garante a compatibilidade com o endpoint antigo:
     * /api/deliveries/mine deve retornar uma lista JSON.
     */
    @Test
    void deveConsultarMinhasEntregasRetornandoLista() throws Exception {

        when(deliveryService.mine("sender-123"))
                .thenReturn(List.<DeliveryResponse>of());

        mockMvc.perform(get("/api/deliveries/mine")
                        .with(jwt()
                                .jwt(t -> t.subject("sender-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_PASSENGER")
                                )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(deliveryService).mine("sender-123");
    }

    /**
     * Testa separadamente a nova consulta paginada.
     */
    @Test
    void deveConsultarHistoricoPaginadoDoRemetente() throws Exception {

        when(deliveryService.mine(
                eq("sender-123"),
                any(Pageable.class)
        )).thenReturn(Page.<DeliverySummaryResponse>empty());

        mockMvc.perform(get("/api/deliveries/mine/page")
                        .with(jwt()
                                .jwt(t -> t.subject("sender-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_PASSENGER")
                                )))
                .andExpect(status().isOk());

        verify(deliveryService).mine(
                eq("sender-123"),
                any(Pageable.class)
        );
    }

    @Test
    void motoristaDeveConsultarEntregasDisponiveis() throws Exception {

        when(deliveryService.available("driver-123"))
                .thenReturn(List.<AvailableDeliveryResponse>of());

        mockMvc.perform(get("/api/deliveries/available")
                        .with(jwt()
                                .jwt(t -> t.subject("driver-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_DRIVER")
                                )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(deliveryService).available("driver-123");
    }

    @Test
    void passageiroNaoPodeConsultarEntregasDisponiveis() throws Exception {

        mockMvc.perform(get("/api/deliveries/available")
                        .with(jwt()
                                .jwt(t -> t.subject("passenger-123"))
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_PASSENGER")
                                )))
                .andExpect(status().isForbidden());
    }

    @Test
    void deveRetornar401QuandoNaoEstiverAutenticado() throws Exception {

        mockMvc.perform(get("/api/deliveries/mine"))
                .andExpect(status().isUnauthorized());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("Não deve chamar");
            };
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http
                    .csrf(c -> c.disable())
                    .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .oauth2ResourceServer(o ->
                            o.jwt(org.springframework.security.config.Customizer.withDefaults()))
                    .build();
        }
    }
}
