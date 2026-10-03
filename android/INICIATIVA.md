# Iniciativa autonoma en la beta

## Base auditada y componentes reutilizados

La implementacion parte de `3546f40`, la beta de
`copilot/auditoria-completa-repositorio`, no de la copia local anterior.
Esta base ya incluye OpenRouter en `backend/llm/veniceClient.js`, memoria
local cifrada con Room, autenticacion beta, canales Android y alarmas nativas.

| Responsabilidad | Componente |
| --- | --- |
| Decision unica de iniciar/esperar | `backend/comportamiento/iniciativaConversacional.js`, export `evaluarIniciativa` |
| Ritmo y continuidad | `ritmoDeInteraccion.js`, `backend/memoria/continuidad.js` |
| Fuentes, decision y generacion | `backend/orquestador/orquestadorNotificaciones.js` |
| LLM | `backend/llm/dolphinClient.js` (personalidad en el master prompt del modelo) |
| Memoria conversacional | `LocalMemoryStore`, sin otra capa de memoria |
| Registro operativo | `Me2InitiativeStore`, tabla cifrada en la misma base Room |
| Evaluacion en segundo plano | `Me2InitiativeScheduler` y `Me2InitiativeWorker`, WorkManager |
| Entrega | `Me2NotificationCoordinator`, canal `ME2_MESSAGES` |
| Alarmas explicitas | `Me2AlarmScheduler`, `Me2AlarmReceiver` y protocolo existente |
| Apertura y descarte | `MainActivity` y `Me2InitiativeReceiver` |

No existian un scheduler de iniciativas, un registro operativo ni un perfil
de descanso. Los archivos nuevos cubren esas responsabilidades. No se
introduce otro NotificationManager, proveedor LLM, Firebase ni base cloud.
La migracion Room 1 -> 2 agrega solamente una tabla operativa; no elimina ni
reescribe los registros de memoria existentes.

## Flujo y contrato

Android conserva el registro, el contexto y las observaciones de uso
localmente. WorkManager solicita `POST /api/iniciativas/evaluar` con `Authorization: Bearer <token>`
(la identidad sale del token, nunca de un `userId` en el cuerpo):

```json
{
  "memoriaLocal": {
    "recentConversation": [],
    "persistentMemories": [],
    "importantMemories": []
  },
  "registro": [],
  "perfilRitmo": {
    "zonaHoraria": "America/Argentina/Buenos_Aires",
    "observaciones": [],
    "configurado": { "dormir": "23:00", "despertar": "07:00" }
  },
  "disponibilidad": {
    "enPrimerPlano": false,
    "notificacionesHabilitadas": true
  },
  "eventos": []
}
```

Los elementos de memoria contienen `text` y `timestamp` Unix en milisegundos;
los mensajes contienen ademas `role` (`user` o `assistant`).
La respuesta es `{ "ok": true, "data": ... }`. `data` contiene:

- `decision`: `ESPERAR` con `motivoEspera`, o `INICIAR` con `iniciativa`.
- `perfilRitmo`: estimacion actual, origen, horarios y observaciones.
- `fallosFuentes`: fuentes disponibles que fallaron durante esa evaluacion.

Una iniciativa tiene `id`, `categoria`, `motivo`, `timestamp`, `contexto`,
`referenciaEvento`, `prioridad`, `fuente`, `expiresAt` y `mensaje`.
El texto se genera solo despues de una decision positiva, con OpenRouter
y la identidad existente. No se registra un motivo como mensaje ficticio
del usuario ni se genera una respuesta prefabricada cuando falta el LLM.

El registro operativo guarda `ENVIADA`, `ABIERTA`, `RESPONDIDA`, `IGNORADA`
y `CANCELADA`, con fechas de envio, entrega, apertura y respuesta. Tambien
distingue reaccion positiva, neutral y rechazo explicito; el rechazo
pospone nuevas iniciativas normales, sin afectar alarmas. Una reserva
con `entregada: null` no cuenta como entrega. `entregada` indica que Android
acepto publicar el aviso; **no acredita que el usuario lo haya visto**.
Se conservan 200 registros detallados y firmas de IDs procesados para que
la poda no permita repetir una iniciativa antigua.

Cada PendingIntent identifica usuario e iniciativa mediante una URI unica.
Al tocar el aviso se recupera el contexto cifrado, se agrega el mensaje una
sola vez a la conversacion y se conserva la referencia en
`contexto.iniciativa` al responder. Cambiar de cuenta no abre contexto ajeno.
Limpiar el chat oculta los mensajes sin borrar recuerdos; esa visibilidad
persiste al recrear la pantalla. La observacion de Room permite actualizar
la nueva pantalla si una respuesta llega despues de una recreacion.

## Fuentes y reglas

Se utilizan noticias de News API y contexto real de la memoria local: pendientes,
recuerdos importantes, intereses y curiosidad contextual.
El calendario se consulta solo si el usuario lo conectó (calendarioApi); sin
conexión no se inventan eventos.
No hay una API de transito/transporte ni de eventos sociales externos en
esta beta. El contrato admite eventos de esas categorias con evidencia y
referencia estable, pero **no simula un feed ni inventa incidentes**.
Su conexion a una fuente real queda pendiente de disponer de ella.
OpenWeather permanece en el protocolo de despertar; no se inventa ubicacion
para emitir nuevas alertas meteorologicas.

Los eventos externos deben incluir `categoria`, `motivo`, `fuente`,
`timestamp`, `expiresAt` y `contexto.evidencia`; pueden incluir `id` o
`referenciaEvento`. Sin ID se calcula una firma determinista. Las fuentes
externas requieren coincidencias con intereses/contexto.
Un evento no garantiza un aviso. Los avisos de alarma enviados por Android
solo posponen otras iniciativas cercanas: su entrega sigue exclusivamente
por AlarmManager para respetar sonido, vibracion, etapas y horario de sueno.

Las reglas ajustables estan centralizadas en `POLITICA_INICIATIVA`:

| Regla inicial conservadora | Valor |
| --- | --- |
| Pausa tras interaccion | 1 hora |
| Cooldown de iniciativas de igual o menor prioridad | 4 horas |
| Separacion absoluta entre iniciativas | 15 minutos |
| Presupuesto en 24 horas | 3 avisos de igual o mayor prioridad, reducido tras ignoraciones |
| Cooldown de categoria | 24 horas |
| Conversacion espontanea/curiosidad | Desde 24 horas sin interaccion, con contexto |
| Vigencia de noticias | 24 horas |
| Contexto conversacional candidato | Hasta 7 dias |
| Descanso provisional | 22:00 a 09:00, zona local del dispositivo |
| Aprendizaje de horario | Al menos 7 dias con 4 horas distintas observadas por dia |

El descanso configurado prevalece sobre lo aprendido. La inferencia usa
solo interacciones de ME2, en una ventana movil de 30 dias; puede volver a
provisional si deja de tener evidencia. No mide el sueno real ni vigila
otras aplicaciones. Los horarios cruzan medianoche; `configurado.dias`
puede especificar horarios por dia (0 domingo a 6 sabado), correspondientes
a la noche que comienza ese dia.

En la bitacora, **INICIATIVA Y DESCANSO** permite pausar iniciativas,
configurar descanso o volver a la estimacion progresiva. Pausar iniciativas
no cancela alarmas explicitas.

## Ejecucion y limites Android

- Evaluacion periodica unica cada 60 minutos y evaluacion diferida tras uso.
  Esos intervalos son oportunidades de evaluar, no una orden de notificar.
- WorkManager requiere red y bateria suficiente; no usa servicio permanente.
- Dos trabajos concurrentes no evaluan a la vez dentro del proceso.
- Los trabajos persisten ante muerte normal del proceso/reinicio; se reutiliza
  `Me2BootReceiver` sin duplicar trabajos.
- Doze, ahorro de bateria y restricciones del fabricante pueden diferir el
  trabajo. **Forzar detencion desde Ajustes impide ejecucion hasta reabrir
  la app**; ningun scheduler normal garantiza saltarse esa restriccion.
- Sin permiso o con canal deshabilitado no se publica ni se marca entregado.
- Sin backend configurado, red o OpenRouter disponible no hay iniciativa
  generada; chat local y alarmas ya programadas siguen disponibles.
- Los reintentos de errores inesperados son limitados y con backoff.
- La evaluacion usa el contexto enviado, sin consultar la agenda ni
  recuperar memoria de otras cuentas del backend.

## Configuracion y comprobacion

Opciones: `GOOGLE_AUTH_ENABLED=true` (por defecto; el login con Google es la identidad del
backend) y `BETA_PREMIUM_DEFAULT=false` fuera de desarrollo.
Configure `ME2_BACKEND_BASE_URL` (Gradle) o
`ME2_ANDROID_BACKEND_BASE_URL` (entorno) para conectar el backend.
Las claves `OPENROUTER_API_KEY` y `NEWS_API_KEY` permanecen exclusivamente
en el entorno del servidor; no se compilan en el APK.

Comandos desde cada directorio:

```text
backend: npm ci
backend: npm test
android: .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Las pruebas de backend usan reloj y proveedores simulados, sin consultar
servicios externos. Las pruebas Android cubren registro, deduplicacion,
contexto, permisos, PendingIntents, migracion y serializacion de conversacion.
Los comportamientos temporales de Doze, reinicio y restricciones OEM requieren
ademas comprobacion en un dispositivo.
