#!/usr/bin/env python3
"""Lê o CSV do Locust, grava results/scale.json e imprime a eficiência de escala.

Uso: python3 scale_report.py <csv-do-locust> <n-instâncias> <arquivo-json>
"""

from __future__ import annotations

import csv
import json
import pathlib
import sys
import time


def as_float(row: dict, key: str) -> float:
    try:
        return float(row.get(key) or 0)
    except (TypeError, ValueError):
        return 0.0


def main() -> int:
    if len(sys.argv) != 4:
        print("uso: scale_report.py <csv> <instancias> <json>", file=sys.stderr)
        return 2
    csv_path, instances, results_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]

    with open(csv_path, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))

    aggregate = next(
        (r for r in rows if (r.get("Name") or "").strip() in ("Aggregated", "Total")),
        None,
    )
    if aggregate is None:
        print(f"CSV sem linha agregada: {csv_path}", file=sys.stderr)
        return 1

    entry = {
        "instances": instances,
        "rps": as_float(aggregate, "Requests/s"),
        "p50_ms": as_float(aggregate, "50%"),
        "p95_ms": as_float(aggregate, "95%"),
        "p99_ms": as_float(aggregate, "99%"),
        "failures": as_float(aggregate, "Failure Count"),
        "requests": as_float(aggregate, "Request Count"),
        "timestamp": int(time.time()),
    }

    path = pathlib.Path(results_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data: list[dict] = []
    if path.exists():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, list):
                data = []
        except (json.JSONDecodeError, OSError):
            data = []
    data = [d for d in data if int(d.get("instances", -1)) != instances]
    data.append(entry)
    data.sort(key=lambda d: int(d.get("instances", 0)))
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    print(
        f"N={instances} rps={entry['rps']:.2f} p50={entry['p50_ms']:.1f}ms "
        f"p95={entry['p95_ms']:.1f}ms p99={entry['p99_ms']:.1f}ms "
        f"falhas={int(entry['failures'])}"
    )
    base = next((d for d in data if int(d.get("instances", 0)) == 1), None)
    if base and float(base.get("rps") or 0) > 0:
        for item in data:
            n = int(item["instances"])
            efficiency = (float(item["rps"]) / n) / float(base["rps"])
            print(f"  eficiência N={n}: {efficiency:.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
