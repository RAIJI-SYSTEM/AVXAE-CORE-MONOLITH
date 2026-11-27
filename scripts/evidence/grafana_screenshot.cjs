#!/usr/bin/env node
/*
 * Captura o topo do dashboard "Raiji · Serviços" (UP/latência/HTTP/CPU/memória
 * por serviço) com dados reais.
 *
 * Uso (a partir do monólito):
 *   NODE_PATH=e2e/node_modules node scripts/evidence/grafana_screenshot.cjs
 */
const fs = require("fs");
const { chromium } = require("playwright");

const BASE = process.env.GRAFANA_URL || "http://localhost:3300";
const USER = process.env.GRAFANA_USER || "admin";
const PASS = process.env.GRAFANA_PASS || "admin";
const OUT = process.env.OUT || "/tmp/opencode/grafana-full.png";
const POS = process.env.POS || "/tmp/opencode/grafana-positions.json";
const DASH = `${BASE}/d/raiji-servicos/raiji-c2b7-servicos-um-por-servico?orgId=1&from=now-15m&to=now&refresh=5s&kiosk`;

(async () => {
  const browser = await chromium.launch({ args: ["--no-sandbox"] });
  const context = await browser.newContext({
    viewport: { width: 1600, height: 1200 },
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();

  await page.goto(`${BASE}/login`, { waitUntil: "domcontentloaded" });
  await page.fill('input[name="user"]', USER);
  await page.fill('input[name="password"]', PASS);
  await page.click('button[type="submit"]');
  await page.waitForTimeout(2_500);
  // No primeiro login o Grafana exige troca da senha padrão; "Skip" mantém admin/admin.
  const skip = page.getByRole("button", { name: /skip/i });
  if (await skip.count()) {
    await skip.first().click();
    await page.waitForTimeout(1_500);
  }
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 30_000 });

  await page.goto(DASH, { waitUntil: "domcontentloaded" });
  await page.waitForSelector('[data-testid^="data-testid Panel header "]', { timeout: 60_000 });
  // Tempo para o Prometheus responder e os painéis desenharem.
  await page.waitForTimeout(15_000);

  const positions = await page.evaluate(() => {
    const out = {};
    document
      .querySelectorAll('[data-testid^="data-testid Panel header "]')
      .forEach((header) => {
        const title = header
          .getAttribute("data-testid")
          .replace("data-testid Panel header ", "")
          .trim();
        const panel = header.closest('div[class*="panel-container"]') || header;
        const r = panel.getBoundingClientRect();
        out[title] = {
          top: Math.round(r.top + window.scrollY),
          bottom: Math.round(r.bottom + window.scrollY),
          left: Math.round(r.left + window.scrollX),
          right: Math.round(r.right + window.scrollX),
        };
      });
    return out;
  });

  fs.writeFileSync(POS, JSON.stringify(positions, null, 2));
  await page.screenshot({ path: OUT });
  await browser.close();

  console.log(`imagem: ${OUT}`);
  console.log(`painéis encontrados: ${Object.keys(positions).length}`);
})();
