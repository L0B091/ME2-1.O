// Backend/memoria/datosUsuario.js
// Datos e identidad persistente del usuario

import memoriaPersistente from "./memoriaPersistente.js";

const CLAVE_USUARIO = "datos_usuario";

/**
 * Estructura base del usuario.
 *
 * Este módulo representa la ficha del usuario.
 * No almacena el historial completo ni los recuerdos importantes.
 */
function estructuraBase(userId) {
  return {
    userId: userId,

    identidad: {
      nombre: null,
      apodo: null
    },

    cuentas: {
      email: null,
      telefono: null,
      googleId: null,
      appleId: null
    },

    configuracion: {
      idioma: "es",
      zonaHoraria: null,
      plan: "free",
      nombrePersonaje: null,
      nombrePreferido: null,
      widgetSeleccionado: null
    },

    perfil: {
      estiloConversacion: "neutral",
      nivelTecnico: "medio",
      emocionalidad: "media"
    },

    intereses: [],

    patrones: {
      mencionesIA: 0,
      mencionesNegocio: 0,
      tonoEmocional: 0
    },

    resumen: "",

    fechaCreacion: Date.now(),
    ultimaActualizacion: Date.now()
  };
}

/**
 * Normaliza los datos recuperados del almacenamiento.
 */
function normalizarUsuario(userId, usuario) {
  const base = estructuraBase(userId);

  if (!usuario || typeof usuario !== "object") {
    return base;
  }

  return {
    userId:
      typeof usuario.userId === "string"
        ? usuario.userId
        : userId,

    identidad: {
      nombre:
        usuario.identidad &&
        typeof usuario.identidad.nombre === "string"
          ? usuario.identidad.nombre
          : null,

      apodo:
        usuario.identidad &&
        typeof usuario.identidad.apodo === "string"
          ? usuario.identidad.apodo
          : null
    },

    cuentas: {
      email:
        usuario.cuentas &&
        typeof usuario.cuentas.email === "string"
          ? usuario.cuentas.email
          : null,

      telefono:
        usuario.cuentas &&
        typeof usuario.cuentas.telefono === "string"
          ? usuario.cuentas.telefono
          : null,

      googleId:
        usuario.cuentas &&
        typeof usuario.cuentas.googleId === "string"
          ? usuario.cuentas.googleId
          : null,

      appleId:
        usuario.cuentas &&
        typeof usuario.cuentas.appleId === "string"
          ? usuario.cuentas.appleId
          : null
    },

    configuracion: {
      idioma:
        usuario.configuracion &&
        typeof usuario.configuracion.idioma === "string"
          ? usuario.configuracion.idioma
          : base.configuracion.idioma,

      zonaHoraria:
        usuario.configuracion &&
        typeof usuario.configuracion.zonaHoraria === "string"
          ? usuario.configuracion.zonaHoraria
          : null,

      plan:
        usuario.configuracion &&
        typeof usuario.configuracion.plan === "string"
          ? usuario.configuracion.plan
          : base.configuracion.plan,

      nombrePreferido:
        usuario.configuracion &&
        typeof usuario.configuracion.nombrePreferido === "string"
          ? usuario.configuracion.nombrePreferido
          : null,

      nombrePersonaje:
        usuario.configuracion &&
        typeof usuario.configuracion.nombrePersonaje === "string"
          ? usuario.configuracion.nombrePersonaje
          : null,

      widgetSeleccionado:
        usuario.configuracion &&
        typeof usuario.configuracion.widgetSeleccionado === "string"
          ? usuario.configuracion.widgetSeleccionado
          : null
    },

    perfil: {
      estiloConversacion:
        usuario.perfil &&
        typeof usuario.perfil.estiloConversacion === "string"
          ? usuario.perfil.estiloConversacion
          : base.perfil.estiloConversacion,

      nivelTecnico:
        usuario.perfil &&
        typeof usuario.perfil.nivelTecnico === "string"
          ? usuario.perfil.nivelTecnico
          : base.perfil.nivelTecnico,

      emocionalidad:
        usuario.perfil &&
        typeof usuario.perfil.emocionalidad === "string"
          ? usuario.perfil.emocionalidad
          : base.perfil.emocionalidad
    },

    intereses:
      Array.isArray(usuario.intereses)
        ? [...new Set(usuario.intereses)]
        : [],

    patrones: {
      mencionesIA:
        usuario.patrones &&
        Number.isFinite(usuario.patrones.mencionesIA)
          ? usuario.patrones.mencionesIA
          : 0,

      mencionesNegocio:
        usuario.patrones &&
        Number.isFinite(usuario.patrones.mencionesNegocio)
          ? usuario.patrones.mencionesNegocio
          : 0,

      tonoEmocional:
        usuario.patrones &&
        Number.isFinite(usuario.patrones.tonoEmocional)
          ? usuario.patrones.tonoEmocional
          : 0
    },

    resumen:
      typeof usuario.resumen === "string"
        ? usuario.resumen
        : "",

    fechaCreacion:
      Number.isFinite(usuario.fechaCreacion)
        ? usuario.fechaCreacion
        : base.fechaCreacion,

    ultimaActualizacion:
      Number.isFinite(usuario.ultimaActualizacion)
        ? usuario.ultimaActualizacion
        : base.ultimaActualizacion
  };
}

/**
 * Obtiene el registro persistente del usuario.
 */
function obtenerRegistro(userId) {
  if (!userId) {
    return null;
  }

  const memoria = memoriaPersistente.obtener(userId);

  return memoria.find(
    function (item) {
      return item && item.clave === CLAVE_USUARIO;
    }
  ) || null;
}

/**
 * Obtiene los datos completos del usuario.
 *
 * Si todavía no existe un perfil, devuelve una
 * estructura base sin escribir automáticamente.
 */
function obtener(userId) {
  if (!userId) {
    return null;
  }

  const registro = obtenerRegistro(userId);

  if (!registro) {
    return estructuraBase(userId);
  }

  return normalizarUsuario(
    userId,
    registro.usuario
  );
}

/**
 * Crea el usuario si todavía no existe.
 */
function crear(userId) {
  if (!userId) {
    return null;
  }

  const existente = obtenerRegistro(userId);

  if (existente && existente.usuario) {
    return normalizarUsuario(
      userId,
      existente.usuario
    );
  }

  const usuario = estructuraBase(userId);

  memoriaPersistente.actualizarPorClave(
    userId,
    CLAVE_USUARIO,
    {
      usuario: usuario
    }
  );

  return usuario;
}

/**
 * Actualiza datos del usuario.
 *
 * Solo modifica campos que hayan sido proporcionados.
 */
function actualizar(userId, datos = {}) {
  if (!userId) {
    return null;
  }

  const usuario = obtener(userId);

  if (datos.identidad) {
    if (typeof datos.identidad.nombre === "string") {
      usuario.identidad.nombre = datos.identidad.nombre;
    }

    if (typeof datos.identidad.apodo === "string") {
      usuario.identidad.apodo = datos.identidad.apodo;
    }
  }

  if (datos.cuentas) {
    if (typeof datos.cuentas.email === "string") {
      usuario.cuentas.email = datos.cuentas.email;
    }

    if (typeof datos.cuentas.telefono === "string") {
      usuario.cuentas.telefono = datos.cuentas.telefono;
    }

    if (typeof datos.cuentas.googleId === "string") {
      usuario.cuentas.googleId = datos.cuentas.googleId;
    }

    if (typeof datos.cuentas.appleId === "string") {
      usuario.cuentas.appleId = datos.cuentas.appleId;
    }
  }

  if (datos.configuracion) {
    if (typeof datos.configuracion.idioma === "string") {
      usuario.configuracion.idioma = datos.configuracion.idioma;
    }

    if (typeof datos.configuracion.zonaHoraria === "string") {
      usuario.configuracion.zonaHoraria =
        datos.configuracion.zonaHoraria;
    }

    if (typeof datos.configuracion.plan === "string") {
      usuario.configuracion.plan =
        datos.configuracion.plan;
    }

    if (typeof datos.configuracion.nombrePreferido === "string") {
      usuario.configuracion.nombrePreferido =
        datos.configuracion.nombrePreferido;
    }

    if (typeof datos.configuracion.nombrePersonaje === "string") {
      usuario.configuracion.nombrePersonaje =
        datos.configuracion.nombrePersonaje;
    }

    if (typeof datos.configuracion.widgetSeleccionado === "string") {
      usuario.configuracion.widgetSeleccionado =
        datos.configuracion.widgetSeleccionado;
    }
  }

  if (datos.perfil) {
    if (typeof datos.perfil.estiloConversacion === "string") {
      usuario.perfil.estiloConversacion =
        datos.perfil.estiloConversacion;
    }

    if (typeof datos.perfil.nivelTecnico === "string") {
      usuario.perfil.nivelTecnico =
        datos.perfil.nivelTecnico;
    }

    if (typeof datos.perfil.emocionalidad === "string") {
      usuario.perfil.emocionalidad =
        datos.perfil.emocionalidad;
    }
  }

  if (Array.isArray(datos.intereses)) {
    usuario.intereses = [
      ...new Set([
        ...usuario.intereses,
        ...datos.intereses
      ])
    ];
  }

  if (typeof datos.resumen === "string") {
    usuario.resumen = datos.resumen;
  }

  usuario.ultimaActualizacion = Date.now();

  memoriaPersistente.actualizarPorClave(
    userId,
    CLAVE_USUARIO,
    {
      usuario: usuario
    }
  );

  return usuario;
}

/**
 * Detecta patrones simples a partir de datos recibidos.
 */
function detectarPatrones(data) {
  const patrones = {
    mencionesIA: 0,
    mencionesNegocio: 0,
    tonoEmocional: 0
  };

  const textos = JSON.stringify(data).toLowerCase();

  if (textos.includes("ia")) {
    patrones.mencionesIA++;
  }

  if (
    textos.includes("negocio") ||
    textos.includes("app")
  ) {
    patrones.mencionesNegocio++;
  }

  if (
    textos.includes("amor") ||
    textos.includes("emocion")
  ) {
    patrones.tonoEmocional++;
  }

  return patrones;
}

/**
 * Actualiza el perfil aprendido a partir de una interacción.
 */
function aprender(userId, datos = {}) {
  if (!userId) {
    return null;
  }

  const usuario = obtener(userId);

  const patrones = detectarPatrones(datos);

  usuario.patrones.mencionesIA +=
    patrones.mencionesIA;

  usuario.patrones.mencionesNegocio +=
    patrones.mencionesNegocio;

  usuario.patrones.tonoEmocional +=
    patrones.tonoEmocional;

  if (patrones.mencionesIA > 0) {
    if (!usuario.intereses.includes("IA")) {
      usuario.intereses.push("IA");
    }
  }

  if (patrones.mencionesNegocio > 0) {
    if (!usuario.intereses.includes("Emprendimiento")) {
      usuario.intereses.push("Emprendimiento");
    }
  }

  if (patrones.tonoEmocional > 0) {
    usuario.perfil.emocionalidad = "alta";
  }

  if (usuario.patrones.mencionesIA > 2) {
    usuario.perfil.nivelTecnico = "alto";
  }

  usuario.ultimaActualizacion = Date.now();

  memoriaPersistente.actualizarPorClave(
    userId,
    CLAVE_USUARIO,
    {
      usuario: usuario
    }
  );

  return usuario;
}

/**
 * Elimina solamente la ficha de datos del usuario.
 *
 * No elimina las demás memorias.
 */
function reset(userId) {
  if (!userId) {
    return false;
  }

  return memoriaPersistente.eliminarPorClave(
    userId,
    CLAVE_USUARIO
  );
}

export default {
  crear,
  obtener,
  actualizar,
  aprender,
  reset
};