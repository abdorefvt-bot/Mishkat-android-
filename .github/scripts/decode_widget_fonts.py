from pathlib import Path
import base64
ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/"app"/"src"/"main"/"assets"/"font_sources"
DST=ROOT/"app"/"src"/"main"/"res"/"font"
DST.mkdir(parents=True,exist_ok=True)
for name in ("amiri_quran","ibm_plex_sans_arabic"):
 data=base64.b64decode((SRC/f"{name}.ttf.b64").read_text(encoding="ascii").strip())
 (DST/f"{name}.ttf").write_bytes(data)
