import { defineConfig } from "@playwright/test";

/**
 * E2E de sistema do monólito.
 *
 * O monólito não tem UI: o Playwright usa APIRequestContext contra o Kong,
 * que é o mesmo ponto de entrada usado pelo front e pelo app.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.KONG_URL ?? "http://localhost:8000",
    extraHTTPHeaders: { Accept: "application/json" },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "api",
      testIgnore: /demo\.spec\.ts/,
    },
    {
      name: "demo",
      testMatch: /demo\.spec\.ts/,
      use: {
        video: "on",
        trace: "retain-on-failure",
        screenshot: "on",
        viewport: { width: 1280, height: 720 },
      },
    },
  ],
});
