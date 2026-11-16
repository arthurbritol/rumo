import { test } from "node:test";
import assert from "node:assert/strict";
import { Encounters } from "../src/encounter.js";

const PONTO = { lat: -20.3, lon: -40.3 };
const PREF = { ritmo: "moderado", distancia: "50a150", parada: "cafe" };
const MIN = 60_000;

function criar() {
  const relogio = { t: Date.parse("2026-11-11T12:00:00Z") };
  const e = new Encounters({ now: () => relogio.t });
  return { e, relogio };
}
const pos = (distKm, etaMin = 5) => ({ lat: PONTO.lat, lon: PONTO.lon, distKm, etaMin, aprox: { lat: PONTO.lat + 0.003, lon: PONTO.lon } });

test("vagas 1 e 9 recusadas, 2 e 8 aceitas", () => {
  const { e } = criar();
  assert.throws(() => e.propose("a", PONTO, 1, PREF), /vagas inválidas/);
  assert.throws(() => e.propose("a", PONTO, 9, PREF), /vagas inválidas/);
  assert.match(e.propose("a", PONTO, 2, PREF).id, /\S/);
  assert.match(e.propose("b", PONTO, 8, PREF).id, /\S/);
});

test("segunda proposta aberta da mesma sessão é recusada", () => {
  const { e } = criar();
  e.propose("a", PONTO, 4, PREF);
  assert.throws(() => e.propose("a", PONTO, 4, PREF), /já tem proposta/);
});

test("entrada é direta, não duplica e id inexistente é recusado", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  e.join("b", id);
  assert.equal(e.viewFor("a", id).vagasLivres, 1);
  assert.throws(() => e.join("c", "nao-existe"), /inexistente/);
});

test("última vaga disputada: uma entra, a outra recebe cheio, e some das propostas abertas", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 2, PREF);
  assert.deepEqual(e.openProposals(), [
    { id, ponto: PONTO, vagasLivres: 1, pref: PREF, proponente: "a" },
  ]);
  e.join("b", id);
  assert.throws(() => e.join("c", id), /cheio/);
  assert.deepEqual(e.openProposals(), []);
});

test("sair devolve a vaga", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 2, PREF);
  e.join("b", id);
  e.leave("b", id);
  assert.equal(e.openProposals()[0].vagasLivres, 1);
});

test("cancel só por quem propôs; encerra com motivo cancelado", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  assert.throws(() => e.cancel("b", id));
  assert.deepEqual(e.cancel("a", id), { id, motivo: "cancelado" });
  assert.throws(() => e.join("c", id), /inexistente/);
  assert.deepEqual(e.openProposals(), []);
});

test("proposta sem ninguém entrando por 30 min expira", () => {
  const { e, relogio } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  relogio.t += 29 * MIN;
  assert.deepEqual(e.tick(), []);
  relogio.t += 1 * MIN;
  assert.deepEqual(e.tick(), [{ id, motivo: "expirou" }]);
  assert.deepEqual(e.tick(), []);
});

test("proposta com alguém dentro não expira em 30 min", () => {
  const { e, relogio } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  relogio.t += 31 * MIN;
  assert.deepEqual(e.tick(), []);
});

test("em andamento sem chegada por 60 min termina com ninguem_chegou", () => {
  const { e, relogio } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  relogio.t += 59 * MIN;
  assert.deepEqual(e.tick(), []);
  relogio.t += 1 * MIN;
  assert.deepEqual(e.tick(), [{ id, motivo: "ninguem_chegou" }]);
});

test("chegada nova reinicia os 60 min", () => {
  const { e, relogio } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  e.join("c", id);
  relogio.t += 50 * MIN;
  e.position("a", id, pos(0.05));
  relogio.t += 50 * MIN;
  assert.deepEqual(e.tick(), []);
  relogio.t += 10 * MIN;
  assert.deepEqual(e.tick(), [{ id, motivo: "ninguem_chegou" }]);
});

test("dropSession sai sem travar os outros e o encontro continua", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  e.join("c", id);
  e.dropSession("b");
  assert.deepEqual(e.viewFor("a", id).participantes.map((p) => p.sessao).sort(), ["a", "c"]);
  assert.equal(e.viewFor("a", id).vagasLivres, 1);
  assert.throws(() => e.viewFor("b", id));
  e.dropSession("nao-existe");
});

test("dropSession do último participante apaga o encontro", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.dropSession("a");
  assert.deepEqual(e.openProposals(), []);
  assert.throws(() => e.join("b", id), /inexistente/);
  assert.doesNotThrow(() => e.propose("a", PONTO, 3, PREF));
});

test("chegada a 99 m marca chegou, a 101 m não", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  e.position("a", id, pos(0.099));
  e.position("b", id, pos(0.101));
  const v = e.viewFor("a", id);
  assert.equal(v.participantes.find((p) => p.sessao === "a").chegou, true);
  assert.equal(v.participantes.find((p) => p.sessao === "b").chegou, false);
});

test("viewFor: real false a 1,2 km e real true a 900 m", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  e.position("a", id, pos(1.2, 9));
  e.position("b", id, pos(0.9, 7));
  const v = e.viewFor("a", id);
  assert.deepEqual(v.ponto, PONTO);
  const a = v.participantes.find((p) => p.sessao === "a");
  const b = v.participantes.find((p) => p.sessao === "b");
  assert.equal(a.real, false);
  assert.equal(b.real, true);
  assert.equal(a.distKm, 1.2);
  assert.equal(b.etaMin, 7);
});

test("fim chegaram quando todos que ficaram chegaram", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  e.join("c", id);
  e.position("a", id, pos(0.05));
  e.position("b", id, pos(0.05));
  assert.deepEqual(e.tick(), []);
  e.leave("c", id);
  assert.deepEqual(e.tick(), [{ id, motivo: "chegaram" }]);
  assert.deepEqual(e.tick(), []);
});

test("quem não participa não vê nem informa posição", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  assert.throws(() => e.viewFor("x", id));
  assert.throws(() => e.position("x", id, pos(0.05)));
});

test("position com aprox: a 1,2 km mostra a aproximada, a 900 m a real; sem aprox a 1,2 km falha", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  const real = { lat: -20.31, lon: -40.31 };
  const aprox = { lat: -20.312, lon: -40.309 };
  e.position("a", id, { ...real, distKm: 1.2, etaMin: 9, aprox });
  e.position("b", id, { ...real, distKm: 0.9, etaMin: 7, aprox });
  const v = e.viewFor("a", id);
  const a = v.participantes.find((p) => p.sessao === "a");
  const b = v.participantes.find((p) => p.sessao === "b");
  assert.deepEqual([a.lat, a.lon], [aprox.lat, aprox.lon]);
  assert.deepEqual([b.lat, b.lon], [real.lat, real.lon]);
  assert.throws(() => e.position("a", id, { ...real, distKm: 1.2, etaMin: 9 }));
});

test("position recusa distKm inválido", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  for (const d of [NaN, Infinity, -1, undefined, "1"]) {
    assert.throws(() => e.position("a", id, { ...pos(0.5), distKm: d }), /distância inválida/);
  }
});

test("sobrando só quem propôs, volta a proposta e o prazo de 30 min conta de então", () => {
  const { e, relogio } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  e.join("b", id);
  relogio.t += 40 * MIN;
  e.leave("b", id);
  assert.deepEqual(e.tick(), []);
  relogio.t += 29 * MIN;
  assert.deepEqual(e.tick(), []);
  relogio.t += 1 * MIN;
  assert.deepEqual(e.tick(), [{ id, motivo: "expirou" }]);
});

test("quem propôs saindo (leave ou dropSession) cancela no próximo tick e some das propostas", () => {
  const { e } = criar();
  const r1 = e.propose("a", PONTO, 3, PREF).id;
  e.join("b", r1);
  e.leave("a", r1);
  assert.deepEqual(e.openProposals(), []);
  assert.throws(() => e.join("c", r1), /inexistente/);
  const r2 = e.propose("d", PONTO, 3, PREF).id;
  e.join("e", r2);
  e.dropSession("d");
  assert.deepEqual(e.openProposals(), []);
  assert.deepEqual(e.tick(), [
    { id: r1, motivo: "cancelado" },
    { id: r2, motivo: "cancelado" },
  ]);
  assert.deepEqual(e.tick(), []);
});

test("leave de quem não participa lança não participa", () => {
  const { e } = criar();
  const { id } = e.propose("a", PONTO, 3, PREF);
  assert.throws(() => e.leave("x", id), /não participa/);
});
