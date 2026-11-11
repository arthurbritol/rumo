// Ponto de entrada do Worker. Provisório: a Tarefa 5 troca o fetch pelo roteador real
// (autenticação do token, escolha da Celula pelo quadrado, validação do ambiente).

export { Celula } from "./celula.js";
export { Conta } from "./conta.js";

export default {
  async fetch() {
    return new Response("não encontrado", { status: 404 });
  },
};
