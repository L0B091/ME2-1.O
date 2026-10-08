import path from "path";
import { fileURLToPath } from "url";

// Raíz de datos locales del backend. Por defecto backend/data (relativo al código, no al cwd).
// ME2_DATA_DIR permite aislarla (lo usan los tests para no tocar los datos reales); sin la variable no cambia nada.
const DEFAULT_DATA_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../data");

export const DATA_DIR = process.env.ME2_DATA_DIR ? path.resolve(process.env.ME2_DATA_DIR) : DEFAULT_DATA_DIR;
export default DATA_DIR;
