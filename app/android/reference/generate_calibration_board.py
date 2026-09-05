#!/usr/bin/env python3
"""Genera el tablero de calibración de Treasure Hunt sin dependencias externas.

Geometría nominal:
- hoja A4 apaisada: 297 x 210 mm;
- tablero: 10 x 7 cuadros de 20 mm (200 x 140 mm);
- esquinas internas: 9 x 6;
- margen blanco: 48.5 mm horizontal y 35 mm vertical.

El PNG se genera a 10 px/mm (254 dpi). El PDF debe imprimirse al 100 %, sin
"ajustar a página", para conservar las dimensiones físicas nominales.
"""

from __future__ import annotations

import hashlib
import struct
import zlib
from pathlib import Path


PAGE_WIDTH_MM = 297.0
PAGE_HEIGHT_MM = 210.0
BOARD_COLUMNS = 10
BOARD_ROWS = 7
SQUARE_MM = 20.0
BOARD_WIDTH_MM = BOARD_COLUMNS * SQUARE_MM
BOARD_HEIGHT_MM = BOARD_ROWS * SQUARE_MM
MARGIN_X_MM = (PAGE_WIDTH_MM - BOARD_WIDTH_MM) / 2.0
MARGIN_Y_MM = (PAGE_HEIGHT_MM - BOARD_HEIGHT_MM) / 2.0
PIXELS_PER_MM = 10

REFERENCE_DIR = Path(__file__).resolve().parent
ANDROID_RESOURCE_DIR = REFERENCE_DIR.parent / "app" / "src" / "main" / "res" / "drawable-nodpi"


def black_squares() -> list[tuple[int, int]]:
    return [
        (column, row)
        for row in range(BOARD_ROWS)
        for column in range(BOARD_COLUMNS)
        if (column + row) % 2 == 0
    ]


def generate_svg() -> bytes:
    rectangles = "\n".join(
        f'    <rect x="{MARGIN_X_MM + column * SQUARE_MM:g}" '
        f'y="{MARGIN_Y_MM + row * SQUARE_MM:g}" '
        f'width="{SQUARE_MM:g}" height="{SQUARE_MM:g}"/>'
        for column, row in black_squares()
    )
    svg = f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{PAGE_WIDTH_MM:g}mm" height="{PAGE_HEIGHT_MM:g}mm"
     viewBox="0 0 {PAGE_WIDTH_MM:g} {PAGE_HEIGHT_MM:g}" shape-rendering="crispEdges">
  <title>Treasure Hunt calibration chessboard 9 x 6 internal corners</title>
  <desc>Print at 100 percent. 10 x 7 squares, 20 mm each, board 200 x 140 mm.</desc>
  <rect width="{PAGE_WIDTH_MM:g}" height="{PAGE_HEIGHT_MM:g}" fill="white"/>
  <g fill="black">
{rectangles}
  </g>
</svg>
'''
    return svg.encode("utf-8")


def png_chunk(kind: bytes, data: bytes) -> bytes:
    payload = kind + data
    return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)


def generate_png() -> bytes:
    width = int(PAGE_WIDTH_MM * PIXELS_PER_MM)
    height = int(PAGE_HEIGHT_MM * PIXELS_PER_MM)
    margin_x = int(MARGIN_X_MM * PIXELS_PER_MM)
    margin_y = int(MARGIN_Y_MM * PIXELS_PER_MM)
    square = int(SQUARE_MM * PIXELS_PER_MM)
    rows = []
    for y in range(height):
        pixels = bytearray([255]) * width
        board_row = (y - margin_y) // square
        if margin_y <= y < margin_y + BOARD_ROWS * square:
            for board_column in range(BOARD_COLUMNS):
                if (board_column + board_row) % 2 == 0:
                    start = margin_x + board_column * square
                    pixels[start:start + square] = b"\x00" * square
        rows.append(b"\x00" + bytes(pixels))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    physical = struct.pack(">IIB", PIXELS_PER_MM * 1000, PIXELS_PER_MM * 1000, 1)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"pHYs", physical)
        + png_chunk(b"IDAT", zlib.compress(b"".join(rows), level=9))
        + png_chunk(b"IEND", b"")
    )


def generate_pdf() -> bytes:
    points_per_mm = 72.0 / 25.4
    page_width = PAGE_WIDTH_MM * points_per_mm
    page_height = PAGE_HEIGHT_MM * points_per_mm
    commands = [f"1 1 1 rg 0 0 {page_width:.6f} {page_height:.6f} re f", "0 0 0 rg"]
    for column, row in black_squares():
        x = (MARGIN_X_MM + column * SQUARE_MM) * points_per_mm
        y = page_height - (MARGIN_Y_MM + (row + 1) * SQUARE_MM) * points_per_mm
        side = SQUARE_MM * points_per_mm
        commands.append(f"{x:.6f} {y:.6f} {side:.6f} {side:.6f} re f")
    stream = ("\n".join(commands) + "\n").encode("ascii")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {page_width:.6f} {page_height:.6f}] "
            f"/Resources << >> /Contents 4 0 R >>"
        ).encode("ascii"),
        b"<< /Length " + str(len(stream)).encode("ascii") + b" >>\nstream\n" + stream + b"endstream",
    ]
    pdf = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, body in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf.extend(f"{index} 0 obj\n".encode("ascii"))
        pdf.extend(body)
        pdf.extend(b"\nendobj\n")
    xref_offset = len(pdf)
    pdf.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    pdf.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        pdf.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    pdf.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode("ascii")
    )
    return bytes(pdf)


def write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    print(f"{path.name}: {len(content)} bytes, sha256={hashlib.sha256(content).hexdigest()}")


def main() -> None:
    svg = generate_svg()
    png = generate_png()
    pdf = generate_pdf()
    write(REFERENCE_DIR / "calibration_chessboard.svg", svg)
    write(REFERENCE_DIR / "calibration_chessboard.png", png)
    write(REFERENCE_DIR / "calibration_chessboard_a4.pdf", pdf)
    write(ANDROID_RESOURCE_DIR / "calibration_chessboard.png", png)


if __name__ == "__main__":
    main()
