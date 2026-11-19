from pathlib import Path
import io
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SVG = ROOT / "app/src/main/res/drawable/mishkat_logo.svg"
RES = ROOT / "app/src/main/res"

# Standard launcher assets preserve the complete original artwork and burgundy background.
for density, size in {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}.items():
    out = RES / f"mipmap-{density}" / "ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    cairosvg.svg2png(url=str(SVG), write_to=str(out), output_width=size, output_height=size)
    round_out = RES / f"mipmap-{density}" / "ic_launcher_round.png"
    cairosvg.svg2png(url=str(SVG), write_to=str(round_out), output_width=size, output_height=size)

# Adaptive icon foreground: remove the full-bleed background rectangles, then scale
# the white logo into Android's central safe zone. Background color is defined separately.
tree = ET.parse(SVG)
root = tree.getroot()
for node in list(root):
    if node.tag.endswith("rect"):
        root.remove(node)
tmp = ROOT / "build" / "mishkat_foreground.svg"
tmp.parent.mkdir(parents=True, exist_ok=True)
tree.write(tmp, encoding="utf-8", xml_declaration=True)
png = cairosvg.svg2png(url=str(tmp), output_width=324, output_height=324)
art = Image.open(io.BytesIO(png)).convert("RGBA")
canvas = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
canvas.alpha_composite(art, (54, 54))
out = RES / "mipmap-anydpi-v26" / "ic_launcher_foreground.png"
out.parent.mkdir(parents=True, exist_ok=True)
canvas.save(out)
