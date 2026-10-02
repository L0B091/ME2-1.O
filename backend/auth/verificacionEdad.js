// verificacionEdad.js — fecha de nacimiento desde la cuenta de Google (People API, scope user.birthday.read).
// Adaptador: Android pide el scope y manda serverAuthCode al login; acá se canjea por access token
// (requiere GOOGLE_CLIENT_ID + GOOGLE_CLIENT_SECRET) y se lee birthdays. Sin datos → la verificación queda pendiente.
import storage from "../utils/jsonStorage.js";

const NAMESPACE = "verificacion_edad";
export const SCOPE_CUMPLEANOS = "https://www.googleapis.com/auth/user.birthday.read";

export function disponible() {
  return Boolean(String(process.env.GOOGLE_CLIENT_ID || "").trim() && String(process.env.GOOGLE_CLIENT_SECRET || "").trim());
}

export async function canjearCodigo(serverAuthCode, fetchImpl = fetch) {
  const res = await fetchImpl("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code: serverAuthCode, client_id: process.env.GOOGLE_CLIENT_ID, client_secret: process.env.GOOGLE_CLIENT_SECRET,
      grant_type: "authorization_code", redirect_uri: process.env.GOOGLE_REDIRECT_URI || ""
    })
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.access_token) throw new Error(`token_google_${res.status}`);
  return data.access_token;
}

// Devuelve "YYYY-MM-DD" si Google tiene la fecha COMPLETA (con año); si no, null.
export async function leerCumpleanos(accessToken, fetchImpl = fetch) {
  const res = await fetchImpl("https://people.googleapis.com/v1/people/me?personFields=birthdays", {
    headers: { Authorization: `Bearer ${accessToken}` }
  });
  if (!res.ok) throw new Error(`people_api_${res.status}`);
  const data = await res.json();
  const fechas = (data.birthdays || []).map(b => b.date).filter(d => d?.year && d?.month && d?.day);
  const d = fechas[0];
  return d ? `${d.year}-${String(d.month).padStart(2, "0")}-${String(d.day).padStart(2, "0")}` : null;
}

export function guardar(userId, fecha, fuente = "google_people_api") {
  const valida = typeof fecha === "string" && /^\d{4}-\d{2}-\d{2}$/.test(fecha) ? fecha : null;
  const reg = { userId, fechaNacimiento: valida, fuente, actualizado: new Date().toISOString() };
  storage.writeUserData(NAMESPACE, userId, reg);
  return reg;
}

export function obtener(userId) {
  return storage.readUserData(NAMESPACE, userId, { userId, fechaNacimiento: null, fuente: null });
}

export function edad(fecha, ahora = Date.now()) {
  const [y, m, d] = fecha.split("-").map(Number);
  const hoy = new Date(ahora);
  let e = hoy.getUTCFullYear() - y;
  if (hoy.getUTCMonth() + 1 < m || (hoy.getUTCMonth() + 1 === m && hoy.getUTCDate() < d)) e--;
  return e;
}

// { estado: "sin_dato" | "menor" | "mayor", edad? }
export function evaluar(userId, ahora = Date.now()) {
  const { fechaNacimiento } = obtener(userId);
  if (!fechaNacimiento) return { estado: "sin_dato" };
  const e = edad(fechaNacimiento, ahora);
  return { estado: e >= 18 ? "mayor" : "menor", edad: e };
}

// Al iniciar sesión con Google: si llega serverAuthCode y el adaptador está configurado, sincroniza la fecha.
export async function sincronizarDesdeLogin(userId, serverAuthCode, fetchImpl = fetch) {
  if (!serverAuthCode || !disponible()) return { sincronizado: false, motivo: !serverAuthCode ? "sin_codigo" : "adaptador_no_configurado" };
  const token = await canjearCodigo(serverAuthCode, fetchImpl);
  const fecha = await leerCumpleanos(token, fetchImpl);
  guardar(userId, fecha);
  return { sincronizado: true, tieneFecha: Boolean(fecha) };
}

export default { SCOPE_CUMPLEANOS, disponible, canjearCodigo, leerCumpleanos, guardar, obtener, edad, evaluar, sincronizarDesdeLogin };
