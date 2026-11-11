// Durable Object "Conta" (uma por pessoa). Esqueleto: a Tarefa 12 preenche a lógica.
// Existe agora porque o wrangler exige que as classes das migrations sejam exportadas.

export class Conta {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch() {
    return new Response("não implementado", { status: 501 });
  }
}
