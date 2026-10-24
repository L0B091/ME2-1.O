import test from "node:test";
import assert from "node:assert/strict";
import reaccionAudiovisual, { REACCIONES_V1, INTENSIDADES_V1 } from "../modulos/media/reaccionAudiovisual.js";
import protocoloDespertador, { ESPERA_ENTRE_INTENTOS_MS } from "../modulos/protocoloDespertador.js";

test("audiovisual: siempre categoría REACCION V1 + intensidad NORMAL/MEDIO/MAXIMO, sin archivos", () => {
  for (const mensaje of ["jajaja", "estoy triste", "qué hora es?", "TE ODIO!!!", ""]) {
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
  assert.equal(reaccionAudiovisual.decidir({ mensaje: "qué es eso?" }).intensidad, "NORMAL");
});

test("audiovisual v4: SALUDO al saludar, DESPEDIDA al despedirse", () => {
  for (const m of ["hola", "Hola ME2!", "buen día", "buenas tardes"]) {
    assert.deepEqual(reaccionAudiovisual.decidir({ mensaje: m }).categoria, "SALUDOS", m);
    assert.equal(reaccionAudiovisual.decidir({ mensaje: m }).subcategoria, "SALUDO", m);
  }
  for (const m of ["chau", "bueno me voy, nos vemos", "hasta mañana", "adiós!"]) {
    assert.equal(reaccionAudiovisual.decidir({ mensaje: m }).subcategoria, "DESPEDIDA", m);
  }
  assert.equal(reaccionAudiovisual.saludoDe("hola, te cuento que mañana tengo un examen muy difícil de matemática y estoy nerviosa"), null);
});

test("audiovisual v4: emociones nuevas se pueden disparar", () => {
  const casos = { "muchas gracias": "GRATITUD", "qué tierno ese gatito": "TERNURA", "no veo la hora de viajar": "ENTUSIASMO",
    "me acuerdo cuando era chica": "NOSTALGIA", "es un secreto, no le digas a nadie": "PICARDIA", "qué locura, impresionante": "ASOMBRO" };
  for (const [m, sub] of Object.entries(casos)) {
    const r = reaccionAudiovisual.decidir({ mensaje: m });
    assert.equal(r.subcategoria, sub, m);
    assert.ok(REACCIONES_V1.includes(sub));
  }
});

test("audiovisual v4: tipeo por largo de respuesta y variantes al azar (sin archivos)", () => {
  assert.equal(reaccionAudiovisual.escribiendoPara("Sí, claro."), "ESCRIBIENDO_RAPIDO");
  assert.equal(reaccionAudiovisual.escribiendoPara("x".repeat(150)), "ESCRIBIENDO_MEDIO");
  assert.equal(reaccionAudiovisual.escribiendoPara("x".repeat(400)), "ESCRIBIENDO_LENTO");
  const vistos = { esperando: new Set(), pensando: new Set(), neutral: new Set() };
  let seed = 1;
  const random = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  for (let i = 0; i < 200; i++) {
    const c = reaccionAudiovisual.conversacion({ respuesta: "hola", random });
    for (const k of Object.keys(vistos)) vistos[k].add(c[k]);
    assert.equal(JSON.stringify(c).includes(".mp4"), false);
  }
  assert.equal(vistos.esperando.size, 3);
  assert.equal(vistos.pensando.size, 3);
  assert.equal(vistos.neutral.size, 5);
  assert.equal(vistos.neutral.has("NEUTRAL_003"), false);
});

test("despertador: 3 intentos, 5 minutos entre intentos, el tercero es alarma", () => {
  const stages = protocoloDespertador.obtenerDefinicionStages();
  assert.equal(stages.length, 3);
  assert.equal(ESPERA_ENTRE_INTENTOS_MS, 5 * 60 * 1000);
  assert.deepEqual(stages.map(s => s.delayToNextStageMs), [ESPERA_ENTRE_INTENTOS_MS, ESPERA_ENTRE_INTENTOS_MS, 0]);
  assert.deepEqual(stages.map(s => s.notificationType), ["message", "message", "alarm"]);
  assert.deepEqual(stages.map(s => s.vibration), ["double", "double", "alarm"]);
});
