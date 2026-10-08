// Corre la suite con un entorno hermético: sin credenciales reales del shell (Mercado Pago, LLM, Google)
// y con un directorio de datos temporal (ME2_DATA_DIR), así nunca lee ni escribe backend/data.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "me2-test-data-"));
const env = { ...process.env, ME2_DATA_DIR: dataDir };
const SENSIBLES = /^(MERCADO_PAGO_|GROQ_|DOLPHIN_|OLLAMA_|GOOGLE_CLIENT_SECRET$|BACKEND_PUBLIC_URL$|NODE_ENV$)/;
for (const k of Object.keys(env)) if (SENSIBLES.test(k)) delete env[k];

let status = 1;
try {
  const r = spawnSync(process.execPath, ["--test", "--test-concurrency=1", ...process.argv.slice(2)], { cwd: backendDir, env, stdio: "inherit" });
  status = r.status ?? 1;
} finally {
  fs.rmSync(dataDir, { recursive: true, force: true });
}
process.exit(status);
