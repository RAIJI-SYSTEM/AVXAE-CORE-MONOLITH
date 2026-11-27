#!/usr/bin/env python3
"""Recorta a captura do Grafana na seção de serviços (UP/latência/HTTP/CPU/memória)."""
import json
import sys
from pathlib import Path

from PIL import Image

POS = Path("/tmp/opencode/grafana-positions.json")
FULL = Path("/tmp/opencode/grafana-full.png")
OUT = Path(sys.argv[1]) / "reports" / "raw" / "grafana-servicos.png"


def find(pos, needle):
    needle = needle.lower()
    for title, box in pos.items():
        if needle in title.lower():
            return title, box
    return None, None


pos = json.loads(POS.read_text(encoding="utf-8"))
img = Image.open(FULL).convert("RGB")

_, box = find(pos, "Keycloak · memória")
if not box:
    _, box = find(pos, "Front · memória")
if not box:
    _, box = find(pos, "Front · ")

bottom = min(img.height, int(box["bottom"]) + 12) if box else min(img.height, 900)
out = img.crop((0, 0, img.width, bottom))

OUT.parent.mkdir(parents=True, exist_ok=True)
out.save(OUT, optimize=True)
print(f"{OUT} ({out.width}x{out.height})")
