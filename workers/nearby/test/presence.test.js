import { test } from "node:test";
import assert from "node:assert/strict";
import { Presence } from "../src/presence.js";
import { distanceM } from "../src/geo.js";

const SECRET = "segredo-de-teste";
const ROLE = { ritmo: "moderado", distancia: "50a150", parada: "cafe", raioKm: 5 };
const BASE = { lat: -20.3, lon: -40.3 };
// 1° de latitude = 111,32 km; ao norte do BASE.
const norte = (km) => ({ lat: BASE.lat + km / 111.32, lon: BASE.lon });
const pos = (p, extra = {}) => ({ lat: p.lat, lon: p.lon, rumo: 90, vel: 40, ...extra });

function criar(t0 = Date.parse("2026-11-11T12:00:00Z")) {
  const relogio = { t: t0 };
  const p = new Presence({ now: () => relogio.t, secret: SECRET });
  return { p, relogio };
}

test("sem NEARBY_SECRET o construtor falha com mensagem clara", () => {
  assert.throws(() => new Presence({ now: Date.now, secret: "" }), /NEARBY_SECRET ausente/);
  assert.throws(() => new Presence({ now: Date.now, secret: undefined }), /NEARBY_SECRET ausente/);
});

test("join devolve id de sessão de 16 hex, distinto por conexão", () => {
  const { p } = criar();
  const a = p.join("c1", "uid-a", ROLE);
  const b = p.join("c2", "uid-b", ROLE);
  assert.match(a, /^[0-9a-f]{16}$/);
  assert.match(b, /^[0-9a-f]{16}$/);
  assert.notEqual(a, b);
});

test("neighborsFor nunca expõe uid, nem o próprio, e traz só os campos combinados", async () => {
  const { p } = criar();
  const uidB = "uid-secreto-do-bruno-12345";
  const uidA = "uid-secreto-da-ana-67890";
  p.join("a", uidA, ROLE);
  p.join("b", uidB, ROLE);
  p.update("a", pos(BASE));
  p.update("b", pos(norte(2)));
  const lista = await p.neighborsFor("a");
  assert.equal(lista.length, 1);
  const json = JSON.stringify(lista);
  assert.ok(!json.includes(uidB));
  assert.ok(!json.includes(uidA));
  assert.ok(!json.toLowerCase().includes("uid"));
  assert.deepEqual(Object.keys(lista[0]).sort(), [
    "combina", "distancia", "lat", "lon", "parada", "ritmo", "rumo", "sessao",
  ]);
});

test("posição enviada difere da real entre 200 e 400 m, é estável e muda no outro dia", async () => {
  const { p, relogio } = criar();
  p.join("a", "uid-a", ROLE);
  p.join("b", "uid-b", ROLE);
  p.update("a", pos(BASE));
  const real = norte(2);
  p.update("b", pos(real));
  const [v1] = await p.neighborsFor("a");
  const d = distanceM(real, v1);
  assert.ok(d >= 195 && d <= 405, `desvio ${d}`);
  const [v2] = await p.neighborsFor("a");
  assert.equal(v2.lat, v1.lat);
  assert.equal(v2.lon, v1.lon);

  // sai e entra no mesmo dia: mesma posição aproximada (anti-média)
  p.leave("b");
  p.join("b", "uid-b", ROLE);
  p.update("b", pos(real));
  p.update("a", pos(BASE));
  const [v3] = await p.neighborsFor("a");
  assert.equal(v3.lat, v1.lat);
  assert.equal(v3.lon, v1.lon);

  // outro dia: muda
  relogio.t += 24 * 3600 * 1000;
  p.update("a", pos(BASE));
  p.update("b", pos(real));
  const [v4] = await p.neighborsFor("a");
  assert.ok(v4.lat !== v1.lat || v4.lon !== v1.lon);
});

test("raio: 6 km fora com raioKm 5, 4 km dentro, 6 km entra com raioKm 10", async () => {
  const { p } = criar();
  p.join("a", "uid-a", ROLE);
  p.join("b4", "uid-b4", ROLE);
  p.join("b6", "uid-b6", ROLE);
  p.update("a", pos(BASE));
  p.update("b4", pos(norte(4)));
  p.update("b6", pos(norte(6)));
  assert.equal((await p.neighborsFor("a")).length, 1);
  p.join("a", "uid-a", { ...ROLE, raioKm: 10 });
  p.update("a", pos(BASE));
  assert.equal((await p.neighborsFor("a")).length, 2);
});

test("a distância usa a posição real, não a aproximada", async () => {
  const { p } = criar();
  p.join("a", "uid-a", { ...ROLE, raioKm: 5 });
  p.join("b", "uid-b", ROLE);
  p.update("a", pos(BASE));
  p.update("b", pos(norte(5.1))); // real fora do raio, mesmo que o desvio a aproxime
  assert.equal((await p.neighborsFor("a")).length, 0);
});

test("combina vem true/false conforme match.js", async () => {
  const { p } = criar();
  p.join("a", "uid-a", ROLE);
  p.join("ok", "uid-ok", { ...ROLE, distancia: "ate50" });
  p.join("no", "uid-no", { ...ROLE, ritmo: "animado" });
  p.update("a", pos(BASE));
  p.update("ok", pos(norte(1)));
  p.update("no", pos(norte(2)));
  const lista = await p.neighborsFor("a");
  const porDist = Object.fromEntries(lista.map((v) => [v.ritmo, v.combina]));
  assert.equal(porDist.moderado, true);
  assert.equal(porDist.animado, false);
});

test("quem ainda não mandou posição não vê nem é visto", async () => {
  const { p } = criar();
  p.join("a", "uid-a", ROLE);
  p.join("b", "uid-b", ROLE);
  p.update("b", pos(BASE));
  assert.deepEqual(await p.neighborsFor("a"), []);
  p.update("a", pos(norte(1)));
  assert.equal((await p.neighborsFor("a")).length, 1);
  assert.deepEqual(await p.neighborsFor("desconhecido"), []);
});

test("expire: 121 s sem update remove; 119 s mantém; some para os outros", async () => {
  const { p, relogio } = criar();
  p.join("a", "uid-a", ROLE);
  p.join("b", "uid-b", ROLE);
  p.update("a", pos(BASE));
  p.update("b", pos(norte(1)));
  relogio.t += 119_000;
  p.update("a", pos(BASE));
  assert.deepEqual(p.expire(), []);
  assert.equal((await p.neighborsFor("a")).length, 1);
  relogio.t += 2_000; // b: 121 s sem update; a: 2 s
  assert.deepEqual(p.expire(), ["b"]);
  assert.deepEqual(await p.neighborsFor("a"), []);
  assert.deepEqual(p.expire(), []);
});

test("join sem nenhum update também expira depois de 120 s", () => {
  const { p, relogio } = criar();
  p.join("a", "uid-a", ROLE);
  relogio.t += 121_000;
  assert.deepEqual(p.expire(), ["a"]);
});

test("velocidade 300 km/h é rejeitada; 250 km/h passa; vel nula passa", () => {
  const { p } = criar();
  p.join("a", "uid-a", ROLE);
  assert.throws(() => p.update("a", pos(BASE, { vel: 300 })), /posição implausível/);
  assert.throws(() => p.update("a", pos(BASE, { vel: 1e12 })), /posição implausível/);
  p.update("a", pos(BASE, { vel: 250 }));
  p.update("a", pos(BASE, { vel: null }));
});

test("salto de mais de 50 km em menos de 60 s é rejeitado; em 60 s ou mais passa", () => {
  const { p, relogio } = criar();
  p.join("a", "uid-a", ROLE);
  p.update("a", pos(BASE));
  relogio.t += 30_000;
  assert.throws(() => p.update("a", pos(norte(60))), /posição implausível/);
  // a posição rejeitada não foi gravada: um salto pequeno depois ainda passa
  p.update("a", pos(norte(1)));
  relogio.t += 61_000;
  p.update("a", pos(norte(80)));
});

test("update sem join é erro; leave de desconhecido é inofensivo", () => {
  const { p } = criar();
  assert.throws(() => p.update("x", pos(BASE)), /não está procurando/);
  p.leave("x");
});

test("um join repetido na mesma conexão troca o rolê e mantém a sessão", async () => {
  const { p } = criar();
  const s1 = p.join("a", "uid-a", ROLE);
  const s2 = p.join("a", "uid-a", { ...ROLE, raioKm: 10 });
  assert.equal(s1, s2);
});

test("exportar e importar reconstrói o estado (hibernação)", async () => {
  const { p, relogio } = criar();
  p.join("a", "uid-a", ROLE);
  p.join("b", "uid-b", ROLE);
  p.update("a", pos(BASE));
  p.update("b", pos(norte(1)));
  const antes = await p.neighborsFor("a");

  const novo = new Presence({ now: () => relogio.t, secret: SECRET });
  novo.import("a", JSON.parse(JSON.stringify(p.export("a"))));
  novo.import("b", JSON.parse(JSON.stringify(p.export("b"))));
  assert.deepEqual(await novo.neighborsFor("a"), antes);
  assert.equal(p.export("zzz"), null);
});
