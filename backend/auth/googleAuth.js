import verificacionEdad from "./verificacionEdad.js";
import { OAuth2Client } from "google-auth-library";
import login from "./login.js";
import usuariosMemoria from "../memoria/usuariosMemoria.js";
import datosUsuario from "../memoria/datosUsuario.js";
import HttpError from "../utils/httpError.js";

function getGoogleAudiences() {
  return String(process.env.GOOGLE_CLIENT_ID || "")
    .split(",")
    .map(item => item.trim())
    .filter(Boolean);
}

function googleAuthEnabled() {
  // Product path: Google is the only login. Default ON unless explicitly disabled.
  const raw = String(process.env.GOOGLE_AUTH_ENABLED ?? "true").trim().toLowerCase();
  return raw !== "false" && raw !== "0" && raw !== "off";
}

async function verificarIdToken(idToken) {
  const audiences = getGoogleAudiences();
  if (audiences.length === 0) {
    throw new HttpError(503, "GOOGLE_CLIENT_ID no está configurado");
  }

  const client = new OAuth2Client();
  const ticket = await client.verifyIdToken({
    idToken,
    audience: audiences
  });

  const payload = ticket.getPayload();
  if (!payload?.email || !payload?.sub) {
    throw new HttpError(401, "Token de Google inválido");
  }

  return payload;
}

function emailVerificado(payload = {}) {
  return payload.email_verified === true || String(payload.email_verified).toLowerCase() === "true";
}

/**
 * Vincula la identidad de Google con el registro de usuario.
 * - Exige email_verified (sin eso el email no prueba titularidad).
 * - Solo se reutiliza un registro que ya pertenece a ESTE googleId. Un registro previo con el mismo email creado por
 *   login local (sin googleId) NO se fusiona: se reemplaza por uno nuevo (id nuevo, sin contraseña), para evitar el
 *   pre-secuestro de cuenta (alguien registra el email ajeno con contraseña antes del primer login con Google).
 * - Nunca se conservan passwordHash/passwordSalt en una cuenta Google.
 */
export function sincronizarPerfil(payload) {
  if (!emailVerificado(payload)) {
    throw new HttpError(403, "El email de la cuenta de Google no está verificado");
  }
  const email = usuariosMemoria.normalizeEmail(payload.email);
  const existentePorGoogleId = usuariosMemoria.obtenerUsuarioPorGoogleId(payload.sub);
  const existentePorEmail = usuariosMemoria.obtenerUsuario(email);
  if (existentePorEmail?.googleId && existentePorEmail.googleId !== payload.sub) {
    throw new HttpError(409, "El email ya está vinculado a otra cuenta de Google");
  }
  const propio = existentePorGoogleId || (existentePorEmail?.googleId === payload.sub ? existentePorEmail : null);
  const perfilGoogle = {
    email,
    googleId: payload.sub,
    tipoLogin: "google",
    passwordHash: null,
    passwordSalt: null,
    displayName: payload.name || propio?.displayName || payload.email,
    photoUrl: payload.picture || propio?.photoUrl || null,
    emailVerified: true
  };

  let usuario;
  if (propio) {
    usuario = usuariosMemoria.guardarUsuario(email, { ...propio, ...perfilGoogle });
  } else {
    if (existentePorEmail) {
      console.warn("[googleAuth] registro local previo con el mismo email descartado (no se fusiona con Google)");
    }
    usuario = usuariosMemoria.reemplazarUsuario(email, perfilGoogle);
  }

  datosUsuario.actualizar(usuario.id, {
    identidad: {
      nombre: payload.name || usuario.displayName,
      apodo: propio?.displayName || payload.given_name || payload.name || null
    },
    cuentas: {
      email,
      googleId: payload.sub
    }
  });

  return usuario;
}

async function autenticarConGoogle(idToken, serverAuthCode = null) {
  if (!googleAuthEnabled()) {
    throw new HttpError(503, "Google Auth desactivada en Beta");
  }
  if (!idToken || typeof idToken !== "string") {
    throw new HttpError(400, "idToken de Google requerido");
  }

  let payload;
  try {
    payload = await verificarIdToken(idToken);
  } catch (error) {
    if (error instanceof HttpError) {
      throw error;
    }

    throw new HttpError(401, "No se pudo validar el token de Google");
  }

  const usuario = sincronizarPerfil(payload);
  const sesion = login.iniciarSesionParaUsuario(usuario.email);
  // Fecha de nacimiento (People API) para la verificación de edad de Premium.
  try {
    await verificacionEdad.sincronizarDesdeLogin(usuario.id, serverAuthCode);
  } catch (error) {
    console.error("[googleAuth] cumpleaños no disponible:", error.message);
  }

  if (!sesion.ok) {
    throw new HttpError(500, sesion.error || "No se pudo iniciar la sesión");
  }

  return {
    token: sesion.token,
    expiraEn: sesion.expiraEn,
    perfil: {
      userId: usuario.id,
      email: usuario.email,
      displayName: usuario.displayName,
      photoUrl: usuario.photoUrl,
      emailVerified: usuario.emailVerified
    }
  };
}

function validarToken(token) {
  return login.validarToken(token);
}

function sesionesActivas() {
  return login.sesionesActivas();
}

export default {
  autenticarConGoogle,
  validarToken,
  sesionesActivas,
  googleAuthEnabled
};
