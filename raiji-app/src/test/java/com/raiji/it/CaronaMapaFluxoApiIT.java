package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Carona com mapa: busca pelo caminho (PostGIS real), preço do trecho, "aceitar todos",
 * bagagem opcional/editável, ciclo de vida e notificações.
 */
@DisplayName("Caronas com mapa: busca pelo caminho, trecho, aceitar todos, bagagem e ciclo de vida")
class CaronaMapaFluxoApiIT extends FluxoTestSupport {

    private List<Map<String, Object>> buscar(String token, double[] origem, double[] destino, String kind) {
        var req = given().auth().oauth2(token)
                .queryParam("originLat", origem[0]).queryParam("originLng", origem[1])
                .queryParam("radiusMeters", 5000).queryParam("kind", kind);
        if (destino != null) req = req.queryParam("destinationLat", destino[0]).queryParam("destinationLng", destino[1]);
        return req.when().get(app("/api/rides/search")).then().statusCode(200).extract().jsonPath().getList("$");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultado(List<Map<String, Object>> lista, String rideId) {
        return lista.stream().filter(r -> rideId.equals(((Map<String, Object>) r.get("ride")).get("id")))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("busca: perto de mim, de A para B, no meio do caminho (preço proporcional); sentido contrário e longe não acham")
    void buscaPeloCaminho() {
        Motorista m = motorista("mapa-mot", 4, null);
        String rideId = publicarOk(m, carona(m, "PASSENGER", 3, 100.0, agora().plusDays(1)));
        Conta ana = conta("mapa-ana");

        // perto de mim (só origem)
        assertThat(resultado(buscar(ana.token(), SE, null, "SEAT"), rideId)).isNotNull();

        // de A para B: viagem inteira, preço cheio
        Map<String, Object> inteira = resultado(buscar(ana.token(), SE, CAMPINAS, "SEAT"), rideId);
        assertThat(inteira).isNotNull();
        assertThat(inteira.get("fullRoute")).isEqualTo(true);
        assertThat(new BigDecimal(inteira.get("suggestedPrice").toString())).isEqualByComparingTo("100.00");

        // no meio do caminho: Jundiaí -> Campinas paga só o trecho
        Map<String, Object> trecho = resultado(buscar(ana.token(), JUNDIAI, CAMPINAS, "SEAT"), rideId);
        assertThat(trecho).isNotNull();
        assertThat(trecho.get("fullRoute")).isEqualTo(false);
        BigDecimal precoTrecho = new BigDecimal(trecho.get("suggestedPrice").toString());
        assertThat(precoTrecho).isGreaterThan(BigDecimal.ZERO).isLessThan(new BigDecimal("100"));

        // sentido contrário (Campinas -> Sé) e longe (Rio) não acham
        assertThat(resultado(buscar(ana.token(), CAMPINAS, SE, "SEAT"), rideId)).isNull();
        assertThat(resultado(buscar(ana.token(), RIO, null, "SEAT"), rideId)).isNull();
        // a própria carona não aparece para o motorista; encomendas não acham carona só de passageiros
        assertThat(resultado(buscar(m.token(), SE, CAMPINAS, "SEAT"), rideId)).isNull();
        assertThat(resultado(buscar(ana.token(), SE, CAMPINAS, "PACKAGE"), rideId)).isNull();

        // detalhe traz a rota para o mapa
        given().auth().oauth2(ana.token()).when().get(app("/api/rides/" + rideId)).then().statusCode(200)
                .body("ride.meetingPoint", equalTo("Em frente à catedral"))
                .body("ride.routeDistanceMeters", notNullValue())
                .body("route", hasSize(3));
    }

    @Test
    @DisplayName("pedido de trecho: preço proporcional; sem bagagem é aceito")
    void pedidoDeTrecho() {
        Motorista m = motorista("trecho-mot", 4, null);
        String rideId = publicarOk(m, carona(m, "PASSENGER", 3, 100.0, agora().plusDays(1)));
        Conta bia = conta("trecho-bia");

        Map<String, Object> body = new HashMap<>();
        body.put("passengerName", "Bia");
        body.put("pickup", lugar(JUNDIAI, "Rodoviária de Jundiaí"));
        pedirVaga(bia.token(), rideId, body).statusCode(201)
                .body("fullRoute", equalTo(false))
                .body("pickupAddress", equalTo("Rodoviária de Jundiaí"))
                .body("baggage", hasSize(0));

        // embarque longe do caminho => 400
        Conta caio = conta("trecho-caio");
        Map<String, Object> longe = new HashMap<>();
        longe.put("passengerName", "Caio");
        longe.put("pickup", lugar(RIO, "Rio"));
        pedirVaga(caio.token(), rideId, longe).statusCode(400).body("message", containsString("longe"));
    }

    @Test
    @DisplayName("aceitar todos: confirma os pedidos de viagem inteira; o de trecho fica para decidir um a um")
    void aceitarTodos() {
        Motorista m = motorista("todos-mot", 4, null);
        String rideId = publicarOk(m, carona(m, "PASSENGER", 3, 50.0, agora().plusDays(1)));
        for (String nome : List.of("a", "b")) {
            pedirVaga(conta("todos-" + nome).token(), rideId, Map.of("passengerName", "P " + nome)).statusCode(201);
        }
        Map<String, Object> trecho = new HashMap<>();
        trecho.put("passengerName", "Trecho");
        trecho.put("pickup", lugar(JUNDIAI, "Jundiaí"));
        pedirVaga(conta("todos-trecho").token(), rideId, trecho).statusCode(201);

        given().auth().oauth2(m.token()).when().post(app("/api/rides/" + rideId + "/requests/accept-all"))
                .then().statusCode(200)
                .body("accepted", hasSize(2))
                .body("skipped", hasSize(1))
                .body("skipped[0].reason", containsString("trecho"))
                .body("availableSeats", equalTo(1));
    }

    @Test
    @DisplayName("bagagem: editar depois do aceite — aumentar espera o motorista; diminuir vale na hora")
    void bagagemEditavel() {
        Motorista m = motorista("bag-mot", 4, 400);
        String rideId = publicarOk(m, carona(m, "PASSENGER", 3, 40.0, agora().plusDays(1)));
        Conta dani = conta("bag-dani");
        String pedido = pedirVaga(dani.token(), rideId, Map.of("passengerName", "Dani")).statusCode(201)
                .extract().path("id");
        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("decision", "ACCEPT"))
                .when().patch(app("/api/rides/" + rideId + "/requests/" + pedido)).then().statusCode(200);

        String url = app("/api/rides/" + rideId + "/requests/" + pedido + "/baggage");
        // aumenta: fica pendente, a bagagem em vigor continua vazia; o motorista é avisado
        given().auth().oauth2(dani.token()).contentType(ContentType.JSON)
                .body(Map.of("baggage", List.of(bagagem("mala", 70, 45, 30, 15))))
                .when().put(url).then().statusCode(200)
                .body("baggage", hasSize(0)).body("pendingBaggage", hasSize(1));
        assertThat(temKind(notificacoes(m.token(), "DRIVER"), "BAGGAGE_CHANGE_REQUEST")).isTrue();

        // motorista aprova
        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("decision", "ACCEPT"))
                .when().patch(url).then().statusCode(200)
                .body("baggage", hasSize(1)).body("pendingBaggage", hasSize(0));

        // diminui: vale na hora
        given().auth().oauth2(dani.token()).contentType(ContentType.JSON)
                .body(Map.of("baggage", List.of(bagagem("mochila", 40, 30, 20, 5))))
                .when().put(url).then().statusCode(200)
                .body("baggage[0].type", equalTo("mochila")).body("pendingBaggage", hasSize(0));

        // outra pessoa não edita
        given().auth().oauth2(conta("bag-outra").token()).contentType(ContentType.JSON)
                .body(Map.of("baggage", List.of())).when().put(url).then().statusCode(403);
    }

    @Test
    @DisplayName("ciclo: iniciar cedo demais 409; iniciar recusa pendentes; finalizar conclui as reservas")
    void cicloDeVida() {
        Motorista m = motorista("ciclo-mot", 4, null);
        String longe = publicarOk(m, carona(m, "PASSENGER", 3, 30.0, agora().plusDays(2)));
        given().auth().oauth2(m.token()).when().post(app("/api/rides/" + longe + "/start"))
                .then().statusCode(409).body("message", containsString("60 minutos"));

        // "agora": sai em 30 min
        String rideId = publicarOk(m, carona(m, "PASSENGER", 3, 30.0, agora().plusMinutes(30)));
        Conta eva = conta("ciclo-eva");
        Conta fabi = conta("ciclo-fabi");
        String pedidoEva = pedirVaga(eva.token(), rideId, Map.of("passengerName", "Eva")).statusCode(201).extract().path("id");
        pedirVaga(fabi.token(), rideId, Map.of("passengerName", "Fabi")).statusCode(201);
        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("decision", "ACCEPT"))
                .when().patch(app("/api/rides/" + rideId + "/requests/" + pedidoEva)).then().statusCode(200);

        given().auth().oauth2(m.token()).when().post(app("/api/rides/" + rideId + "/start"))
                .then().statusCode(200).body("status", equalTo("IN_PROGRESS")).body("startedAt", notNullValue());
        // Fabi (pendente) foi recusada e avisada
        assertThat(temKind(notificacoes(fabi.token(), "PASSENGER"), "RIDE_REQUEST_REJECTED")).isTrue();

        given().auth().oauth2(m.token()).when().post(app("/api/rides/" + rideId + "/finish"))
                .then().statusCode(200).body("status", equalTo("COMPLETED"));

        List<Map<String, Object>> minhas = given().auth().oauth2(eva.token())
                .when().get(app("/api/rides/bookings/mine")).then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(minhas).anySatisfy(b -> {
            assertThat(((Map<?, ?>) b.get("booking")).get("status")).isEqualTo("COMPLETED");
            assertThat(((Map<?, ?>) b.get("ride")).get("id")).isEqualTo(rideId);
        });
    }

    @Test
    @DisplayName("cancelar a viagem: reservas aceitas canceladas e passageiro avisado; encerrar reservas bloqueia pedidos")
    void cancelarEEncerrar() {
        Motorista m = motorista("canc-mot", 4, null);
        String rideId = publicarOk(m, carona(m, "PASSENGER", 3, 30.0, agora().plusDays(1)));
        Conta gil = conta("canc-gil");
        String pedido = pedirVaga(gil.token(), rideId, Map.of("passengerName", "Gil")).statusCode(201).extract().path("id");
        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("decision", "ACCEPT"))
                .when().patch(app("/api/rides/" + rideId + "/requests/" + pedido)).then().statusCode(200);

        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("reason", "Carro quebrou"))
                .when().post(app("/api/rides/" + rideId + "/cancel")).then().statusCode(200)
                .body("status", equalTo("CANCELLED"));
        assertThat(temKind(notificacoes(gil.token(), "PASSENGER"), "RIDE_CANCELLED")).isTrue();

        String outra = publicarOk(m, carona(m, "PASSENGER", 3, 30.0, agora().plusDays(1)));
        given().auth().oauth2(m.token()).when().post(app("/api/rides/" + outra + "/close-bookings"))
                .then().statusCode(200).body("bookingsClosed", equalTo(true));
        pedirVaga(conta("canc-h").token(), outra, Map.of("passengerName", "H")).statusCode(409);
    }

    @Test
    @DisplayName("vagas: só encomendas (CARGO) = 0 vagas; com passageiros >= 1; minhas caronas lista")
    void regrasDeVagas() {
        Motorista m = motorista("vagas-mot", 4, null);
        Map<String, Object> cargoComVaga = carona(m, "CARGO", 2, null, agora().plusDays(1));
        cargoComVaga.put("cargoDetails", Map.of("maxWeightKg", 50));
        publicar(m, cargoComVaga).statusCode(400).body("message", containsString("CARGO"));

        Map<String, Object> soCarga = carona(m, "CARGO", 0, null, agora().plusDays(1));
        soCarga.put("cargoDetails", Map.of("maxWeightKg", 50));
        soCarga.put("pricePerPackage", 15);
        String id = publicarOk(m, soCarga);

        publicar(m, carona(m, "PASSENGER", 0, 10.0, agora().plusDays(1))).statusCode(400);

        given().auth().oauth2(m.token()).when().get(app("/api/rides/mine")).then().statusCode(200)
                .body("id", org.hamcrest.Matchers.hasItem(id));
    }

    @Test
    @DisplayName("notificações: lista por caixa, contador e marcar como lidas")
    void notificacoesEndpoint() {
        Motorista m = motorista("not-mot", 4, null);
        String rideId = publicarOk(m, carona(m, "PASSENGER", 3, 30.0, agora().plusDays(1)));
        pedirVaga(conta("not-p").token(), rideId, Map.of("passengerName", "P")).statusCode(201);

        assertThat(temKind(notificacoes(m.token(), "DRIVER"), "RIDE_REQUEST")).isTrue();
        int unread = given().auth().oauth2(m.token()).queryParam("audience", "DRIVER")
                .when().get(app("/api/notifications/unread-count")).then().statusCode(200).extract().path("unread");
        assertThat(unread).isPositive();
        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("audience", "DRIVER"))
                .when().post(app("/api/notifications/read")).then().statusCode(200);
        given().auth().oauth2(m.token()).queryParam("audience", "DRIVER")
                .when().get(app("/api/notifications/unread-count")).then().statusCode(200).body("unread", equalTo(0));
    }
}
