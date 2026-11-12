// Ponto de entrada do Worker `nearby`: roteador HTTP/WebSocket.
//
//   GET /health                       -> 200 "ok"
//   GET /ws?cell=<lat>:<lon>&t=<token> (Upgrade: websocket) -> repassa à Celula do quadrado
//
// O Worker confere o ID token do Firebase e só ele define os cabeçalhos internos
// x-rumo-uid / x-rumo-exp que a Celula confia. Nada do cliente com esses nomes passa.
// O token (parâmetro `t`) e o uid nunca são registrados em log nem repassados na URL.

import { verifyIdToken, fetchGoogleKeys } from "./auth.js";

export { Celula } from "./celula.js";
export { Conta } from "./conta.js";

// Mesmo retângulo do Worker de trânsito (traffic-proxy), com folga nas fronteiras.
const BRAZIL = { south: -34.0, north: 6.0, west: -74.5, east: -33.5 };

const CELL_RE = /^-?\d+:-?\d+$/;

// O quadrado (1° x 1°, graus inteiros pelo piso) cruza o retângulo do Brasil?
function cellNoBrasil(cell) {
  if (!CELL_RE.test(cell)) return false;
  const [la, lo] = cell.split(":").map(Number);
  if (!Number.isSafeInteger(la) || !Number.isSafeInteger(lo)) return false;
  return la + 1 > BRAZIL.south && la < BRAZIL.north && lo + 1 > BRAZIL.west && lo < BRAZIL.east;
}

const texto = (msg, status) => new Response(msg, { status });

// Roteador testável: `deps.getKeys` e `deps.now` são injetáveis.
export async function handle(request, env, deps = {}) {
  const url = new URL(request.url);

  if (url.pathname === "/health") return texto("ok", 200);
  if (url.pathname !== "/ws" || request.method !== "GET") return texto("não encontrado", 404);

  // Ambiente incompleto: erro genérico, antes de mexer no token.
  if (!env?.FIREBASE_PROJECT_ID || !env?.NEARBY_SECRET) return texto("server not configured", 500);

  const cell = url.searchParams.get("cell") ?? "";
  if (!cellNoBrasil(cell)) return texto("quadrado inválido", 404);

  if (request.headers.get("Upgrade") !== "websocket") return texto("esperava WebSocket", 426);

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

  // Repasse sem o token na URL e sem nenhum cabeçalho interno vindo do cliente.
  url.searchParams.delete("t");
  const headers = new Headers(request.headers);
  headers.delete("x-rumo-uid");
  headers.delete("x-rumo-exp");
  headers.set("x-rumo-uid", ident.uid);
  headers.set("x-rumo-exp", String(ident.exp));

  const repasse = new Request(url.toString(), { method: "GET", headers });
  const celula = env.CELULA.get(env.CELULA.idFromName(cell));
  return celula.fetch(repasse);
}

export default {
  fetch(request, env) {
    return handle(request, env);
  },
};
