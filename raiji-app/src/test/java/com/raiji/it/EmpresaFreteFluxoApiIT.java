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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Empresas (CNPJ na Receita falsa, vários gestores) e propostas de frete (direta e pública). */
@DisplayName("Empresas e frete: CNPJ, membros, proposta direta e pedido público com ofertas")
class EmpresaFreteFluxoApiIT extends FluxoTestSupport {

    private ValidatableResponse cadastrar(Conta c, String cnpj) {
        return given().auth().oauth2(c.token()).contentType(ContentType.JSON).body(Map.of("cnpj", cnpj))
                .when().post(app("/api/companies")).then();
    }

    private String empresa(Conta dono) {
        return cadastrar(dono, FakeBrasilApi.cnpj("12")).statusCode(201).extract().path("id");
    }

    private Map<String, Object> frete(String alvoSub, Double valor) {
        Map<String, Object> b = new HashMap<>();
        if (alvoSub != null) b.put("targetDriverSub", alvoSub);
        if (valor != null) b.put("offeredPrice", valor);
        b.put("origin", Map.of("city", "São Paulo", "state", "SP", "latitude", SE[0], "longitude", SE[1],
                "address", "Rua da Loja, 100"));
        b.put("destination", Map.of("city", "Campinas", "state", "SP", "latitude", CAMPINAS[0],
                "longitude", CAMPINAS[1], "address", "Av. Cliente, 200"));
        b.put("desiredDate", agora().plusDays(1).format(ISO));
        b.put("pkg", EncomendaFluxoApiIT.pacote("MEDIUM", 10));
        b.put("notes", "Retirar no balcão");
        return b;
    }

    private ValidatableResponse criarFrete(Conta membro, String companyId, Map<String, Object> body) {
        return given().auth().oauth2(membro.token()).contentType(ContentType.JSON).body(body)
                .when().post(app("/api/companies/" + companyId + "/freight-requests")).then();
    }

    @Test
    @DisplayName("cadastro pelo CNPJ: inválido 400, inexistente 404, Receita fora 503, baixado 422, ativo 201, repetido 409, MEI")
    void cadastroCnpj() {
        Conta dono = conta("cnpj-dono");
        cadastrar(dono, "11.111.111/1111-11").statusCode(400).body("message", containsString("CNPJ inválido"));
        cadastrar(dono, FakeBrasilApi.cnpj("98")).statusCode(404);
        cadastrar(dono, FakeBrasilApi.cnpj("97")).statusCode(503);
        cadastrar(dono, FakeBrasilApi.cnpj("99")).statusCode(422).body("message", containsString("BAIXADA"));

        String cnpj = FakeBrasilApi.cnpj("12");
        String formatado = cnpj.substring(0, 2) + "." + cnpj.substring(2, 5) + "." + cnpj.substring(5, 8) + "/"
                + cnpj.substring(8, 12) + "-" + cnpj.substring(12);
        cadastrar(dono, formatado).statusCode(201)
                .body("myRole", equalTo("OWNER")).body("kind", equalTo("EMPRESA"))
                .body("cnpjFormatted", equalTo(formatado));
        cadastrar(conta("cnpj-outro"), cnpj).statusCode(409);

        given().auth().oauth2(dono.token()).when().get(app("/api/companies/cnpj/" + FakeBrasilApi.cnpj("96")))
                .then().statusCode(200).body("kind", equalTo("MEI")).body("active", equalTo(true));
    }

    @Test
    @DisplayName("vários gestores: convite por e-mail, aceite, só membros veem, só o dono remove")
    void membros() {
        Conta dono = conta("mem-dono");
        Conta joana = conta("mem-joana");
        String companyId = empresa(dono);

        String conviteId = given().auth().oauth2(dono.token()).contentType(ContentType.JSON)
                .body(Map.of("email", joana.username() + "@raiji.com", "name", "Joana"))
                .when().post(app("/api/companies/" + companyId + "/members")).then().statusCode(201)
                .body("status", equalTo("INVITED")).extract().path("id");
        assertThat(FakeResend.EMAILS).anySatisfy(e -> assertThat(e.toString()).contains(joana.username() + "@raiji.com"));

        // antes de aceitar, Joana não vê a empresa
        given().auth().oauth2(joana.token()).when().get(app("/api/companies/" + companyId)).then().statusCode(404);
        given().auth().oauth2(joana.token()).when().get(app("/api/companies/invites")).then().statusCode(200)
                .body("memberId", hasItem(conviteId));
        given().auth().oauth2(joana.token()).when().post(app("/api/companies/invites/" + conviteId + "/accept"))
                .then().statusCode(200).body("myRole", equalTo("MEMBER"));
        given().auth().oauth2(joana.token()).when().get(app("/api/companies/" + companyId)).then().statusCode(200)
                .body("members", hasSize(2));

        // membro não remove o dono; dono remove o membro
        String donoMemberId = given().auth().oauth2(dono.token()).when().get(app("/api/companies/" + companyId))
                .then().extract().path("members.find { it.role == 'OWNER' }.id");
        given().auth().oauth2(joana.token()).when().delete(app("/api/companies/" + companyId + "/members/" + donoMemberId))
                .then().statusCode(403);
        given().auth().oauth2(dono.token()).when().delete(app("/api/companies/" + companyId + "/members/" + conviteId))
                .then().statusCode(204);
        given().auth().oauth2(joana.token()).when().get(app("/api/companies/" + companyId)).then().statusCode(404);
    }

    @Test
    @DisplayName("proposta direta: motorista vê (sem telefone de quem recebe), aceita com o veículo -> viagem exclusiva + encomenda")
    void propostaDireta() {
        Conta dono = conta("dir-dono");
        String companyId = empresa(dono);
        Motorista m = motorista("dir-mot", 4, 500);

        // para quem não é motorista => 400
        criarFrete(dono, companyId, frete(conta("dir-nao").sub(), 80.0)).statusCode(400);
        // direta sem valor => 400
        criarFrete(dono, companyId, frete(m.sub(), null)).statusCode(400);

        String freteId = criarFrete(dono, companyId, frete(m.sub(), 80.0)).statusCode(201)
                .body("direct", equalTo(true)).body("status", equalTo("OPEN")).extract().path("id");
        assertThat(temKind(notificacoes(m.token(), "DRIVER"), "FREIGHT_PROPOSAL")).isTrue();

        given().auth().oauth2(m.token()).when().get(app("/api/freight-requests/for-me")).then().statusCode(200)
                .body("id", hasItem(freteId))
                .body("find { it.id == '" + freteId + "' }.recipientPhone", nullValue());

        String rideId = given().auth().oauth2(m.token()).contentType(ContentType.JSON)
                .body(Map.of("vehicleId", m.vehicleId()))
                .when().post(app("/api/freight-requests/" + freteId + "/accept")).then().statusCode(200)
                .body("status", equalTo("ACCEPTED")).extract().path("rideId");
        assertThat(rideId).as("o motorista não recebe ids internos na visão dele").isNull();
        assertThat(temKind(notificacoes(dono.token(), "COMPANY"), "FREIGHT_ACCEPTED")).isTrue();

        // a empresa vê a encomenda (com os códigos) e a viagem criada
        Map<String, Object> detalhe = given().auth().oauth2(dono.token())
                .when().get(app("/api/freight-requests/" + freteId)).then().statusCode(200)
                .body("rideId", notNullValue()).body("deliveryId", notNullValue()).extract().as(Map.class);
        String deliveryId = (String) detalhe.get("deliveryId");
        String codigo = given().auth().oauth2(dono.token()).when().get(app("/api/deliveries/" + deliveryId))
                .then().statusCode(200).body("status", equalTo("CONFIRMED")).body("price", equalTo(80.0f))
                .body("companyId", equalTo(companyId)).extract().path("pickupCode");

        // viagem exclusiva: está nas viagens do motorista, mas não aparece na busca
        given().auth().oauth2(m.token()).when().get(app("/api/rides/mine")).then().statusCode(200)
                .body("find { it.id == '" + detalhe.get("rideId") + "' }.visibility", equalTo("PRIVATE"));
        given().auth().oauth2(conta("dir-busca").token())
                .queryParam("originLat", SE[0]).queryParam("originLng", SE[1]).queryParam("kind", "PACKAGE")
                .when().get(app("/api/rides/search")).then().statusCode(200)
                .body("ride.id", not(hasItem(detalhe.get("rideId"))));

        // daqui em diante é o fluxo normal da encomenda
        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("code", codigo))
                .when().post(app("/api/deliveries/" + deliveryId + "/pickup")).then().statusCode(200)
                .body("status", equalTo("IN_TRANSIT"));
    }

    @Test
    @DisplayName("proposta direta recusada")
    void propostaRecusada() {
        Conta dono = conta("rec-dono");
        String companyId = empresa(dono);
        Motorista m = motorista("rec-mot", 4, null);
        String freteId = criarFrete(dono, companyId, frete(m.sub(), 50.0)).statusCode(201).extract().path("id");
        given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(Map.of("reason", "Sem agenda"))
                .when().post(app("/api/freight-requests/" + freteId + "/decline")).then().statusCode(200)
                .body("status", equalTo("DECLINED"));
        assertThat(temKind(notificacoes(dono.token(), "COMPANY"), "FREIGHT_DECLINED")).isTrue();
        // outro motorista não aceita uma proposta que não é dele
        Motorista outro = motorista("rec-outro", 4, null);
        given().auth().oauth2(outro.token()).contentType(ContentType.JSON).body(Map.of("vehicleId", outro.vehicleId()))
                .when().post(app("/api/freight-requests/" + freteId + "/accept")).then().statusCode(404);
    }

    @Test
    @DisplayName("pedido público: motoristas perto se oferecem; qualquer membro escolhe; as outras ofertas são recusadas")
    void pedidoPublico() {
        Conta dono = conta("pub-dono");
        Conta joana = conta("pub-joana");
        String companyId = empresa(dono);
        String convite = given().auth().oauth2(dono.token()).contentType(ContentType.JSON)
                .body(Map.of("email", joana.username() + "@raiji.com"))
                .when().post(app("/api/companies/" + companyId + "/members")).then().statusCode(201).extract().path("id");
        given().auth().oauth2(joana.token()).when().post(app("/api/companies/invites/" + convite + "/accept"))
                .then().statusCode(200);

        String freteId = criarFrete(dono, companyId, frete(null, 70.0)).statusCode(201)
                .body("direct", equalTo(false)).extract().path("id");

        Motorista barato = motorista("pub-barato", 4, null);
        Motorista caro = motorista("pub-caro", 4, null);
        given().auth().oauth2(barato.token()).queryParam("lat", SE[0]).queryParam("lng", SE[1])
                .when().get(app("/api/freight-requests/nearby")).then().statusCode(200).body("id", hasItem(freteId));
        given().auth().oauth2(barato.token()).queryParam("lat", RIO[0]).queryParam("lng", RIO[1])
                .when().get(app("/api/freight-requests/nearby")).then().statusCode(200).body("id", not(hasItem(freteId)));

        String ofertaBarata = given().auth().oauth2(barato.token()).contentType(ContentType.JSON)
                .body(Map.of("price", 60, "vehicleId", barato.vehicleId(), "message", "Saio cedo"))
                .when().post(app("/api/freight-requests/" + freteId + "/offers")).then().statusCode(201)
                .extract().path("id");
        given().auth().oauth2(barato.token()).contentType(ContentType.JSON)
                .body(Map.of("price", 55, "vehicleId", barato.vehicleId()))
                .when().post(app("/api/freight-requests/" + freteId + "/offers")).then().statusCode(409);
        given().auth().oauth2(caro.token()).contentType(ContentType.JSON)
                .body(Map.of("price", 90, "vehicleId", caro.vehicleId()))
                .when().post(app("/api/freight-requests/" + freteId + "/offers")).then().statusCode(201);
        assertThat(temKind(notificacoes(joana.token(), "COMPANY"), "FREIGHT_OFFER")).isTrue();

        // a membro Joana vê as ofertas (mais barata primeiro) e escolhe
        List<Map<String, Object>> ofertas = given().auth().oauth2(joana.token())
                .when().get(app("/api/freight-requests/" + freteId + "/offers")).then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertThat(ofertas).hasSize(2);
        assertThat(ofertas.get(0).get("id")).isEqualTo(ofertaBarata);

        given().auth().oauth2(joana.token())
                .when().post(app("/api/freight-requests/" + freteId + "/offers/" + ofertaBarata + "/accept"))
                .then().statusCode(200).body("status", equalTo("ACCEPTED")).body("deliveryId", notNullValue());
        assertThat(temKind(notificacoes(barato.token(), "DRIVER"), "FREIGHT_OFFER_ACCEPTED")).isTrue();
        assertThat(temKind(notificacoes(caro.token(), "DRIVER"), "FREIGHT_OFFER_DECLINED")).isTrue();

        // fechado: não aceita mais ofertas; quem não é membro não vê as ofertas
        given().auth().oauth2(motorista("pub-tarde", 4, null).token()).contentType(ContentType.JSON)
                .body(Map.of("price", 10, "vehicleId", barato.vehicleId()))
                .when().post(app("/api/freight-requests/" + freteId + "/offers")).then().statusCode(409);
        given().auth().oauth2(caro.token()).when().get(app("/api/freight-requests/" + freteId + "/offers"))
                .then().statusCode(404);
    }

    @Test
    @DisplayName("encomenda em nome de empresa: só membro; todos os membros acompanham")
    void encomendaDeEmpresa() {
        Conta dono = conta("ee-dono");
        String companyId = empresa(dono);
        Motorista m = motorista("ee-mot", 4, null);
        Map<String, Object> carona = carona(m, "MIXED", 2, 30.0, agora().plusDays(1));
        carona.put("cargoDetails", Map.of("maxWeightKg", 30));
        carona.put("pricePerPackage", 25);
        String rideId = publicarOk(m, carona);

        Map<String, Object> body = new HashMap<>();
        body.put("pkg", EncomendaFluxoApiIT.pacote("SMALL", 2));
        body.put("companyId", companyId);
        given().auth().oauth2(conta("ee-intruso").token()).contentType(ContentType.JSON).body(body)
                .when().post(app("/api/rides/" + rideId + "/deliveries")).then().statusCode(404);
        String id = given().auth().oauth2(dono.token()).contentType(ContentType.JSON).body(body)
                .when().post(app("/api/rides/" + rideId + "/deliveries")).then().statusCode(201).extract().path("id");
        given().auth().oauth2(dono.token()).when().get(app("/api/deliveries/mine")).then().statusCode(200)
                .body("id", hasItem(id));
    }
}
