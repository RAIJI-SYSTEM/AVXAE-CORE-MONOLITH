#!/usr/bin/env python3
"""Organiza os relatórios em arquivos padronizados por tipo e serviço.

Cada repositório recebe em reports/:
  <slug>_sistema-e2e.html
  <slug>_mensageria.html
  <slug>_carga-escalabilidade-monitoramento.html
  <slug>_mutacao.html
  <slug>_regressao-analise-falhas.html
  index.html
"""
import html
import shutil
import sys
from pathlib import Path

CATEGORIES = [
    ("sistema-e2e", "Testes de Sistema / E2E",
     "Jornadas completas de sistema e navegador executadas com Playwright."),
    ("mensageria", "Testes de Mensageria",
     "Testes de eventos AMQP/RabbitMQ (Locust + pika) dos fluxos de mensageria."),
    ("carga-escalabilidade-monitoramento", "Testes de Carga, Escalabilidade e Monitoramento",
     "Testes de carga, comparativos 1xN e monitoramento dos serviços."),
    ("mutacao", "Teste de Mutação",
     "Avaliação da qualidade dos testes por mutação: PIT ou StrykerJS."),
    ("regressao-analise-falhas", "Teste de Regressão e Análise de Falhas",
     "Suíte de regressão, contratos de erro e testes de caminhos de falha."),
]


def latest(paths):
    return max(paths, key=lambda p: p.stat().st_mtime) if paths else None


def page(title, description, links, note=""):
    items = "".join(
        f"<li><a href='{html.escape(href)}'>{html.escape(label)}</a></li>" for label, href in links
    )
    body = f"<ul>{items}</ul>" if items else "<p class='muted'>Nenhum relatório detalhado disponível.</p>"
    return f"""<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<title>{html.escape(title)}</title>
<style>body{{font-family:system-ui,Arial,sans-serif;margin:2rem;max-width:900px;color:#111827}}
a{{color:#1d4ed8;font-weight:600}}li{{margin:.5rem 0}}.muted{{color:#6b7280}}
.note{{background:#f3f4f6;border-left:4px solid #9ca3af;padding:.75rem;margin:1rem 0}}</style>
<p><a href="index.html">← Voltar ao índice</a></p>
<h1>{html.escape(title)}</h1><p>{html.escape(description)}</p>{body}
{f'<div class="note">{html.escape(note)}</div>' if note else ''}
</html>"""


def main(repo: Path, slug: str):
    reports = repo / "reports"
    detailed = reports / "testes"
    raw = reports / "raw"
    raw.mkdir(parents=True, exist_ok=True)

    # Locust: relatórios ficam nos diretórios de carga de cada repositório.
    load_dirs = [repo / "tests" / "load" / "reports", repo / "load-tests" / "reports"]
    locust_files = []
    for directory in load_dirs:
        if directory.is_dir():
            locust_files.extend(directory.glob("*.html"))
    messaging_file = latest([p for p in locust_files if "amqp" in p.name.lower()])
    scale_files = [p for p in locust_files if "scale" in p.name.lower()]
    load_candidates = [p for p in locust_files if "amqp" not in p.name.lower() and "scale" not in p.name.lower()]
    load_file = latest(load_candidates)
    scale_file = latest(scale_files)

    if messaging_file:
        shutil.copyfile(messaging_file, raw / f"{slug}_mensageria.html")
    if load_file:
        shutil.copyfile(load_file, raw / f"{slug}_carga.html")
    if scale_file:
        shutil.copyfile(scale_file, raw / f"{slug}_escalabilidade.html")

    e2e = detailed / "e2e" / "index.html"
    tests = detailed / "testes.html"
    mutation = (
        detailed / "mutacao" / "mutation.html"
        if (detailed / "mutacao" / "mutation.html").exists()
        else None
    )
    pit = sorted((detailed / "mutacao").glob("*/index.html")) if (detailed / "mutacao").is_dir() else []

    links = {
        "sistema-e2e": [("Relatório Playwright", "testes/e2e/index.html")] if e2e.exists() else [],
        "mensageria": [("Relatório Locust — mensageria", f"raw/{slug}_mensageria.html")] if messaging_file else [],
        "carga-escalabilidade-monitoramento": (
            ([("Relatório Locust — carga", f"raw/{slug}_carga.html")] if load_file else []) +
            ([("Relatório Locust — escalabilidade 1xN", f"raw/{slug}_escalabilidade.html")] if scale_file else [])
        ),
        "mutacao": ([("Relatório StrykerJS", "testes/mutacao/mutation.html")] if mutation else []) +
                   [(f"Relatório PIT — {p.parent.name}", f"testes/mutacao/{p.parent.name}/index.html") for p in pit],
        "regressao-analise-falhas": [("Resultados e falhas", "testes/testes.html")] if tests.exists() else [],
    }

    for key, title, desc in CATEGORIES:
        note = ""
        if key == "mensageria" and not messaging_file:
            note = (
                "Não se aplica: o FRONT-PWA não possui consumidor/produtor AMQP."
                if slug == "front-pwa"
                else "Não executado nesta execução. Rode o smoke de mensageria do Locust com a stack e o RabbitMQ disponíveis."
            )
        if key == "carga-escalabilidade-monitoramento" and not load_file and not scale_file:
            note = "Não executado nesta execução. Rode os perfis de carga e escala do Locust com a stack disponível."
        (reports / f"{slug}_{key}.html").write_text(
            page(f"{title} — {slug}", desc, links[key], note), encoding="utf-8"
        )

    cards = "".join(
        f"<article><h2>{html.escape(title)}</h2><p>{html.escape(desc)}</p>"
        f"<a href='{slug}_{key}.html'>Abrir relatório</a></article>"
        for key, title, desc in CATEGORIES
    )
    index = f"""<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<title>Relatórios — {slug}</title>
<style>body{{font-family:system-ui,Arial,sans-serif;margin:2rem;background:#f9fafb;color:#111827}}
.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:1rem}}
article{{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:1rem}}
h2{{font-size:1.05rem}}a{{color:#1d4ed8;font-weight:600}}</style>
<h1>Relatórios de testes — {slug}</h1>
<p>Relatórios por tipo de teste, todos no mesmo diretório.</p>
<div class="grid">{cards}</div></html>"""
    (reports / "index.html").write_text(index, encoding="utf-8")


if __name__ == "__main__":
    main(Path(sys.argv[1]), sys.argv[2])
