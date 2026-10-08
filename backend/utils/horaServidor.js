// horaServidor.js — hora del servidor como fuente de verdad para el reloj propio de ME2 en el teléfono (Me2Clock).
// Cada respuesta lleva X-ME2-Server-Time (epoch ms al responder) y X-ME2-Proc-Ms (cuánto tardó el servidor), para
// que el teléfono descuente el procesamiento y compense solo la latencia de red. La zona de ME2 es fija.
export const ZONA_ME2 = process.env.ME2_TZ || "America/Argentina/Buenos_Aires";

export function horaServidor(_req, res, next) {
  const inicio = Date.now();
  const writeHead = res.writeHead;
  res.writeHead = function (...args) {
    try {
      if (!res.headersSent) {
        const ahora = Date.now();
        res.setHeader("X-ME2-Server-Time", String(ahora));
        res.setHeader("X-ME2-Proc-Ms", String(ahora - inicio));
      }
    } catch { /* nunca rompe la respuesta */ }
    return writeHead.apply(this, args);
  };
  next();
}

export default { horaServidor, ZONA_ME2 };
