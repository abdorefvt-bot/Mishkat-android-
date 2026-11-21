from pathlib import Path
import base64

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app" / "src" / "main" / "assets" / "font_sources"
DEST = ROOT / "app" / "src" / "main" / "res" / "font"
DEST.mkdir(parents=True, exist_ok=True)

for name in ("amiri_quran", "ibm_plex_sans_arabic"):
    encoded = "".join((SOURCE / f"{name}.ttf.b64").read_text(encoding="ascii").split())
    encoded += "=" * ((4 - len(encoded) % 4) % 4)
    (DEST / f"{name}.ttf").write_bytes(base64.b64decode(encoded))
