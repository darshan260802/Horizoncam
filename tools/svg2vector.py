#!/usr/bin/env python3
"""Converts simple single-colour Material Design SVG icons into Android vector drawables.

Usage: svg2vector.py <svg-dir> <out-res-drawable-dir> name[:outname] ...
Only <path d> and <circle> elements are supported (enough for the Material icon set).
"""
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"


def circle_path(cx, cy, r):
    return (f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0z")


def convert(svg_file, out_file):
    root = ET.parse(svg_file).getroot()
    vb = root.get("viewBox", "0 0 24 24").split()
    w, h = vb[2], vb[3]
    paths = []
    for el in root.iter():
        tag = el.tag.replace(NS, "")
        if el.get("opacity") is not None or el.get("fill") == "none":
            continue
        if tag == "path" and el.get("d"):
            paths.append(el.get("d"))
        elif tag == "circle":
            paths.append(circle_path(float(el.get("cx")), float(el.get("cy")), float(el.get("r"))))
    body = "\n".join(
        f'    <path android:fillColor="@android:color/white" android:pathData="{d}"/>' for d in paths)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Material Design icon (Apache License 2.0), converted by tools/svg2vector.py -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp"\n'
        f'    android:viewportWidth="{w}" android:viewportHeight="{h}">\n'
        f'{body}\n'
        '</vector>\n')
    with open(out_file, "w") as f:
        f.write(xml)


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    for spec in sys.argv[3:]:
        name, _, out = spec.partition(":")
        out = out or name
        convert(f"{src}/{name}.svg", f"{dst}/ic_{out}.xml")
        print("ic_" + out)
