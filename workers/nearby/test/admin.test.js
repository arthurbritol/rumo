// Testes das rotas do dono (/admin/*) com Contas reais em memória.
import test from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { Conta, INDICE_UID } from "../src/conta.js";

const TOKEN = "segredo-do-dono";

function ambiente(extra = {}) {
  const contas = new Map();
  const env = {
    FIREBASE_PROJECT_ID: "p",
    NEARBY_SECRET: "s",
    ADMIN_TOKEN: TOKEN,
    CELULA: { idFromName: (n) => n, get: () => ({ fetch: async () => new Response("x") }) },
    ...extra,
  };
  env.CONTA = {
    idFromName: (n) => n,
    get(nome) {
      if (!contas.has(nome)) {
        const dados = new Map();
        const c = new Conta({ storage: { get: async (k) => dados.get(k), put: async (k, v) => void dados.set(k, structuredClone(v)) } }, env);
        contas.set(nome, c);
      }
      return { fetch: (url, init) => contas.get(nome).fetch(new Request(url, init)) };
    },
  };
  return { env, contas };
}
const auth = (t = TOKEN) => ({ Authorization: `Bearer ${t}` });
const req = (metodo, caminho, headers = {}) => new Request(`https://nearby.example${caminho}`, { method: metodo, headers });
const denunciar = async (env, alvo, por, motivo = "incomodo", extra = "") => {
  const r = await env.CONTA.get(alvo).fetch(`https://conta/denunciar?por=${por}&motivo=${motivo}&uid=${alvo}${extra}`, { method: "POST" });
  await new Promise((ok) => setTimeout(ok, 5)); // o índice é gravado sem atrasar a resposta
  return r;
};

test("sem cabeçalho ou com token errado: 401, sem eco do token", async () => {
  const { env } = ambiente();
  for (const h of [{}, auth("errado"), auth(""), { Authorization: TOKEN }, auth(TOKEN + "x")]) {
    const r = await handle(req("GET", "/admin/denuncias?uid=ana", h), env);
    assert.equal(r.status, 401);
    assert.equal(r.headers.get("cache-control"), "no-store");
    assert.ok(!(await r.text()).includes("errado"));
  }
  assert.equal((await handle(req("POST", "/admin/suspensao?uid=ana&acao=por", auth("errado")), env)).status, 401);
});

test("token na query string não vale", async () => {
  const { env } = ambiente();
  assert.equal((await handle(req("GET", `/admin/denuncias?uid=ana&token=${TOKEN}`), env)).status, 401);
});

test("ADMIN_TOKEN ausente: 500; método errado: 405", async () => {
  const { env } = ambiente({ ADMIN_TOKEN: undefined });
  assert.equal((await handle(req("GET", "/admin/denuncias", auth()), env)).status, 500);
  const ok = ambiente().env;
  assert.equal((await handle(req("POST", "/admin/denuncias", auth()), ok)).status, 405);
  assert.equal((await handle(req("GET", "/admin/suspensao?uid=ana&acao=por", auth()), ok)).status, 405);
});

test("uid inválido: 400; uid reservado do índice não pode ser alvo", async () => {
  const { env } = ambiente();
  for (const u of ["a b", "a/b", "x".repeat(129), "", INDICE_UID]) {
    assert.equal((await handle(req("GET", `/admin/denuncias?uid=${encodeURIComponent(u)}`, auth()), env)).status, 400, u);
  }
  assert.equal((await handle(req("POST", `/admin/suspensao?uid=${INDICE_UID}&acao=por`, auth()), env)).status, 400);
  assert.equal((await handle(req("POST", "/admin/suspensao?acao=por", auth()), env)).status, 400);
  assert.equal((await handle(req("POST", "/admin/suspensao?uid=ana&acao=x", auth()), env)).status, 400);
});

test("lista as denúncias de um uid e, sem uid, o índice recente", async () => {
  const { env } = ambiente();
  await denunciar(env, "bruno", "ana", "incomodo", "&lat=-20.1&lon=-40.2");
  await denunciar(env, "bruno", "carla", "falso");
  await denunciar(env, "dani", "ana", "outro");
  const r = await handle(req("GET", "/admin/denuncias?uid=bruno", auth()), env);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("cache-control"), "no-store");
  const { denuncias } = await r.json();
  assert.equal(denuncias.length, 2);
  assert.deepEqual(denuncias.map((d) => d.por).sort(), ["ana", "carla"]);
  assert.equal(denuncias.find((d) => d.por === "ana").lat, -20.1);

  const g = await (await handle(req("GET", "/admin/denuncias", auth()), env)).json();
  assert.equal(g.denuncias.length, 3);
  assert.deepEqual(g.denuncias.map((d) => d.uid).sort(), ["bruno", "bruno", "dani"]);
});

test("uid com . e : é aceito (igual à Conta), __indice__ não", async () => {
  const { env } = ambiente();
  assert.equal((await handle(req("GET", "/admin/denuncias?uid=a.b:c", auth()), env)).status, 200);
});

test("o índice guarda só as 500 mais recentes", async () => {
  const { env } = ambiente();
  for (let i = 0; i < 503; i++) await env.CONTA.get(INDICE_UID).fetch(`https://conta/indice-add?uid=u${i}&por=p&motivo=falso`, { method: "POST" });
  const g = await (await handle(req("GET", "/admin/denuncias", auth()), env)).json();
  assert.equal(g.denuncias.length, 500);
  assert.equal(g.denuncias[0].uid, "u502"); // mais recente primeiro
  assert.equal(g.denuncias.at(-1).uid, "u3");
});

test("por e tirar mudam o estado da Conta", async () => {
  const { env } = ambiente();
  const suspenso = async () => (await (await env.CONTA.get("bruno").fetch("https://conta/suspensao")).json()).suspenso;
  assert.equal(await suspenso(), false);
  const a = await handle(req("POST", "/admin/suspensao?uid=bruno&acao=por", auth()), env);
  assert.equal(a.status, 200);
  assert.deepEqual(await a.json(), { uid: "bruno", suspenso: true });
  assert.equal(await suspenso(), true);
  const b = await handle(req("POST", "/admin/suspensao?uid=bruno&acao=tirar", auth()), env);
  assert.deepEqual(await b.json(), { uid: "bruno", suspenso: false });
  assert.equal(await suspenso(), false);
});
