import test from "node:test";
import assert from "node:assert/strict";
import { camposFaltantes } from "../modulos/onboarding/perfilBasico.js";
import { interesCoincidente } from "../orquestador/contextoLLM.js";
import { evaluarIniciativa } from "../comportamiento/iniciativaConversacional.js";

test("onboarding pide un dato por vez en orden", () => {
  assert.deepEqual(camposFaltantes({}, null), ["nombre", "nombreAvatar", "ciudad"]);
  assert.deepEqual(camposFaltantes({ nombre: "Ema" }, "Nova"), ["ciudad"]);
  assert.deepEqual(camposFaltantes({ nombre: "Ema", ubicacion: { lat: 1 }, onboarding: {} }, "Nova"), ["confirmacionHora"]);
  assert.deepEqual(camposFaltantes({ nombre: "Ema", ubicacion: { lat: 1 }, onboarding: { horaConfirmada: true } }, "Nova"), []);
});

test("interés nombrado coincide con una palabra fuerte", () => {
  assert.equal(interesCoincidente("Boca vs. Banfield, semifinal de la Copa Argentina", ["boca juniors"]), "boca juniors");
  assert.equal(interesCoincidente("Reforma tributaria postergada", ["boca juniors", "rock"]), null);
});

test("noticia con interés nombrado inicia con una sola coincidencia", () => {
  const ahora = Date.parse("2026-10-02T20:00:00Z");
  const r = evaluarIniciativa({
    ahora, memoriaLocal: { recentConversation: [], persistentMemories: [{ text: "Le gusta: boca juniors", timestamp: ahora }], importantMemories: [] },
    perfilRitmo: { zonaHoraria: "America/Argentina/Buenos_Aires", ultimaInteraccion: ahora - 3 * 3600e3 },
    disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true },
    eventos: [{ categoria: "NOTICIA", fuente: "rss", referenciaEvento: "https://x/1", motivo: "m", timestamp: ahora - 60e3, expiresAt: ahora + 3600e3,
      contexto: { evidencia: "Boca vs. Banfield en la Copa", interesUsuario: "boca juniors" } }]
  });
  assert.equal(r.decision, "INICIAR");
});
