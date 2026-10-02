import test from "node:test";
import assert from "node:assert/strict";
import formatoAdulto from "../modulos/media/formatoAdulto.js";
import storage from "../utils/jsonStorage.js";

const cats = [{ nombre: "xxx", adulto: true, items: [
  { id: "g_beso1", tipo: "gif", tags: ["beso"], intensidad: "soft_flirt" },
  { id: "g_beso2", tipo: "gif", tags: ["beso"], intensidad: "soft_flirt" },
  { id: "g_guino", tipo: "gif", tags: ["guiño"], intensidad: "soft_flirt" }
] }];
const activo = { premiumActivo: true, unlocked: true, intensity: "soft_flirt" };
const nuevo = () => `test-fmt-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;

test("pista del LLM: se parsea y se quita del texto", () => {
  const p = formatoAdulto.parsearPista("Mirá esto [[media: tags=beso, guiño; formato=texto+gif]]");
  assert.equal(p.texto, "Mirá esto");
  assert.deepEqual(p.pista, { formato: "texto+gif", tags: ["beso", "guiño"] });
  assert.equal(formatoAdulto.parsearPista("hola").pista, null);
});

test("fuera del modo adulto siempre texto (aunque el LLM pida gif)", () => {
  for (const adult of [null, { premiumActivo: true, unlocked: false }]) {
    const r = formatoAdulto.decidir({ respuesta: "ok [[media: tags=beso; formato=gif]]", adult, catalogos: cats, persistir: false });
    assert.equal(r.formato, "texto"); assert.equal(r.gif, null); assert.equal(r.texto, "ok");
  }
});

test("elección por contexto: conversacional → texto; pedido de acto → gif; gif + línea corta → texto+gif", () => {
  const u = nuevo();
  assert.equal(formatoAdulto.decidir({ userId: u, respuesta: "¿Cómo estuvo tu día?", adult: activo, catalogos: cats }).formato, "texto");
  const g = formatoAdulto.decidir({ userId: u, respuesta: "[[media: tags=beso; formato=gif]]", adult: activo, catalogos: cats });
  assert.equal(g.formato, "gif"); assert.equal(g.texto, ""); assert.match(g.gif.id, /^g_beso/);
  const tg = formatoAdulto.decidir({ userId: u, respuesta: "Para vos 😉 [[media: tags=guiño; formato=texto+gif]]", adult: activo, catalogos: cats });
  assert.equal(tg.formato, "texto+gif"); assert.equal(tg.texto, "Para vos 😉"); assert.equal(tg.gif.id, "g_guino");
  storage.writeUserData("media_adulto", u, {});
});

test("sin GIF que coincida → fallback a texto", () => {
  const r = formatoAdulto.decidir({ userId: nuevo(), respuesta: "Te leo [[media: tags=volcan; formato=gif]]", adult: activo, catalogos: cats, persistir: false });
  assert.equal(r.formato, "texto"); assert.equal(r.motivo, "sin_gif_coincidente"); assert.equal(r.texto, "Te leo");
  const vacio = formatoAdulto.decidir({ respuesta: "x [[media: tags=beso; formato=gif]]", adult: activo, catalogos: [{ nombre: "xxx", adulto: true, items: [] }], persistir: false });
  assert.equal(vacio.formato, "texto");
});

test("no repite el mismo GIF en los últimos N y limita gif-solo seguidos", () => {
  const u = nuevo();
  const ids = [];
  const formatos = [];
  for (let i = 0; i < 4; i++) {
    const r = formatoAdulto.decidir({ userId: u, respuesta: "dale [[media: tags=beso; formato=gif]]", adult: activo, catalogos: cats });
    formatos.push(r.formato); ids.push(r.gif?.id || null);
  }
  assert.notEqual(ids[0], ids[1], "no repite");
  assert.equal(ids[2], null, "sin GIFs nuevos de 'beso' → texto");
  assert.deepEqual(formatos.slice(0, 2), ["gif", "gif"]);
  const u2 = nuevo();
  storage.writeUserData("media_adulto", u2, { ultimosGif: [], gifSoloSeguidos: formatoAdulto.POLITICA.maxGifSoloSeguidos });
  const lim = formatoAdulto.decidir({ userId: u2, respuesta: "ahí va [[media: tags=guiño; formato=gif]]", adult: activo, catalogos: cats });
  assert.equal(lim.formato, "texto+gif", "tras varios gif-solo seguidos, lleva texto");
  [u, u2].forEach(id => storage.writeUserData("media_adulto", id, {}));
});
