import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import storage from "../utils/jsonStorage.js";
import flujoPremium from "../modulos/premium/flujoPremium.js";
import adultMode from "../modulos/premium/adultMode.js";
import premiumManager from "../modulos/premium/premiumManager.js";
import verificacionEdad from "../auth/verificacionEdad.js";
import mercadoPago from "../api/mercadoPago.js";

const u = () => `test-premium-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
const link = async () => ({ url: "http://localhost/mock/checkout/p1", preferenceId: "p1", mock: true });
const libre = { premiumActivo: false };

test("oferta → rechazo respetado (sin texto armado, solo hechos)", async () => {
  const id = u();
  const a = await flujoPremium.procesar(id, "activá el modo adulto", { premium: libre, generarLink: link });
  assert.equal(a.evento, "oferta");
  assert.match(a.lineas.join("\n"), /BLOQUEADA.*\n.*Alcance de Premium.*\n.*mejoras todos los meses.*\n.*preguntarle si quiere suscribirse/s);
  const b = await flujoPremium.procesar(id, "no, gracias", { premium: libre, generarLink: link });
  assert.equal(b.evento, "rechazado");
  assert.equal(flujoPremium.obtener(id).estado, null);
});

test("aceptar → sin fecha en Google bloquea; menor rechaza; mayor + confirmación genera link", async () => {
  const sin = u();
  await flujoPremium.procesar(sin, "quiero usar el gestor fiscal", { premium: libre, generarLink: link });
  const s1 = await flujoPremium.procesar(sin, "sí, dale", { premium: libre, generarLink: link });
  assert.equal(s1.evento, "edad_sin_dato");
  assert.match(s1.lineas.join(" "), /cuenta de Google NO tiene fecha/);

  const menor = u();
  verificacionEdad.guardar(menor, "2012-05-01", "test");
  await flujoPremium.procesar(menor, "activá el modo adulto", { premium: libre, generarLink: link });
  const m1 = await flujoPremium.procesar(menor, "sí", { premium: libre, generarLink: link });
  assert.equal(m1.evento, "edad_menor");
  const m2 = await flujoPremium.procesar(menor, "activá el modo adulto", { premium: libre, generarLink: link });
  assert.equal(m2.evento, "bloqueado_edad");
  assert.equal(m2.link, null);

  const mayor = u();
  verificacionEdad.guardar(mayor, "1990-01-15", "test");
  await flujoPremium.procesar(mayor, "activá el modo adulto", { premium: libre, generarLink: link });
  assert.equal((await flujoPremium.procesar(mayor, "sí", { premium: libre, generarLink: link })).evento, "edad_mayor");
  assert.equal((await flujoPremium.procesar(mayor, "sí", { premium: libre, generarLink: link })).link, null, "“sí” solo no confirma 18+");
  const ok = await flujoPremium.procesar(mayor, "confirmo, soy mayor de 18", { premium: libre, generarLink: link });
  assert.equal(ok.evento, "link_generado");
  assert.match(ok.lineas.join(" "), /http:\/\/localhost\/mock\/checkout\/p1/);
});

test("palabra clave: solo hash en disco, se entrega una vez, desbloquea solo la sesión", async () => {
  const id = u();
  premiumManager.activarPremium(id, "test-pay", { dev: true });
  const en = adultMode.habilitarExtension(id);
  assert.match(en.keyword, /^\S+ \S+$/);
  const disco = fs.readFileSync(`${storage.DATA_DIR}/adult_mode/${id}.json`, "utf8");
  assert.ok(!disco.includes(en.keyword) && JSON.parse(disco).keywordHash);
  flujoPremium.alActivarPremium(id, en.keyword);
  const t1 = await flujoPremium.procesar(id, "hola", { premium: { premiumActivo: true } });
  assert.equal(t1.evento, "keyword_entregada");
  assert.ok(t1.lineas.join(" ").includes(en.keyword));
  assert.equal((await flujoPremium.procesar(id, "hola", { premium: { premiumActivo: true } })).lineas.length, 0, "no se repite");
  const p = { premiumActivo: true };
  const sinKw = await adultMode.procesarEnChat(id, "quiero sexo con vos", { premium: p });
  assert.equal(sinKw.evento, "bloqueado_sin_keyword");
  assert.equal(sinKw.adult.unlocked, false);
  const ahora = Date.now();
  const conKw = await adultMode.procesarEnChat(id, `${en.keyword.toUpperCase()}`, { premium: p, ahora });
  assert.equal(conKw.evento, "desbloqueado_con_keyword");
  assert.equal((await adultMode.procesarEnChat(id, "seguimos", { premium: p, ahora: ahora + 60e3 })).adult.unlocked, true);
  const vencida = await adultMode.procesarEnChat(id, "quiero sexo", { premium: p, ahora: ahora + 5 * 3600e3 });
  assert.equal(vencida.evento, "bloqueado_sin_keyword", "la sesión adulta vence");
  adultMode.bloquearSesion(id);
});

test("Mercado Pago mock: pago aprobado activa Premium y deja palabra clave pendiente", async () => {
  const prev = process.env.MERCADO_PAGO_ACCESS_TOKEN;
  delete process.env.MERCADO_PAGO_ACCESS_TOKEN;
  const id = u();
  storage.writeUserData("mercadopago_mock", "mock-pref-test1", { preferenceId: "mock-pref-test1", userId: id, feature: "Modo Adulto", estado: "pendiente" });
  await assert.rejects(() => mercadoPago.pagarMock("mock-pref-test1", "approved", "otro-usuario"), { status: 403 });
  const r = await mercadoPago.pagarMock("mock-pref-test1", "approved", id);
  assert.equal(r.premiumActivo, true);
  assert.equal(premiumManager.obtenerEstado(id).premiumActivo, true);
  assert.ok(flujoPremium.obtener(id).keywordPendiente);
  if (prev) process.env.MERCADO_PAGO_ACCESS_TOKEN = prev;
});

test("intentos Premium detectados (no confunde charla común)", () => {
  assert.equal(flujoPremium.detectarIntento("quiero guardar mi código"), "Gestor de código");
  assert.equal(flujoPremium.detectarIntento("activá el respaldo en la nube"), "Respaldo en la nube");
  assert.equal(flujoPremium.detectarIntento("me gusta el código limpio"), null);
  assert.equal(flujoPremium.detectarIntento("hola, ¿cómo andás?"), null);
});
