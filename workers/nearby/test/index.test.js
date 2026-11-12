// Testes do roteador do Worker: tokens RS256 assinados por uma chave de teste e uma Celula falsa.
import test from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";

const PROJECT = "rumo-test";
const enc = new TextEncoder();
const b64url = (b) => Buffer.from(b).toString("base64url");
const b64urlJson = (o) => b64url(enc.encode(JSON.stringify(o)));

const par = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"],
);
const getKeys = async () => ({ k1: par.publicKey });

async function token({ uid = "uid-verdadeiro", exp, projeto = PROJECT } = {}) {
  const agora = Math.floor(Date.now() / 1000);
  const corpo = `${b64urlJson({ alg: "RS256", kid: "k1" })}.${b64urlJson({
    iss: `https://securetoken.google.com/${projeto}`,
    aud: projeto,
    sub: uid,
    iat: agora - 10,
    exp: exp ?? agora + 3600,
  })}`;
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", par.privateKey, enc.encode(corpo));
  return `${corpo}.${b64url(new Uint8Array(sig))}`;
}

// Celula falsa: grava o nome pedido e o Request recebido.
function novoAmbiente(extra = {}) {
  const reg = { nomes: [], pedidos: [] };
  const env = {
    FIREBASE_PROJECT_ID: PROJECT,
    NEARBY_SECRET: "segredo",
    CELULA: {
      idFromName(nome) {
        reg.nomes.push(nome);
        return `id:${nome}`;
      },
      get() {
        return {
          async fetch(req) {
            reg.pedidos.push(req);
            return new Response("repassado", { status: 200 });
          },
        };
      },
    },
    ...extra,
  };
  return { env, reg };
}

const pedido = (url, headers = {}) =>
  new Request(`https://nearby.example${url}`, { headers: { Upgrade: "websocket", ...headers } });
const chamar = (req, env) => handle(req, env, { getKeys });

test("/health responde 200 ok", async () => {
  const { env } = novoAmbiente();
  const r = await chamar(new Request("https://nearby.example/health"), env);
  assert.equal(r.status, 200);
  assert.equal(await r.text(), "ok");
});

test("caminho desconhecido: 404", async () => {
  const { env } = novoAmbiente();
  assert.equal((await chamar(new Request("https://nearby.example/x"), env)).status, 404);
});

test("sem token: 401", async () => {
  const { env, reg } = novoAmbiente();
  const r = await chamar(pedido("/ws?cell=-21:-41"), env);
  assert.equal(r.status, 401);
  assert.equal(reg.pedidos.length, 0);
});

test("token inválido: 401", async () => {
  const { env, reg } = novoAmbiente();
  assert.equal((await chamar(pedido("/ws?cell=-21:-41&t=lixo"), env)).status, 401);
  const outroProjeto = await token({ projeto: "outro" });
  assert.equal((await chamar(pedido(`/ws?cell=-21:-41&t=${outroProjeto}`), env)).status, 401);
  assert.equal(reg.pedidos.length, 0);
});

test("cell mal formada ou fora do Brasil: 404", async () => {
  const { env, reg } = novoAmbiente();
  const t = await token();
  for (const cell of ["abc", "1.5:2", "-21", "-21:-41:3", "", "40:-3", "-21:10", "-60:-41", "20:-50"]) {
    const r = await chamar(pedido(`/ws?cell=${encodeURIComponent(cell)}&t=${t}`), env);
    assert.equal(r.status, 404, `cell ${cell}`);
  }
  assert.equal((await chamar(pedido(`/ws?t=${t}`), env)).status, 404);
  assert.equal(reg.pedidos.length, 0);
});

test("quadrados do Brasil são aceitos", async () => {
  const { env } = novoAmbiente();
  const t = await token();
  for (const cell of ["-21:-41", "-34:-54", "5:-60", "-34:-74", "-1:-34"]) {
    const r = await chamar(pedido(`/ws?cell=${cell}&t=${t}`), env);
    assert.equal(r.status, 200, `cell ${cell}`);
  }
});

test("sem Upgrade: 426", async () => {
  const { env } = novoAmbiente();
  const t = await token();
  const r = await chamar(new Request(`https://nearby.example/ws?cell=-21:-41&t=${t}`), env);
  assert.equal(r.status, 426);
});

test("ambiente sem FIREBASE_PROJECT_ID ou NEARBY_SECRET: 500 server not configured", async () => {
  const t = await token();
  for (const faltando of ["FIREBASE_PROJECT_ID", "NEARBY_SECRET"]) {
    const { env, reg } = novoAmbiente({ [faltando]: undefined });
    const r = await chamar(pedido(`/ws?cell=-21:-41&t=${t}`), env);
    assert.equal(r.status, 500);
    assert.equal(await r.text(), "server not configured");
    assert.equal(reg.pedidos.length, 0);
  }
});

test("token válido: repassa à Celula do quadrado com uid e exp do token", async () => {
  const { env, reg } = novoAmbiente();
  const exp = Math.floor(Date.now() / 1000) + 1800;
  const t = await token({ uid: "uid-abc", exp });
  const r = await chamar(pedido(`/ws?cell=-21:-41&t=${t}`), env);
  assert.equal(r.status, 200);
  assert.deepEqual(reg.nomes, ["-21:-41"]);
  const enviado = reg.pedidos[0];
  assert.equal(enviado.headers.get("x-rumo-uid"), "uid-abc");
  assert.equal(enviado.headers.get("x-rumo-exp"), String(exp));
  assert.equal(enviado.headers.get("Upgrade"), "websocket");
});

test("o token não é repassado à Celula (nem na URL)", async () => {
  const { env, reg } = novoAmbiente();
  const t = await token();
  await chamar(pedido(`/ws?cell=-21:-41&t=${t}`), env);
  const url = new URL(reg.pedidos[0].url);
  assert.equal(url.searchParams.has("t"), false);
  assert.equal(reg.pedidos[0].url.includes(t), false);
});

test("SEGURANÇA: cabeçalho x-rumo-uid forjado com token válido é substituído pelo uid do token", async () => {
  const { env, reg } = novoAmbiente();
  const exp = Math.floor(Date.now() / 1000) + 1800;
  const t = await token({ uid: "uid-do-token", exp });
  const r = await chamar(
    pedido(`/ws?cell=-21:-41&t=${t}`, { "x-rumo-uid": "uid-da-vitima", "X-Rumo-Exp": "9999999999", "X-RUMO-UID": "outro" }),
    env,
  );
  assert.equal(r.status, 200);
  const h = reg.pedidos[0].headers;
  assert.equal(h.get("x-rumo-uid"), "uid-do-token");
  assert.equal(h.get("x-rumo-exp"), String(exp));
});

test("SEGURANÇA: cabeçalho x-rumo-uid forjado sem token válido: 401 e nada chega à Celula", async () => {
  const { env, reg } = novoAmbiente();
  assert.equal((await chamar(pedido("/ws?cell=-21:-41", { "x-rumo-uid": "uid-da-vitima" }), env)).status, 401);
  assert.equal((await chamar(pedido("/ws?cell=-21:-41&t=lixo", { "x-rumo-uid": "uid-da-vitima" }), env)).status, 401);
  assert.equal(reg.pedidos.length, 0);
});
