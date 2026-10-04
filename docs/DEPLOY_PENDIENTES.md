# Pendientes de despliegue: URL del backend, Mercado Pago, LLM y galería de video

Estado de la rama `me2_dlp`. Todo se configura por entorno/build: no hay URLs de túnel, claves ni prompts en el repo.

## 0. URL del backend (sin túnel)

- El APK toma la URL **solo** de la configuración de build. Orden de búsqueda (`android/app/build.gradle.kts`):
  1. env `ME2_BACKEND_URL`
  2. `-PME2_BACKEND_URL=...` o `ME2_BACKEND_URL` en `~/.gradle/gradle.properties`
  3. `ME2_BACKEND_BASE_URL` (alias legacy, Gradle)
  4. `ME2_BACKEND_URL` / `ME2_BACKEND_BASE_URL` en `android/local.properties` (gitignored)
  5. env `ME2_ANDROID_BACKEND_BASE_URL` (alias legacy)
- **Release**: no tiene default. `assembleRelease` / `bundleRelease` / `assemble` / `build` ejecutan primero
  `:app:verificarBackendUrlRelease`, que **corta el build** si la URL falta, no es `https`, apunta a
  `10.0.2.2`/`localhost`/`127.0.0.1` o es un túnel `*.trycloudflare.com`. Se puede probar sola:
  `ME2_BACKEND_URL=https://api.tu-dominio.com ./gradlew :app:verificarBackendUrlRelease`.
- **Debug**: si no hay URL usa `http://10.0.2.2:3000` (loopback del emulador al host; comportamiento previo).
  El cleartext solo se permite en debug (`src/debug/res/xml/network_security_config.xml`).
- Los tests unitarios (`:app:testDebugUnitTest`) no necesitan URL.

Checklist:
- [ ] Servidor propio con dominio y TLS (p. ej. `https://api.tu-dominio.com`) corriendo `backend/` (`npm ci && npm start`).
- [ ] `ME2_BACKEND_URL=https://api.tu-dominio.com` al compilar el release.
- [ ] En el backend: `BACKEND_PUBLIC_URL` = la misma URL, `NODE_ENV=production`, `TRUST_PROXY=1` si hay proxy delante.

## 1. Mercado Pago (Checkout Pro)

Ya implementado en `backend/api/mercadoPago.js` (rutas en `server.js`):
`POST /api/mercadopago/checkout`, `POST /api/mercadopago/verify`, `POST /api/mercadopago/webhook`, `GET /api/mercadopago/plan`.
El APK abre el `init_point` que devuelve el backend (el access token nunca va al APK).

| Variable | Uso |
|---|---|
| `MERCADO_PAGO_ACCESS_TOKEN` | Token privado (`APP_USR-...`; `TEST-...` para sandbox). Vacío: mock fuera de producción; en producción el checkout responde 503 (deshabilitado sin romper). |
| `MERCADO_PAGO_WEBHOOK_SECRET` | Clave secreta de webhooks (valida `x-signature`). Obligatoria en producción (sin ella el webhook responde 401). |
| `BACKEND_PUBLIC_URL` | Base pública: `notification_url = <BACKEND_PUBLIC_URL>/api/mercadopago/webhook`. Sin ella no se envía `notification_url`. |
| `MERCADO_PAGO_SUCCESS_URL` / `_PENDING_URL` / `_FAILURE_URL` | `back_urls`. Las tres o ninguna; con las tres se agrega `auto_return=approved`. |
| `PREMIUM_PRICE_ARS` | Precio del plan (ARS). |
| `MERCADO_PAGO_TIMEOUT_MS` | Timeout de la API de MP (default 10000). |
| `ME2_MERCADO_PAGO_PUBLIC_KEY` (Gradle, opcional) | Solo para un SDK nativo futuro; hoy no hace falta. |

Checklist:
- [ ] Crear la aplicación en el panel de desarrolladores de Mercado Pago y copiar el access token de producción.
- [ ] En “Webhooks”, URL `https://api.tu-dominio.com/api/mercadopago/webhook`, evento **Pagos**; copiar la clave secreta a `MERCADO_PAGO_WEBHOOK_SECRET`.
- [ ] Definir `BACKEND_PUBLIC_URL` y, si se quiere retorno automático, las tres `MERCADO_PAGO_*_URL`.
- [ ] Probar primero con credenciales `TEST-` y usuarios de prueba; en desarrollo `GET /health` muestra `integrations.mercadoPagoConfigured`.
- Tests: `backend/test/mercadopago-config.test.js` (URLs por entorno, mock solo sin token, 503 en producción sin token).

## 2. LLM (Dolphin Mistral Venice en servidor propio)

Cliente: `backend/llm/dolphinClient.js`, OpenAI-compatible (`POST <DOLPHIN_URL>/chat/completions`). El cambio de proveedor es
**solo por entorno** (no hay URLs ni modelos en el código). El backend no inyecta personalidad: va fija en el servidor.

| Variable | Uso |
|---|---|
| `DOLPHIN_URL` | Base OpenAI-compatible; acepta base, `/v1` o `/v1/chat/completions`. |
| `DOLPHIN_MODEL` | Nombre del modelo en el servidor. |
| `DOLPHIN_API_KEY` | Bearer opcional (Ollama no lo pide; usar si hay proxy con token). |
| `DOLPHIN_TIMEOUT_MS`, `DOLPHIN_MAX_TOKENS`, `DOLPHIN_MIN_TOKENS`, `DOLPHIN_TEMPERATURE`, `DOLPHIN_RETRIES`, `DOLPHIN_REASONING_EFFORT` | Opcionales. `DOLPHIN_REASONING_EFFORT` dejarlo vacío con Ollama. |

Sin `DOLPHIN_URL`+`DOLPHIN_MODEL` el chat sigue funcionando con los fallbacks locales (`reason: dolphin_no_configurado`).

Ollama (producción):
1. En el servidor del LLM: `ollama pull` del modelo Dolphin Mistral Venice y un `Modelfile` con `FROM <modelo>` y el
   `SYSTEM` de personalidad (lo escribe el dueño en el servidor; **no** está ni se agrega en este repo).
2. `ollama create me2-dolphin -f Modelfile`.
3. Exponer Ollama solo al backend (red privada, o proxy https con Bearer). Ollama escucha en `:11434`; endpoint OpenAI = `/v1`.
4. En el backend:
   ```
   DOLPHIN_URL=http://IP-PRIVADA:11434/v1     # o https://llm.tu-dominio.com/v1 detrás de proxy
   DOLPHIN_MODEL=me2-dolphin
   DOLPHIN_API_KEY=                           # token del proxy, si hay
   ```
5. Verificar: `curl $DOLPHIN_URL/models`, `GET /health` en desarrollo (bloque `llm`) y un turno de chat.

Checklist:
- [ ] Servidor Ollama con el modelo creado desde el Modelfile.
- [ ] Reemplazar en el `.env` del backend las variables de Groq (pruebas) por las de Ollama.
- [ ] No exponer `:11434` a internet sin proxy/autenticación.

## 3. Galería de video del avatar

Biblioteca V1 (detalle en `android/MEDIA_V1.md`), descubierta por nombre de archivo, sin listas en el código:
- Empaquetado: `android/app/src/main/assets/ME2_MEDIA/` (requiere recompilar).
- Drop-in sin recompilar: `filesDir/ME2_MEDIA/` del dispositivo (misma estructura; mismo id reemplaza al empaquetado).
- Carga: `media/MediaLibrary.kt` → `MediaNameParser.kt` (ruta+nombre → metadatos) → `MediaSelector.kt` → `MainActivity` (ExoPlayer).
- Overrides opcionales: `ME2_MEDIA/metadata.json` → `{"recursos": {"ID": {"habilitado": false, "prioridad": 2, "duracion_ms": 6000}}}`.
- Nombre: `NN_CATEGORIA/SUBCATEGORIA/SUBCATEGORIA[_INTENSIDAD]_NNN.mp4` (intensidad `NORMAL|MEDIO|MAXIMO` solo en reacciones;
  `NNN` = variante). Ej.: `02_REACCIONES/DUDA/DUDA_MEDIO_001.mp4`, `05_DESPERTADOR/AVISO_01/AVISO_01_001.mp4`.
- Videos `mp4` (también webm/mkv/3gp); widget acepta gif/webp/png/jpg/mp4. Voz solo en `00_PRESENTACION`.
- `assets/videos/<mood>/` y `filesDir/gallery/<mood>/` (galería vieja de `ClipCatalog`) quedan vacías; usar `ME2_MEDIA`.
- Catálogos de medios del chat en el backend (`backend/media/catalogos/{normal,xxx}/manifest.json`) están vacíos: es otro
  sistema (medios enviados en el chat), con su esquema en `catalogos/xxx/README.md`.
- No hay UI de galería; no se agregó.

Clips faltantes (lo que el código/orquestador ya puede pedir y no existe; hoy cae en fallbacks → LOOP_NEUTRAL):
- `03_CONVERSACION/ESCRIBIENDO/ESCRIBIENDO_001.mp4` — “escribiendo mensaje” (solo mientras ME2 tipea, sobre todo offline).
- `03_CONVERSACION/DESPEDIDA/DESPEDIDA_001.mp4`.
- `05_DESPERTADOR/` completo: `AVISO_01/`, `AVISO_02/` (escalaciones 1 y 2), `ALARMA/` (escalación final),
  `POST_ALARMA/` (despierto), y según `MEDIA_V1.md` también `AVISO_03/`, `DESPERTANDO/`.
- `08_SISTEMA/ERROR/`, `08_SISTEMA/SIN_CONEXION/`, `08_SISTEMA/CARGANDO/`.
- `02_REACCIONES` sin ningún clip: CURIOSIDAD, PENSATIVA, DUDA, CONFUSION, TRISTEZA, PREOCUPACION, ENOJO, MOLESTIA,
  VERGUENZA, CANSANCIO (las 10 las emite `backend/modulos/media/reaccionAudiovisual.js`; CURIOSIDAD es la de “pregunta”).
- `02_REACCIONES` incompletas: EMPATIA (MEDIO, MAXIMO), ORGULLO (NORMAL, MAXIMO), SARCASMO (MAXIMO), TIMIDEZ (MAXIMO).
- Opcionales (sin clips): `04_WIDGET/`, `06_TRANSICIONES/`, `07_PREMIUM/ESPECIALES/`, `07_PREMIUM/ADULTO/`.
- Sin clasificar: `avatar/sin_clasificar/MICRO-REACCIONES_01.mp4` (fuera del APK, ver su README).
- Todos los clips actuales son provisionales.

Checklist:
- [ ] Copiar los clips con la nomenclatura en `assets/ME2_MEDIA/` (o en `filesDir/ME2_MEDIA/` para probar sin recompilar).
- [ ] Recompilar con `ME2_BACKEND_URL` definida.
