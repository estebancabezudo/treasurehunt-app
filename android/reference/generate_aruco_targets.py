#!/usr/bin/env python3
"""Generate Treasure Hunt's deterministic DICT_4X4_50 ArUco test assets.

The 4x4 payloads below are the row-major, unrotated bits published by OpenCV's
predefined DICT_4X4_1000 table. DICT_4X4_50 is its first 50 entries.
"""

from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parent
DRAWABLE = ROOT.parent / "app/src/main/res/drawable-nodpi"
BASE_SVG = ROOT / "guardian_door_target.svg"
EXPECTED_ID = 27
UNEXPECTED_ID = 12
PAYLOADS = {
    27: ("1010", "0101", "0101", "0100"),
    12: ("0000", "1110", "1011", "0111"),
}


def marker_group(marker_id: int, x: int, y: int, side: int) -> str:
    modules = 6
    cells = [[0] * modules for _ in range(modules)]
    for row, bits in enumerate(PAYLOADS[marker_id], start=1):
        for column, bit in enumerate(bits, start=1):
            cells[row][column] = int(bit)
    rectangles = []
    for row in range(modules):
        top = y + round(row * side / modules)
        bottom = y + round((row + 1) * side / modules)
        for column in range(modules):
            if cells[row][column] != 0:
                continue
            left = x + round(column * side / modules)
            right = x + round((column + 1) * side / modules)
            rectangles.append(
                f'<rect x="{left}" y="{top}" width="{right-left}" '
                f'height="{bottom-top}" fill="#000000"/>'
            )
    return "\n    ".join(rectangles)


def standalone_svg(marker_id: int) -> str:
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="60mm" height="60mm" viewBox="0 0 480 480">
  <rect width="480" height="480" fill="#ffffff"/>
  <g aria-label="OpenCV DICT_4X4_50 marker {marker_id}">
    {marker_group(marker_id, 80, 80, 320)}
  </g>
</svg>
'''


def composite_svg(marker_id: int) -> str:
    source = BASE_SVG.read_text(encoding="utf-8")
    start = source.index('  <g transform="translate(700 1160)">')
    end = source.index("  </g>", start) + len("  </g>")
    replacement = f'''  <g aria-label="OpenCV DICT_4X4_50 marker {marker_id}, nominal 40 mm">
    <rect x="706" y="1166" width="353" height="303" rx="4" fill="#ffffff"/>
    {marker_group(marker_id, 749, 1184, 267)}
  </g>'''
    return source[:start] + replacement + source[end:]


def export(svg_name: str, png_name: str, svg: str, width: int, height: int) -> None:
    svg_path = ROOT / svg_name
    png_path = DRAWABLE / png_name
    svg_path.write_text(svg, encoding="utf-8", newline="\n")
    subprocess.run(
        [
            "inkscape",
            str(svg_path),
            "--export-type=png",
            f"--export-filename={png_path}",
            f"--export-width={width}",
            f"--export-height={height}",
            "--export-background=#ffffff",
            "--export-background-opacity=255",
        ],
        check=True,
    )


def main() -> None:
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    export(
        "aruco_marker_27.svg",
        "aruco_marker_27.png",
        standalone_svg(EXPECTED_ID),
        480,
        480,
    )
    export(
        "guardian_door_target_with_marker.svg",
        "guardian_door_target_with_marker.png",
        composite_svg(EXPECTED_ID),
        1200,
        1600,
    )
    export(
        "guardian_door_target_with_marker_12.svg",
        "guardian_door_target_with_marker_12.png",
        composite_svg(UNEXPECTED_ID),
        1200,
        1600,
    )


if __name__ == "__main__":
    main()
