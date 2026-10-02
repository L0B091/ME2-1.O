// Adaptador Google Calendar (STUB listo para enchufar).
// Env: GOOGLE_CALENDAR_ENABLED=true, GOOGLE_CALENDAR_ID, y credenciales OAuth del usuario
// (GOOGLE_CALENDAR_ACCESS_TOKEN o flujo OAuth futuro). Hasta entonces, no se usa.
function configurado() {
  return String(process.env.GOOGLE_CALENDAR_ENABLED || "").toLowerCase() === "true" &&
    Boolean(String(process.env.GOOGLE_CALENDAR_ID || "").trim()) &&
    Boolean(String(process.env.GOOGLE_CALENDAR_ACCESS_TOKEN || "").trim());
}

async function listar() {
  if (!configurado()) throw new Error("Google Calendar no configurado");
  const id = encodeURIComponent(process.env.GOOGLE_CALENDAR_ID);
  const url = `https://www.googleapis.com/calendar/v3/calendars/${id}/events?singleEvents=true&orderBy=startTime&timeMin=${new Date().toISOString()}&maxResults=20`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${process.env.GOOGLE_CALENDAR_ACCESS_TOKEN}` }, signal: AbortSignal.timeout(8000) });
  if (!res.ok) throw new Error(`Google Calendar HTTP ${res.status}`);
  const data = await res.json();
  return (data.items || []).map(ev => {
    const inicio = ev.start?.dateTime || ev.start?.date || "";
    return { id: ev.id, tipo: "google", fecha: inicio.slice(0, 10), hora: inicio.slice(11, 16) || "00:00", descripcion: ev.summary || "", origen: "google" };
  });
}

async function guardarTodos() {
  throw new Error("Escritura en Google Calendar aún no implementada");
}

export default { nombre: "google", disponible: configurado, listar, guardarTodos };
