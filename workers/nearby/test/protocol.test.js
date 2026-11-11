import { test } from "node:test";
import assert from "node:assert/strict";
import { parseClientMessage } from "../src/protocol.js";

const procurar = { t: "procurar", ritmo: "moderado", distancia: "ate50", parada: "cafe", raioKm: 5 };
const posicao = { t: "posicao", lat: -20.3, lon: -40.3, rumo: 90, vel: 80, distKm: 12.5, etaMin: 30 };

function ok(obj) {
  const r = parseClientMessage(JSON.stringify(obj));
  assert.equal(r.ok, true, JSON.stringify(r));
  return r.msg;
}
function bad(raw) {
  const r = parseClientMessage(typeof raw === "string" ? raw : JSON.stringify(raw));
  assert.equal(r.ok, false, `deveria recusar: ${JSON.stringify(raw)}`);
  assert.equal(typeof r.error, "string");
  assert.ok(r.error.length > 0);
}

test("procurar válido passa", () => {
  assert.deepEqual(ok(procurar), procurar);
});

test("parar válido passa", () => {
  assert.deepEqual(ok({ t: "parar" }), { t: "parar" });
});

test("posicao válida passa, com e sem nulos", () => {
  assert.deepEqual(ok(posicao), posicao);
  const nulos = { t: "posicao", lat: 0, lon: 0, rumo: null, vel: null, distKm: null, etaMin: null };
  assert.deepEqual(ok(nulos), nulos);
});

test("procurar: valores fora das listas ou da faixa são recusados", () => {
  bad({ ...procurar, ritmo: "voando" });
  bad({ ...procurar, distancia: "10km" });
  bad({ ...procurar, parada: "bar" });
  bad({ ...procurar, raioKm: 4.9 });
  bad({ ...procurar, raioKm: 20.1 });
  bad({ ...procurar, raioKm: "5" });
  bad({ ...procurar, raioKm: null });
  const { ritmo, ...semRitmo } = procurar;
  bad(semRitmo);
});

test("procurar: limites do raio passam", () => {
  ok({ ...procurar, raioKm: 5 });
  ok({ ...procurar, raioKm: 20 });
});

test("posicao: campos fora de faixa são recusados", () => {
  bad({ ...posicao, lat: 90.1 });
  bad({ ...posicao, lat: -90.1 });
  bad({ ...posicao, lon: 180.1 });
  bad({ ...posicao, lon: -180.1 });
  bad({ ...posicao, rumo: -1 });
  bad({ ...posicao, rumo: 360.1 });
  bad({ ...posicao, vel: -1 });
  bad({ ...posicao, distKm: -0.1 });
  bad({ ...posicao, etaMin: -1 });
});

test("posicao: limites passam", () => {
  ok({ ...posicao, lat: 90, lon: 180, rumo: 360, vel: 0, distKm: 0, etaMin: 0 });
  ok({ ...posicao, lat: -90, lon: -180, rumo: 0 });
});

test("posicao: não números, NaN e Infinity são recusados", () => {
  bad({ ...posicao, lat: "1" });
  bad({ ...posicao, lat: null });
  bad({ ...posicao, lon: true });
  bad({ ...posicao, vel: "80" });
  bad({ ...posicao, rumo: [] });
  bad('{"t":"posicao","lat":1e999,"lon":0,"rumo":null,"vel":null,"distKm":null,"etaMin":null}');
  bad('{"t":"posicao","lat":NaN,"lon":0,"rumo":null,"vel":null,"distKm":null,"etaMin":null}');
  bad('{"t":"posicao","lat":0,"lon":0,"rumo":null,"vel":1e999,"distKm":null,"etaMin":null}');
});

test("posicao: campo ausente é recusado (null precisa ser explícito)", () => {
  const { rumo, ...semRumo } = posicao;
  bad(semRumo);
});

test("t desconhecido, ausente ou não texto é recusado", () => {
  bad({ t: "hackear" });
  bad({ t: "toString" });
  bad({ t: "__proto__" });
  bad({});
  bad({ t: 1 });
});

test("JSON inválido ou que não é objeto é recusado", () => {
  bad("{nao e json");
  bad("");
  bad("null");
  bad("[]");
  bad('"parar"');
  bad("42");
});

test("campos extras são descartados", () => {
  assert.deepEqual(ok({ ...procurar, uid: "abc", extra: 1 }), procurar);
  assert.deepEqual(ok({ t: "parar", lixo: true }), { t: "parar" });
  assert.deepEqual(ok({ ...posicao, uid: "abc" }), posicao);
});

test("entrada que não é string é recusada", () => {
  bad(undefined);
  assert.equal(parseClientMessage(undefined).ok, false);
  assert.equal(parseClientMessage(123).ok, false);
});

test("limite de 2 KB: exatamente 2048 bytes passa, 2049 não", () => {
  const base = JSON.stringify({ t: "parar" });
  const folga = (n) => base.slice(0, -1) + " ".repeat(n - base.length) + "}";
  const dentro = folga(2048);
  assert.equal(Buffer.byteLength(dentro), 2048);
  assert.equal(parseClientMessage(dentro).ok, true);
  const fora = folga(2049);
  assert.equal(Buffer.byteLength(fora), 2049);
  assert.equal(parseClientMessage(fora).ok, false);
});

test("limite mede bytes UTF-8, não caracteres", () => {
  // 1100 caracteres "é" = 2200 bytes, mas só 1100 caracteres.
  const raw = JSON.stringify({ t: "parar", x: "é".repeat(1100) });
  assert.ok(raw.length < 2048);
  assert.ok(Buffer.byteLength(raw) > 2048);
  assert.equal(parseClientMessage(raw).ok, false);
});
