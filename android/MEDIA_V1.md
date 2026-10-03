# Biblioteca audiovisual V1 (Android)

Raíz empaquetada: `app/src/main/assets/ME2_MEDIA/` · drop-in en runtime: `filesDir/ME2_MEDIA/` (misma estructura; mismo id reemplaza al empaquetado).

```
00_PRESENTACION/PRESENTACION_001.mp4          (todas las variantes, en orden, una sola vez)
01_LOOP_NEUTRAL/NEUTRAL_001.mp4
02_REACCIONES/<REACCION>/<REACCION>_<NORMAL|MEDIO|MAXIMO>_<VARIANTE>.mp4
03_CONVERSACION/<ESCUCHANDO|ATENCION|PENSANDO|ESPERANDO|PROCESANDO|DESPEDIDA>/<SUB>_001.mp4
04_WIDGET/<SUB>/WIDGET_<SUB>_001.gif           (gif/webp/png/jpg/mp4)
05_DESPERTADOR/<AVISO_01|AVISO_02|AVISO_03|ALARMA|DESPERTANDO|POST_ALARMA>/...
06_TRANSICIONES/<ENTRADA|SALIDA|NEUTRAL|CAMBIO_ESTADO>/...
07_PREMIUM/ESPECIALES/...   07_PREMIUM/ADULTO/... (solo Premium + modo adulto desbloqueado)
08_SISTEMA/<ERROR|CARGANDO|SIN_CONEXION>/...
```

- Agregar/reemplazar un clip = copiar el archivo con la nomenclatura (p. ej. `ALEGRIA_NORMAL_002.mp4`). Sin cambios de código.
- Metadatos: se derivan de ruta+nombre (`media/MediaNameParser.kt`). Overrides opcionales en `ME2_MEDIA/metadata.json`:
  `{"recursos": {"ALEGRIA_NORMAL_002": {"habilitado": false, "prioridad": 2, "duracion_ms": 6000}}}` (premium/adulto solo pueden agregarse, no quitarse).
- Selección: `media/MediaSelector.kt` (categoría → intensidad más cercana → categoría/fallbacks → LOOP_NEUTRAL → cualquier video no adulto; anti-repetición inmediata; prioridad). Último recurso: `res/raw` vía `gallery/ClipCatalog`.
- Estado: `media/AvatarStateMachine.kt`; integración en `MainActivity` (fin de clip = `Player.STATE_ENDED`).
- Los clips actuales son PROVISIONALES (copiados desde `avatar/galeria/`, que se conserva). Detalle en la auditoría.
