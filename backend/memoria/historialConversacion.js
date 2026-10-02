// Backend/memoria/historialConversacion.js

import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

// Relativo a backend/ (no al cwd) para que el historial sobreviva reinicios desde cualquier directorio.
const rutaBase = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../data/historial");

if (!fs.existsSync(rutaBase)) {
  fs.mkdirSync(rutaBase, { recursive: true });
}

function obtenerRuta(usuarioId) {
  if (!usuarioId) {
    return null;
  }

  const id = String(usuarioId).replace(
    /[^a-zA-Z0-9_-]/g,
    "_"
  );

  return path.join(
    rutaBase,
    id + ".json"
  );
}

function registrarMensaje(
  usuarioId,
  mensaje,
  tipo = "usuario"
) {
  if (!usuarioId || !mensaje) {
    return [];
  }

  const ruta = obtenerRuta(usuarioId);

  if (!ruta) {
    return [];
  }

  let historial = [];

  if (fs.existsSync(ruta)) {
    try {
      const contenido = fs.readFileSync(
        ruta,
        "utf-8"
      );

      if (contenido.trim()) {
        const datos = JSON.parse(contenido);

        if (Array.isArray(datos)) {
          historial = datos;
        }
      }
    } catch {
      historial = [];
    }
  }

  historial.push({
    mensaje: mensaje,
    tipo: tipo === "joi" ? "joi" : "usuario",
    timestamp: Date.now()
  });

  if (historial.length > 200) {
    historial.splice(
      0,
      historial.length - 200
    );
  }

  try {
    fs.writeFileSync(
      ruta,
      JSON.stringify(historial, null, 2),
      "utf-8"
    );
  } catch (error) {
    console.error(
      "[historialConversacion] Error guardando historial:",
      error
    );

    return [];
  }

  return historial;
}

function obtenerHistorial(
  usuarioId,
  limite = 50
) {
  const ruta = obtenerRuta(usuarioId);

  if (!ruta || !fs.existsSync(ruta)) {
    return [];
  }

  const cantidad =
    Number.isInteger(limite) && limite > 0
      ? limite
      : 50;

  try {
    const contenido = fs.readFileSync(
      ruta,
      "utf-8"
    );

    if (!contenido.trim()) {
      return [];
    }

    const historial = JSON.parse(contenido);

    if (!Array.isArray(historial)) {
      return [];
    }

    return historial.slice(-cantidad);
  } catch {
    return [];
  }
}

function limpiarHistorial(usuarioId) {
  const ruta = obtenerRuta(usuarioId);

  if (!ruta) {
    return false;
  }

  try {
    if (fs.existsSync(ruta)) {
      fs.unlinkSync(ruta);
    }

    return true;
  } catch (error) {
    console.error(
      "[historialConversacion] Error eliminando historial:",
      error
    );

    return false;
  }
}

function ultimosMensajes(
  usuarioId,
  limite = 10
) {
  const historial = obtenerHistorial(
    usuarioId,
    limite
  );

  return historial.map(
    function (mensaje) {
      return (
        mensaje.tipo +
        ": " +
        mensaje.mensaje
      );
    }
  );
}

function resumirConversacion(usuarioId) {
  const historial = obtenerHistorial(
    usuarioId,
    100
  );

  const resumen = historial
    .map(
      function (mensaje) {
        return mensaje.mensaje;
      }
    )
    .join(" | ");

  return resumen.slice(0, 2000);
}

function extraerContexto(usuarioId) {
  const historial = obtenerHistorial(
    usuarioId,
    100
  );

  const texto = historial
    .map(
      function (mensaje) {
        return mensaje.mensaje;
      }
    )
    .join(" ")
    .toLowerCase();

  return {
    mencionaIA: texto.includes("ia"),

    mencionaNegocio:
      texto.includes("negocio") ||
      texto.includes("app"),

    mencionaWhatsApp:
      texto.includes("whatsapp"),

    mencionaProyecto:
      texto.includes("proyecto")
  };
}

export default {
  registrarMensaje,
  obtenerHistorial,
  limpiarHistorial,
  ultimosMensajes,
  resumirConversacion,
  extraerContexto
};