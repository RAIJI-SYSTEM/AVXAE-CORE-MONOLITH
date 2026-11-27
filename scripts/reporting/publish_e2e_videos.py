#!/usr/bin/env python3
"""Publica o relatório Playwright e os vídeos E2E em reports/testes/e2e."""
import html
import re
import shutil
import sys
import unicodedata
from pathlib import Path

DISPLAY = {
    "AVXAE-CORE-MONOLITH": "Monólito",
    "FRONT-PWA": "Front",
    "MESSAGE-SERVICE": "Message",
}

FRIENDLY = {
    "AVXAE-CORE-MONOLITH": {
        1: "Jornada completa: usuário, veículo, corrida, reserva e chat",
    },
    "MESSAGE-SERVICE": {
        1: "Jornada completa: grupo, membros, mensagens e exclusão",
    },
}

PAGE_LABELS = {
    "": "Tela raiz",
    "homepage": "Tela inicial",
    "corridas": "Corridas",
    "mural": "Mural",
    "mensagens": "Mensagens",
    "mensagens-conversa": "Mensagens — conversa",
    "perfil": "Perfil",
    "configuracoes": "Configurações",
    "recompensas": "Recompensas",
    "buscar": "Buscar",
    "notificacoes": "Notificações",
    "mais": "Mais",
    "login": "Login",
    "auth-callback": "Retorno do login",
    "privacidade": "Privacidade",
    "termos": "Termos",
    "admin-repasses": "Admin — repasses",
    "empresa": "Empresa",
    "empresa-detalhe": "Empresa — detalhe",
    "empresa-frete": "Empresa — frete",
    "empresa-frete-novo": "Empresa — novo frete",
    "encomenda": "Encomenda",
    "encomenda-nova": "Encomenda — nova",
    "encomenda-produto": "Encomenda — produto",
    "encomenda-pagamento": "Encomenda — pagamento",
    "encomenda-pagamento-status": "Encomenda — pagamento (status)",
    "viagem-nova": "Viagem — nova",
    "viagem-busca": "Viagem — busca",
    "viagem-pedido": "Viagem — pedido",
    "viagem-reserva": "Viagem — reserva",
    "viagem-pagamento": "Viagem — pagamento",
    "viagem-pagamento-status": "Viagem — pagamento (status)",
    "motorista": "Motorista",
    "motorista-cadastro": "Motorista — cadastro",
    "motorista-pedidos": "Motorista — pedidos",
    "motorista-fretes": "Motorista — fretes",
    "motorista-rotas": "Motorista — rotas",
    "motorista-rotas-nova": "Motorista — nova rota",
    "motorista-rotas-gerenciar": "Motorista — gerenciar rotas",
    "motorista-perfil": "Motorista — perfil",
    "motorista-repasses": "Motorista — repasses",
    "motorista-mensagens": "Motorista — mensagens",
    "motorista-notificacoes": "Motorista — notificações",
    "motorista-mais": "Motorista — mais",
}


def clean_name(name: str) -> str:
    name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode("ascii")
    name = re.sub(r"[^A-Za-z0-9._-]+", "-", name)
    name = re.sub(r"-{2,}", "-", name).strip("-._")
    return name[:120] or "video"


def label_from_dir(name: str) -> str:
    if name.startswith("ui-"):
        return "Login e navegação autenticada"
    match = re.search(r"-tela-(.*?)-pages$", name)
    slug = match.group(1) if match else ""
    if slug and slug in PAGE_LABELS:
        return PAGE_LABELS[slug]
    for key in ("viagem-pagamento-status", "encomenda-pagamento-status",
                "motorista-rotas-gerenciar"):
        if key in name:
            return PAGE_LABELS[key]
    if not slug and "tela--pages" in name:
        return PAGE_LABELS[""]
    text = slug or name
    text = text.replace("-", " ").replace("_", " ")
    text = re.sub(r"\s+", " ", text).strip()
    return text.capitalize() if text else "Vídeo E2E"


def main(repo: Path) -> None:
    repo_name = repo.name
    display = DISPLAY.get(repo_name, repo_name)
    src = repo / "e2e" / "playwright-report"
    dst = repo / "reports" / "testes" / "e2e"
    if not src.exists():
        print(f"{repo_name}: relatório Playwright não encontrado")
        return

    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)

    videos = sorted((repo / "e2e" / "test-results").glob("**/video.webm"))
    vdir = dst / "videos"
    vdir.mkdir(exist_ok=True)
    items = []
    for index, video in enumerate(videos, start=1):
        label = FRIENDLY.get(repo_name, {}).get(index) or label_from_dir(video.parent.name)
        filename = f"{index:02d}-{clean_name(video.parent.name)}.webm"
        shutil.copyfile(video, vdir / filename)
        items.append((label, filename))

    cards = "\n".join(
        f"<article><h2>{html.escape(label)}</h2>"
        f"<video controls preload='metadata' src='videos/{html.escape(filename)}'></video>"
        f"<p><a href='videos/{html.escape(filename)}'>Abrir vídeo em nova aba</a></p></article>"
        for label, filename in items
    ) or "<p>Nenhum vídeo encontrado nesta execução.</p>"

    page = f"""<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<title>Vídeos E2E — {html.escape(display)}</title>
<style>
body{{font-family:system-ui,Arial,sans-serif;margin:2rem;background:#f9fafb;color:#111827}}
h1{{margin-bottom:.25rem}} .sub{{color:#4b5563}}
.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(420px,1fr));gap:1rem;margin-top:1.5rem}}
article{{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:1rem}}
h2{{font-size:.95rem;min-height:2.4em}} video{{width:100%;border-radius:8px;background:#000}}
a{{color:#1d4ed8;font-weight:600}}
</style>
<p><a href="index.html">← Relatório Playwright</a></p>
<h1>Vídeos E2E — {html.escape(display)}</h1>
<p class="sub">{len(items)} vídeo(s) gerado(s) pela execução dos testes.</p>
<div class="grid">{cards}</div></html>"""
    (dst / "videos.html").write_text(page, encoding="utf-8")
    print(f"{repo_name}: {len(items)} vídeo(s) publicados em {dst}")


if __name__ == "__main__":
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
    main(root)
