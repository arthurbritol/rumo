// Testes de bloqueios e denúncias na Conta, e das mensagens bloquear/denunciar no protocolo.
import test from "node:test";
import assert from "node:assert/strict";
import { Conta, MAX_BLOQUEADOS } from "../src/conta.js";
import { parseClientMessage } from "../src/protocol.js";

function nova() {
  const dados = new Map();
  const ctx = {
    storage: {
      async get(k) { return dados.get(k); },
      async put(k, v) { dados.set(k, structuredClone(v)); },
    },
  };
  const c = new Conta(ctx, {});
  const relogio = { t: 1_000_000 };
  c.now = () => relogio.t;
  const chamar = async (caminho, metodo = "POST") => {
    const r = await c.fetch(new Request(`https://conta${caminho}`, { method: metodo }));
    return { status: r.status, corpo: await r.json() };
  };
  return { c, relogio, chamar };
}

test("bloquear grava nos dois sentidos e GET /bloqueios devolve os dois conjuntos", async () => {
  const { chamar } = nova();
  assert.deepEqual((await chamar("/bloqueios", "GET")).corpo, { ok: true, bloqueados: [], bloqueadoPor: [] });
  assert.equal((await chamar("/bloquear?uid=bruno")).status, 200);
  assert.equal((await chamar("/bloquear?uid=bruno")).status, 200); // idempotente
  assert.equal((await chamar("/bloquear?uid=carla&sentido=bloqueadoPor")).status, 200);
  assert.deepEqual((await chamar("/bloqueios", "GET")).corpo, { ok: true, bloqueados: ["bruno"], bloqueadoPor: ["carla"] });
});

test("bloquear recusa uid inválido, sentido inválido e método errado", async () => {
  const { chamar } = nova();
  assert.equal((await chamar("/bloquear")).status, 400);
  assert.equal((await chamar("/bloquear?uid=a/b")).status, 400);
  assert.equal((await chamar("/bloquear?uid=x&sentido=outro")).status, 400);
  assert.equal((await chamar("/bloquear?uid=x", "GET")).status, 405);
});

test("teto de bloqueados: o 501º é recusado com limite; bloqueadoPor recusa acima de 5000 sem descartar", async () => {
  const { chamar } = nova();
  for (let i = 0; i < MAX_BLOQUEADOS; i++) assert.equal((await chamar(`/bloquear?uid=u${i}`)).status, 200);
  const r = await chamar("/bloquear?uid=extra");
  assert.equal(r.status, 409);
  assert.equal(r.corpo.erro, "limite");
  assert.equal((await chamar("/bloquear?uid=u0")).status, 200); // já bloqueado continua ok
  for (let i = 0; i < 5000; i++) assert.equal((await chamar(`/bloquear?uid=p${i}&sentido=bloqueadoPor`)).status, 200);
  const r2 = await chamar("/bloquear?uid=pextra&sentido=bloqueadoPor");
  assert.equal(r2.status, 409); // recusa em vez de descartar entradas antigas
  const { bloqueadoPor } = (await chamar("/bloqueios", "GET")).corpo;
  assert.equal(bloqueadoPor.length, 5000);
  assert.equal(bloqueadoPor[0], "p0");
});

test("denunciar grava {por, em, motivo, lat, lon}; motivo inválido é recusado", async () => {
  const { chamar, relogio } = nova();
  assert.equal((await chamar("/denunciar?por=ana&motivo=xyz")).status, 400);
  assert.equal((await chamar("/denunciar?motivo=falso")).status, 400);
  assert.equal((await chamar("/denunciar?por=ana&motivo=falso&lat=-20.1&lon=-40.2")).status, 200);
  const { denuncias } = (await chamar("/denuncias", "GET")).corpo;
  assert.deepEqual(denuncias, [{ por: "ana", em: relogio.t, motivo: "falso", lat: -20.1, lon: -40.2 }]);
});

test("denunciar sem posição ou com posição absurda grava null", async () => {
  const { chamar } = nova();
  await chamar("/denunciar?por=ana&motivo=outro");
  await chamar("/denunciar?por=bia&motivo=outro&lat=999&lon=abc");
  const { denuncias } = (await chamar("/denuncias", "GET")).corpo;
  assert.deepEqual(denuncias.map((d) => [d.lat, d.lon]), [[null, null], [null, null]]);
});

test("mesmo denunciante em 24 h conta uma vez; depois de 24 h conta de novo", async () => {
  const { chamar, relogio } = nova();
  await chamar("/denunciar?por=ana&motivo=incomodo");
  assert.equal((await chamar("/denunciar?por=ana&motivo=falso")).corpo.duplicada, true);
  await chamar("/denunciar?por=bia&motivo=falso");
  assert.equal((await chamar("/denuncias", "GET")).corpo.denuncias.length, 2);
  relogio.t += 24 * 3_600_000 + 1;
  await chamar("/denunciar?por=ana&motivo=falso");
  assert.equal((await chamar("/denuncias", "GET")).corpo.denuncias.length, 3);
});

test("teto de denúncias: guarda só as 200 mais recentes", async () => {
  const { chamar } = nova();
  for (let i = 0; i < 205; i++) await chamar(`/denunciar?por=d${i}&motivo=outro`);
  const { denuncias } = (await chamar("/denuncias", "GET")).corpo;
  assert.equal(denuncias.length, 200);
  assert.equal(denuncias[0].por, "d5");
  assert.equal(denuncias.at(-1).por, "d204");
});

test("cota de denúncias do denunciante: 5 por hora, alvo repetido não gasta cota", async () => {
  const { chamar, relogio } = nova();
  for (let i = 0; i < 5; i++) {
    assert.deepEqual((await chamar(`/cota-denuncia?alvo=x${i}`)).corpo, { ok: true });
    relogio.t += 1000;
  }
  assert.equal((await chamar("/cota-denuncia?alvo=x0")).corpo.duplicada, true);
  const r = await chamar("/cota-denuncia?alvo=novo");
  assert.equal(r.status, 409);
  assert.equal(r.corpo.erro, "espera");
  assert.ok(r.corpo.restanteMs > 3_500_000 && r.corpo.restanteMs <= 3_600_000);
  relogio.t += 3_600_000;
  assert.equal((await chamar("/cota-denuncia?alvo=novo")).status, 200);
});

test("desfazer devolve a reserva da cota", async () => {
  const { chamar } = nova();
  for (let i = 0; i < 5; i++) await chamar(`/cota-denuncia?alvo=y${i}`);
  assert.equal((await chamar("/cota-denuncia?alvo=z")).status, 409);
  await chamar("/cota-denuncia?alvo=y0&desfazer=1");
  assert.equal((await chamar("/cota-denuncia?alvo=z")).status, 200);
});

test("protocolo: bloquear e denunciar exigem sessão de 16 hex e motivo válido", () => {
  const s = "0123456789abcdef";
  assert.deepEqual(parseClientMessage(JSON.stringify({ t: "bloquear", sessao: s })), { ok: true, msg: { t: "bloquear", sessao: s } });
  assert.equal(parseClientMessage(JSON.stringify({ t: "bloquear", sessao: "uid-ana" })).ok, false);
  assert.equal(parseClientMessage(JSON.stringify({ t: "bloquear" })).ok, false);
  for (const motivo of ["incomodo", "comportamento", "falso", "outro"]) {
    assert.equal(parseClientMessage(JSON.stringify({ t: "denunciar", sessao: s, motivo })).ok, true);
  }
  assert.equal(parseClientMessage(JSON.stringify({ t: "denunciar", sessao: s, motivo: "spam" })).ok, false);
  assert.equal(parseClientMessage(JSON.stringify({ t: "denunciar", sessao: s })).ok, false);
});

test("reserva de cota não confirmada expira em 30 s; confirmar a torna definitiva por 24 h", async () => {
  const { chamar, relogio } = nova();
  assert.deepEqual((await chamar("/cota-denuncia?alvo=q")).corpo, { ok: true });
  assert.equal((await chamar("/cota-denuncia?alvo=q")).corpo.duplicada, true);
  relogio.t += 31_000;
  assert.equal((await chamar("/cota-denuncia?alvo=q")).corpo.duplicada, undefined); // expirou: reserva nova
  await chamar("/cota-denuncia?alvo=q&confirmar=1");
  relogio.t += 3_600_000;
  assert.equal((await chamar("/cota-denuncia?alvo=q")).corpo.duplicada, true);
  relogio.t += 24 * 3_600_000;
  assert.equal((await chamar("/cota-denuncia?alvo=q")).corpo.duplicada, undefined);
});

test("cota: duplicada devolve pendente:true com a reserva aberta e pendente:false depois de confirmar", async () => {
  const { chamar } = nova();
  await chamar("/cota-denuncia?alvo=k");
  assert.deepEqual((await chamar("/cota-denuncia?alvo=k")).corpo, { ok: true, duplicada: true, pendente: true });
  await chamar("/cota-denuncia?alvo=k&confirmar=1");
  assert.deepEqual((await chamar("/cota-denuncia?alvo=k")).corpo, { ok: true, duplicada: true, pendente: false });
});

test("bloquear com simular=1 só confere o teto e não grava", async () => {
  const { chamar } = nova();
  assert.equal((await chamar("/bloquear?uid=zz&simular=1")).status, 200);
  assert.deepEqual((await chamar("/bloqueios", "GET")).corpo.bloqueados, []);
  for (let i = 0; i < MAX_BLOQUEADOS; i++) await chamar(`/bloquear?uid=u${i}`);
  assert.equal((await chamar("/bloquear?uid=zz&simular=1")).status, 409);
});
