// Marcas "quero ser amigo" na Conta (Tarefa 19): várias Contas em memória ligadas por um env.CONTA falso.
import test from "node:test";
import assert from "node:assert/strict";
import { Conta, MAX_MARCAS_AMIGO } from "../src/conta.js";
import { parseClientMessage } from "../src/protocol.js";

const H24 = 24 * 3_600_000;

function mundo() {
  const relogio = { t: 1_000_000 };
  const contas = new Map();
  const env = {
    CONTA: {
      idFromName: (n) => n,
      get: (n) => ({
        fetch: (url, init) => contas.get(n).fetch(new Request(url, init)),
      }),
    },
  };
  const conta = (uid) => {
    if (!contas.has(uid)) {
      const dados = new Map();
      const ctx = { storage: { async get(k) { return dados.get(k); }, async put(k, v) { dados.set(k, structuredClone(v)); } } };
      const c = new Conta(ctx, env);
      c.now = () => relogio.t;
      contas.set(uid, c);
    }
    return contas.get(uid);
  };
  const chamar = async (uid, caminho, metodo = "POST") => {
    const r = await conta(uid).fetch(new Request(`https://conta${caminho}`, { method: metodo }));
    return { status: r.status, corpo: await r.json() };
  };
  const confirmados = async (uid) => (await chamar(uid, `/amigos-confirmados?eu=${uid}`, "GET")).corpo.uids;
  conta("ana"); conta("bruno"); conta("carla");
  return { relogio, chamar, confirmados };
}

test("marca de um lado só não revela nada a ninguém", async () => {
  const { chamar, confirmados } = mundo();
  assert.equal((await chamar("ana", "/amigo?uid=bruno")).status, 200);
  assert.deepEqual(await confirmados("ana"), []);
  assert.deepEqual(await confirmados("bruno"), []);
});

test("as duas marcas revelam o uid de cada um só ao outro", async () => {
  const { chamar, confirmados } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  assert.deepEqual(await confirmados("ana"), ["bruno"]);
  assert.deepEqual(await confirmados("bruno"), ["ana"]);
  assert.deepEqual(await confirmados("carla"), []);
});

test("marca vale 24 h: depois disso a marca antiga não conta", async () => {
  const { chamar, confirmados, relogio } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  relogio.t += H24 + 1;
  await chamar("bruno", "/amigo?uid=ana");
  assert.deepEqual(await confirmados("ana"), []);
  assert.deepEqual(await confirmados("bruno"), []); // a marca de ana expirou
  // Dentro das 24 h de cada uma, vale.
  relogio.t += 1000;
  await chamar("ana", "/amigo?uid=bruno");
  assert.deepEqual(await confirmados("ana"), ["bruno"]);
});

test("entrega única: entregue some da lista e desfazer devolve", async () => {
  const { chamar, confirmados } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  assert.equal((await chamar("ana", "/amigo-entregue?uid=bruno")).corpo.novo, true);
  assert.equal((await chamar("ana", "/amigo-entregue?uid=bruno")).corpo.novo, false);
  assert.deepEqual(await confirmados("ana"), []);
  assert.deepEqual(await confirmados("bruno"), ["ana"]); // cada lado entrega o seu
  await chamar("ana", "/amigo-entregue?uid=bruno&desfazer=1");
  assert.deepEqual(await confirmados("ana"), ["bruno"]);
});

test("bloqueio posterior (qualquer sentido) apaga marcas e confirmações pendentes", async () => {
  const { chamar, confirmados } = mundo();
  await chamar("ana", "/amigo?uid=bruno");
  await chamar("bruno", "/amigo?uid=ana");
  await chamar("bruno", "/bloquear?uid=ana");
  await chamar("ana", "/bloquear?uid=bruno&sentido=bloqueadoPor");
  assert.deepEqual(await confirmados("ana"), []);
  assert.deepEqual(await confirmados("bruno"), []);
  // Marca nova contra bloqueado não grava (responde ok igual).
  assert.equal((await chamar("ana", "/amigo?uid=bruno")).corpo.ok, true);
  await chamar("carla", "/amigo?uid=ana");
  assert.deepEqual((await chamar("ana", "/amigo-marca?uid=bruno", "GET")).corpo, { ok: true, marcou: false });
});

test("teto de 50 marcas ativas, simular não grava, uid inválido e método errado", async () => {
  const { chamar } = mundo();
  for (let i = 0; i < MAX_MARCAS_AMIGO; i++) assert.equal((await chamar("ana", `/amigo?uid=u${i}`)).status, 200);
  const r = await chamar("ana", "/amigo?uid=extra");
  assert.equal(r.status, 409);
  assert.equal(r.corpo.erro, "limite");
  assert.equal((await chamar("ana", "/amigo?uid=u0")).status, 200); // repetida continua ok
  assert.equal((await chamar("bruno", "/amigo?uid=x&simular=1")).status, 200);
  assert.equal((await chamar("bruno", "/amigo-marca?uid=x", "GET")).corpo.marcou, false);
  assert.equal((await chamar("ana", "/amigo?uid=a/b")).status, 400);
  assert.equal((await chamar("ana", "/amigos-confirmados", "GET")).status, 400);
  assert.equal((await chamar("ana", "/amigo?uid=x", "GET")).status, 405);
});

test("protocolo: amigo exige sessao de 16 hex", () => {
  assert.deepEqual(parseClientMessage('{"t":"amigo","sessao":"0123456789abcdef"}'), { ok: true, msg: { t: "amigo", sessao: "0123456789abcdef" } });
  assert.equal(parseClientMessage('{"t":"amigo","sessao":"x"}').ok, false);
  assert.equal(parseClientMessage('{"t":"amigo"}').ok, false);
});
