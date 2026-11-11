// Testes da verificação do ID token do Firebase: tokens RS256 assinados de verdade, sem rede.
import test from "node:test";
import assert from "node:assert/strict";
import { verifyIdToken, fetchGoogleKeys } from "../src/auth.js";

const PROJECT = "rumo-test";
const NOW = 1_800_000_000; // segundos
const enc = new TextEncoder();

function b64url(bytes) {
  return Buffer.from(bytes).toString("base64url");
}
function b64urlJson(obj) {
  return b64url(enc.encode(JSON.stringify(obj)));
}

async function novoPar() {
  return crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  );
}

async function assinar(privateKey, header, payload) {
  const corpo = `${b64urlJson(header)}.${b64urlJson(payload)}`;
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privateKey, enc.encode(corpo));
  return `${corpo}.${b64url(new Uint8Array(sig))}`;
}

const par = await novoPar();
const outroPar = await novoPar();
const getKeys = async () => ({ k1: par.publicKey });

function payloadOk(extra = {}) {
  return {
    iss: `https://securetoken.google.com/${PROJECT}`,
    aud: PROJECT,
    sub: "uid-123",
    iat: NOW - 10,
    exp: NOW + 3600,
    ...extra,
  };
}
const opts = { projectId: PROJECT, now: NOW, getKeys };

async function rejeita(token, o = opts) {
  await assert.rejects(() => verifyIdToken(token, o), /^Error: token inválido: /);
}

test("token válido devolve uid e exp", async () => {
  const t = await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk());
  assert.deepEqual(await verifyIdToken(t, opts), { uid: "uid-123", exp: NOW + 3600 });
});

test("aceita chave em formato JWK", async () => {
  const jwk = await crypto.subtle.exportKey("jwk", par.publicKey);
  const t = await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk());
  const r = await verifyIdToken(t, { ...opts, getKeys: async () => ({ k1: jwk }) });
  assert.equal(r.uid, "uid-123");
});

test("rejeita assinatura de outra chave", async () => {
  const t = await assinar(outroPar.privateKey, { alg: "RS256", kid: "k1" }, payloadOk());
  await rejeita(t);
});

test("rejeita payload adulterado", async () => {
  const t = await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk());
  const [h, , s] = t.split(".");
  await rejeita(`${h}.${b64urlJson(payloadOk({ sub: "outro" }))}.${s}`);
});

test("rejeita aud errado", async () => {
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk({ aud: "outro" })));
});

test("rejeita iss errado", async () => {
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk({ iss: "https://evil.example/" + PROJECT })));
});

test("rejeita alg=none e alg=HS256", async () => {
  const corpo = `${b64urlJson({ alg: "none", kid: "k1" })}.${b64urlJson(payloadOk())}`;
  await rejeita(`${corpo}.`);
  await rejeita(`${corpo}.AAAA`);
  await rejeita(await assinar(par.privateKey, { alg: "HS256", kid: "k1" }, payloadOk()));
});

test("rejeita kid desconhecido ou ausente", async () => {
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "zzz" }, payloadOk()));
  await rejeita(await assinar(par.privateKey, { alg: "RS256" }, payloadOk()));
});

test("rejeita token com 2 partes, 4 partes, lixo e vazio", async () => {
  const t = await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk());
  const [h, p] = t.split(".");
  await rejeita(`${h}.${p}`);
  await rejeita(`${t}.x`);
  await rejeita("isso.nao.eJson");
  await rejeita("");
});

test("rejeita sub vazio ou ausente", async () => {
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk({ sub: "" })));
  const p = payloadOk();
  delete p.sub;
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, p));
});

test("rejeita exp ou iat ausentes ou não numéricos", async () => {
  const p1 = payloadOk();
  delete p1.exp;
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, p1));
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk({ exp: "9999999999" })));
  const p2 = payloadOk();
  delete p2.iat;
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, p2));
});

test("rejeita exp vencido há mais de 300 s", async () => {
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk({ exp: NOW - 301 })));
});

test("aceita exp vencido há 200 s (relógio atrasado)", async () => {
  const t = await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk({ exp: NOW - 200 }));
  assert.equal((await verifyIdToken(t, opts)).uid, "uid-123");
});

test("aceita iat 200 s no futuro e rejeita 301 s no futuro", async () => {
  const ok = await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk({ iat: NOW + 200 }));
  assert.equal((await verifyIdToken(ok, opts)).uid, "uid-123");
  await rejeita(await assinar(par.privateKey, { alg: "RS256", kid: "k1" }, payloadOk({ iat: NOW + 301 })));
});

// --- fetchGoogleKeys ---

function fakeFetch(maxAge) {
  const f = async () => {
    f.chamadas++;
    return new Response(JSON.stringify({ keys: [{ kid: "a", kty: "RSA", n: "x", e: "AQAB" }] }), {
      status: 200,
      headers: { "cache-control": `public, max-age=${maxAge}, must-revalidate` },
    });
  };
  f.chamadas = 0;
  return f;
}

test("fetchGoogleKeys usa cache e busca de novo após o prazo", async () => {
  const f = fakeFetch(1000);
  let t = 1_000_000;
  const agora = () => t;
  const k1 = await fetchGoogleKeys(f, agora);
  const k2 = await fetchGoogleKeys(f, agora);
  assert.equal(f.chamadas, 1);
  assert.deepEqual(Object.keys(k1), ["a"]);
  assert.deepEqual(k1, k2);
  t += 1_001_000;
  await fetchGoogleKeys(f, agora);
  assert.equal(f.chamadas, 2);
});

test("fetchGoogleKeys impõe mínimo de 60 s", async () => {
  const f = fakeFetch(5);
  let t = 5_000_000;
  const agora = () => t;
  await fetchGoogleKeys(f, agora);
  t += 30_000;
  await fetchGoogleKeys(f, agora);
  assert.equal(f.chamadas, 1);
  t += 31_000;
  await fetchGoogleKeys(f, agora);
  assert.equal(f.chamadas, 2);
});

test("fetchGoogleKeys falha com resposta não ok", async () => {
  const f = async () => new Response("x", { status: 500 });
  await assert.rejects(() => fetchGoogleKeys(f, () => 9_000_000_000), /chaves/);
});
