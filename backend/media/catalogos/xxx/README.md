# Catálogo `xxx` (modo adulto) — placeholder vacío

Solo se usa con **Premium activo + palabra clave desbloqueada en la sesión actual** (`adultMode.unlocked`).
Fuera de esa sesión el selector nunca lo considera y `/api/media/xxx/*` responde 403.

Contenido: únicamente avatar adulto ficticio. Este repositorio **no incluye** archivos; se cargan en el servidor.

## Esquema de `manifest.json` → `items[]`
| campo | tipo | descripción |
|---|---|---|
| `id` | string | único, `[a-z0-9_-]` |
| `tipo` | `"clip"` \| `"gif"` | clip de video (mp4/webm) o GIF animado en loop |
| `archivo` | string | nombre del archivo dentro de esta carpeta |
| `tags` | string[] | palabras clave de contexto (se comparan con el mensaje del usuario y la conversación) |
| `intensidad` | `"soft_flirt"` \| `"suggestive"` \| `"intimate"` \| `"explicit"` | nivel máximo; nunca se elige por encima de la intensidad actual de la sesión |
| `duracionMs` | number (opcional) | duración del clip |
