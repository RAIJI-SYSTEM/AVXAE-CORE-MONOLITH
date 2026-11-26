import { test, expect } from "@playwright/test";
import { KONG, auth, createRide, createUser, createVehicle, requestSeat } from "../helpers/raiji";

test.describe("AVXAE-CORE-MONOLITH — empresas, fretes e pagamentos", () => {
  test("empresas e fretes: listagens vazias e validações sem integração externa", async ({
    request,
  }) => {
    const user = await createUser(request, "PASSENGER");
    const driver = await createUser(request, "DRIVER");

    for (const path of ["/api/companies/mine", "/api/companies/invites"]) {
      const response = await request.get(`${KONG}${path}`, { headers: auth(user.token) });
      expect(response.status(), `${path}: ${await response.text()}`).toBe(200);
    }

    const invalidCnpj = await request.get(`${KONG}/api/companies/cnpj/123`, {
      headers: auth(user.token),
    });
    expect(invalidCnpj.status()).toBe(400);

    const invalidCompany = await request.post(`${KONG}/api/companies`, {
      headers: { ...auth(user.token), "Content-Type": "application/json" },
      data: { cnpj: "123" },
    });
    expect(invalidCompany.status()).toBe(400);

    for (const path of [
      "/api/freight-requests/for-me",
      "/api/freight-requests/nearby?lat=-23.55&lng=-46.63",
      "/api/freight-offers/mine",
    ]) {
      const response = await request.get(`${KONG}${path}`, { headers: auth(driver.token) });
      expect(response.status(), `${path}: ${await response.text()}`).toBe(200);
    }
  });

  test("pagamento de reserva gratuita não exige cobrança", async ({ request }) => {
    const driver = await createUser(request, "DRIVER");
    const passenger = await createUser(request, "PASSENGER");
    const vehicleId = await createVehicle(request, driver.token);
    const ride = await createRide(request, driver.token, vehicleId);
    const requestId = await requestSeat(request, passenger.token, ride.id);

    const status = await request.get(
      `${KONG}/api/rides/${ride.id}/requests/${requestId}/payment`,
      { headers: auth(passenger.token) },
    );
    expect(status.status(), await status.text()).toBe(200);
    expect(await status.json()).toMatchObject({ status: "NOT_REQUIRED" });

    const start = await request.post(
      `${KONG}/api/rides/${ride.id}/requests/${requestId}/payment`,
      { headers: auth(passenger.token) },
    );
    expect(start.status()).toBe(409);
  });

  test("webhook do Stripe sem assinatura é rejeitado", async ({ request }) => {
    const response = await request.post(`${KONG}/api/public/stripe/webhook`, {
      data: { id: "evt_e2e" },
      headers: { "Content-Type": "application/json" },
    });
    expect(response.status()).toBeGreaterThanOrEqual(400);
    expect(response.status()).not.toBe(500);
  });
});
