import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { DATA_DIR } from "./dataDir.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function ensureDir(...segments) {
  const dir = path.join(DATA_DIR, ...segments);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function sanitizeId(value = "anonimo") {
  return String(value).replace(/[^a-zA-Z0-9_-]/g, "_");
}

function ensureUserFile(namespace, userId, defaultData = []) {
  const dir = ensureDir(namespace);
  const filePath = path.join(dir, `${sanitizeId(userId)}.json`);
  if (!fs.existsSync(filePath)) {
    fs.writeFileSync(filePath, JSON.stringify(defaultData, null, 2), "utf8");
  }
  return filePath;
}

function readUserData(namespace, userId, defaultData = []) {
  const filePath = ensureUserFile(namespace, userId, defaultData);
  try {
    const raw = fs.readFileSync(filePath, "utf8").trim();
    if (!raw) {
      return structuredClone(defaultData);
    }
    return JSON.parse(raw);
  } catch {
    return structuredClone(defaultData);
  }
}

function writeUserData(namespace, userId, data) {
  const filePath = ensureUserFile(namespace, userId, Array.isArray(data) ? [] : {});
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2), "utf8");
  return filePath;
}

function readGlobalData(namespace, key, defaultData = {}) {
  const dir = ensureDir(namespace);
  const filePath = path.join(dir, `${sanitizeId(key)}.json`);
  if (!fs.existsSync(filePath)) {
    fs.writeFileSync(filePath, JSON.stringify(defaultData, null, 2), "utf8");
  }
  try {
    const raw = fs.readFileSync(filePath, "utf8").trim();
    return raw ? JSON.parse(raw) : structuredClone(defaultData);
  } catch {
    return structuredClone(defaultData);
  }
}

function writeGlobalData(namespace, key, data) {
  const dir = ensureDir(namespace);
  const filePath = path.join(dir, `${sanitizeId(key)}.json`);
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2), "utf8");
  return filePath;
}

export default {
  DATA_DIR,
  ensureDir,
  sanitizeId,
  readUserData,
  writeUserData,
  readGlobalData,
  writeGlobalData,
  ensureUserFile
};
