import test from "node:test";
import assert from "node:assert/strict";
import { detectarAlarma } from "../modulos/detectorAlarmas.js";

// 14:22:36 ART (17:22:36Z): "en 2 minutos" → 14:25 (redondeo al minuto siguiente, nunca antes de lo pedido).
const opts = { ahora: Date.parse("2026-10-08T17:22:36Z"), zonaHoraria: "America/Argentina/Buenos_Aires" };

test("alarma relativa: variantes es-AR de 'en 2 minutos' producen la hora local correcta", () => {
  for (const m of [
    "poné una alarma en 2 minutos", "programá una alarma dentro de 2 minutos", "alarma en dos minutos",
    "programa alarma para dentro de dos minutos", "poné el despertador en 2 minutos", "alarma de acá a 2 minutos",
    "poné una alarma 2 minutos más tarde"
  ]) {
    const r = detectarAlarma(m, opts);
    assert.equal(r?.accion, "crear", m);
    assert.equal(r.hora, "14:25", m);
  }
  assert.equal(detectarAlarma("despertame en un minuto", opts).hora, "14:24");
  assert.equal(detectarAlarma("alarma en una hora y media", opts).hora, "15:53");
});

test("alarma relativa: 'para 2 minutos' no se confunde con las 02:00; horas del reloj siguen igual", () => {
  assert.equal(detectarAlarma("programá una alarma para 2 minutos", opts).hora, "14:25");
  assert.equal(detectarAlarma("alarma para las 2", opts).hora, "02:00");
  assert.equal(detectarAlarma("poné una alarma a las 14:25", opts).hora, "14:25");
  assert.equal(detectarAlarma("despertame a las 7:30", opts).hora, "07:30");
});

test("pedido de alarma con plazo ilegible: se informa 'hora no entendida' (el LLM no puede confirmar una alarma inexistente)", () => {
  assert.deepEqual(detectarAlarma("alarma en un ratito de minutos", opts), { accion: "crear", hora: null, motivo: "hora_no_entendida" });
  assert.equal(detectarAlarma("qué alarmas tengo?", opts), null);
  assert.equal(detectarAlarma("recordame comprar pan en 5 minutos", opts), null);
});

test("reloj de ME2: el instante absoluto (epochMs) sale de la hora del servidor en la zona fija, nunca del teléfono", async () => {
  const { epochDeHora } = await import("../modulos/detectorAlarmas.js");
  // Relativa: exacta respecto de 'ahora' del servidor (redondeada al minuto siguiente).
  const r = detectarAlarma("poné una alarma en 2 minutos", { ...opts, zonaHoraria: "Europe/Madrid" });
  assert.equal(r.epochMs, Date.parse("2026-10-08T17:25:00Z"));
  assert.equal(r.hora, "14:25", "zona del teléfono ignorada");
  // Absoluta: próxima ocurrencia en Buenos Aires (hoy si todavía no pasó, si no mañana).
  assert.equal(detectarAlarma("alarma a las 14:30", opts).epochMs, Date.parse("2026-10-08T17:30:00Z"));
  assert.equal(detectarAlarma("despertame a las 7:30", opts).epochMs, Date.parse("2026-10-09T10:30:00Z"));
  assert.equal(epochDeHora("14:22", opts.ahora), Date.parse("2026-10-09T17:22:00Z"), "la hora actual ya pasó → mañana");
  assert.equal(epochDeHora("xx"), null);
});
