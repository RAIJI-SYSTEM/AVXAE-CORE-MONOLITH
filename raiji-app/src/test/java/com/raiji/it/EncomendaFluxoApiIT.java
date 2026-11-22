package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Encomenda como espaço de carga numa carona: pedido, aceite com capacidade, coleta e confirmação. */
@DisplayName("Encomendas em caronas: pedido, capacidade, código de coleta e confirmação por código")
class EncomendaFluxoApiIT extends FluxoTestSupport {

    private String caronaMista(Motorista m, double maxKg) {
        Map<String, Object> body = carona(m, "MIXED", 2, 40.0, agora().plusDays(1));
        body.put("cargoDetails", Map.of("maxWeightKg", maxKg));
        body.put("pricePerPackage", 20);
        return publicarOk(m, body);
    }

    static Map<String, Object> pacote(String tamanho, double kg) {
        Map<String, Object> p = new HashMap<>();
        p.put("size", tamanho);
        p.put("description", "Documentos e livros");
        p.put("weightKg", kg);
        p.put("fragile", false);
        p.put("recipientName", "Rita Destinatária");
        p.put("recipientPhone", "(19) 99999-0000");
        return p;
    }

    private ValidatableResponse enviar(String token, String rideId, Map<String, Object> pacote) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("pkg", pacote))
                .when().post(app("/api/rides/" + rideId + "/deliveries")).then();
    }

    private ValidatableResponse decidir(Motorista m, String rideId, String id, String decisao) {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decisao);
        if ("REJECT".equals(decisao)) body.put("reason", "Não cabe");
        return given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(body)
                .when().patch(app("/api/rides/" + rideId + "/deliveries/" + id)).then();
    }

    private ValidatableResponse codigo(Motorista m, String id, String acao, String code) {
        return given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("code", code))
                .when().post(app("/api/deliveries/" + id + "/" + acao)).then();
    }

    private ValidatableResponse confirmar(
            Motorista m,
            String id,
            String confirmationCode,
            String recipientName,
            String observation) {

        Map<String, Object> body = new HashMap<>();
        body.put("confirmationCode", confirmationCode);
        body.put("recipientName", recipientName);
        body.put("observation", observation);

        return given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(body)
                .when().post(app("/api/deliveries/" + id + "/confirm")).then();
    }

    @Test
    @DisplayName("fluxo completo: pedido -> aceite -> coleta com código -> confirmação da entrega")
    void fluxoCompleto() {
        Motorista m = motorista("enc-mot", 4, 400);
        String rideId = caronaMista(m, 20);
        Conta rem = conta("enc-rem");

        var criado = enviar(rem.token(), rideId, pacote("SMALL", 3)).statusCode(201)
                .body("status", equalTo("PENDING"))
                .body("pickupCode", matchesPattern("\\d{4}"))
                .body("confirmationCode", matchesPattern("\\d{6}"))
                .body("price", equalTo(20.0f))
                .body("platformFee", equalTo(0.30f))
                .body("lengthCm", equalTo(30))
                .extract();

        String id = criado.path("id");
        String codigoColeta = criado.path("pickupCode");
        String codigoConfirmacao = criado.path("confirmationCode");

        assertThat(codigoConfirmacao).matches("\\d{6}");
        assertThat(temKind(notificacoes(m.token(), "DRIVER"), "DELIVERY_REQUEST")).isTrue();

        // O motorista NÃO vê os códigos.
        List<Map<String, Object>> doMotorista = given().auth().oauth2(m.token())
                .when().get(app("/api/rides/" + rideId + "/deliveries")).then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(doMotorista).allSatisfy(d -> {
            assertThat(d.get("pickupCode")).isNull();
            assertThat(d.get("deliveryKeyword")).isNull();
            assertThat(d.get("confirmationCode")).isNull();
        });

        decidir(m, rideId, id, "ACCEPT").statusCode(200).body("status", equalTo("CONFIRMED"));
        assertThat(temKind(notificacoes(rem.token(), "PASSENGER"), "DELIVERY_ACCEPTED")).isTrue();

        codigo(m, id, "pickup", "0000".equals(codigoColeta) ? "1111" : "0000").statusCode(400);
        codigo(m, id, "pickup", codigoColeta).statusCode(200).body("status", equalTo("IN_TRANSIT"));

        // Um código incorreto deve ser rejeitado.
        String codigoIncorreto = "000000".equals(codigoConfirmacao) ? "000001" : "000000";

        confirmar(m, id, codigoIncorreto, "Rita Destinatária", null)
                .statusCode(400);

        // O código correto confirma a entrega.
        confirmar(m, id, codigoConfirmacao, "Rita Destinatária", "Entrega confirmada")
                .statusCode(200)
                .body("status", equalTo("DELIVERED"))
                .body("deliveredAt", notNullValue());

        assertThat(temKind(notificacoes(rem.token(), "PASSENGER"), "DELIVERY_DELIVERED")).isTrue();

        // O remetente vê a encomenda nas suas encomendas, com a carona.
        List<Map<String, Object>> minhas = given().auth().oauth2(rem.token())
                .when().get(app("/api/deliveries/mine")).then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(minhas).anySatisfy(d -> assertThat(d.get("id")).isEqualTo(id));
    }

    @Test
    @DisplayName("capacidade: o aceite confere o peso total da carona (bagagens + encomendas)")
    void capacidade() {
        Motorista m = motorista("cap-mot", 4, 400);
        String rideId = caronaMista(m, 10);
        String a = enviar(conta("cap-a").token(), rideId, pacote("SMALL", 4)).statusCode(201).extract().path("id");
        String b = enviar(conta("cap-b").token(), rideId, pacote("MEDIUM", 8)).statusCode(201).extract().path("id");

        decidir(m, rideId, a, "ACCEPT").statusCode(200);
        decidir(m, rideId, b, "ACCEPT").statusCode(400).body("message", containsString("excede"));
        decidir(m, rideId, b, "REJECT").statusCode(200).body("status", equalTo("CANCELLED"));

        // Peso acima do máximo do tamanho.
        enviar(conta("cap-c").token(), rideId, pacote("ENVELOPE", 3)).statusCode(400)
                .body("message", containsString("tamanho maior"));
    }

    @Test
    @DisplayName("regras: carona só de passageiros não leva encomenda; 5 códigos errados bloqueiam; cancelar antes da coleta")
    void regras() {
        Motorista m = motorista("regra-mot", 4, null);
        String soPassageiros = publicarOk(m, carona(m, "PASSENGER", 2, 30.0, agora().plusDays(1)));
        enviar(conta("regra-x").token(), soPassageiros, pacote("SMALL", 1)).statusCode(409);

        String rideId = caronaMista(m, 30);
        Conta rem = conta("regra-rem");
        String id = enviar(rem.token(), rideId, pacote("SMALL", 1)).statusCode(201).extract().path("id");

        decidir(m, rideId, id, "ACCEPT").statusCode(200);
        for (int i = 0; i < 5; i++) codigo(m, id, "pickup", "abcd").statusCode(400);
        codigo(m, id, "pickup", "abcd").statusCode(429);

        String outra = enviar(rem.token(), rideId, pacote("SMALL", 1)).statusCode(201).extract().path("id");
        given().auth().oauth2(rem.token()).contentType(ContentType.JSON).body(Map.of("reason", "Desisti"))
                .when().post(app("/api/deliveries/" + outra + "/cancel")).then().statusCode(200)
                .body("status", equalTo("CANCELLED"));

        assertThat(temKind(notificacoes(m.token(), "DRIVER"), "DELIVERY_CANCELLED")).isTrue();

        // Outra pessoa não vê.
        given().auth().oauth2(conta("regra-curioso").token()).when().get(app("/api/deliveries/" + outra))
                .then().statusCode(404);
    }

    @Test
    @DisplayName("viagem cancelada: encomenda aceita e não coletada é cancelada e o remetente avisado")
    void viagemCancelada() {
        Motorista m = motorista("vc-mot", 4, null);
        String rideId = caronaMista(m, 30);
        Conta rem = conta("vc-rem");
        String id = enviar(rem.token(), rideId, pacote("SMALL", 2)).statusCode(201).extract().path("id");

        decidir(m, rideId, id, "ACCEPT").statusCode(200);
        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of())
                .when().post(app("/api/rides/" + rideId + "/cancel")).then().statusCode(200);

        given().auth().oauth2(rem.token()).when().get(app("/api/deliveries/" + id)).then().statusCode(200)
                .body("status", equalTo("CANCELLED")).body("cancellationReason", notNullValue());

        assertThat(temKind(notificacoes(rem.token(), "PASSENGER"), "DELIVERY_CANCELLED")).isTrue();
    }

    @Test
    @DisplayName("busca de encomenda acha carona MIXED/CARGO com preço por encomenda")
    void buscaParaEncomenda() {
        Motorista m = motorista("be-mot", 4, null);
        String rideId = caronaMista(m, 30);

        List<Map<String, Object>> achados = given().auth().oauth2(conta("be-x").token())
                .queryParam("originLat", SE[0]).queryParam("originLng", SE[1])
                .queryParam("destinationLat", CAMPINAS[0]).queryParam("destinationLng", CAMPINAS[1])
                .queryParam("kind", "PACKAGE")
                .when().get(app("/api/rides/search")).then().statusCode(200)
                .extract().jsonPath().getList("ride");

        assertThat(achados).anySatisfy(r -> {
            assertThat(r.get("id")).isEqualTo(rideId);
            assertThat(r.get("pricePerPackage")).isNotNull();
        });
    }
}
