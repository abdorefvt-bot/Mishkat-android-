from pathlib import Path
import io
import re
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SVG = ROOT / ".github/assets/mishkat_logo.svg"
RES = ROOT / "app/src/main/res"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID_NS)
A = "{" + ANDROID_NS + "}"

# Legacy launchers use density-specific PNGs, each rasterized directly from
# the original SVG at the correct density (never enlarged from a small bitmap).
for density, size in {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}.items():
    png = cairosvg.svg2png(url=str(SVG), output_width=size, output_height=size)
    artwork = Image.open(io.BytesIO(png)).convert("RGBA")
    square = Image.new("RGBA", (size, size), (119, 26, 50, 255))
    square.alpha_composite(artwork)
    square_out = RES / f"mipmap-{density}" / "ic_launcher.png"
    square_out.parent.mkdir(parents=True, exist_ok=True)
    square.save(square_out)
    round_icon = square.copy()
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    round_icon.putalpha(mask)
    round_icon.save(RES / f"mipmap-{density}" / "ic_launcher_round.png")

# Build a true Android VectorDrawable from the original SVG for adaptive
# launchers (Android 8+). This keeps the installed app icon vector-sharp.
tree = ET.parse(SVG)
svg_root = tree.getroot()
defs = {}
for node in svg_root.iter():
    if node.tag.endswith("clipPath"):
        defs[node.attrib.get("id")] = node

vector = ET.Element("vector", {
    A+"width": "432dp", A+"height": "432dp",
    A+"viewportWidth": "864", A+"viewportHeight": "864"
})
outer = ET.SubElement(vector, "group", {
    A+"translateX": "162", A+"translateY": "162",
    A+"scaleX": "0.703125", A+"scaleY": "0.703125"
})

def rect_path(node):
    x = float(node.attrib.get("x", 0)); y = float(node.attrib.get("y", 0))
    w = float(node.attrib.get("width", 0)); h = float(node.attrib.get("height", 0))
    return f"M{x},{y}h{w}v{h}H{x}z"

def append_node(parent, node, inherited_fill="#ffffff", inherited_opacity="1"):
    tag = node.tag.split("}")[-1]
    if tag in ("defs", "clipPath", "rect"):
        return
    if tag == "g":
        attrs = {}
        transform = node.attrib.get("transform", "")
        if transform:
            m = re.fullmatch(r"translate\\(\\s*([-+0-9.eE]+)(?:[, ]+([-+0-9.eE]+))?\\s*\\)", transform)
            mx = re.fullmatch(r"matrix\\(\\s*([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)\\s*\\)", transform)
            if m:
                attrs[A+"translateX"] = m.group(1)
                attrs[A+"translateY"] = m.group(2) or "0"
            elif mx and all(abs(float(mx.group(i)) - expected) < 1e-9 for i, expected in [(1,1),(2,0),(3,0),(4,1)]):
                attrs[A+"translateX"] = mx.group(5)
                attrs[A+"translateY"] = mx.group(6)
            else:
                raise ValueError(f"Unsupported SVG transform: {transform}")
        group = ET.SubElement(parent, "group", attrs)
        clip = node.attrib.get("clip-path", "")
        if clip:
            match = re.search(r"url\\(#([^)]+)\\)", clip)
            if not match or match.group(1) not in defs:
                raise ValueError(f"Unsupported SVG clip path: {clip}")
            clip_def = defs[match.group(1)]
            for shape in clip_def:
                if shape.tag.endswith("rect"):
                    ET.SubElement(group, "clip-path", {A+"pathData": rect_path(shape)})
        fill = node.attrib.get("fill", inherited_fill)
        opacity = node.attrib.get("fill-opacity", inherited_opacity)
        for child in node:
            append_node(group, child, fill, opacity)
    elif tag == "path":
        fill = node.attrib.get("fill", inherited_fill)
        opacity = float(node.attrib.get("fill-opacity", inherited_opacity))
        attrs = {A+"pathData": node.attrib["d"], A+"fillColor": fill}
        if opacity < 1:
            attrs[A+"fillAlpha"] = str(opacity)
        if node.attrib.get("fill-rule") == "evenodd":
            attrs[A+"fillType"] = "evenOdd"
        ET.SubElement(parent, "path", attrs)

for child in svg_root:
    if child.tag.endswith("g"):
        append_node(outer, child)

out = RES / "drawable" / "ic_launcher_foreground.xml"
out.parent.mkdir(parents=True, exist_ok=True)
ET.ElementTree(vector).write(out, encoding="utf-8", xml_declaration=True)
