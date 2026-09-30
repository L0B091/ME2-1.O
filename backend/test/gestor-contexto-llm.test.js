import test from "node:test";
import assert from "node:assert/strict";
import { construirContextoLLM } from "../orquestador/gestorContextoLLM.js";

function crearFuentes({ perfil, recuerdos = [], historial = [], codigo = [], fiscal = [] } = {}) {
  const llamadas = { perfil: 0, persistente: 0, importantes: 0, historial: 0, codigo: 0, fiscal: 0 };
  const fuentes = {
    datosUsuario: { obtener() { llamadas.perfil++; return perfil || {}; } },
    memoriaPersistente: { relevantes(_id, termino) { llamadas.persistente++; return recuerdos.filter(item => item.texto.toLowerCase().includes(termino)); } },
    recuerdosImportantes: { obtenerTodos() { llamadas.importantes++; return {}; } },
    historialConversacion: { obtenerHistorial(_id, limite) { llamadas.historial++; return historial.slice(-limite); } },
    codigoMemoria: { buscarArchivos(_id, termino) { llamadas.codigo++; return codigo.filter(item => `${item.nombre} ${item.ruta} ${item.resumen}`.toLowerCase().includes(termino)); } },
    documentosFiscales: { listarDocumentos() { llamadas.fiscal++; return fiscal; } }
  };
  return { fuentes, llamadas };
}

test("TEST 1: un saludo no consulta memorias de proyecto, código ni fiscales", async () => {
  const { fuentes, llamadas } = crearFuentes();
  const result = await construirContextoLLM({ userId: "u1", mensajeUsuario: "Hola, ¿cómo estás?", fuentes });
  assert.equal(result.memoria.nivel, "NONE");
  assert.equal(result.memoria.usada, false);
  assert.equal(llamadas.persistente + llamadas.importantes + llamadas.historial + llamadas.codigo + llamadas.fiscal, 0);
});

test("TEST 2: una decisión sobre el avatar selecciona recuerdos de proyecto relacionados", async () => {
  const { fuentes, llamadas } = crearFuentes({ recuerdos: [{ texto: "Decidimos usar el avatar azul en la pantalla inicial", categoria: "proyecto", importancia: 3, timestamp: 10 }] });
  const result = await construirContextoLLM({ userId: "u2", mensajeUsuario: "¿Qué habíamos decidido sobre el avatar?", fuentes });
  assert.equal(result.memoria.nivel, "RELEVANT");
  assert.equal(result.memoria.categoria, "project");
  assert.match(result.memoria.elementos[0].texto, /avatar azul/i);
  assert.equal(llamadas.fiscal, 0);
});

test("TEST 3: preguntar el nombre consulta solo el perfil básico", async () => {
  const { fuentes, llamadas } = crearFuentes({ perfil: { identidad: { nombre: "Lucía" }, intereses: ["música"], cuentas: { email: "privado@example.com", token: "secret" }, resumen: "" } });
  const result = await construirContextoLLM({ userId: "u3", mensajeUsuario: "¿Cuál era mi nombre?", fuentes });
  assert.equal(result.memoria.nivel, "PROFILE");
  assert.equal(result.perfil.nombre, "Lucía");
  assert.equal(JSON.stringify(result.perfil).includes("privado@example.com"), false);
  assert.equal(JSON.stringify(result.perfil).includes("secret"), false);
  assert.equal(llamadas.persistente + llamadas.importantes + llamadas.historial + llamadas.codigo + llamadas.fiscal, 0);
});

test("TEST 4: preguntar por un archivo consulta código e historial, no fiscal", async () => {
  const { fuentes, llamadas } = crearFuentes({ historial: [{ tipo: "usuario", mensaje: "Ayer modificamos el archivo src/ui/Chat.kt", timestamp: 20 }], codigo: [{ nombre: "Chat.kt", ruta: "src/ui/Chat.kt", lenguaje: "Kotlin", resumen: "Pantalla de chat" }] });
  const result = await construirContextoLLM({ userId: "u4", mensajeUsuario: "¿Qué archivo modificamos ayer?", fuentes });
  assert.ok(result.memoria.categorias.includes("code"));
  assert.ok(result.memoria.categorias.includes("conversation"));
  assert.ok(result.historial.elementos.length > 0 || result.especializados.codigo.length > 0);
  assert.equal(llamadas.fiscal, 0);
});

test("TEST 5: una pregunta fiscal consulta únicamente la fuente fiscal y devuelve pocos campos", async () => {
  const { fuentes, llamadas } = crearFuentes({ fiscal: [{ id: "f1", tipo: "factura", numero: "A-12", emisor: "Proveedor", monto: 1200, moneda: "ARS", fecha: "2026-09-01", descripcion: "Compra de monitor", token: "no enviar" }] });
  const result = await construirContextoLLM({ userId: "u5", mensajeUsuario: "¿Qué decía el documento fiscal que guardamos?", fuentes });
  assert.equal(result.memoria.categoria, "fiscal");
  assert.equal(result.especializados.fiscal.numero, "A-12");
  assert.equal(JSON.stringify(result.especializados.fiscal).includes("token"), false);
  assert.equal(llamadas.codigo + llamadas.historial + llamadas.persistente + llamadas.importantes, 0);
});

test("TEST 6: una pregunta general sobre JavaScript no recupera memoria", async () => {
  const { fuentes, llamadas } = crearFuentes();
  const result = await construirContextoLLM({ userId: "u6", mensajeUsuario: "¿Qué es JavaScript?", fuentes });
  assert.equal(result.memoria.nivel, "NONE");
  assert.equal(llamadas.perfil + llamadas.persistente + llamadas.importantes + llamadas.historial + llamadas.codigo + llamadas.fiscal, 0);
});

test("TEST 7: una consulta sin coincidencias devuelve elementos vacíos sin fallar", async () => {
  const { fuentes } = crearFuentes();
  const result = await construirContextoLLM({ userId: "u7", mensajeUsuario: "¿Qué habíamos decidido sobre el avatar?", fuentes });
  assert.equal(result.memoria.nivel, "RELEVANT");
  assert.deepEqual(result.memoria.elementos, []);
  assert.equal(result.metadatos.resultadosEncontrados, 0);
});

test("TEST 8: una consulta ambigua elige NONE y no carga contexto profundo", async () => {
  const { fuentes, llamadas } = crearFuentes();
  const result = await construirContextoLLM({ userId: "u8", mensajeUsuario: "Y eso qué tal?", fuentes });
  assert.equal(result.memoria.nivel, "NONE");
  assert.equal(result.memoria.elementos.length, 0);
  assert.equal(llamadas.persistente + llamadas.importantes + llamadas.historial + llamadas.codigo + llamadas.fiscal, 0);
});

test("una reconstrucción explícita usa DEEP y consulta conversación y proyecto", async () => {
  const { fuentes, llamadas } = crearFuentes({
    recuerdos: [{ texto: "Acordamos usar el avatar azul", categoria: "proyecto", importancia: 3, timestamp: 10 }],
    historial: [{ tipo: "usuario", mensaje: "Hablamos del avatar y su pantalla inicial", timestamp: 20 }]
  });
  const result = await construirContextoLLM({ userId: "u10", mensajeUsuario: "Reconstruye el contexto completo de lo que hablamos sobre el avatar", fuentes });
  assert.equal(result.memoria.nivel, "DEEP");
  assert.ok(llamadas.historial > 0);
  assert.ok(llamadas.persistente > 0);
});

test("la selección es determinista y respeta el presupuesto configurado", async () => {
  const recuerdos = Array.from({ length: 20 }, (_, i) => ({ texto: `Avatar proyecto detalle ${i} ${"x".repeat(220)}`, categoria: "proyecto", importancia: i % 4, timestamp: i }));
  const { fuentes } = crearFuentes({ recuerdos, perfil: { identidad: { nombre: "Lucía" }, intereses: ["a", "b", "c", "d"] } });
  const input = { userId: "u9", mensajeUsuario: "¿Qué habíamos decidido sobre el avatar?", fuentes, presupuestoTokens: 120 };
  const first = await construirContextoLLM(input);
  const second = await construirContextoLLM(input);
  assert.deepEqual(first, second);
  assert.ok(first.metadatos.tokensEstimados <= first.metadatos.presupuesto);
});

test("un mensaje muy largo queda limitado también con el presupuesto mínimo", async () => {
  const { fuentes } = crearFuentes();
  const result = await construirContextoLLM({
    userId: "u11",
    mensajeUsuario: `¿Qué habíamos decidido sobre el avatar? ${"detalle ".repeat(500)}`,
    fuentes,
    presupuestoTokens: 64
  });
  assert.equal(result.metadatos.presupuesto, 128);
  assert.ok(result.metadatos.tokensEstimados <= 128);
});

