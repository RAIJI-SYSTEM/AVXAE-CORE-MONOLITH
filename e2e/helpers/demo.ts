import type { Page } from "@playwright/test";

/**
 * Página de demonstração usada para gravar o vídeo do tour guiado.
 * O Playwright só grava vídeo quando existe um navegador; os testes de API
 * puro não geram vídeo. Esta página transforma o fluxo de API em uma
 * apresentação visual, passo a passo, sem depender de interface externa.
 */
export async function startDemo(
  page: Page,
  service: string,
  title: string,
  description: string,
): Promise<void> {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.setContent(`<!doctype html>
<html lang="pt-BR">
<meta charset="utf-8">
<title>${service} — tour E2E</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; background: #0b1020; color: #e5e7eb;
    font-family: Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif;
    display: flex; flex-direction: column;
  }
  header {
    padding: 34px 48px 28px; background:
      radial-gradient(900px 300px at 0% 0%, #1d4ed855, transparent),
      linear-gradient(135deg, #111827, #0b1020);
    border-bottom: 1px solid #1f2937;
  }
  .badge {
    display: inline-flex; align-items: center; gap: 8px; font-size: 13px;
    letter-spacing: .08em; text-transform: uppercase; color: #93c5fd;
    border: 1px solid #1d4ed8; background: #1e3a8a33; border-radius: 999px;
    padding: 6px 12px; margin-bottom: 14px;
  }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: #22c55e; box-shadow: 0 0 12px #22c55e; }
  h1 { margin: 0; font-size: 34px; letter-spacing: -.03em; }
  header p { margin: 10px 0 0; max-width: 900px; color: #9ca3af; font-size: 16px; line-height: 1.5; }
  main { flex: 1; padding: 30px 48px 42px; }
  .panel {
    max-width: 1080px; background: #111827cc; border: 1px solid #1f2937;
    border-radius: 18px; padding: 10px 24px; box-shadow: 0 24px 80px #0006;
  }
  ol { list-style: none; margin: 0; padding: 0; }
  li {
    display: grid; grid-template-columns: 34px 1fr auto; gap: 14px;
    align-items: center; padding: 15px 0; border-bottom: 1px solid #1f2937;
    animation: in .35s ease both;
  }
  li:last-child { border-bottom: 0; }
  .icon {
    width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center;
    font-size: 15px; font-weight: 700; border: 1px solid #374151; color: #9ca3af;
  }
  .label { font-size: 16px; font-weight: 600; }
  .detail { font-size: 13px; color: #9ca3af; margin-top: 4px; }
  li.running .icon { border-color: #f59e0b; color: #f59e0b; animation: pulse 1s infinite; }
  li.ok .icon { border-color: #22c55e; color: #22c55e; background: #052e1633; }
  li.fail .icon { border-color: #ef4444; color: #ef4444; background: #450a0a55; }
  li.ok .detail { color: #86efac; }
  li.fail .detail { color: #fca5a5; }
  footer { padding: 0 48px 26px; color: #6b7280; font-size: 12px; }
  @keyframes pulse { 50% { opacity: .35; } }
  @keyframes in { from { opacity: 0; transform: translateY(6px); } }
</style>
<header>
  <div class="badge"><span class="dot"></span> E2E · tour guiado · ${service}</div>
  <h1>${title}</h1>
  <p>${description}</p>
</header>
<main><div class="panel"><ol id="steps"></ol></div></main>
<footer>Execução automatizada pelo Playwright · vídeo gerado a partir do fluxo real de API</footer>
</html>`);
}

let sequence = 0;

/** Adiciona um passo visual, executa a ação e atualiza o resultado. */
export async function demoStep<T>(
  page: Page,
  label: string,
  action: () => Promise<T>,
): Promise<T> {
  const id = `step-${++sequence}`;
  await page.evaluate(({ id, label }) => {
    const list = document.getElementById("steps")!;
    const li = document.createElement("li");
    li.id = id;
    li.className = "running";
    const icon = document.createElement("span");
    icon.className = "icon";
    icon.textContent = "◌";
    const text = document.createElement("div");
    const title = document.createElement("div");
    title.className = "label";
    title.textContent = label;
    const detail = document.createElement("div");
    detail.className = "detail";
    detail.textContent = "executando…";
    text.append(title, detail);
    const status = document.createElement("span");
    status.className = "detail";
    status.textContent = "aguarde";
    li.append(icon, text, status);
    list.appendChild(li);
    li.scrollIntoView({ block: "nearest" });
  }, { id, label });

  await page.waitForTimeout(450);

  try {
    const result = await action();
    const detail = typeof result === "string" ? result : "";
    await page.evaluate(({ id, detail }) => {
      const li = document.getElementById(id)!;
      li.className = "ok";
      li.querySelector(".icon")!.textContent = "✓";
      li.querySelector(".detail")!.textContent = detail || "concluído";
      (li.lastElementChild as HTMLElement).textContent = "OK";
    }, { id, detail });
    await page.waitForTimeout(550);
    return result;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    await page.evaluate(({ id, message }) => {
      const li = document.getElementById(id)!;
      li.className = "fail";
      li.querySelector(".icon")!.textContent = "✕";
      li.querySelector(".detail")!.textContent = message.slice(0, 180);
      (li.lastElementChild as HTMLElement).textContent = "FALHOU";
    }, { id, message });
    await page.waitForTimeout(900);
    throw error;
  }
}
