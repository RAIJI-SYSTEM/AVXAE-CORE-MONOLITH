package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Mural da região: pedidos agendados que os motoristas veem e para os quais oferecem um valor. */
@DisplayName("Mural: pedidos para depois, motoristas da região oferecem, o dono escolhe")
class MuralFluxoApiIT extends FluxoTestSupport {

    private static Map<String, Object> pedido(String kind, LocalDateTime quando) {
        Map<String, Object> b = new HashMap<>();
        b.put("kind", kind);
        b.put("origin", Map.of("city", "São Paulo", "state", "SP", "latitude", SE[0], "longitude", SE[1],
                "address", "Rua Secreta, 10"));
        b.put("destination", Map.of("city", "Campinas", "state", "SP", "latitude", CAMPINAS[0], "longitude", CAMPINAS[1],
                "address", "Av. Sigilosa, 20"));
        b.put("desiredAt", quando.format(ISO));
        if ("PACKAGE".equals(kind)) b.put("pkg", EncomendaFluxoApiIT.pacote("SMALL", 3));
        return b;
    }

    private ValidatableResponse publicarPedido(String token, Map<String, Object> body) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(body).when().post(app("/api/board")).then();
    }

    private String pedidoOk(Conta c, String kind, LocalDateTime quando) {
        return publicarPedido(c.token(), pedido(kind, quando)).statusCode(201)
                .body("status", equalTo("OPEN")).body("mine", equalTo(true)).extract().path("id");
    }

    private ValidatableResponse oferecer(Motorista m, String pedidoId, Map<String, Object> body) {
        return given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(body)
                .when().post(app("/api/board/" + pedidoId + "/offers")).then();
    }

    private ValidatableResponse escolher(String token, String pedidoId, String ofertaId) {
        return given().auth().oauth2(token).when()
                .post(app("/api/board/" + pedidoId + "/offers/" + ofertaId + "/accept")).then();
    }

    private ValidatableResponse regiao(Motorista m, double[] posicao) {
        var req = given().auth().oauth2(m.token());
        if (posicao != null) req.queryParam("lat", posicao[0]).queryParam("lng", posicao[1]);
        return req.when().get(app("/api/board/region")).then().statusCode(200);
    }

    @Test
    @DisplayName("publicação: menos de 1h 400, sem pacote 400, empresa em carona 400, sem token 401; retirar some do mural")
    void publicacao() {
        Conta c = conta("mural-pub");
        publicarPedido(c.token(), pedido("SEAT", agora().plusMinutes(45))).statusCode(400)
                .body("message", containsString("1 hora"));
        publicarPedido(c.token(), pedido("SEAT", agora().plusMonths(4))).statusCode(400);
        Map<String, Object> semPacote = pedido("PACKAGE", agora().plusDays(1));
        semPacote.remove("pkg");
        publicarPedido(c.token(), semPacote).statusCode(400);
        given().contentType(ContentType.JSON).body(pedido("SEAT", agora().plusDays(1)))
                .when().post(app("/api/board")).then().statusCode(401);

        String id = pedidoOk(c, "SEAT", agora().plusDays(1));
        given().auth().oauth2(c.token()).when().get(app("/api/board/mine")).then().statusCode(200).body("id", hasItem(id));
        // passageiro não é motorista: não vê o mural da região nem o pedido dos outros
        Conta outro = conta("mural-outro");
        given().auth().oauth2(outro.token()).when().get(app("/api/board/region")).then().statusCode(403);
        given().auth().oauth2(outro.token()).when().get(app("/api/board/" + id)).then().statusCode(404);
        given().auth().oauth2(outro.token()).when().post(app("/api/board/" + id + "/cancel")).then().statusCode(404);

        Motorista m = motorista("mural-pub-mot", 4, 300);
        regiao(m, SE).body("id", hasItem(id));
        oferecer(m, id, Map.of("price", 30, "vehicleId", m.vehicleId())).statusCode(201);
        given().auth().oauth2(c.token()).when().post(app("/api/board/" + id + "/cancel")).then().statusCode(200)
                .body("status", equalTo("CANCELLED"));
        regiao(m, SE).body("id", not(hasItem(id)));
        assertThat(temKind(notificacoes(m.token(), "DRIVER"), "BOARD_OFFER_DECLINED")).isTrue();
        oferecer(m, id, Map.of("price", 30, "vehicleId", m.vehicleId())).statusCode(409);
    }

    @Test
    @DisplayName("região: 30 km da saída ou no caminho de carona publicada; endereço, sobrenome e destinatário escondidos")
    void regiaoEPrivacidade() {
        Conta c = conta("mural-reg");
        LocalDateTime quando = agora().plusDays(2);
        String id = pedidoOk(c, "PACKAGE", quando);

        Motorista perto = motorista("mural-perto", 4, 300);
        regiao(perto, SE).body("id", hasItem(id));
        regiao(perto, RIO).body("id", not(hasItem(id)));
        regiao(perto, null).body("id", not(hasItem(id)));
        String p = "find { it.id == '" + id + "' }.";
        regiao(perto, SE).body(p + "originAddress", nullValue()).body(p + "destinationAddress", nullValue())
                .body(p + "recipientName", nullValue()).body(p + "recipientPhone", nullValue())
                .body(p + "originLatitude", equalTo(-23.55f)).body(p + "mine", equalTo(false))
                .body(p + "description", equalTo("Documentos e livros"));

        // longe (Rio), mas com carona publicada passando pelo caminho no horário
        Motorista rota = motorista("mural-rota", 4, 300);
        Map<String, Object> carona = carona(rota, "MIXED", 3, 50.0, quando.plusHours(1));
        carona.put("cargoDetails", Map.of("maxWeightKg", 30, "maxLengthCm", 80, "maxWidthCm", 60, "maxHeightCm", 50));
        String rideId = publicarOk(rota, carona);
        regiao(rota, RIO).body(p + "matchingRide.id", equalTo(rideId));
        regiao(rota, null).body(p + "matchingRide.id", equalTo(rideId));

        // carona só de passageiros não serve para encomenda; fora do horário também não
        Motorista outra = motorista("mural-fora", 4, 300);
        publicarOk(outra, carona(outra, "PASSENGER", 3, 50.0, quando));
        publicarOk(outra, carona(outra, "PASSENGER", 3, 50.0, quando.plusHours(9)));
        regiao(outra, null).body("id", not(hasItem(id)));
    }

    @Test
    @DisplayName("passageiro: dois motoristas oferecem, ele escolhe a viagem nova -> reserva confirmada e viagem exclusiva")
    void passageiroViagemNova() {
        Conta c = conta("mural-pass");
        LocalDateTime quando = agora().plusDays(1).withNano(0);
        String id = pedidoOk(c, "SEAT", quando);
        Motorista a = motorista("mural-a", 4, 300);
        Motorista b = motorista("mural-b", 4, 300);

        oferecer(a, id, Map.of("price", 0.5, "vehicleId", a.vehicleId())).statusCode(400);
        oferecer(a, id, Map.of("price", 40)).statusCode(400); // nem carona nem veículo
        oferecer(a, id, Map.of("price", 40, "vehicleId", b.vehicleId())).statusCode(not(201)); // veículo de outro
        String ofertaA = oferecer(a, id, Map.of("price", 45.5, "vehicleId", a.vehicleId(), "message", "Ar-condicionado"))
                .statusCode(201).body("newRide", equalTo(true)).extract().path("id");
        oferecer(a, id, Map.of("price", 44, "vehicleId", a.vehicleId())).statusCode(409); // já tem oferta
        oferecer(b, id, Map.of("price", 60, "vehicleId", b.vehicleId())).statusCode(201);
        assertThat(temKind(notificacoes(c.token(), "PASSENGER"), "BOARD_OFFER")).isTrue();
        regiao(a, SE).body("find { it.id == '" + id + "' }.myOffer.price", equalTo(45.5f));

        given().auth().oauth2(c.token()).when().get(app("/api/board/" + id + "/offers")).then().statusCode(200)
                .body("$", hasSize(2)).body("[0].price", equalTo(45.5f)).body("[0].vehicle", containsString("Argo"));
        // o motorista não vê as ofertas dos outros e ninguém além do dono escolhe
        given().auth().oauth2(b.token()).when().get(app("/api/board/" + id + "/offers")).then().statusCode(404);
        escolher(b.token(), id, ofertaA).statusCode(404);

        Map<String, Object> fechado = escolher(c.token(), id, ofertaA).statusCode(200)
                .body("status", equalTo("ACCEPTED")).body("bookingId", notNullValue()).extract().as(Map.class);
        escolher(c.token(), id, ofertaA).statusCode(409);
        String rideId = (String) fechado.get("rideId");

        given().auth().oauth2(c.token()).when().get(app("/api/rides/bookings/mine")).then().statusCode(200)
                .body("find { it.booking.id == '" + fechado.get("bookingId") + "' }.booking.status", equalTo("CONFIRMED"))
                .body("find { it.booking.id == '" + fechado.get("bookingId") + "' }.booking.price", equalTo(45.5f));
        // viagem exclusiva: o passageiro e o motorista veem; estranhos não
        given().auth().oauth2(c.token()).when().get(app("/api/rides/" + rideId)).then().statusCode(200);
        given().auth().oauth2(a.token()).when().get(app("/api/rides/" + rideId)).then().statusCode(200)
                .body("ride.availableSeats", equalTo(0));
        given().auth().oauth2(conta("mural-estranho").token()).when().get(app("/api/rides/" + rideId)).then().statusCode(404);
        pedirVaga(conta("mural-intruso").token(), rideId, Map.of()).statusCode(not(201));
        given().auth().oauth2(a.token()).when().get(app("/api/rides/mine")).then().statusCode(200).body("id", hasItem(rideId));

        assertThat(temKind(notificacoes(a.token(), "DRIVER"), "BOARD_OFFER_ACCEPTED")).isTrue();
        assertThat(temKind(notificacoes(b.token(), "DRIVER"), "BOARD_OFFER_DECLINED")).isTrue();
        given().auth().oauth2(b.token()).when().get(app("/api/board/offered")).then().statusCode(200)
                .body("find { it.id == '" + id + "' }.myOffer.status", equalTo("DECLINED"));
    }

    @Test
    @DisplayName("passageiro numa carona já publicada: desconta a vaga; carona lotada ou fora do caminho 409")
    void passageiroCaronaExistente() {
        Conta c = conta("mural-exist");
        LocalDateTime quando = agora().plusDays(1).withNano(0);
        String id = pedidoOk(c, "SEAT", quando);
        Motorista m = motorista("mural-exist-mot", 4, 300);
        String tarde = publicarOk(m, carona(m, "PASSENGER", 1, 50.0, quando.plusHours(8)));
        oferecer(m, id, Map.of("price", 40, "rideId", tarde)).statusCode(409).body("message", containsString("horário"));
        String rideId = publicarOk(m, carona(m, "PASSENGER", 1, 50.0, quando.plusMinutes(30)));
        Motorista outro = motorista("mural-exist-out", 4, 300);
        oferecer(outro, id, Map.of("price", 40, "rideId", rideId)).statusCode(404); // carona alheia
        String oferta = oferecer(m, id, Map.of("price", 40, "rideId", rideId)).statusCode(201)
                .body("newRide", equalTo(false)).extract().path("id");
        escolher(c.token(), id, oferta).statusCode(200).body("rideId", equalTo(rideId));
        given().auth().oauth2(m.token()).when().get(app("/api/rides/" + rideId)).then().body("ride.availableSeats", equalTo(0));

        // segundo pedido: a vaga acabou
        Conta d = conta("mural-exist-2");
        String id2 = pedidoOk(d, "SEAT", quando);
        oferecer(m, id2, Map.of("price", 40, "rideId", rideId)).statusCode(409).body("message", containsString("vagas"));
    }

    @Test
    @DisplayName("encomenda: carona existente ou viagem nova -> encomenda confirmada com códigos; oferta retirada não vale")
    void encomenda() {
        Conta c = conta("mural-enc");
        LocalDateTime quando = agora().plusDays(1).withNano(0);
        String id = pedidoOk(c, "PACKAGE", quando);
        Motorista m = motorista("mural-enc-mot", 4, 300);

        String retirada = oferecer(m, id, Map.of("price", 25, "vehicleId", m.vehicleId())).statusCode(201).extract().path("id");
        given().auth().oauth2(m.token()).when().delete(app("/api/board/" + id + "/offers/" + retirada)).then().statusCode(204);
        escolher(c.token(), id, retirada).statusCode(404);

        Map<String, Object> corpo = carona(m, "MIXED", 3, 50.0, quando);
        corpo.put("cargoDetails", Map.of("maxWeightKg", 30, "maxLengthCm", 80, "maxWidthCm", 60, "maxHeightCm", 50));
        String rideId = publicarOk(m, corpo);
        String oferta = oferecer(m, id, Map.of("price", 22, "rideId", rideId)).statusCode(201).extract().path("id");
        String entregaId = escolher(c.token(), id, oferta).statusCode(200).body("rideId", equalTo(rideId))
                .extract().path("deliveryId");
        given().auth().oauth2(c.token()).when().get(app("/api/deliveries/" + entregaId)).then().statusCode(200)
                .body("status", equalTo("CONFIRMED")).body("price", equalTo(22.0f)).body("pickupCode", notNullValue())
                .body("deliveryKeyword", notNullValue());
        given().auth().oauth2(m.token()).when().get(app("/api/deliveries/driving")).then().statusCode(200)
                .body("id", hasItem(entregaId));

        String id2 = pedidoOk(c, "PACKAGE", quando);
        String oferta2 = oferecer(m, id2, Map.of("price", 70, "vehicleId", m.vehicleId())).statusCode(201).extract().path("id");
        Map<String, Object> novo = escolher(c.token(), id2, oferta2).statusCode(200).extract().as(Map.class);
        assertThat(novo.get("rideId")).isNotNull().isNotEqualTo(rideId);
        given().auth().oauth2(c.token()).when().get(app("/api/deliveries/" + novo.get("deliveryId"))).then().statusCode(200)
                .body("status", equalTo("CONFIRMED")).body("price", equalTo(70.0f));
        // o próprio motorista não se oferece para o pedido dele
        Motorista dono = motorista("mural-dono", 4, 300);
        String proprio = publicarPedido(dono.token(), pedido("SEAT", quando)).statusCode(201).extract().path("id");
        oferecer(dono, proprio, Map.of("price", 10, "vehicleId", dono.vehicleId())).statusCode(409);
        regiao(dono, SE).body("id", not(hasItem(proprio)));
    }
}
