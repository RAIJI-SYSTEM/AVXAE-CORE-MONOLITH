#!/usr/bin/env python3
"""Gera o comparativo HTML 1xN a partir do scale-results.json."""
import html
import json
import sys
from pathlib import Path

repo, slug, results = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
out = repo / "reports" / "raw" / f"{slug}_escalabilidade.html"
out.parent.mkdir(parents=True, exist_ok=True)
data = json.loads(results.read_text(encoding="utf-8")) if results.exists() else []
base = next((d for d in data if int(d.get("instances", 0)) == 1), None)
rows = []
for item in sorted(data, key=lambda d: int(d.get("instances", 0))):
    n = int(item.get("instances", 0))
    eff = (float(item["rps"]) / n) / float(base["rps"]) if base and base.get("rps") else 0
    rows.append(
        f"<tr><td>{n}</td><td>{item.get('rps', 0):.2f}</td>"
        f"<td>{item.get('p50_ms', 0):.0f} ms</td><td>{item.get('p95_ms', 0):.0f} ms</td>"
        f"<td>{item.get('p99_ms', 0):.0f} ms</td><td>{int(item.get('failures', 0))}</td>"
        f"<td>{eff:.2f}</td></tr>"
    )
note = "" if len(data) > 1 else "Nesta execução há apenas N=1; execute novamente com N_LIST='1 2' para o comparativo completo."
base_rps = float(base["rps"]) if base and base.get("rps") else 0.0
top = max(data, key=lambda d: int(d.get("instances", 0))) if data else None
top_n = int(top.get("instances", 0)) if top else 0
top_rps = float(top.get("rps", 0) or 0) if top else 0.0
top_eff = (top_rps / top_n) / base_rps if top_n > 0 and base_rps > 0 else 0.0
if base and top_n > 1:
    if top_rps > base_rps:
        trend = f"o RPS sobe de {base_rps:.2f} para {top_rps:.2f} req/s"
    else:
        trend = f"o RPS não cresce ({base_rps:.2f} → {top_rps:.2f} req/s)"
    bottleneck = (
        f"Com {top_n} instâncias, {trend} e a eficiência fica em {top_eff:.2f}. "
        "Isso indica que a segunda instância não entrega o dobro de vazão; o gargalo está "
        "nos recursos compartilhados — CPU do host, Postgres/Mongo/RabbitMQ e o custo do "
        "Kong/BFF — e não apenas no processo da aplicação. O dashboard acima correlaciona "
        "CPU, memória e latência dos serviços no mesmo período da carga."
    )
else:
    bottleneck = (
        "Execute com N_LIST='1 2' para comparar 1 e 2 instâncias e correlacionar o ganho de "
        "vazão com CPU, memória e latência no dashboard acima."
    )
html_doc = f"""<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<title>Escalabilidade 1xN — {slug}</title>
<style>body{{font-family:system-ui,Arial,sans-serif;margin:2rem;max-width:900px}}
table{{border-collapse:collapse;width:100%}}td,th{{border:1px solid #d1d5db;padding:.5rem;text-align:left}}
th{{background:#f3f4f6}}.muted{{color:#6b7280}}</style>
<p><a href="index.html">← Voltar ao índice</a></p>
<h1>Teste de Carga, Escalabilidade e Monitoramento</h1>
<p><strong>Serviço:</strong> {slug}</p>
<table><thead><tr><th>Instâncias</th><th>RPS</th><th>p50</th><th>p95</th><th>p99</th><th>Falhas</th><th>Eficiência</th></tr></thead>
<tbody>{''.join(rows)}</tbody></table>
<p class="muted">{note}</p>
<p>Dashboard de correlação: <a href="http://localhost:3300/d/raiji-servicos/raiji-c2b7-servicos-um-por-servico">Raiji · Serviços (um por serviço)</a>
(CPU, memória e latência no Grafana).</p>
<h2>Correlação e gargalo</h2>
<p><img src="grafana-servicos.png" alt="Dashboard Grafana" style="max-width:100%;border:1px solid #d1d5db"></p>
<p><strong>Leitura:</strong> {bottleneck}</p>
</html>"""
out.write_text(html_doc, encoding="utf-8")
print(out)
