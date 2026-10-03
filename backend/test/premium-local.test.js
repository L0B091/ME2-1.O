import test from "node:test";
import assert from "node:assert/strict";
import gestorFiscal from "../modulos/premium/gestorFiscal.js";
import gestorProyectos from "../modulos/premium/gestorProyectos.js";
import premiumLocal from "../modulos/premium/premiumLocal.js";
import premiumManager from "../modulos/premium/premiumManager.js";
import backupManager from "../modulos/premium/backupManager.js";
import flujoPremium from "../modulos/premium/flujoPremium.js";

const AHORA = Date.parse("2026-10-02T22:00:00-03:00");
const uid = (t) => `test_${t}_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;

test("gestor fiscal: registra, resume, vencimientos y pagado (estado puro)", () => {
  let e = gestorFiscal.estadoBase();
  for (const m of ["mi categoría de monotributo es C", "Registrá factura emitida de $50.000 a Juan Pérez por diseño web",
    "registrá otra factura emitida por 25 mil el 01/10", "me facturaron 12 mil de luz, guardá el comprobante",
    "El vencimiento del monotributo es el 20/10", "vence IIBB el 15 por 8.500", "nota fiscal: pedir constancia a ARCA", "ya pagué IIBB"]) {
    ({ estado: e } = gestorFiscal.aplicar(e, gestorFiscal.detectar(m, AHORA), AHORA));
  }
  const s = gestorFiscal.resumen(e, AHORA);
  assert.equal(s.categoria, "C");
  assert.equal(s.facturadoMes, 75000);
  assert.equal(s.gastosMes, 12000);
  assert.deepEqual(s.proximos.map(v => v.concepto), ["monotributo"]);
  const l = gestorFiscal.lineas(e, { op: "consulta" }, AHORA).join("\n");
  assert.match(l, /facturado este mes \$75\.000 \(2 facturas emitidas\)/);
  assert.match(l, /20\/10\/2026 monotributo/);
  assert.match(l, /no inventar cifras/);
  const falta = gestorFiscal.aplicar(e, gestorFiscal.detectar("registrá una factura emitida a Pedro", AHORA), AHORA).resultado;
  assert.equal(falta.ok, false); assert.equal(falta.falta, "monto");
  assert.equal(gestorFiscal.detectar("mañana vence mi pasaporte", AHORA), null);
});

test("proyectos: mini-repo con archivo, snapshot, diff, restauración y ver", () => {
  let e = gestorProyectos.estadoBase(); let r;
  const run = (m) => ({ estado: e, resultado: r } = gestorProyectos.aplicar(e, gestorProyectos.detectar(m), AHORA));
  run("creá un proyecto llamado bot-clima");
  run("guardá el archivo main.py\n```python\nprint('hola')\n```");
  run("guardá una versión: inicial"); assert.equal(r.version, 1);
  run("guardá el archivo main.py\n```python\nprint('chau')\nx = 1\n```");
  assert.deepEqual(r.cambios, ["- print('hola')", "+ print('chau')", "+ x = 1"]);
  run("snapshot del proyecto bot-clima: cambio saludo"); assert.equal(r.version, 2);
  run("diff del proyecto bot-clima v1 y v2"); assert.equal(r.cambios[0].ruta, "main.py");
  assert.match(gestorProyectos.lineas(e, r).join("\n"), /\+ print\('chau'\)/);
  run("restaurá la versión 1"); assert.equal(e.proyectos["bot-clima"].archivos["main.py"], "print('hola')");
  run("mostrame el archivo main.py"); assert.equal(r.contenido, "print('hola')");
  run("guardá una versión"); assert.equal(r.version, 3);
  run("guardá una versión"); assert.equal(r.ok, false); assert.equal(r.motivo, "sin_cambios");
  run("mis proyectos"); assert.deepEqual(r.proyectos, [{ nombre: "bot-clima", archivos: 1, versiones: 3 }]);
});

test("premiumLocal: Free no opera; en teléfono no guarda en servidor y devuelve estado; sin teléfono persiste en JSON", () => {
  const u = uid("pl");
  assert.equal(premiumLocal.procesar(u, "registrá factura emitida por 1000", { premiumActivo: false }), null);
  const tel = premiumLocal.procesar(u, "registrá factura emitida por 1000", { premiumActivo: true, enTelefono: true, local: { fiscal: null } });
  assert.equal(tel.persistidoEn, "telefono"); assert.equal(tel.estado.comprobantes.length, 1);
  assert.equal(premiumLocal.obtenerEstadoServidor(u, "fiscal").comprobantes.length, 0);
  const tel2 = premiumLocal.procesar(u, "registrá factura emitida por 2000", { premiumActivo: true, enTelefono: true, local: { fiscal: tel.estado } });
  assert.equal(tel2.estado.comprobantes.length, 2);
  premiumLocal.procesar(u, "creá un proyecto llamado demo", { premiumActivo: true });
  assert.ok(premiumLocal.obtenerEstadoServidor(u, "proyectos").proyectos.demo);
});

test("respaldo: continuidad (cuándo/dónde) tras restaurar, una sola vez", async () => {
  const u = uid("bk");
  const ultima = AHORA - 3 * 24 * 3600e3;
  await backupManager.guardarBackup(u, { ownerHash: "h", iv: "aXY=", ciphertext: "Yw==", continuidad: { ultimaInteraccionAt: ultima, lugar: { ciudad: "Rosario", zonaHoraria: "America/Argentina/Buenos_Aires" } } });
  assert.deepEqual(await backupManager.lineasContinuidad(u, { ahora: AHORA }), []);
  const r = await backupManager.restaurarBackup(u, { dispositivo: "pixel-nuevo", ahora: AHORA });
  assert.equal(r.continuidad.lugar.ciudad, "Rosario");
  const l = (await backupManager.lineasContinuidad(u, { ahora: AHORA })).join("\n");
  assert.match(l, /RESTAURÓ en un teléfono nuevo/);
  assert.match(l, /hace 3 días, cuando el usuario estaba en Rosario/);
  assert.deepEqual(await backupManager.lineasContinuidad(u, { ahora: AHORA }), []);
});

test("flujo: primera pregunta por Premium trae el alcance completo; la siguiente, el resumen", async () => {
  const u = uid("alc");
  const a = await flujoPremium.procesar(u, "¿qué incluye premium?", { premium: { premiumActivo: false }, ahora: AHORA });
  const t = a.lineas.join("\n");
  assert.equal(a.evento, "oferta");
  for (const re of [/Respaldo de memoria en la nube/, /monotributista/, /proyectos de programación/, /Modo Adulto/]) assert.match(t, re);
  await flujoPremium.procesar(u, "no, gracias", { premium: { premiumActivo: false }, ahora: AHORA + 1000 });
  const b = await flujoPremium.procesar(u, "quiero premium", { premium: { premiumActivo: false }, ahora: AHORA + 2000 });
  assert.ok(!b.lineas.join("\n").includes("primera vez"));
  assert.equal(flujoPremium.detectarIntento("registrá factura emitida por 5000"), "Gestor Fiscal");
  assert.equal(flujoPremium.detectarIntento("creá un proyecto llamado api"), "Proyectos de programación");
  assert.ok(premiumManager.ALCANCE_PREMIUM.length === 4);
});
