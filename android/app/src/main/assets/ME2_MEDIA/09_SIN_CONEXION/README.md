# 09_SIN_CONEXION — clips de reposo sin conexión

Loop del contenedor del avatar **solo mientras el teléfono no tiene red**. Al volver la conexión, el contenedor
regresa a `01_LOOP_NEUTRAL`. Si esta carpeta está vacía, sin red se usa `01_LOOP_NEUTRAL` como siempre.

- Nombre: `SIN_CONEXION_001.mp4`, `SIN_CONEXION_002.mp4`, … (`NNN` = variante; sin subcarpetas).
- Formato: igual que los clips neutrales actuales — **1264 × 1120**, H.264, 24 fps, sin audio, mp4 (faststart).
- Para que encadenen sin saltos: cada clip debería empezar y terminar en la misma pose (la misma que los demás).
- Se reproducen al azar sin repetir el anterior; con un solo clip, se repite en loop.
- Probar sin recompilar: copiar a `filesDir/ME2_MEDIA/09_SIN_CONEXION/` en el teléfono.

Este archivo no es media: la biblioteca (`MediaLibrary`) lo ignora.
