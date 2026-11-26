import { test, expect } from "@playwright/test";
import {
  CHAT,
  KONG,
  acceptSeat,
  auth,
  cancelSeat,
  createRide,
  createUser,
  createVehicle,
  getToken,
  requestSeat,
  waitForChatByRide,
} from "../helpers/raiji";

/**
 * E2E de sistema do monólito.
 *
 * O monólito não tem tela; o Playwright exercita a API real via Kong e,
 * no fluxo de carona, verifica a integração com o MESSAGE-SERVICE pelo
 * grupo criado no chat.
 */
test.describe("AVXAE-CORE-MONOLITH — sistema", () => {
  test("health, autenticação e proteção sem token", async ({ request }) => {
    const health = await request.get(`${KONG}/actuator/health`);
    expect(health.status()).toBe(200);
    expect(await health.json()).toMatchObject({ status: "UP" });

    const token = await getToken(request, "teste", "teste123");
    const me = await request.get(`${KONG}/api/me`, { headers: auth(token) });
    expect(me.status()).toBe(200);
    expect(await me.json()).toMatchObject({ email: "teste@raiji.com" });

    const unauthorized = await request.get(`${KONG}/api/me`);
    expect(unauthorized.status()).toBe(401);
  });

  test("carona: publicar, buscar, solicitar, aceitar e conversar no chat", async ({ request }) => {
    const driver = await createUser(request, "DRIVER");
    const passenger = await createUser(request, "PASSENGER");

    const vehicleId = await createVehicle(request, driver.token);
    const ride = await createRide(request, driver.token, vehicleId);

    // Passageiro encontra a carona publicada
    const search = await request.get(`${KONG}/api/rides`, {
      headers: auth(passenger.token),
      params: { originCity: ride.originCity, minSeats: 1 },
    });
    expect(search.status(), await search.text()).toBe(200);
    const searchBody = await search.json();
    expect(searchBody.content.map((r: { id: string }) => r.id)).toContain(ride.id);

    // Solicita vaga e o motorista aceita
    const requestId = await requestSeat(request, passenger.token, ride.id);
    await acceptSeat(request, driver.token, ride.id, requestId);

    // O evento de carona cria o grupo no chat (RabbitMQ -> MESSAGE-SERVICE)
    const conversationId = await waitForChatByRide(request, driver.token, ride.id);

    // Motorista envia uma mensagem; passageiro lê a mesma conversa
    const messageText = `Olá do E2E ${Date.now()}`;
    const sent = await request.post(`${CHAT}/conversations/${conversationId}/messages`, {
      headers: auth(driver.token),
      data: { text: messageText },
    });
    expect(sent.status(), await sent.text()).toBe(201);

    const messages = await request.get(`${CHAT}/conversations/${conversationId}/messages`, {
      headers: auth(passenger.token),
    });
    expect(messages.status(), await messages.text()).toBe(200);
    expect(JSON.stringify(await messages.json())).toContain(messageText);

    // Cancela a reserva para não deixar vaga presa
    await cancelSeat(request, passenger.token, ride.id, requestId);
  });

  test("chat respeita autenticação e membros", async ({ request }) => {
    const userA = await createUser(request, "PASSENGER");
    const userB = await createUser(request, "PASSENGER");

    const conversation = await request.post(`${CHAT}/conversations/direct`, {
      headers: auth(userA.token),
      data: { userId: userB.sub, name: "Contato E2E" },
    });
    expect(conversation.status(), await conversation.text()).toBe(200);
    const conversationBody = await conversation.json();
    const conversationId = (conversationBody.id ?? conversationBody._id) as string;
    expect(conversationId).toBeTruthy();

    const asMember = await request.get(`${CHAT}/conversations/${conversationId}`, {
      headers: auth(userB.token),
    });
    expect(asMember.status(), await asMember.text()).toBe(200);

    const outsider = await createUser(request, "PASSENGER");
    const asOutsider = await request.get(`${CHAT}/conversations/${conversationId}`, {
      headers: auth(outsider.token),
    });
    expect(asOutsider.status()).toBe(404);

    const anonymous = await request.get(`${CHAT}/conversations`);
    expect(anonymous.status()).toBe(401);
  });
});
