// premiumLocal.js — Funciones Premium con datos LOCALES (gestor fiscal monotributista + proyectos de programación).
// Teléfono (memoriaLocal android_local_primary): el estado viaja en contexto.premiumLocal, el backend lo transforma y
// devuelve el estado nuevo en acciones.premium para que Android lo guarde en su memoria local (Room, dentro del
// respaldo cifrado). Sin teléfono (simulación E2E / web): almacén JSON del backend (namespaces premium_fiscal/premium_proyectos).
import storage from "../../utils/jsonStorage.js";
import gestorFiscal from "./gestorFiscal.js";
import gestorProyectos from "./gestorProyectos.js";

const MODULOS = {
  fiscal: { gestor: gestorFiscal, ns: "premium_fiscal" },
  proyectos: { gestor: gestorProyectos, ns: "premium_proyectos" }
};

export function detectarModulo(mensaje = "") {
  if (gestorProyectos.detectar(mensaje)) return "proyectos"; // un bloque de código con "factura" sigue siendo código
  if (gestorFiscal.detectar(mensaje)) return "fiscal";
  return null;
}

/**
 * @returns {null | { modulo, resultado, lineas: string[], estado, persistidoEn: "telefono"|"servidor" }}
 */
export function procesar(userId, mensaje, { premiumActivo = false, local = null, enTelefono = false, ahora = Date.now() } = {}) {
  if (!userId || userId === "anonimo" || !premiumActivo) return null;
  const modulo = detectarModulo(mensaje);
  if (!modulo) return null;
  const { gestor, ns } = MODULOS[modulo];
  const op = gestor.detectar(mensaje, ahora);
  const actual = enTelefono ? (local && typeof local === "object" ? local[modulo] : null) || gestor.estadoBase()
    : storage.readUserData(ns, userId, gestor.estadoBase());
  const { estado, resultado } = gestor.aplicar(actual, op, ahora);
  if (!enTelefono) storage.writeUserData(ns, userId, estado);
  return { modulo, resultado, lineas: gestor.lineas(estado, resultado, ahora), estado, persistidoEn: enTelefono ? "telefono" : "servidor" };
}

export function obtenerEstadoServidor(userId, modulo) {
  const m = MODULOS[modulo];
  return m ? storage.readUserData(m.ns, userId, m.gestor.estadoBase()) : null;
}

export default { procesar, detectarModulo, obtenerEstadoServidor };
