// Verificação do ID token do Firebase (RS256) só com Web Crypto, sem dependências.

const JWK_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const TOLERANCIA_S = 300; // tolerância de relógio, para exp e iat
const CACHE_MIN_S = 60;
const CACHE_PADRAO_S = 3600;

function invalido(motivo) {
  return new Error(`token inválido: ${motivo}`);
}

function decodeB64url(s) {
  if (typeof s !== "string" || !/^[A-Za-z0-9_-]*$/.test(s)) throw invalido("base64 malformado");
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(s.length / 4) * 4, "="));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function decodeJson(s) {
  let obj;
  try {
    obj = JSON.parse(new TextDecoder().decode(decodeB64url(s)));
  } catch {
    throw invalido("JSON malformado");
  }
  if (obj === null || typeof obj !== "object" || Array.isArray(obj)) throw invalido("JSON malformado");
  return obj;
}

async function importarChave(key) {
  if (key && typeof key === "object" && key.type === "public" && key.algorithm) return key; // CryptoKey
  return crypto.subtle.importKey(
    "jwk",
    key,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
}

// Confere o token e devolve { uid, exp } (exp em segundos). Lança Error("token inválido: ...").
export async function verifyIdToken(token, { projectId, now, getKeys }) {
  if (typeof token !== "string") throw invalido("formato");
  const partes = token.split(".");
  if (partes.length !== 3) throw invalido("formato");
  const [hB64, pB64, sB64] = partes;

  const header = decodeJson(hB64);
  if (header.alg !== "RS256") throw invalido("algoritmo");
  if (typeof header.kid !== "string" || header.kid === "") throw invalido("kid ausente");

  let chaves;
  try {
    chaves = await getKeys();
  } catch {
    throw invalido("chaves indisponíveis");
  }
  if (!chaves || !Object.prototype.hasOwnProperty.call(chaves, header.kid)) throw invalido("kid desconhecido");

  let ok = false;
  try {
    const chave = await importarChave(chaves[header.kid]);
    ok = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      chave,
      decodeB64url(sB64),
      new TextEncoder().encode(`${hB64}.${pB64}`),
    );
  } catch {
    ok = false;
  }
  if (!ok) throw invalido("assinatura");

  const p = decodeJson(pB64);
  if (p.iss !== `https://securetoken.google.com/${projectId}`) throw invalido("iss");
  if (p.aud !== projectId) throw invalido("aud");
  if (typeof p.sub !== "string" || p.sub === "") throw invalido("sub");

  const agora = now ?? Math.floor(Date.now() / 1000);
  if (typeof p.exp !== "number" || !Number.isFinite(p.exp)) throw invalido("exp");
  if (typeof p.iat !== "number" || !Number.isFinite(p.iat)) throw invalido("iat");
  if (p.exp + TOLERANCIA_S < agora) throw invalido("expirado");
  if (p.iat - TOLERANCIA_S > agora) throw invalido("iat no futuro");

  return { uid: p.sub, exp: p.exp };
}

// Cache por função de busca: { keys, ate (ms) }.
const caches = new WeakMap();

// Baixa as chaves públicas do Google (JWK por kid), com cache pelo max-age (mín. 60 s, padrão 3600 s).
export async function fetchGoogleKeys(fetchFn = fetch, nowMs = () => Date.now()) {
  const c = caches.get(fetchFn);
  if (c && nowMs() < c.ate) return c.keys;

  const resp = await fetchFn(JWK_URL);
  if (!resp.ok) throw new Error(`falha ao buscar chaves do Google: HTTP ${resp.status}`);
  const corpo = await resp.json();
  const keys = {};
  for (const k of corpo.keys ?? []) if (k && typeof k.kid === "string") keys[k.kid] = k;

  const m = /max-age=(\d+)/.exec(resp.headers.get("cache-control") ?? "");
  const segundos = Math.max(m ? parseInt(m[1], 10) : CACHE_PADRAO_S, CACHE_MIN_S);
  caches.set(fetchFn, { keys, ate: nowMs() + segundos * 1000 });
  return keys;
}
