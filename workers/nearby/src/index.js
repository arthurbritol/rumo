// Ponto de entrada do Worker `nearby`: roteador HTTP/WebSocket.
//
//   GET /health                       -> 200 "ok"
//   GET /ws?cell=<lat>:<lon>&t=<token> (Upgrade: websocket) -> repassa à Celula do quadrado
//   GET /admin/denuncias[?uid=<uid>]  (Authorization: Bearer <ADMIN_TOKEN>) -> denúncias do uid, ou as 500 mais
//     recentes de todos (índice na Conta reservada INDICE_UID); POST /admin/suspensao?uid=&acao=tirar|por
//     -> {uid, suspenso}. Rotas do dono: nunca passam pelo /ws nem levam x-rumo-*, nada disso vai ao app.
//   GET /ws?cell=<id>&enc=<id do encontro>&t=<token>        -> conexão extra do encontro: `cell` é o
//     quadrado do PONTO marcado (o dono do encontro), mesmo que a pessoa esteja em outro quadrado
//
// O Worker confere o ID token do Firebase e só ele define os cabeçalhos internos
// x-rumo-uid / x-rumo-exp / x-rumo-cell / x-rumo-enc em que a Celula confia (qualquer x-rumo-* do cliente é apagado). Nada do cliente com esses nomes passa.
// O token (parâmetro `t`) e o uid nunca são registrados em log nem repassados na URL.

import { verifyIdToken, fetchGoogleKeys } from "./auth.js";
import { ID_ENCONTRO_RE } from "./protocol.js";
import { INDICE_UID } from "./conta.js";

export { Celula } from "./celula.js";
export { Conta } from "./conta.js";

// Mesmo retângulo do Worker de trânsito (traffic-proxy), com folga nas fronteiras.
const BRAZIL = { south: -34.0, north: 6.0, west: -74.5, east: -33.5 };

// Forma canônica do id do quadrado (a mesma de cellId() do app): inteiros sem zeros à esquerda,
// sem "+" e sem "-0". Qualquer outra grafia do mesmo quadrado ("-021:-41", "-0:-41") é recusada,
// então cada quadrado tem um único nome de Durable Object e uma única Celula.
const CELL_RE = /^(0|-?[1-9]\d{0,2}):(0|-?[1-9]\d{0,3})$/;

// O quadrado (1° x 1°, graus inteiros pelo piso) cruza o retângulo do Brasil?
function cellNoBrasil(cell) {
  if (!CELL_RE.test(cell)) return false;
  const [la, lo] = cell.split(":").map(Number);
  if (!Number.isSafeInteger(la) || !Number.isSafeInteger(lo)) return false;
  return la + 1 > BRAZIL.south && la < BRAZIL.north && lo + 1 > BRAZIL.west && lo < BRAZIL.east;
}

const texto = (msg, status) => new Response(msg, { status });

const ADMIN_UID_RE = /^[A-Za-z0-9_-]{1,128}$/;
const jsonAdmin = (corpo, status = 200) =>
  new Response(JSON.stringify(corpo), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });

// Comparação em tempo constante: SHA-256 dos dois lados (tamanho fixo) e XOR de todos os bytes,
// sem retorno antecipado por tamanho nem por byte diferente.
async function tokenConfere(recebido, esperado) {
  const enc = new TextEncoder();
  const [x, y] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(recebido)),
    crypto.subtle.digest("SHA-256", enc.encode(esperado)),
  ]);
  const a = new Uint8Array(x);
  const b = new Uint8Array(y);
  let dif = 0;
  for (let i = 0; i < a.length; i++) dif |= a[i] ^ b[i];
  return dif === 0;
}

async function admin(request, env, url) {
  if (!env?.ADMIN_TOKEN) return jsonAdmin({ erro: "server not configured" }, 500);
  const denuncias = url.pathname === "/admin/denuncias";
  if (request.method !== (denuncias ? "GET" : "POST")) return jsonAdmin({ erro: "metodo" }, 405);
  const cab = request.headers.get("Authorization") ?? "";
  const m = /^Bearer (.+)$/.exec(cab);
  // A comparação sempre roda (cabeçalho ausente compara com string vazia).
  const confere = await tokenConfere(m ? m[1] : "", env.ADMIN_TOKEN);
  if (!m || !confere) return jsonAdmin({ erro: "não autorizado" }, 401);

  const uid = url.searchParams.get("uid");
  if (uid !== null && (!ADMIN_UID_RE.test(uid) || uid === INDICE_UID)) return jsonAdmin({ erro: "uid inválido" }, 400);
  if (!env.CONTA) return jsonAdmin({ erro: "server not configured" }, 500);
  const conta = (nome, caminho, method) =>
    env.CONTA.get(env.CONTA.idFromName(nome)).fetch(`https://conta${caminho}`, { method });

  try {
    if (denuncias) {
      const r = uid === null ? await conta(INDICE_UID, "/indice", "GET") : await conta(uid, "/denuncias", "GET");
      const c = await r.json();
      const lista = (c.denuncias ?? []).map((d) => ({ uid: d.uid ?? uid, por: d.por, em: d.em, motivo: d.motivo, lat: d.lat ?? null, lon: d.lon ?? null }));
      return jsonAdmin({ denuncias: lista.reverse() });
    }
    if (uid === null) return jsonAdmin({ erro: "uid obrigatório" }, 400);
    const acao = url.searchParams.get("acao");
    if (acao !== "tirar" && acao !== "por") return jsonAdmin({ erro: "acao inválida" }, 400);
    const r = await conta(uid, `/suspensao?acao=${acao}`, "POST");
    const c = await r.json();
    if (r.status !== 200 || c.ok !== true) return jsonAdmin({ erro: "falha" }, 502);
    return jsonAdmin({ uid, suspenso: c.suspenso === true });
  } catch {
    return jsonAdmin({ erro: "falha" }, 502);
  }
}

// Roteador testável: `deps.getKeys` e `deps.now` são injetáveis.
export async function handle(request, env, deps = {}) {
  const url = new URL(request.url);

  if (url.pathname === "/health") return texto("ok", 200);
  if (url.pathname === "/admin/denuncias" || url.pathname === "/admin/suspensao") return admin(request, env, url);
  if (url.pathname !== "/ws" || request.method !== "GET") return texto("não encontrado", 404);

  // Ambiente incompleto: erro genérico, antes de mexer no token.
  if (!env?.FIREBASE_PROJECT_ID || !env?.NEARBY_SECRET) return texto("server not configured", 500);

  const cell = url.searchParams.get("cell") ?? "";
  if (!cellNoBrasil(cell)) return texto("quadrado inválido", 404);

  if (request.headers.get("Upgrade") !== "websocket") return texto("esperava WebSocket", 426);

  // Conexão do encontro (opcional): só o formato dos ids gerados pelo servidor.
  const enc = url.searchParams.get("enc");
  if (enc !== null && !ID_ENCONTRO_RE.test(enc)) return texto("encontro inválido", 400);

  const token = url.searchParams.get("t");
  if (!token) return texto("não autorizado", 401);

  let ident;
  try {
    ident = await verifyIdToken(token, {
      projectId: env.FIREBASE_PROJECT_ID,
      now: deps.now,
      getKeys: deps.getKeys ?? (() => fetchGoogleKeys()),
    });
  } catch {
    return texto("não autorizado", 401);
  }

  if (ident.uid === INDICE_UID) return texto("não autorizado", 401); // uid reservado da Conta índice

  // Repasse sem o token na URL e sem nenhum cabeçalho interno vindo do cliente.
  url.searchParams.delete("t");
  url.searchParams.delete("enc");
  const headers = new Headers(request.headers);
  for (const nome of [...headers.keys()]) {
    if (nome.toLowerCase().startsWith("x-rumo-")) headers.delete(nome);
  }
  headers.set("x-rumo-uid", ident.uid);
  headers.set("x-rumo-exp", String(ident.exp));
  headers.set("x-rumo-cell", cell);
  if (enc !== null) headers.set("x-rumo-enc", enc);

  const repasse = new Request(url.toString(), { method: "GET", headers });
  const celula = env.CELULA.get(env.CELULA.idFromName(cell));
  return celula.fetch(repasse);
}

export default {
  fetch(request, env) {
    return handle(request, env);
  },
};
