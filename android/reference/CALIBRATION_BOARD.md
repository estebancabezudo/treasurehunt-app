# Tablero de calibración manual

- Esquinas internas: 9 columnas × 6 filas (54).
- Cuadros físicos: 10 columnas × 7 filas.
- Lado nominal: 20 mm.
- Patrón: 200 × 140 mm.
- Página: A4 apaisada, 297 × 210 mm.
- Margen blanco: 48.5 mm a izquierda/derecha y 35 mm arriba/abajo.
- PNG: 2970 × 2100 píxeles, escala de grises, 254 dpi (10 px/mm).

El PDF se debe imprimir con escala **100 %** y con cualquier opción de “ajustar a
página” desactivada. Un escalado automático invalida los 20 mm nominales. Antes
de usar el tablero para una calibración futura, se debe medir físicamente un
cuadro impreso.

Archivos reproducibles:

| Archivo | SHA-256 |
| --- | --- |
| `calibration_chessboard.svg` | `64162d72b67046ad2f0b5e1c8b9d34c5275f685347c01a794886386571c60594` |
| `calibration_chessboard.png` | `f637d4e5d2efd4e28666b2603b4adfe306ed75957bfebd39cc726cccfebeab7f` |
| `calibration_chessboard_a4.pdf` | `fbdc269e1845cde25bedeef7c0d24649f59337ff7f41e571e6ce4be50585b98b` |

`generate_calibration_board.py` usa únicamente la biblioteca estándar de
Python y vuelve a producir los tres archivos, además de copiar exactamente el
mismo PNG a `app/src/main/res/drawable-nodpi/` para pruebas instrumentales.
