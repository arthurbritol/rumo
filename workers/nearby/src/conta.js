// Durable Object "Conta" (uma por pessoa, nomeada pelo uid; só a Celula alcança, via binding).
// Guarda se a pessoa tem uma proposta de encontro aberta e até quando ela está de espera.
//
//   POST /abrir               -> 200 {ok:true}
//                                409 {ok:false, erro:"ja_tem_proposta"}
//                                409 {ok:false, erro:"espera", restanteMs}
//   POST /fechar?motivo=<m>   -> 200 {ok:true}; cancelado, expirou e recusa geram 5 min de espera.
//
// Segurança de estado: uma proposta "aberta" há mais de 2 h é tratada como fechada (um encontro
// dura no máximo 30 min de proposta + 60 min em andamento), para um fechar perdido nunca
// travar a pessoa para sempre. O estado fica no storage do próprio Durable Object.

const ESPERA_MS = 5 * 60_000;
const ABERTA_MAX_MS = 2 * 60 * 60_000;
const COM_ESPERA = new Set(["cancelado", "expirou", "recusa"]);
const SEM_ESPERA = new Set(["chegaram", "ninguem_chegou", "falha"]);

const json = (corpo, status = 200) =>
  new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });

export class Conta {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.now = () => Date.now(); // trocável nos testes
  }

  async estado() {
    return (await this.ctx.storage.get("estado")) ?? { aberta: false, abertaEm: 0, cooldownUntil: 0 };
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname !== "/abrir" && url.pathname !== "/fechar") return json({ ok: false, erro: "nao_encontrado" }, 404);
    if (request.method !== "POST") return json({ ok: false, erro: "metodo" }, 405);
    const agora = this.now();
    const e = await this.estado();

    if (url.pathname === "/abrir") {
      if (e.aberta && agora - e.abertaEm < ABERTA_MAX_MS) {
        return json({ ok: false, erro: "ja_tem_proposta" }, 409);
      }
      if (agora < e.cooldownUntil) {
        return json({ ok: false, erro: "espera", restanteMs: e.cooldownUntil - agora }, 409);
      }
      await this.ctx.storage.put("estado", { aberta: true, abertaEm: agora, cooldownUntil: e.cooldownUntil });
      return json({ ok: true });
    }

    const motivo = url.searchParams.get("motivo") ?? "";
    if (!COM_ESPERA.has(motivo) && !SEM_ESPERA.has(motivo)) return json({ ok: false, erro: "motivo_invalido" }, 400);
    const cooldownUntil = COM_ESPERA.has(motivo) ? agora + ESPERA_MS : e.cooldownUntil;
    await this.ctx.storage.put("estado", { aberta: false, abertaEm: 0, cooldownUntil });
    return json({ ok: true });
  }
}
