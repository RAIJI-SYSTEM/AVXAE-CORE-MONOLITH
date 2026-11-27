#!/usr/bin/env python3
"""Executa uma quebra controlada em uma worktree temporária e mede a regressão."""
import json
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SLUG = sys.argv[1]
REPORT = ROOT / "reports" / "drills"
REPORT.mkdir(parents=True, exist_ok=True)

CASES = {
    "avxae-core-monolith": {
        "file": "raiji-rides/src/main/java/com/raiji/rides/service/RideRefundService.java",
        "old": "RoundingMode.HALF_UP",
        "new": "RoundingMode.DOWN",
        "kind": "maven",
    },
    "front-pwa": {
        "file": "src/services/backend/client.ts",
        "old": "Math.round(reais * 100)",
        "new": "Math.round(reais * 100) + 1",
        "kind": "vitest",
    },
    "message-service": {
        "file": "src/modules/messages/attachment-rules.ts",
        "old": "10 * 1024 * 1024",
        "new": "1024",
        "kind": "vitest",
    },
}
case = CASES[SLUG]
work = Path(tempfile.mkdtemp(prefix=f"raiji-drill-{SLUG}-"))
worktree = work / "repo"
added = False
try:
    subprocess.run(["git", "-C", str(ROOT), "worktree", "add", "--detach", str(worktree), "HEAD"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    added = True
    target = worktree / case["file"]
    source = target.read_text(encoding="utf-8")
    if case["old"] not in source:
        raise SystemExit(f"alvo da quebra não encontrado em {target}")
    target.write_text(source.replace(case["old"], case["new"], 1), encoding="utf-8")

    env = os.environ.copy()
    if case["kind"] == "maven":
        env["JAVA_HOME"] = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
        env["PATH"] = f"{env['JAVA_HOME']}/bin:" + env["PATH"]
        proc = subprocess.run(
            ["mvn", "-B", "-ntp", "-pl", "raiji-app,raiji-rides,raiji-users", "-am", "test"],
            cwd=worktree, env=env, text=True, capture_output=True,
        )
        total = failed = errors = 0
        for xml in (worktree).glob("*/target/surefire-reports/TEST-*.xml"):
            suite = ET.parse(xml).getroot()
            total += int(suite.attrib.get("tests", 0))
            failed += int(suite.attrib.get("failures", 0))
            errors += int(suite.attrib.get("errors", 0))
        failed += errors
        output = proc.stdout + proc.stderr
    else:
        node_modules = ROOT / "node_modules"
        if node_modules.exists():
            os.symlink(node_modules, worktree / "node_modules", target_is_directory=True)
        output_file = work / "vitest.json"
        proc = subprocess.run(
            ["npx", "vitest", "run", "--reporter=json", f"--outputFile={output_file}"],
            cwd=worktree, env=env, text=True, capture_output=True,
        )
        data = json.loads(output_file.read_text(encoding="utf-8")) if output_file.exists() else {}
        total = int(data.get("numTotalTests", 0))
        failed = int(data.get("numFailedTests", 0))
        output = proc.stdout + proc.stderr

    passed = max(total - failed, 0)
    pct = (failed / total * 100) if total else 0
    status = "FALHA DETECTADA" if failed else "quebra não detectada"
    log_file = REPORT / f"{SLUG}_quebra.log"
    log_file.write_text(output[-100000:], encoding="utf-8")
    html = f"""<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<title>Quebra proposital — {SLUG}</title>
<style>body{{font-family:system-ui,Arial,sans-serif;margin:2rem;max-width:900px}}
table{{border-collapse:collapse;width:100%}}td,th{{border:1px solid #d1d5db;padding:.5rem;text-align:left}}
.bad{{color:#b91c1c}}.ok{{color:#047857}}</style>
<p><a href="index.html">← Voltar ao índice</a></p>
<h1>Teste de Regressão e Análise de Falhas — quebra proposital</h1>
<table>
<tr><th>Serviço</th><td>{SLUG}</td></tr>
<tr><th>Arquivo alterado</th><td>{case['file']}</td></tr>
<tr><th>Quebra aplicada</th><td>{case['old']} → {case['new']}</td></tr>
<tr><th>Total de testes</th><td>{total}</td></tr>
<tr><th>Falhas detectadas</th><td class="bad">{failed}</td></tr>
<tr><th>Aprovados</th><td>{passed}</td></tr>
<tr><th>Percentual de falha</th><td class="bad">{pct:.2f}%</td></tr>
<tr><th>Resultado</th><td>{status}</td></tr>
</table>
<p>A alteração foi feita em uma worktree temporária e removida ao final; o repositório original não foi modificado.</p>
<p>Log completo: <a href="{log_file.name}">{log_file.name}</a></p>
</html>"""
    (REPORT / f"{SLUG}_quebra.html").write_text(html, encoding="utf-8")
    print(f"{SLUG}: {failed}/{total} falhas ({pct:.2f}%) — {status}")
finally:
    if added:
        subprocess.run(["git", "-C", str(ROOT), "worktree", "remove", "--force", str(worktree)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["rm", "-rf", str(work)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
