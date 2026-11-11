// Durable Object "Celula": uma por quadrado de 1° x 1°. Aceita WebSockets com a API de
// hibernação e usa Presence para dizer a cada pessoa quem está por perto.
//
// Contrato com o Worker (Tarefa 5): só o Worker alcança este objeto, e ele já verificou o token.
// Cabeçalhos internos do pedido de upgrade:
//   x-rumo-uid  (obrigatório) uid do Firebase de quem conectou. Nunca sai daqui.
//   x-rumo-exp  (opcional) exp do token, em segundos; a conexão é fechada 300 s depois dele.
//
// Hibernação: o estado de cada pessoa (Presence.export) é gravado no anexo do próprio WebSocket
// (serializeAttachment, limite de 2 KB) a cada mudança, e o construtor reconstrói a Presence
// dos anexos de ctx.getWebSockets(). Não há nada em ctx.storage além do alarme.
//
// O servidor nunca manda uid, posição real ou qualquer identificação: só o que Presence devolve.

import { Presence } from "./presence.js";
import { parseClientMessage } from "./protocol.js";

const INTERVALO_MIN_ENVIO_MS = 2000; // no máximo uma lista a cada 2 s por conexão
const ALARME_MS = 30_000; // varredura de quem ficou sem sinal
const FOLGA_EXP_MS = 300_000; // tolerância de relógio no exp do token

export class Celula {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.now = () => Date.now(); // trocável nos testes
    this.presence = new Presence({ now: () => this.now(), secret: env.NEARBY_SECRET });
    this.ultimoEnvio = new Map(); // connId -> ms do último envio
    this.pendentes = new Set(); // connIds com lista nova esperando o intervalo
    this.timer = null;
    // Depois de acordar da hibernação: reconstrói a presença dos anexos.
    for (const ws of ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a?.presence) this.presence.import(a.connId, a.presence);
    }
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("esperava WebSocket", { status: 426 });
    }
    const uid = request.headers.get("x-rumo-uid");
    if (!uid) return new Response("uid ausente", { status: 400 });
    const exp = Number(request.headers.get("x-rumo-exp"));

    const { 0: cliente, 1: servidor } = new WebSocketPair();
    this.ctx.acceptWebSocket(servidor);
    servidor.serializeAttachment({
      connId: crypto.randomUUID(),
      uid,
      exp: Number.isFinite(exp) && exp > 0 ? exp : null,
      presence: null,
    });
    if ((await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(this.now() + ALARME_MS);
    }
    return new Response(null, { status: 101, webSocket: cliente });
  }

  async webSocketMessage(ws, mensagem) {
    const a = ws.deserializeAttachment();
    if (this.expirou(a)) {
      this.fechar(ws, "token expirado");
      this.mudou();
      return;
    }

    const r = parseClientMessage(mensagem);
    if (!r.ok) return this.erro(ws, "mensagem_invalida");
    const msg = r.msg;

    if (msg.t === "procurar") {
      this.presence.join(a.connId, a.uid, msg);
      this.guardar(ws, a);
      this.mudou();
    } else if (msg.t === "parar") {
      this.presence.leave(a.connId);
      this.guardar(ws, a);
      this.enviar(ws, []);
      this.mudou();
    } else if (msg.t === "posicao") {
      try {
        // Posição antes do piso de 10 s é ignorada em silêncio (sem guardar nem notificar).
        if (!this.presence.update(a.connId, msg)) return;
      } catch (e) {
        if (e.message === "posição implausível") return this.erro(ws, "posicao_implausivel");
        if (e.message === "conexão não está procurando") return this.erro(ws, "nao_procurando");
        throw e;
      }
      this.guardar(ws, a);
      this.mudou();
    }
  }

  webSocketClose(ws) {
    this.sair(ws);
    try {
      ws.close();
    } catch {
      // já fechado
    }
  }

  webSocketError(ws) {
    this.sair(ws);
  }

  // Varredura periódica: remove quem está sem sinal há mais de 2 min e conexões com token vencido.
  async alarm() {
    for (const connId of this.presence.expire()) {
      this.fechar(this.socketDe(connId), "sem sinal");
    }
    for (const ws of this.ctx.getWebSockets()) {
      if (this.expirou(ws.deserializeAttachment())) this.fechar(ws, "token expirado");
    }
    this.mudou();
    if (this.ctx.getWebSockets().length > 0) {
      await this.ctx.storage.setAlarm(this.now() + ALARME_MS);
    }
  }

  // --- auxiliares ---

  expirou(a) {
    return a?.exp != null && this.now() > a.exp * 1000 + FOLGA_EXP_MS;
  }

  socketDe(connId) {
    return this.ctx.getWebSockets().find((ws) => ws.deserializeAttachment()?.connId === connId);
  }

  guardar(ws, a) {
    ws.serializeAttachment({ ...a, presence: this.presence.export(a.connId) });
  }

  sair(ws) {
    const a = ws.deserializeAttachment();
    if (!a) return;
    this.presence.leave(a.connId);
    this.ultimoEnvio.delete(a.connId);
    this.pendentes.delete(a.connId);
    this.mudou();
  }

  fechar(ws, motivo) {
    if (!ws) return;
    const a = ws.deserializeAttachment();
    if (a) {
      this.presence.leave(a.connId);
      this.ultimoEnvio.delete(a.connId);
      this.pendentes.delete(a.connId);
    }
    try {
      ws.close(1000, motivo);
    } catch {
      // já fechado
    }
  }

  erro(ws, codigo) {
    try {
      ws.send(JSON.stringify({ t: "erro", codigo }));
    } catch {
      // socket já caiu
    }
  }

  enviar(ws, lista) {
    try {
      ws.send(JSON.stringify({ t: "vizinhos", lista }));
    } catch {
      // socket já caiu
    }
  }

  // Algo mudou: todos que estão procurando precisam de lista nova (respeitando os 2 s).
  mudou() {
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a?.presence) this.pendentes.add(a.connId);
    }
    this.despachar();
  }

  despachar() {
    const agora = this.now();
    let espera = null;
    for (const connId of [...this.pendentes]) {
      const falta = (this.ultimoEnvio.get(connId) ?? -Infinity) + INTERVALO_MIN_ENVIO_MS - agora;
      if (falta <= 0) {
        this.pendentes.delete(connId);
        this.ultimoEnvio.set(connId, agora);
        this.presence
          .neighborsFor(connId)
          .then((lista) => {
            const ws = this.socketDe(connId);
            if (ws) this.enviar(ws, lista);
          })
          .catch((e) => console.error("falha ao montar a lista de vizinhos", e));
      } else if (espera === null || falta < espera) {
        espera = falta;
      }
    }
    if (espera !== null && this.timer === null) {
      this.timer = setTimeout(() => {
        this.timer = null;
        this.despachar();
      }, espera);
    }
  }
}
