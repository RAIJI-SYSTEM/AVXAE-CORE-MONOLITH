#!/usr/bin/env python3
"""Gera um HTML legível a partir dos XMLs do Surefire/Failsafe."""
import html
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def text(node, tag, default=""):
    found = node.find(tag)
    return found.text if found is not None and found.text else default


def main(root: Path, out: Path):
    rows = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    files = sorted(root.glob("*/target/surefire-reports/TEST-*.xml"))
    files += sorted(root.glob("*/target/failsafe-reports/TEST-*.xml"))

    for file in files:
        suite = ET.parse(file).getroot()
        kind = "Integração" if "failsafe" in str(file) else "Unitário"
        module = file.parts[-4] if len(file.parts) >= 4 else ""
        tests = int(suite.attrib.get("tests", 0))
        failures = int(suite.attrib.get("failures", 0))
        errors = int(suite.attrib.get("errors", 0))
        skipped = int(suite.attrib.get("skipped", 0))
        totals["tests"] += tests
        totals["failures"] += failures
        totals["errors"] += errors
        totals["skipped"] += skipped
        rows.append((kind, module, suite.attrib.get("name", file.stem), tests, failures, errors, skipped))

    lines = [
        "<!doctype html><html lang='pt-BR'><meta charset='utf-8'>",
        "<title>Testes do monólito</title>",
        "<style>body{font-family:system-ui,Arial,sans-serif;margin:2rem;color:#1f2937}"
        "table{border-collapse:collapse;width:100%;margin-top:1rem}th,td{border:1px solid #d1d5db;padding:.5rem;text-align:left}"
        "th{background:#f3f4f6}.ok{color:#047857}.bad{color:#b91c1c}</style>",
        "<h1>Testes automatizados — AVXAE-CORE-MONOLITH</h1>",
        f"<p><strong>Total:</strong> {totals['tests']} testes · "
        f"{totals['failures']} falhas · {totals['errors']} erros · {totals['skipped']} ignorados</p>",
        "<table><thead><tr><th>Tipo</th><th>Módulo</th><th>Classe</th><th>Testes</th><th>Falhas</th><th>Erros</th><th>Ignorados</th></tr></thead><tbody>",
    ]
    for kind, module, name, tests, failures, errors, skipped in rows:
        cls = "bad" if failures or errors else "ok"
        lines.append(
            f"<tr><td>{html.escape(kind)}</td><td>{html.escape(module)}</td>"
            f"<td>{html.escape(name)}</td><td>{tests}</td>"
            f"<td class='{cls}'>{failures}</td><td class='{cls}'>{errors}</td><td>{skipped}</td></tr>"
        )
    lines.append("</tbody></table></html>")
    (out / "testes.html").write_text("\n".join(lines), encoding="utf-8")
    (out / "summary.json").write_text(json.dumps(totals, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
