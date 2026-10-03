import test from "node:test";
import assert from "node:assert/strict";
import reaccionAudiovisual, { REACCIONES_V1, INTENSIDADES_V1 } from "../modulos/media/reaccionAudiovisual.js";
import protocoloDespertador, { ESPERA_ENTRE_INTENTOS_MS } from "../modulos/protocoloDespertador.js";

test("audiovisual: siempre categoría REACCION V1 + intensidad NORMAL/MEDIO/MAXIMO, sin archivos", () => {
  for (const mensaje of ["hola", "jajaja", "estoy triste", "qué hora es?", "TE ODIO!!!", ""]) {
    const r = reaccionAudiovisual.decidir({ mensaje });
    assert.equal(r.categoria, "REACCION");
    assert.ok(REACCIONES_V1.includes(r.subcategoria), r.subcategoria);
    assert.ok(INTENSIDADES_V1.includes(r.intensidad), r.intensidad);
    assert.equal(JSON.stringify(r).includes(".mp4"), false);
  }
});

test("audiovisual: mapea emoción del usuario e intensidad por énfasis", () => {
  assert.equal(reaccionAudiovisual.decidir({ mensaje: "jajaja" }).subcategoria, "RISAS");
  assert.equal(reaccionAudiovisual.decidir({ mensaje: "JAJAJAJAJAJA!!!" }).intensidad, "MAXIMO");
  assert.equal(reaccionAudiovisual.decidir({ mensaje: "estoy triste" }).subcategoria, "EMPATIA");
  assert.equal(reaccionAudiovisual.decidir({ mensaje: "no entiendo" }).subcategoria, "CONFUSION");
  assert.equal(reaccionAudiovisual.decidir({ mensaje: "qué es eso?" }).subcategoria, "CURIOSIDAD");
  assert.equal(reaccionAudiovisual.decidir({ mensaje: "por fin terminé!!" }).intensidad, "MEDIO");
  assert.equal(reaccionAudiovisual.decidir({ mensaje: "hola" }).intensidad, "NORMAL");
});

test("despertador: 3 intentos, 5 minutos entre intentos, el tercero es alarma", () => {
  const stages = protocoloDespertador.obtenerDefinicionStages();
  assert.equal(stages.length, 3);
  assert.equal(ESPERA_ENTRE_INTENTOS_MS, 5 * 60 * 1000);
  assert.deepEqual(stages.map(s => s.delayToNextStageMs), [ESPERA_ENTRE_INTENTOS_MS, ESPERA_ENTRE_INTENTOS_MS, 0]);
  assert.deepEqual(stages.map(s => s.notificationType), ["message", "message", "alarm"]);
  assert.deepEqual(stages.map(s => s.vibration), ["double", "double", "alarm"]);
});
