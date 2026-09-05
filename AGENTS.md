# Instrucciones específicas de Treasure Hunt

La rama permanente de este producto es `treasurehunt`.

El producto se organiza bajo `/home/esteban/Documents/treasurehunt` mientras no tenga
un dominio definitivo:

- `app/`: aplicaciones instalables. Android es la plataforma activa; iOS permanece
  reservado para una etapa posterior. Antes de modificar la aplicación, leer
  completamente `app/docs/agent-memory.md`.
- `frontend/`: sitio web propio del producto cuando se implemente.
- `backend/`: servidor exclusivo del producto cuando sea necesario.
- `platform/`: checkout independiente de Plataforma para cambios compartidos
  requeridos por Treasure Hunt.
- `scripts/`: herramientas del producto. El empaquetador no compila ni prueba el
  código y sólo se ejecuta cuando el usuario solicita un ZIP.

No confundir el backend propio de Treasure Hunt con el backend general de juegos. La
memoria vigente determina en cuál debe vivir cada capacidad antes de implementarla.
La documentación pública de este producto vive exclusivamente en el frontend de
`cabezudo.dev`; las conversaciones de código sólo actualizan memorias privadas.

Los endpoints REST no pueden contener palabras unidas mediante guiones y deben
representar recursos jerárquicos en inglés.
