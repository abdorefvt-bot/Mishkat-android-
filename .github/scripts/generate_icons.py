from pathlib import Path
import io
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SVG = ROOT / ".github/assets/mishkat_logo.svg"
RES = ROOT / "app/src/main/res"

# Legacy launcher icons for launchers that do not use adaptive icons.
for density, size in {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}.items():
    for name in ("ic_launcher.png", "ic_launcher_round.png"):
        out = RES / f"mipmap-{density}" / name
        out.parent.mkdir(parents=True, exist_ok=True)
        cairosvg.svg2png(url=str(SVG), write_to=str(out), output_width=size, output_height=size)

# Adaptive foreground: remove both full-bleed SVG background rectangles.
tree = ET.parse(SVG)
root = tree.getroot()
for node in list(root):
    if node.tag.endswith("rect"):
        root.remove(node)

tmp = ROOT / "build" / "mishkat_foreground.svg"
tmp.parent.mkdir(parents=True, exist_ok=True)
tree.write(tmp, encoding="utf-8", xml_declaration=True)

# Android's adaptive-icon safe zone is approximately the central 66% of the canvas.
# Keep the complete Arabic wordmark inside it so different launcher masks cannot crop it.
art_size = 270
canvas_size = 432
png = cairosvg.svg2png(url=str(tmp), output_width=art_size, output_height=art_size)
art = Image.open(io.BytesIO(png)).convert("RGBA")
canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
offset = (canvas_size - art_size) // 2
canvas.alpha_composite(art, (offset, offset))
out = RES / "drawable-nodpi" / "ic_launcher_foreground.png"
out.parent.mkdir(parents=True, exist_ok=True)
canvas.save(out)
