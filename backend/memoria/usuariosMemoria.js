import crypto from "crypto";
import storage from "../utils/jsonStorage.js";

const NAMESPACE = "usuariosAuth";
const KEY = "usuarios";

function readUsers() {
  return storage.readGlobalData(NAMESPACE, KEY, {});
}

function writeUsers(users) {
  storage.writeGlobalData(NAMESPACE, KEY, users);
  return users;
}

function normalizeEmail(email = "") {
  return String(email).trim().toLowerCase();
}

function normalizeUser(email, user = {}) {
  const normalizedEmail = normalizeEmail(email || user.email || "");
  const now = new Date().toISOString();
  return {
    id: typeof user.id === "string" && user.id.trim() ? user.id : crypto.randomUUID(),
    email: normalizedEmail,
    passwordHash: typeof user.passwordHash === "string" ? user.passwordHash : null,
    passwordSalt: typeof user.passwordSalt === "string" ? user.passwordSalt : null,
    tipoLogin: typeof user.tipoLogin === "string" ? user.tipoLogin : "local",
    googleId: typeof user.googleId === "string" ? user.googleId : null,
    displayName: typeof user.displayName === "string" ? user.displayName : null,
    photoUrl: typeof user.photoUrl === "string" ? user.photoUrl : null,
    leyenda: typeof user.leyenda === "string" ? user.leyenda : "",
    emailVerified: Boolean(user.emailVerified),
    premiumUntil: typeof user.premiumUntil === "string" ? user.premiumUntil : null,
    backupMaterial: typeof user.backupMaterial === "string" ? user.backupMaterial : null,
    createdAt: typeof user.createdAt === "string" ? user.createdAt : now,
    updatedAt: now,
    lastLoginAt: typeof user.lastLoginAt === "string" ? user.lastLoginAt : null
  };
}

function guardarUsuario(email, userData = {}) {
  const normalizedEmail = normalizeEmail(email || userData.email);
  if (!normalizedEmail) {
    return null;
  }

  const users = readUsers();
  const existing = users[normalizedEmail] || {};
  const merged = normalizeUser(normalizedEmail, {
    ...existing,
    ...userData,
    email: normalizedEmail,
    id: existing.id || userData.id,
    createdAt: existing.createdAt || userData.createdAt,
    updatedAt: new Date().toISOString()
  });

  users[normalizedEmail] = merged;
  writeUsers(users);
  return merged;
}

/** Reemplaza el registro del email SIN fusionar con el existente (id nuevo si no se pasa uno). */
function reemplazarUsuario(email, userData = {}) {
  const normalizedEmail = normalizeEmail(email || userData.email);
  if (!normalizedEmail) return null;
  const users = readUsers();
  const nuevo = normalizeUser(normalizedEmail, { ...userData, email: normalizedEmail, updatedAt: new Date().toISOString() });
  users[normalizedEmail] = nuevo;
  writeUsers(users);
  return nuevo;
}

function obtenerUsuario(email) {
  const normalizedEmail = normalizeEmail(email);
  if (!normalizedEmail) return null;
  const users = readUsers();
  return users[normalizedEmail] || null;
}

function obtenerUsuarioPorGoogleId(googleId) {
  if (!googleId) return null;
  const users = Object.values(readUsers());
  return users.find(user => user?.googleId === googleId) || null;
}

function obtenerUsuarioPorId(userId) {
  if (!userId) return null;
  const users = Object.values(readUsers());
  return users.find(user => user?.id === userId) || null;
}

function actualizarUltimoLogin(email) {
  const user = obtenerUsuario(email);
  if (!user) return null;
  return guardarUsuario(email, {
    ...user,
    lastLoginAt: new Date().toISOString()
  });
}

function listarUsuarios() {
  return Object.values(readUsers());
}

function obtenerOMaterializarBackupMaterial(userId) {
  if (!userId) return null;
  const user = obtenerUsuarioPorId(userId);
  if (!user) return null;
  if (typeof user.backupMaterial === "string" && user.backupMaterial.trim()) {
    return user.backupMaterial;
  }
  const backupMaterial = crypto.randomBytes(32).toString("hex");
  guardarUsuario(user.email, {
    ...user,
    backupMaterial
  });
  return backupMaterial;
}

export default {
  guardarUsuario,
  reemplazarUsuario,
  obtenerUsuario,
  obtenerUsuarioPorGoogleId,
  obtenerUsuarioPorId,
  actualizarUltimoLogin,
  listarUsuarios,
  obtenerOMaterializarBackupMaterial,
  normalizeEmail
};
