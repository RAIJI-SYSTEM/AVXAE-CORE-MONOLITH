package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("Testes de API (§5) — busca de caronas com filtros (S1-05 / RF04)")
class RideSearchApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record DriverSetup(String token, UUID vehicleId) {}

    private DriverSetup criarMotoristaComVeiculo(int seats) {
        String usuario = "search-it-" + System.nanoTime();
        String userId = criarUsuario(usuario);
        atribuirRoleRealm(userId, "DRIVER");
        String token = obterToken(usuario, SENHA_NOVOS_USUARIOS);

        String placa = "S" + String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000));

        String vehicleIdStr = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of(
                        "plate", placa,
                        "type", "CARRO",
                        "model", "Toyota Corolla",
                        "color", "Prata",
                        "year", 2024,
                        "passengerSeats", seats,
                        "cargoVolumeLiters", 400))
                .when().post(app("/api/vehicles"))
                .then().statusCode(201)
                .extract().path("id");

        return new DriverSetup(token, UUID.fromString(vehicleIdStr));
    }

    private String criarCarona(DriverSetup driver,
                               String originCity,
                               String destinationCity,
                               BigDecimal price,
                               int totalSeats,
                               LocalDateTime departure) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", driver.vehicleId().toString());
        body.put("type", "PASSENGER");
        body.put("originCity", originCity);
        body.put("originState", "CE");
        body.put("destinationCity", destinationCity);
        body.put("destinationState", "CE");
        body.put("departureDate", departure.format(ISO));
        body.put("totalSeats", totalSeats);

        if (price != null) {
            body.put("pricePerPassenger", price);
        }

        return given().auth().oauth2(driver.token()).contentType(ContentType.JSON)
                .body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .extract().path("id");
    }

    private void marcarSemVagas(String rideId) {
        jdbcTemplate.update(
                "UPDATE rides SET available_seats = 0 WHERE id = CAST(? AS uuid)",
                rideId);
    }

    private void marcarExcluida(String rideId) {
        jdbcTemplate.update(
                "UPDATE rides SET deleted_at = NOW() WHERE id = CAST(? AS uuid)",
                rideId);
    }

    @Test
    @DisplayName("CT-039: GET /api/rides sem token -> 401")
    void ct039_semToken() {
        given()
                .when().get(app("/api/rides"))
                .then().statusCode(401);
    }

    @Test
    @DisplayName("CT-040: sem filtros retorna paginação e somente caronas PLANNED com vagas")
    void ct040_semFiltros() {
        DriverSetup driver = criarMotoristaComVeiculo(4);
        String rideId = criarCarona(
                driver,
                "SemFiltro-" + System.nanoTime(),
                "Destino",
                new BigDecimal("10.00"),
                2,
                LocalDateTime.now().plusDays(1));

        given().auth().oauth2(driver.token())
                .queryParam("size", 50)
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("page", equalTo(0))
                .body("size", equalTo(50))
                .body("totalElements", greaterThanOrEqualTo(1))
                .body("totalPages", greaterThanOrEqualTo(1))
                .body("content.id", hasItem(rideId))
                .body("content.status", everyItem(equalTo("PLANNED")))
                .body("content.availableSeats", everyItem(greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("CT-041: freeOnly=true retorna apenas caronas gratuitas")
    void ct041_freeOnly() {
        DriverSetup driver = criarMotoristaComVeiculo(4);
        String city = "Gratuitas-" + System.nanoTime();

        String freeId = criarCarona(driver, city, "Destino", null, 2, LocalDateTime.now().plusDays(1));
        String paidId = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(1));

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .queryParam("freeOnly", true)
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("content.id", hasItem(freeId))
                .body("content.id", not(hasItem(paidId)))
                .body("content.pricePerPassenger", everyItem(nullValue()));
    }

    @Test
    @DisplayName("CT-042: maxPrice=50 retorna apenas caronas com preco <= 50")
    void ct042_maxPrice() {
        DriverSetup driver = criarMotoristaComVeiculo(4);
        String city = "MaxPrice-" + System.nanoTime();

        String cheapId = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(1));
        String expensiveId = criarCarona(driver, city, "Destino", new BigDecimal("60.00"), 2, LocalDateTime.now().plusDays(1));

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .queryParam("maxPrice", new BigDecimal("50.00"))
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("content.id", hasItem(cheapId))
                .body("content.id", not(hasItem(expensiveId)));
    }

    @Test
    @DisplayName("CT-043: minSeats=2 retorna apenas caronas com pelo menos 2 vagas")
    void ct043_minSeats() {
        DriverSetup driver = criarMotoristaComVeiculo(4);
        String city = "MinSeats-" + System.nanoTime();

        String twoSeatsId = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(1));
        String oneSeatId = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 1, LocalDateTime.now().plusDays(1));

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .queryParam("minSeats", 2)
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("content.id", hasItem(twoSeatsId))
                .body("content.id", not(hasItem(oneSeatId)))
                .body("content.availableSeats", everyItem(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("CT-044: filtros combinados sao aplicados com AND")
    void ct044_filtrosCombinados() {
        DriverSetup driver = criarMotoristaComVeiculo(4);
        String city = "Combinado-" + System.nanoTime();
        String destino = "DestinoCombo-" + System.nanoTime();

        String esperadoId = criarCarona(driver, city, destino, new BigDecimal("20.00"), 3, LocalDateTime.now().plusDays(2));

        criarCarona(driver, city, destino, new BigDecimal("80.00"), 3, LocalDateTime.now().plusDays(2));
        criarCarona(driver, city, destino, new BigDecimal("20.00"), 1, LocalDateTime.now().plusDays(2));
        criarCarona(driver, city, "OutroDestino-" + System.nanoTime(), new BigDecimal("20.00"), 3, LocalDateTime.now().plusDays(2));

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .queryParam("destinationCity", destino)
                .queryParam("maxPrice", new BigDecimal("50.00"))
                .queryParam("minSeats", 2)
                .queryParam("departureFrom", LocalDateTime.now().plusDays(1).format(ISO))
                .queryParam("departureTo", LocalDateTime.now().plusDays(3).format(ISO))
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("content", hasSize(1))
                .body("content.id", hasItem(esperadoId))
                .body("content.totalSeats", everyItem(greaterThanOrEqualTo(2)))
                .body("content.originCity", everyItem(equalTo(city)))
                .body("content.destinationCity", everyItem(equalTo(destino)));
    }

    @Test
    @DisplayName("CT-045: paginacao retorna content, page, size, totalElements e totalPages")
    void ct045_paginacao() {
        DriverSetup driver = criarMotoristaComVeiculo(4);
        String city = "Paginacao-" + System.nanoTime();

        criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(1));
        criarCarona(driver, city, "Destino", new BigDecimal("20.00"), 2, LocalDateTime.now().plusDays(2));
        criarCarona(driver, city, "Destino", new BigDecimal("30.00"), 2, LocalDateTime.now().plusDays(3));

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .queryParam("page", 0)
                .queryParam("size", 2)
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("page", equalTo(0))
                .body("size", equalTo(2))
                .body("totalElements", equalTo(3))
                .body("totalPages", equalTo(2))
                .body("content", hasSize(2));

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .queryParam("page", 1)
                .queryParam("size", 2)
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("page", equalTo(1))
                .body("content", hasSize(1));
    }

    @Test
    @DisplayName("CT-046: ordenacao padrao por departureDate ASC")
    void ct046_ordenacaoPadrao() {
        DriverSetup driver = criarMotoristaComVeiculo(4);
        String city = "Ordenacao-" + System.nanoTime();

        String segunda = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(2));
        String primeira = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(1));

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("content[0].id", equalTo(primeira));

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .queryParam("sort", "departureDate,desc")
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("content[0].id", equalTo(segunda));
    }

    @Test
    @DisplayName("CT-047: caronas sem vagas ou com deleted_at nao aparecem")
    void ct047_ocultasPorVagasOuSoftDelete() {
        DriverSetup driver = criarMotoristaComVeiculo(4);
        String city = "Ocultas-" + System.nanoTime();

        String visivelId = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(1));
        String semVagasId = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(1));
        String excluidaId = criarCarona(driver, city, "Destino", new BigDecimal("10.00"), 2, LocalDateTime.now().plusDays(1));

        marcarSemVagas(semVagasId);
        marcarExcluida(excluidaId);

        given().auth().oauth2(driver.token())
                .queryParam("originCity", city)
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .body("content", hasSize(1))
                .body("content.id", hasItem(visivelId))
                .body("content.id", not(hasItem(semVagasId)))
                .body("content.id", not(hasItem(excluidaId)));
    }
}
