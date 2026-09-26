process.env.BETA_PREMIUM_DEFAULT = "false";
process.env.NODE_ENV = "production";
import adultMode from "../modulos/premium/adultMode.js";
import premiumManager from "../modulos/premium/premiumManager.js";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const userId = `test_adult_${Date.now()}`;
const dataFile = path.resolve(__dirname, `../data/adult_mode/${userId}.json`);
const premFile = path.resolve(__dirname, `../data/premium/${userId}.json`);

premiumManager.activarPremium(userId, "pay_test", { feature: "Modo Adulto" });
adultMode.habilitarExtension(userId);

let r = await adultMode.procesarEnChat(userId, "hola como estas", { intentarCheckout: false });
console.log("1 normal", { intercept: r.intercept, phase: r.adult?.phase, unlocked: r.adult?.unlocked });

r = await adultMode.procesarEnChat(userId, "quiero sexo contigo", { intentarCheckout: false });
console.log("2 sex no kw", { intercept: r.intercept, slice: (r.respuesta||"").slice(0,70) });

const st = adultMode.obtenerRegistro(userId);
const kw = st.keyword;
console.log("3 keyword", kw);

r = await adultMode.procesarEnChat(userId, `mi palabra es ${kw}`, { intentarCheckout: false });
console.log("4 unlock", { intercept: r.intercept, unlocked: r.adult?.unlocked, intensity: r.adult?.intensity });

r = await adultMode.procesarEnChat(userId, "besame", { intentarCheckout: false });
console.log("5 soft escalate", { intensity: r.adult?.intensity, allow: r.adult?.allowAdultTone });

r = await adultMode.procesarEnChat(userId, "quiero sexo oral", { intentarCheckout: false });
console.log("6 no jump to max", { intensity: r.adult?.intensity });

r = await adultMode.procesarEnChat(userId, "seguir", { intentarCheckout: false });
r = await adultMode.procesarEnChat(userId, "quiero sexo oral", { intentarCheckout: false });
r = await adultMode.procesarEnChat(userId, "masturbacion", { intentarCheckout: false });
console.log("7 gradual", { intensity: r.adult?.intensity, turns: adultMode.obtenerRegistro(userId).adultTurnCount });

r = await adultMode.procesarEnChat("free_user_fresh", "quiero sexo", { intentarCheckout: false, premium: { premiumActivo: false } });
console.log("8 free", { intercept: r.intercept, needsCheckout: r.needsCheckout, premium: (r.respuesta||"").includes("Premium") });

// cleanup
for (const f of [dataFile, premFile]) {
  try { fs.unlinkSync(f); } catch {}
}
