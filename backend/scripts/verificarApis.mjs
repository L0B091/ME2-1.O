// Verifica las API keys del entorno con 1 llamada real cada una (nunca imprime las keys).
// Uso: node scripts/verificarApis.mjs  → JSON con proveedor detectado y estado HTTP.
import { detectarProveedorNoticias } from "../api/noticias.js";
import { detectarProveedorClima } from "../api/clima.js";
const out = { fecha: new Date().toISOString(), hora: "sin API externa: Intl + TZ del sistema / Open-Meteo timezone" };
out.noticias = process.env.NEWS_API_KEY ? await detectarProveedorNoticias({ forzar: true }) : { configurado: false };
out.clima = (process.env.WEATHER_API_KEY || process.env.OPENWEATHER_API_KEY) ? await detectarProveedorClima({ forzar: true }) : { configurado: false };
console.log(JSON.stringify(out, null, 2));
