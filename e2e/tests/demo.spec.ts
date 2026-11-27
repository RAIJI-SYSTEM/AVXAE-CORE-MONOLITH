import { test, expect } from "@playwright/test";
import {
  CHAT,
  KONG,
  type E2EUser,
  acceptSeat,
  auth,
  cancelSeat,
  createRide,
  createUser,
  createVehicle,
  requestSeat,
  waitForChatByRide,
} from "../helpers/raiji";
import { demoStep, startDemo } from "../helpers/demo";

/**
 * Tour guiado em vídeo: usa o fluxo real de API e desenha cada passo em uma
 * página do navegador. O Playwright grava o vídeo porque existe um browser
 * context, mesmo sendo um teste de API.
 */
test.describe("Monólito — tour guiado em vídeo", () => {
  test("jornada completa: usuário, veículo, corrida, reserva e chat", async ({ page }) => {
    await startDemo(
      page,
      "Monólito",
      "Jornada completa de uma corrida",
      "Conta no Keycloak, veículo, publicação da corrida, pedido de vaga, aceite, "
        + "grupo de chat via RabbitMQ, mensagem e auditoria — tudo pelo mesmo fluxo real.",
    );

    let driver!: E2EUser;
    let passenger!: E2EUser;
    let vehicleId = "";
    let ride!: { id: string; originCity: string; destinationCity: string };
    let requestId = "";
    let conversationId = "";
    const messageText = `Olá do tour E2E ${Date.now()}`;

    await demoStep(page, "Criar motorista no Keycloak", async () => {
      driver = await createUser(page.request, "DRIVER");
      return `Conta ${driver.username}`;
    });

    await demoStep(page, "Criar passageiro no Keycloak", async () => {
      passenger = await createUser(page.request, "PASSENGER");
      return `Conta ${passenger.username}`;
    });

    await demoStep(page, "Cadastrar veículo do motorista", async () => {
      vehicleId = await createVehicle(page.request, driver.token);
      return `Veículo ${vehicleId}`;
    });

    await demoStep(page, "Publicar corrida", async () => {
      ride = await createRide(page.request, driver.token, vehicleId);
      return `${ride.originCity} → ${ride.destinationCity}`;
    });

    await demoStep(page, "Passageiro solicita uma vaga", async () => {
      requestId = await requestSeat(page.request, passenger.token, ride.id);
      return `Solicitação ${requestId}`;
    });

    await demoStep(page, "Motorista aceita a solicitação", async () => {
      await acceptSeat(page.request, driver.token, ride.id, requestId);
      return "Reserva confirmada";
    });

    await demoStep(page, "Grupo de chat criado pelo evento da corrida", async () => {
      conversationId = await waitForChatByRide(page.request, driver.token, ride.id);
      return `Conversa ${conversationId}`;
    });

    await demoStep(page, "Motorista envia mensagem no chat", async () => {
      const sent = await page.request.post(`${CHAT}/conversations/${conversationId}/messages`, {
        headers: auth(driver.token),
        data: { text: messageText },
      });
      expect(sent.status(), await sent.text()).toBe(201);
      return "Mensagem enviada";
    });

    await demoStep(page, "Passageiro lê a mesma mensagem", async () => {
      const messages = await page.request.get(
        `${CHAT}/conversations/${conversationId}/messages`,
        { headers: auth(passenger.token) },
      );
      expect(messages.status(), await messages.text()).toBe(200);
      expect(JSON.stringify(await messages.json())).toContain(messageText);
      return "Mensagem confirmada na conversa";
    });

    await demoStep(page, "Consultar a auditoria da conta", async () => {
      const audit = await page.request.get(`${KONG}/api/audit/me`, {
        headers: auth(driver.token),
      });
      expect(audit.status(), await audit.text()).toBe(200);
      return "Auditoria consultada (HTTP 200)";
    });

    await demoStep(page, "Cancelar a reserva para liberar a vaga", async () => {
      await cancelSeat(page.request, passenger.token, ride.id, requestId);
      return "Vaga liberada";
    });
  });
});
