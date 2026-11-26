from pathlib import Path
import io
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SVG = ROOT / ".github/assets/mishkat_logo.svg"
RES = ROOT / "app/src/main/res"

# Render every launcher size directly from the original vector.
for density, size in {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}.items():
    png = cairosvg.svg2png(url=str(SVG), output_width=size, output_height=size)
    artwork = Image.open(io.BytesIO(png)).convert("RGBA")
    # Match the adaptive icon background on legacy launchers too.
    square = Image.new("RGBA", (size, size), (119, 26, 50, 255))
    square.alpha_composite(artwork)

    square_out = RES / f"mipmap-{density}" / "ic_launcher.png"
    square_out.parent.mkdir(parents=True, exist_ok=True)
    square.save(square_out)

    # Older launchers use this pre-masked round icon instead of adaptive masks.
    round_icon = square.copy()
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    round_icon.putalpha(mask)
    round_out = RES / f"mipmap-{density}" / "ic_launcher_round.png"
    round_icon.save(round_out)

# Adaptive foreground: remove the full-bleed SVG background rectangles.
tree = ET.parse(SVG)
root = tree.getroot()
for node in list(root):
    if node.tag.endswith("rect"):
        root.remove(node)

tmp = ROOT / "build" / "mishkat_foreground.svg"
tmp.parent.mkdir(parents=True, exist_ok=True)
tree.write(tmp, encoding="utf-8", xml_declaration=True)

# High-resolution adaptive foreground; artwork remains inside Android's safe zone.
art_size = 540
canvas_size = 864
png = cairosvg.svg2png(url=str(tmp), output_width=art_size, output_height=art_size)
art = Image.open(io.BytesIO(png)).convert("RGBA")
canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
offset = (canvas_size - art_size) // 2
canvas.alpha_composite(art, (offset, offset))
out = RES / "drawable-nodpi" / "ic_launcher_foreground.png"
out.parent.mkdir(parents=True, exist_ok=True)
canvas.save(out)
