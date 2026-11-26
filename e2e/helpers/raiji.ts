import { APIRequestContext, expect } from "@playwright/test";

/** URLs do sistema em execução local (docker-compose + front). */
export const KONG = process.env.KONG_URL ?? "http://localhost:8000";
export const KC_ADMIN = process.env.KEYCLOAK_ADMIN_URL ?? "http://localhost:8180";
export const CHAT = `${KONG}/chat`;

export const USER_PASSWORD = "E2e@12345";

export interface E2EUser {
  username: string;
  password: string;
  sub: string;
  token: string;
}

export const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

function randomSuffix() {
  return `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`;
}

/** Token de usuário comum no Keycloak (password grant no client público raiji-api). */
export async function getToken(
  request: APIRequestContext,
  username: string,
  password: string,
): Promise<string> {
  const response = await request.post(`${KONG}/realms/raiji/protocol/openid-connect/token`, {
    form: {
      grant_type: "password",
      client_id: "raiji-api",
      username,
      password,
    },
  });

  expect(response.status(), await response.text()).toBe(200);
  return (await response.json()).access_token as string;
}

/** Token do admin master, usado apenas para criar as contas únicas do teste. */
async function adminToken(request: APIRequestContext): Promise<string> {
  const response = await request.post(`${KC_ADMIN}/realms/master/protocol/openid-connect/token`, {
    form: {
      grant_type: "password",
      client_id: "admin-cli",
      username: "admin",
      password: "admin",
    },
  });

  expect(response.status(), await response.text()).toBe(200);
  return (await response.json()).access_token as string;
}

/**
 * Cria uma conta única no Keycloak. A conta padrão já nasce PASSENGER; para
 * motorista adicionamos a role DRIVER (sem passar pela verificação de CNH,
 * que é um fluxo separado e tem testes próprios).
 */
export async function createUser(
  request: APIRequestContext,
  role: "DRIVER" | "PASSENGER" = "PASSENGER",
): Promise<E2EUser> {
  const username = `e2e-${role.toLowerCase()}-${randomSuffix()}`;
  const admin = await adminToken(request);

  const created = await request.post(`${KC_ADMIN}/admin/realms/raiji/users`, {
    headers: auth(admin),
    data: {
      username,
      email: `${username}@raiji.test`,
      firstName: "E2E",
      lastName: role === "DRIVER" ? "Driver" : "Passenger",
      enabled: true,
      emailVerified: true,
      requiredActions: [],
      credentials: [{ type: "password", value: USER_PASSWORD, temporary: false }],
    },
  });

  expect(created.status(), await created.text()).toBe(201);
  const sub = created.headers()["location"]?.split("/").pop();
  expect(sub, "Location do usuário criado no Keycloak").toBeTruthy();

  // Atribui explicitamente as roles: o usuário criado pela Admin API pode
  // ainda não ter o default-role composto materializado no token.
  const roleNames = role === "DRIVER" ? ["PASSENGER", "DRIVER"] : ["PASSENGER"];
  const roles = [];
  for (const roleName of roleNames) {
    const roleResponse = await request.get(`${KC_ADMIN}/admin/realms/raiji/roles/${roleName}`, {
      headers: auth(admin),
    });
    expect(roleResponse.status(), await roleResponse.text()).toBe(200);
    roles.push(await roleResponse.json());
  }

  const assigned = await request.post(
    `${KC_ADMIN}/admin/realms/raiji/users/${sub}/role-mappings/realm`,
    { headers: auth(admin), data: roles },
  );
  expect(assigned.status(), await assigned.text()).toBe(204);

  const token = await getToken(request, username, USER_PASSWORD);
  return { username, password: USER_PASSWORD, sub: sub!, token };
}

export async function createVehicle(
  request: APIRequestContext,
  driverToken: string,
): Promise<string> {
  const plate = `E${String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0")}`;
  const response = await request.post(`${KONG}/api/vehicles`, {
    headers: auth(driverToken),
    data: {
      plate,
      type: "CARRO",
      model: "Fiat Argo",
      color: "Branco",
      year: 2024,
      passengerSeats: 4,
      cargoVolumeLiters: 400,
    },
  });

  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()).id as string;
}

export async function createRide(
  request: APIRequestContext,
  driverToken: string,
  vehicleId: string,
): Promise<{ id: string; originCity: string; destinationCity: string }> {
  const suffix = randomSuffix();
  const originCity = `E2E-Origem-${suffix}`;
  const destinationCity = `E2E-Destino-${suffix}`;
  const departureDate = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000)
    .toISOString()
    .slice(0, 19);

  const response = await request.post(`${KONG}/api/rides`, {
    headers: auth(driverToken),
    data: {
      vehicleId,
      type: "PASSENGER",
      originCity,
      originState: "CE",
      destinationCity,
      destinationState: "CE",
      departureDate,
      totalSeats: 3,
    },
  });

  expect(response.status(), await response.text()).toBe(201);
  const body = await response.json();
  return { id: body.id as string, originCity, destinationCity };
}

export async function requestSeat(
  request: APIRequestContext,
  passengerToken: string,
  rideId: string,
  passengerName = "Passageiro E2E",
): Promise<string> {
  const response = await request.post(`${KONG}/api/rides/${rideId}/requests`, {
    headers: auth(passengerToken),
    data: { passengerName, baggage: [] },
  });

  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()).id as string;
}

export async function acceptSeat(
  request: APIRequestContext,
  driverToken: string,
  rideId: string,
  requestId: string,
): Promise<void> {
  const response = await request.patch(
    `${KONG}/api/rides/${rideId}/requests/${requestId}`,
    {
      headers: auth(driverToken),
      data: { decision: "ACCEPT" },
    },
  );

  expect(response.status(), await response.text()).toBe(200);
}

export async function cancelSeat(
  request: APIRequestContext,
  passengerToken: string,
  rideId: string,
  requestId: string,
): Promise<void> {
  const response = await request.delete(
    `${KONG}/api/rides/${rideId}/requests/${requestId}`,
    {
      headers: auth(passengerToken),
      data: { reason: "Limpeza do teste E2E" },
    },
  );

  expect(response.status(), await response.text()).toBe(200);
}

/**
 * O grupo do chat nasce de um evento RabbitMQ (ride.created), então a
 * conversa pode aparecer alguns instantes depois do aceite.
 */
export async function waitForChatByRide(
  request: APIRequestContext,
  token: string,
  rideId: string,
): Promise<string> {
  let conversationId = "";

  await expect
    .poll(
      async () => {
        const response = await request.get(`${CHAT}/conversations/by-ride/${rideId}`, {
          headers: auth(token),
        });
        if (response.status() !== 200) return response.status();
        const body = await response.json();
        conversationId = (body.id ?? body._id) as string;
        return 200;
      },
      { timeout: 30_000, intervals: [500, 1000, 2000, 3000] },
    )
    .toBe(200);

  expect(conversationId).toBeTruthy();
  return conversationId;
}
