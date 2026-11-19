// Ponto de entrada SÓ do teste de carga local (nunca é publicado): o mesmo roteador de produção
// (src/index.js), mas confiando numa chave de teste em vez das chaves do Google. Assim o teste
// exercita de verdade a conferência do ID token (RS256), os cabeçalhos x-rumo-* e as Durable Objects.
// A chave pública vem de LOADTEST_JWK (arquivo .dev.vars gerado por simulate.js a cada execução).

import { handle } from "../src/index.js";

export { Celula, Conta } from "../src/index.js";

export default {
  fetch(request, env) {
    if (!env.LOADTEST_JWK) return new Response("LOADTEST_JWK ausente", { status: 500 });
    const jwk = JSON.parse(env.LOADTEST_JWK);
    return handle(request, env, { getKeys: async () => ({ [jwk.kid]: jwk }) });
  },
};
