import { test, expect } from "@playwright/test";
import { KONG, auth, createUser } from "../helpers/raiji";

test.describe("AVXAE-CORE-MONOLITH — repasses e admin", () => {
  test("motorista cadastra Pix e consulta repasses; admin consulta e registra repasse", async ({
    request,
  }) => {
    const driver = await createUser(request, "DRIVER");
    const admin = await createUser(request, "PASSENGER", ["admin"]);

    const summary = await request.get(`${KONG}/api/drivers/me/payouts`, {
      headers: auth(driver.token),
    });
    expect(summary.status(), await summary.text()).toBe(200);
    expect(await summary.json()).toHaveProperty("pendingCents");

    const pix = await request.put(`${KONG}/api/drivers/me/pix-key`, {
      headers: { ...auth(driver.token), "Content-Type": "application/json" },
      data: { pixKey: "e2e@raiji.test" },
    });
    expect(pix.status(), await pix.text()).toBe(200);
    expect(await pix.json()).toMatchObject({ pixKey: "e2e@raiji.test" });

    const balances = await request.get(`${KONG}/api/admin/payouts/balances`, {
      headers: auth(admin.token),
    });
    expect(balances.status(), await balances.text()).toBe(200);

    const history = await request.get(`${KONG}/api/admin/payouts`, {
      headers: auth(admin.token),
    });
    expect(history.status(), await history.text()).toBe(200);

    const invalid = await request.post(`${KONG}/api/admin/payouts`, {
      headers: { ...auth(admin.token), "Content-Type": "application/json" },
      data: {},
    });
    expect(invalid.status()).toBe(400);

    // Sem saldo de corrida/encomenda paga, o repasse é recusado com 409.
    const payout = await request.post(`${KONG}/api/admin/payouts`, {
      headers: { ...auth(admin.token), "Content-Type": "application/json" },
      data: { driverSub: driver.sub, amountCents: 100, note: "E2E" },
    });
    expect(payout.status(), await payout.text()).toBe(409);

    const ping = await request.get(`${KONG}/api/admin/ping`, { headers: auth(admin.token) });
    expect(ping.status(), await ping.text()).toBe(200);

    const driverForbidden = await request.get(`${KONG}/api/admin/payouts/balances`, {
      headers: auth(driver.token),
    });
    expect(driverForbidden.status()).toBe(403);
  });
});
