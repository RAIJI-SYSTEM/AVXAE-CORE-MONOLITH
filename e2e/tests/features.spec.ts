import { test, expect } from "@playwright/test";
import {
  CHAT,
  KONG,
  auth,
  createRide,
  createUser,
  createVehicle,
  requestSeat,
} from "../helpers/raiji";

const jsonHeaders = { "Content-Type": "application/json" };
const future = (days = 2) =>
  new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString().slice(0, 19);

test.describe("AVXAE-CORE-MONOLITH — funcionalidades", () => {
  test("perfil, contato e CRUD de veículos", async ({ request }) => {
    const driver = await createUser(request, "DRIVER");

    const me = await request.get(`${KONG}/api/users/me`, { headers: auth(driver.token) });
    expect(me.status(), await me.text()).toBe(200);
    expect(await me.json()).toHaveProperty("realmRoles");

    const account = await request.put(`${KONG}/api/users/me/account-type`, {
      headers: { ...auth(driver.token), ...jsonHeaders },
      data: { userType: "PF" },
    });
    expect(account.status(), await account.text()).toBe(200);

    const phone = "+5511999999999";
    const updated = await request.patch(`${KONG}/api/users/me`, {
      headers: { ...auth(driver.token), ...jsonHeaders },
      data: { phone },
    });
    expect(updated.status(), await updated.text()).toBe(200);
    expect((await updated.json()).phone).toBe(phone);

    const contact = await request.get(`${KONG}/api/users/me/contact`, {
      headers: auth(driver.token),
    });
    expect(contact.status(), await contact.text()).toBe(200);
    expect(await contact.json()).toMatchObject({ phone });

    const vehicleId = await createVehicle(request, driver.token);
    const list = await request.get(`${KONG}/api/vehicles`, { headers: auth(driver.token) });
    expect(list.status(), await list.text()).toBe(200);
    expect(JSON.stringify(await list.json())).toContain(vehicleId);

    const one = await request.get(`${KONG}/api/vehicles/${vehicleId}`, {
      headers: auth(driver.token),
    });
    expect(one.status(), await one.text()).toBe(200);

    const changed = await request.put(`${KONG}/api/vehicles/${vehicleId}`, {
      headers: { ...auth(driver.token), ...jsonHeaders },
      data: {
        plate: `E${String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0")}`,
        type: "CARRO",
        model: "Fiat Argo",
        color: "Vermelho",
        year: 2024,
        passengerSeats: 4,
        cargoVolumeLiters: 400,
      },
    });
    expect(changed.status(), await changed.text()).toBe(200);
    expect((await changed.json()).color).toBe("Vermelho");

    const removed = await request.delete(`${KONG}/api/vehicles/${vehicleId}`, {
      headers: auth(driver.token),
    });
    expect(removed.status()).toBe(204);
  });

  test("ciclo da carona: publicar, buscar, aceitar, mala, código e cancelar", async ({ request }) => {
    const driver = await createUser(request, "DRIVER");
    const passenger = await createUser(request, "PASSENGER");
    const vehicleId = await createVehicle(request, driver.token);
    const ride = await createRide(request, driver.token, vehicleId);

    const detail = await request.get(`${KONG}/api/rides/${ride.id}`, {
      headers: auth(passenger.token),
    });
    expect(detail.status(), await detail.text()).toBe(200);

    const mine = await request.get(`${KONG}/api/rides/mine`, { headers: auth(driver.token) });
    expect(mine.status(), await mine.text()).toBe(200);
    expect(JSON.stringify(await mine.json())).toContain(ride.id);

    const preview = await request.get(`${KONG}/api/rides/price-preview?price=50.00`, {
      headers: auth(driver.token),
    });
    expect(preview.status(), await preview.text()).toBe(200);
    expect(await preview.json()).toBeTruthy();

    const priced = await request.patch(`${KONG}/api/rides/${ride.id}/price`, {
      headers: { ...auth(driver.token), ...jsonHeaders },
      data: { pricePerPassenger: 60 },
    });
    expect(priced.status(), await priced.text()).toBe(200);

    const bagagem = [
      { type: "mala", quantity: 1, lengthCm: 60, widthCm: 40, heightCm: 30, weightKg: 10 },
    ];
    const created = await request.post(`${KONG}/api/rides/${ride.id}/requests`, {
      headers: { ...auth(passenger.token), ...jsonHeaders },
      data: { passengerName: "Passageiro E2E", baggage: bagagem },
    });
    expect(created.status(), await created.text()).toBe(201);
    const requestId = (await created.json()).id as string;

    const driverView = await request.get(`${KONG}/api/rides/${ride.id}/requests`, {
      headers: auth(driver.token),
    });
    expect(driverView.status(), await driverView.text()).toBe(200);
    expect(JSON.stringify(await driverView.json())).toContain(requestId);

    const changedBaggage = await request.put(
      `${KONG}/api/rides/${ride.id}/requests/${requestId}/baggage`,
      {
        headers: { ...auth(passenger.token), ...jsonHeaders },
        data: { baggage: [] },
      },
    );
    expect(changedBaggage.status(), await changedBaggage.text()).toBe(200);

    const accepted = await request.patch(`${KONG}/api/rides/${ride.id}/requests/${requestId}`, {
      headers: { ...auth(driver.token), ...jsonHeaders },
      data: { decision: "ACCEPT", paymentIntentId: null },
    });
    expect(accepted.status(), await accepted.text()).toBe(200);
    expect(await accepted.json()).toMatchObject({ status: "CONFIRMED" });

    const bookings = await request.get(`${KONG}/api/rides/bookings/mine`, {
      headers: auth(passenger.token),
    });
    expect(bookings.status(), await bookings.text()).toBe(200);
    expect(JSON.stringify(await bookings.json())).toContain(requestId);

    const code = await request.get(`${KONG}/api/rides/drivers/me/code`, {
      headers: auth(driver.token),
    });
    expect(code.status(), await code.text()).toBe(200);
    expect((await code.json()).code).toBeTruthy();

    const cancelled = await request.delete(
      `${KONG}/api/rides/${ride.id}/requests/${requestId}`,
      { headers: { ...auth(passenger.token), ...jsonHeaders }, data: { reason: "E2E" } },
    );
    expect(cancelled.status(), await cancelled.text()).toBe(200);
    expect(await cancelled.json()).toMatchObject({ status: "CANCELLED", seatReleased: true });
  });

  test("encomenda em carona: pedido, aceite, coleta e entrega", async ({ request }) => {
    const driver = await createUser(request, "DRIVER");
    const sender = await createUser(request, "PASSENGER");
    const vehicleId = await createVehicle(request, driver.token);
    const ride = await createRide(request, driver.token, vehicleId, {
      type: "MIXED",
      totalSeats: 2,
      pricePerPackage: "20.00",
      cargoDetails: { maxWeightKg: 30, maxLengthCm: 80, maxWidthCm: 60, maxHeightCm: 50 },
    });

    const pkg = {
      size: "SMALL",
      description: "Documentos e livros",
      weightKg: 3,
      fragile: false,
      recipientName: "Rita Destinatária",
      recipientPhone: "(19) 99999-0000",
    };
    const delivery = await request.post(`${KONG}/api/rides/${ride.id}/deliveries`, {
      headers: { ...auth(sender.token), ...jsonHeaders },
      data: { pkg },
    });
    expect(delivery.status(), await delivery.text()).toBe(201);
    const deliveryBody = await delivery.json();
    expect(deliveryBody).toMatchObject({ status: "PENDING" });

    const list = await request.get(`${KONG}/api/rides/${ride.id}/deliveries`, {
      headers: auth(driver.token),
    });
    expect(list.status(), await list.text()).toBe(200);

    const accepted = await request.patch(
      `${KONG}/api/rides/${ride.id}/deliveries/${deliveryBody.id}`,
      { headers: { ...auth(driver.token), ...jsonHeaders }, data: { decision: "ACCEPT" } },
    );
    expect(accepted.status(), await accepted.text()).toBe(200);

    const picked = await request.post(`${KONG}/api/deliveries/${deliveryBody.id}/pickup`, {
      headers: { ...auth(driver.token), ...jsonHeaders },
      data: { code: deliveryBody.pickupCode },
    });
    expect(picked.status(), await picked.text()).toBe(200);
    expect(await picked.json()).toMatchObject({ status: "IN_TRANSIT" });

    const delivered = await request.post(`${KONG}/api/deliveries/${deliveryBody.id}/deliver`, {
      headers: { ...auth(driver.token), ...jsonHeaders },
      data: { code: deliveryBody.deliveryKeyword },
    });
    expect(delivered.status(), await delivered.text()).toBe(200);
    expect(await delivered.json()).toMatchObject({ status: "DELIVERED" });

    const mine = await request.get(`${KONG}/api/deliveries/mine`, {
      headers: auth(sender.token),
    });
    expect(mine.status(), await mine.text()).toBe(200);
    expect(JSON.stringify(await mine.json())).toContain(deliveryBody.id);
  });

  test("mural: pedido, oferta do motorista e aceite", async ({ request }) => {
    const owner = await createUser(request, "PASSENGER");
    const driver = await createUser(request, "DRIVER");
    const vehicleId = await createVehicle(request, driver.token);

    const board = await request.post(`${KONG}/api/board`, {
      headers: { ...auth(owner.token), ...jsonHeaders },
      data: {
        kind: "SEAT",
        origin: { city: "São Paulo", state: "SP", latitude: -23.55, longitude: -46.63 },
        destination: { city: "Campinas", state: "SP", latitude: -22.9, longitude: -47.06 },
        desiredAt: future(),
      },
    });
    expect(board.status(), await board.text()).toBe(201);
    const boardId = (await board.json()).id as string;

    const region = await request.get(`${KONG}/api/board/region?lat=-23.55&lng=-46.63`, {
      headers: auth(driver.token),
    });
    expect(region.status(), await region.text()).toBe(200);
    expect(JSON.stringify(await region.json())).toContain(boardId);

    const offer = await request.post(`${KONG}/api/board/${boardId}/offers`, {
      headers: { ...auth(driver.token), ...jsonHeaders },
      data: { price: 30, vehicleId },
    });
    expect(offer.status(), await offer.text()).toBe(201);
    const offerId = (await offer.json()).id as string;

    const offers = await request.get(`${KONG}/api/board/${boardId}/offers`, {
      headers: auth(owner.token),
    });
    expect(offers.status(), await offers.text()).toBe(200);
    expect(JSON.stringify(await offers.json())).toContain(offerId);

    const accepted = await request.post(
      `${KONG}/api/board/${boardId}/offers/${offerId}/accept`,
      { headers: auth(owner.token) },
    );
    expect(accepted.status(), await accepted.text()).toBe(200);
    expect(await accepted.json()).toMatchObject({ status: "ACCEPTED" });
  });

  test("recompensas, notificações e push", async ({ request }) => {
    const user = await createUser(request, "PASSENGER");

    expect(
      (await request.get(`${KONG}/api/rewards`, { headers: auth(user.token) })).status(),
    ).toBe(200);

    const ad = await request.post(`${KONG}/api/rewards/ad-watched`, {
      headers: { ...auth(user.token), ...jsonHeaders },
      data: { kind: "short", transactionId: `e2e-${Date.now()}` },
    });
    expect(ad.status(), await ad.text()).toBe(200);

    expect(
      (await request.get(`${KONG}/api/notifications?audience=PASSENGER`, {
        headers: auth(user.token),
      })).status(),
    ).toBe(200);
    expect(
      (await request.get(`${KONG}/api/notifications/unread-count?audience=PASSENGER`, {
        headers: auth(user.token),
      })).status(),
    ).toBe(200);

    const read = await request.post(`${KONG}/api/notifications/read`, {
      headers: { ...auth(user.token), ...jsonHeaders },
      data: { audience: "PASSENGER", ids: [] },
    });
    expect(read.status(), await read.text()).toBe(200);

    const status = await request.get(`${KONG}/api/push/devices/status`, {
      headers: auth(user.token),
    });
    expect(status.status(), await status.text()).toBe(200);

    const registered = await request.put(`${KONG}/api/push/devices`, {
      headers: { ...auth(user.token), ...jsonHeaders },
      data: { token: `e2e-token-${Date.now()}`, platform: "web" },
    });
    expect(registered.status()).toBe(204);

    const removed = await request.delete(`${KONG}/api/push/devices`, {
      headers: { ...auth(user.token), ...jsonHeaders },
      data: { token: "e2e-missing", platform: "web" },
    });
    expect(removed.status()).toBe(204);
  });

  test("intenções de carona: criar, listar, buscar próximas e cancelar", async ({ request }) => {
    // FREE só é permitido para quem tem a role DRIVER; o tipo PASSENGER_REQUEST é o pedido.
    const user = await createUser(request, "DRIVER");
    const suffix = `${Date.now()}`;

    const created = await request.post(`${KONG}/api/ride-intents`, {
      headers: { ...auth(user.token), ...jsonHeaders },
      data: {
        type: "DRIVER_OFFER",
        mode: "FREE",
        originCity: `Fortaleza-${suffix}`,
        originState: "CE",
        originLatitude: -3.73,
        originLongitude: -38.52,
        desiredAt: new Date(Date.now() + 3600_000).toISOString(),
      },
    });
    expect(created.status(), await created.text()).toBe(201);
    const intentId = (await created.json()).id as string;

    const mine = await request.get(`${KONG}/api/ride-intents/me`, {
      headers: auth(user.token),
    });
    expect(mine.status(), await mine.text()).toBe(200);
    expect(JSON.stringify(await mine.json())).toContain(intentId);

    const viewer = await createUser(request, "DRIVER");
    const nearby = await request.get(
      `${KONG}/api/ride-intents/nearby?type=DRIVER_OFFER&mode=FREE&latitude=-3.73&longitude=-38.52`,
      { headers: auth(viewer.token) },
    );
    expect(nearby.status(), await nearby.text()).toBe(200);

    const deleted = await request.delete(`${KONG}/api/ride-intents/${intentId}`, {
      headers: auth(user.token),
    });
    expect(deleted.status()).toBe(204);
  });

  test("verificação de CNH: validações sem chamar a Infosimples", async ({ request }) => {
    const user = await createUser(request, "PASSENGER");

    const noPhoto = await request.post(`${KONG}/api/users/me/driver/cnh`, {
      headers: auth(user.token),
      multipart: { securityCode: "11111111111" },
    });
    expect(noPhoto.status()).toBe(400);

    const wrongType = await request.post(`${KONG}/api/users/me/driver/cnh`, {
      headers: auth(user.token),
      multipart: {
        image: { name: "cnh.pdf", mimeType: "application/pdf", buffer: Buffer.from("pdf") },
        securityCode: "11111111111",
      },
    });
    expect(wrongType.status()).toBe(415);

    const invalidCode = await request.post(`${KONG}/api/users/me/driver/cnh`, {
      headers: auth(user.token),
      multipart: {
        image: { name: "cnh.png", mimeType: "image/png", buffer: Buffer.from("foto") },
        securityCode: "abc",
      },
    });
    expect(invalidCode.status()).toBe(400);

    const noCnh = await request.post(`${KONG}/api/users/me/driver`, {
      headers: auth(user.token),
    });
    expect(noCnh.status()).toBe(409);

    const status = await request.get(`${KONG}/api/users/me/driver/cnh`, {
      headers: auth(user.token),
    });
    expect(status.status(), await status.text()).toBe(200);
    expect(await status.json()).toMatchObject({ status: "NONE" });
  });
});
