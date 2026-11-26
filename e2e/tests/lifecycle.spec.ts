import { test, expect } from "@playwright/test";
import {
  KONG,
  acceptSeat,
  auth,
  createRide,
  createUser,
  createVehicle,
  requestSeat,
} from "../helpers/raiji";

const json = { "Content-Type": "application/json" };

test.describe("AVXAE-CORE-MONOLITH — endpoints restantes", () => {
  test("ciclo da viagem: fechar reservas, iniciar, finalizar e cancelar carona", async ({
    request,
  }) => {
    const driver = await createUser(request, "DRIVER");
    const vehicleId = await createVehicle(request, driver.token);

    const close = await createRide(request, driver.token, vehicleId, { departureInMinutes: 30 });
    const closed = await request.post(`${KONG}/api/rides/${close.id}/close-bookings`, {
      headers: auth(driver.token),
    });
    expect(closed.status(), await closed.text()).toBe(200);

    const run = await createRide(request, driver.token, vehicleId, { departureInMinutes: 30 });
    const started = await request.post(`${KONG}/api/rides/${run.id}/start`, {
      headers: auth(driver.token),
    });
    expect(started.status(), await started.text()).toBe(200);
    const finished = await request.post(`${KONG}/api/rides/${run.id}/finish`, {
      headers: auth(driver.token),
    });
    expect(finished.status(), await finished.text()).toBe(200);

    const toCancel = await createRide(request, driver.token, vehicleId);
    const cancelled = await request.post(`${KONG}/api/rides/${toCancel.id}/cancel`, {
      headers: { ...auth(driver.token), ...json },
      data: { reason: "Cancelamento E2E" },
    });
    expect(cancelled.status(), await cancelled.text()).toBe(200);
    expect(await cancelled.json()).toMatchObject({ status: "CANCELLED" });
  });

  test("aceitar todos e aprovar aumento de bagagem", async ({ request }) => {
    const driver = await createUser(request, "DRIVER");
    const p1 = await createUser(request, "PASSENGER");
    const p2 = await createUser(request, "PASSENGER");
    const vehicleId = await createVehicle(request, driver.token);

    const ride = await createRide(request, driver.token, vehicleId, { totalSeats: 3 });
    await requestSeat(request, p1.token, ride.id, "Passageiro 1");
    await requestSeat(request, p2.token, ride.id, "Passageiro 2");

    const acceptAll = await request.post(`${KONG}/api/rides/${ride.id}/requests/accept-all`, {
      headers: auth(driver.token),
    });
    expect(acceptAll.status(), await acceptAll.text()).toBe(200);

    const baggageRide = await createRide(request, driver.token, vehicleId, { totalSeats: 2 });
    const requestId = await requestSeat(request, p1.token, baggageRide.id, "Bagagem E2E");
    await acceptSeat(request, driver.token, baggageRide.id, requestId);

    const changed = await request.put(
      `${KONG}/api/rides/${baggageRide.id}/requests/${requestId}/baggage`,
      {
        headers: { ...auth(p1.token), ...json },
        data: {
          baggage: [
            { type: "mala", quantity: 1, lengthCm: 60, widthCm: 40, heightCm: 30, weightKg: 12 },
          ],
        },
      },
    );
    expect(changed.status(), await changed.text()).toBe(200);

    const decided = await request.patch(
      `${KONG}/api/rides/${baggageRide.id}/requests/${requestId}/baggage`,
      { headers: { ...auth(driver.token), ...json }, data: { decision: "ACCEPT" } },
    );
    expect(decided.status(), await decided.text()).toBe(200);
  });

  test("buscar motorista pelo código e consultar mural completo", async ({ request }) => {
    const driver = await createUser(request, "DRIVER");
    const owner = await createUser(request, "PASSENGER");
    const vehicleId = await createVehicle(request, driver.token);

    await createRide(request, driver.token, vehicleId);

    const codeResponse = await request.get(`${KONG}/api/rides/drivers/me/code`, {
      headers: auth(driver.token),
    });
    expect(codeResponse.status(), await codeResponse.text()).toBe(200);
    const code = (await codeResponse.json()).code as string;

    const byCode = await request.get(`${KONG}/api/rides/drivers/by-code/${code}`, {
      headers: auth(owner.token),
    });
    expect(byCode.status(), await byCode.text()).toBe(200);

    const published = await request.post(`${KONG}/api/board`, {
      headers: { ...auth(owner.token), ...json },
      data: {
        kind: "SEAT",
        origin: { city: "São Paulo", state: "SP", latitude: -23.55, longitude: -46.63 },
        destination: { city: "Campinas", state: "SP", latitude: -22.9, longitude: -47.06 },
        desiredAt: new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 19),
      },
    });
    expect(published.status(), await published.text()).toBe(201);
    const boardId = (await published.json()).id as string;

    for (const path of ["/api/board/mine", `/api/board/${boardId}`, "/api/board/offered"]) {
      const response = await request.get(`${KONG}${path}`, { headers: auth(driver.token) });
      expect(response.status(), `${path}: ${await response.text()}`).toBe(200);
    }

    const offer = await request.post(`${KONG}/api/board/${boardId}/offers`, {
      headers: { ...auth(driver.token), ...json },
      data: { price: 25, vehicleId },
    });
    expect(offer.status(), await offer.text()).toBe(201);
    const offerId = (await offer.json()).id as string;

    const withdraw = await request.delete(`${KONG}/api/board/${boardId}/offers/${offerId}`, {
      headers: auth(driver.token),
    });
    expect(withdraw.status()).toBe(204);

    const cancelled = await request.post(`${KONG}/api/board/${boardId}/cancel`, {
      headers: auth(owner.token),
    });
    expect(cancelled.status(), await cancelled.text()).toBe(200);
  });

  test("encomenda: detalhe, encomendas em condução, pagamento e cancelamento", async ({
    request,
  }) => {
    const driver = await createUser(request, "DRIVER");
    const sender = await createUser(request, "PASSENGER");
    const vehicleId = await createVehicle(request, driver.token);
    const ride = await createRide(request, driver.token, vehicleId, {
      type: "MIXED",
      totalSeats: 2,
      pricePerPackage: "20.00",
      cargoDetails: { maxWeightKg: 30, maxLengthCm: 80, maxWidthCm: 60, maxHeightCm: 50 },
    });

    const created = await request.post(`${KONG}/api/rides/${ride.id}/deliveries`, {
      headers: { ...auth(sender.token), ...json },
      data: {
        pkg: {
          size: "SMALL",
          description: "Pacote E2E",
          weightKg: 2,
          recipientName: "Destino E2E",
          recipientPhone: "(19) 99999-0000",
        },
      },
    });
    expect(created.status(), await created.text()).toBe(201);
    const deliveryId = (await created.json()).id as string;

    for (const path of [
      `/api/deliveries/${deliveryId}`,
      "/api/deliveries/mine",
      "/api/deliveries/driving",
    ]) {
      const response = await request.get(`${KONG}${path}`, { headers: auth(driver.token) });
      expect(response.status(), `${path}: ${await response.text()}`).toBe(200);
    }

    const payment = await request.get(`${KONG}/api/deliveries/${deliveryId}/payment`, {
      headers: auth(sender.token),
    });
    expect(payment.status(), await payment.text()).toBe(200);

    const cancel = await request.post(`${KONG}/api/deliveries/${deliveryId}/cancel`, {
      headers: { ...auth(sender.token), ...json },
      data: { reason: "E2E" },
    });
    expect(cancel.status(), await cancel.text()).toBe(200);
    expect(await cancel.json()).toMatchObject({ status: "CANCELLED" });
  });

  test("perfil legado, deixar de ser motorista e callback AdMob inválido", async ({
    request,
  }) => {
    const driver = await createUser(request, "DRIVER");

    const legacy = await request.post(`${KONG}/api/users/me/profile`, {
      headers: { ...auth(driver.token), ...json },
      data: { role: "PASSENGER", userType: "PF" },
    });
    expect(legacy.status(), await legacy.text()).toBe(200);

    const stop = await request.delete(`${KONG}/api/users/me/driver`, {
      headers: auth(driver.token),
    });
    expect(stop.status(), await stop.text()).toBe(200);

    const admob = await request.get(`${KONG}/api/public/rewards/admob-ssv`);
    expect(admob.status()).toBe(403);
  });
});
