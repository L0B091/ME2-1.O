// Backend/memoria/memoriaPersistente.js

import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { DATA_DIR } from "../utils/dataDir.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const DIRECTORIO_MEMORIA = path.join(DATA_DIR, "memoriaPersistente");

const MAX_ELEMENTOS = 100;

const CLAVE_ACTIVIDAD = "__actividad_usuario__";
const CLAVE_ACTIVIDAD_LEGACY = "_actividad_usuario_";

/**
 * Asegura que exista el directorio de persistencia.
 */
function inicializar() {
  if (!fs.existsSync(DIRECTORIO_MEMORIA)) {
    fs.mkdirSync(DIRECTORIO_MEMORIA, {
      recursive: true
    });
  }
}

/**
 * Obtiene la ruta del archivo de memoria de un usuario.
 */
function obtenerRuta(usuarioId) {
  if (!usuarioId) {
    return null;
  }

  inicializar();

  const id = String(usuarioId)
    .replace(/[^a-zA-Z0-9_-]/g, "_");

  return path.join(
    DIRECTORIO_MEMORIA,
    id + ".json"
  );
}

/**
 * Obtiene la memoria completa del usuario.
 */
function obtener(usuarioId) {
  const ruta = obtenerRuta(usuarioId);

  if (!ruta) {
    return [];
  }

  if (!fs.existsSync(ruta)) {
    return [];
  }

  try {
    const contenido = fs.readFileSync(
      ruta,
      "utf8"
    );

    if (!contenido.trim()) {
      return [];
    }

    const memoria = JSON.parse(contenido);

    if (!Array.isArray(memoria)) {
      return [];
    }

    return memoria;
  } catch (error) {
    console.error(
      "[memoriaPersistente] Error leyendo memoria de usuario:",
      { usuarioId },
      error
    );

    return [];
  }
}

/**
 * Guarda la memoria completa del usuario.
 */
function guardar(usuarioId, memoria) {
  const ruta = obtenerRuta(usuarioId);

  if (!ruta) {
    return false;
  }

  try {
    let datos = memoria;

    if (!Array.isArray(datos)) {
      datos = [];
    }

    datos = datos.slice(-MAX_ELEMENTOS);

    fs.writeFileSync(
      ruta,
      JSON.stringify(datos, null, 2),
      "utf8"
    );

    return true;
  } catch (error) {
    console.error(
      "[memoriaPersistente] Error guardando memoria de usuario:",
      { usuarioId },
      error
    );

    return false;
  }
}

/**
 * Agrega un elemento nuevo a la memoria.
 */
function agregar(usuarioId, elemento) {
  if (!usuarioId || !elemento) {
    return null;
  }

  const memoria = obtener(usuarioId);

  memoria.push(elemento);

  if (memoria.length > MAX_ELEMENTOS) {
    memoria.splice(
      0,
      memoria.length - MAX_ELEMENTOS
    );
  }

  const guardado = guardar(
    usuarioId,
    memoria
  );

  if (!guardado) {
    return null;
  }

  return elemento;
}

/**
 * Actualiza o crea un registro identificado
 * mediante una clave.
 *
 * CONTRATO:
 *
 * {
 *   clave: "...",
 *   ...datos
 * }
 *
 * Ejemplo:
 *
 * {
 *   clave: "__actividad_usuario__",
 *   actividad: { ... }
 * }
 *
 * Ejemplo:
 *
 * {
 *   clave: "datos_usuario",
 *   usuario: { ... }
 * }
 */
function actualizarPorClave(
  usuarioId,
  clave,
  datos
) {
  if (!usuarioId || !clave) {
    return null;
  }

  if (
    !datos ||
    typeof datos !== "object" ||
    Array.isArray(datos)
  ) {
    return null;
  }

  const memoria = obtener(usuarioId);

  const indice = memoria.findIndex(
    function (item) {
      return (
        item &&
        item.clave === clave
      );
    }
  );

  const registro = {
    clave: clave,
    ...datos
  };

  if (indice === -1) {
    memoria.push(registro);
  } else {
    memoria[indice] = registro;
  }

  const guardado = guardar(
    usuarioId,
    memoria
  );

  if (!guardado) {
    return null;
  }

  return datos;
}

/**
 * Actualiza específicamente la actividad del usuario.
 */
function actualizarActividad(
  usuarioId,
  actividad
) {
  // Migrate legacy single-underscore key if present
  const memoria = obtener(usuarioId);
  const legacyIdx = memoria.findIndex(
    item => item && item.clave === CLAVE_ACTIVIDAD_LEGACY
  );
  if (legacyIdx >= 0) {
    memoria.splice(legacyIdx, 1);
    guardar(usuarioId, memoria);
  }
  return actualizarPorClave(
    usuarioId,
    CLAVE_ACTIVIDAD,
    {
      actividad: actividad
    }
  );
}

/**
 * Obtiene un registro específico mediante su clave.
 */
function obtenerPorClave(
  usuarioId,
  clave
) {
  if (!usuarioId || !clave) {
    return null;
  }

  const memoria = obtener(usuarioId);

  let registro = memoria.find(
    function (item) {
      return (
        item &&
        item.clave === clave
      );
    }
  );

  // Activity key migration: accept legacy "_actividad_usuario_"
  if (!registro && clave === CLAVE_ACTIVIDAD) {
    registro = memoria.find(
      function (item) {
        return item && item.clave === CLAVE_ACTIVIDAD_LEGACY;
      }
    ) || null;
  }

  if (!registro) {
    return null;
  }

  return registro;
}

/**
 * Elimina un registro específico mediante su clave.
 */
function eliminarPorClave(
  usuarioId,
  clave
) {
  if (!usuarioId || !clave) {
    return false;
  }

  const memoria = obtener(usuarioId);

  const nuevaMemoria =
    memoria.filter(
      function (item) {
        return !item || item.clave !== clave;
      }
    );

  return guardar(
    usuarioId,
    nuevaMemoria
  );
}

/**
 * Busca elementos relevantes mediante texto.
 */
function relevantes(
  usuarioId,
  texto,
  limite
) {
  const memoria = obtener(usuarioId);

  const cantidad =
    Number.isInteger(limite) && limite > 0
      ? limite
      : 10;

  if (!texto) {
    return memoria.slice(
      -cantidad
    );
  }

  const termino = String(texto)
    .toLowerCase()
    .trim();

  if (!termino) {
    return memoria.slice(
      -cantidad
    );
  }

  const resultados = memoria.filter(
    function (item) {
      if (
        !item ||
        typeof item.texto !== "string"
      ) {
        return false;
      }

      return item.texto
        .toLowerCase()
        .includes(termino);
    }
  );

  return resultados.slice(
    -cantidad
  );
}

/**
 * Elimina toda la memoria persistente
 * de un usuario.
 */
function limpiar(usuarioId) {
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
      "[memoriaPersistente] Error eliminando memoria de " +
      usuarioId +
      ":",
      error
    );

    return false;
  }
}

/**
 * Comprueba si existe memoria persistente.
 */
function existe(usuarioId) {
  const ruta = obtenerRuta(usuarioId);

  if (!ruta) {
    return false;
  }

  return fs.existsSync(ruta);
}

export default {
  inicializar,
  obtenerRuta,
  obtener,
  agregar,
  guardar,
  actualizarPorClave,
  actualizarActividad,
  obtenerPorClave,
  eliminarPorClave,
  relevantes,
  limpiar,
  existe
};