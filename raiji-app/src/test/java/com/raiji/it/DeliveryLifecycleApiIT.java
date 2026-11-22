
package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Ciclo de vida da entrega: confirmação, histórico e timeline")
class DeliveryLifecycleApiIT extends FluxoTestSupport {

    private String criarCaronaComEncomenda(Motorista motorista) {
        Map<String, Object> body =
                carona(motorista, "MIXED", 2, 40.0, agora().plusDays(1));

        body.put("cargoDetails", Map.of("maxWeightKg", 20));
        body.put("pricePerPackage", 20);

        return publicarOk(motorista, body);
    }

    private String solicitarEncomenda(String token, String rideId) {
        Map<String, Object> dadosPacote = new HashMap<>();
        dadosPacote.put("size", "SMALL");
        dadosPacote.put("description", "Documentos e livros");
        dadosPacote.put("weightKg", 3);
        dadosPacote.put("fragile", false);
        dadosPacote.put("recipientName", "Rita Destinataria");
        dadosPacote.put("recipientPhone", "(19) 99999-0000");

        return given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(Map.of("pkg", dadosPacote))
                .when()
                .post(app("/api/rides/" + rideId + "/deliveries"))
                .then()
                .statusCode(201)
                .body("pickupCode", matchesPattern("\\d{4}"))
                .extract()
                .path("id");
    }
    private void aceitarEncomenda(
            Motorista motorista,
            String rideId,
            String deliveryId
    ) {
        given()
                .auth().oauth2(motorista.token())
                .contentType(ContentType.JSON)
                .body(Map.of("decision", "ACCEPT"))
                .when()
                .patch(app("/api/rides/" + rideId + "/deliveries/" + deliveryId))
                .then()
                .statusCode(200)
                .body("status", equalTo("CONFIRMED"));
    }

    private void coletar(
            Motorista motorista,
            String deliveryId,
            String pickupCode
    ) {
        given()
                .auth().oauth2(motorista.token())
                .contentType(ContentType.JSON)
                .body(Map.of("code", pickupCode))
                .when()
                .post(app("/api/deliveries/" + deliveryId + "/pickup"))
                .then()
                .statusCode(200)
                .body("status", equalTo("IN_TRANSIT"));
    }

    @Test
    @DisplayName("remetente acompanha a entrega e motorista confirma com código válido")
    void cicloCompletoDeConfirmacao() {
        Motorista motorista = motorista("life-mot", 4, 400);
        Conta remetente = conta("life-rem");

        String rideId = criarCaronaComEncomenda(motorista);
        String deliveryId = solicitarEncomenda(remetente.token(), rideId);

        // Aceite e coleta
        aceitarEncomenda(motorista, rideId, deliveryId);

        String pickupCode = given()
                .auth().oauth2(remetente.token())
                .when()
                .get(app("/api/deliveries/" + deliveryId))
                .then()
                .statusCode(200)
                .extract()
                .path("pickupCode");

        coletar(motorista, deliveryId, pickupCode);

        // O remetente consulta o código de confirmação
        String confirmationCode = given()
                .auth().oauth2(remetente.token())
                .when()
                .get(app("/api/deliveries/" + deliveryId))
                .then()
                .statusCode(200)
                .body("confirmationCode", matchesPattern("\\d{6}"))
                .extract()
                .path("confirmationCode");

        // Código incorreto não conclui a entrega
        String codigoIncorreto =
                "000000".equals(confirmationCode) ? "999999" : "000000";

        given()
                .auth().oauth2(motorista.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "confirmationCode", codigoIncorreto,
                        "recipientName", "Rita Destinataria",
                        "observation", "Primeira tentativa"
                ))
                .when()
                .post(app("/api/deliveries/" + deliveryId + "/confirm"))
                .then()
                .statusCode(400);

        // Código correto conclui a entrega
        given()
                .auth().oauth2(motorista.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "confirmationCode", confirmationCode,
                        "recipientName", "Rita Destinataria",
                        "observation", "Entrega realizada com sucesso"
                ))
                .when()
                .post(app("/api/deliveries/" + deliveryId + "/confirm"))
                .then()
                .statusCode(200)
                .body("status", equalTo("DELIVERED"))
                .body("deliveredAt", notNullValue())
                .body("rewardCents", notNullValue());

        // Timeline deve registrar a entrega
        List<Map<String, Object>> timeline = given()
                .auth().oauth2(remetente.token())
                .when()
                .get(app("/api/deliveries/" + deliveryId + "/timeline"))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");

        assertThat(timeline).anySatisfy(item ->
                assertThat(item.get("toStatus")).isEqualTo("DELIVERED")
        );

        // Histórico do motorista não pode expor o código
        List<Map<String, Object>> historicoMotorista = given()
                .auth().oauth2(motorista.token())
                .when()
                .get(app("/api/deliveries/driver/history"))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("content");

        assertThat(historicoMotorista).anySatisfy(item -> {
            if (deliveryId.equals(String.valueOf(item.get("id")))) {
                assertThat(item.get("confirmationCode")).isNull();
            }
        });

        // Histórico do remetente deve preservar o código
        List<Map<String, Object>> historicoRemetente = given()
                .auth().oauth2(remetente.token())
                .when()
                .get(app("/api/deliveries/sender/history"))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("content");

        assertThat(historicoRemetente).anySatisfy(item -> {
            if (deliveryId.equals(String.valueOf(item.get("id")))) {
                assertThat(item.get("confirmationCode"))
                        .isEqualTo(confirmationCode);
            }
        });
    }
}
