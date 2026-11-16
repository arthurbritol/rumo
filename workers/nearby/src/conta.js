// Durable Object "Conta" (uma por pessoa, nomeada pelo uid; só a Celula alcança, via binding).
// Guarda se a pessoa tem uma proposta de encontro aberta e até quando ela está de espera.
//
//   POST /abrir[?enc=<id>]    -> 200 {ok:true}
//                                409 {ok:false, erro:"ja_tem_proposta"}
//                                409 {ok:false, erro:"espera", restanteMs}
//   POST /fechar?motivo=<m>[&enc=<id>] -> 200 {ok:true}; cancelado, expirou e recusa geram 5 min de espera.
//     Com `enc`, só fecha se for a proposta aberta com esse id; senão é ignorado (200, ignorado:true),
//     para um aviso atrasado/repetido nunca fechar a proposta nova nem prorrogar a espera.
//   POST /bloquear?uid=<outro>[&sentido=bloqueou|bloqueadoPor], GET /bloqueios  (Tarefa 15)
//   POST /cota-denuncia?alvo=<uid>[&desfazer=1], POST /denunciar?por=&motivo=[&lat=&lon=][&uid=<denunciado>], GET /denuncias
//   GET /suspensao -> {ok, suspenso, ate}; POST /suspensao?acao=por|tirar (Tarefa 16; manual, `ate` reservado
//     para a suspensão automática da Tarefa 22: null = não expira sozinha)
//   Conta especial "índice" (uid reservado INDICE_UID, nunca alcançável por um uid real: o Worker recusa
//     esse uid): POST /indice-add e GET /indice guardam as 500 denúncias mais recentes de todas as
//     contas, só para o dono listar sem uid. Gravado pela Conta do denunciado, melhor esforço.
//
// Segurança de estado: uma proposta "aberta" há mais de 2 h é tratada como fechada (um encontro
// dura no máximo 30 min de proposta + 60 min em andamento), para um fechar perdido nunca
// travar a pessoa para sempre. O estado fica no storage do próprio Durable Object.

const ESPERA_MS = 5 * 60_000;
const ABERTA_MAX_MS = 2 * 60 * 60_000;
const COM_ESPERA = new Set(["cancelado", "expirou", "recusa"]);
const SEM_ESPERA = new Set(["chegaram", "ninguem_chegou", "falha"]);

const ID_RE = /^[0-9a-f]{16}$/;
const UID_RE = /^[A-Za-z0-9_.:-]{1,128}$/;

// Bloqueios e denúncias (Tarefa 15). Tetos para o storage do Durable Object nunca crescer sem fim.
export const MAX_BLOQUEADOS = 500; // quem esta pessoa bloqueou (recusa acima disso)
const MAX_BLOQUEADO_POR = 5000; // quem bloqueou esta pessoa (recusa acima disso; nunca descarta entradas antigas)
const RESERVA_MS = 30_000; // reserva de cota ainda não confirmada vale 30 s
const MAX_DENUNCIAS = 200; // denúncias recebidas (mantém as mais recentes)
const DENUNCIA_DEDUP_MS = 24 * 3_600_000; // mesmo denunciante/denunciado em 24 h conta uma vez
const COTA_DENUNCIAS = 5; // denúncias por denunciante por hora
const COTA_JANELA_MS = 3_600_000;
export const INDICE_UID = "__indice__";
const MAX_INDICE = 500;
export const MOTIVOS_DENUNCIA = ["incomodo", "comportamento", "falso", "outro"];

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
    if (["/bloquear", "/bloqueios", "/cota-denuncia", "/denunciar", "/denuncias", "/suspensao", "/indice", "/indice-add"].includes(url.pathname)) {
      return this.seguranca(request, url);
    }
    if (url.pathname !== "/abrir" && url.pathname !== "/fechar") return json({ ok: false, erro: "nao_encontrado" }, 404);
    if (request.method !== "POST") return json({ ok: false, erro: "metodo" }, 405);
    const agora = this.now();
    const enc = url.searchParams.get("enc");
    if (enc !== null && !ID_RE.test(enc)) return json({ ok: false, erro: "enc_invalido" }, 400);
    const e = await this.estado();

    if (url.pathname === "/abrir") {
      if (e.aberta && agora - e.abertaEm < ABERTA_MAX_MS) {
        return json({ ok: false, erro: "ja_tem_proposta" }, 409);
      }
      if (agora < e.cooldownUntil) {
        return json({ ok: false, erro: "espera", restanteMs: e.cooldownUntil - agora }, 409);
      }
      await this.ctx.storage.put("estado", { aberta: true, abertaEm: agora, encId: enc, cooldownUntil: e.cooldownUntil });
      return json({ ok: true });
    }

    const motivo = url.searchParams.get("motivo") ?? "";
    if (!COM_ESPERA.has(motivo) && !SEM_ESPERA.has(motivo)) return json({ ok: false, erro: "motivo_invalido" }, 400);
    if (enc !== null && (!e.aberta || e.encId !== enc)) return json({ ok: true, ignorado: true });
    const cooldownUntil = COM_ESPERA.has(motivo) ? agora + ESPERA_MS : e.cooldownUntil;
    await this.ctx.storage.put("estado", { aberta: false, abertaEm: 0, encId: null, cooldownUntil });
    return json({ ok: true });
  }

  // --- bloqueios e denúncias (só a Celula alcança; os uids nunca saem do servidor) ---

  async seguranca(request, url) {
    if (url.pathname === "/suspensao" && request.method === "GET") {
      const s = (await this.ctx.storage.get("suspensao")) ?? { suspenso: false, ate: null };
      const vale = s.suspenso && (s.ate == null || s.ate > this.now());
      return json({ ok: true, suspenso: vale, ate: s.ate ?? null });
    }
    const leitura = url.pathname === "/bloqueios" || url.pathname === "/denuncias" || url.pathname === "/indice";
    if (request.method !== (leitura ? "GET" : "POST")) return json({ ok: false, erro: "metodo" }, 405);
    const agora = this.now();

    if (url.pathname === "/suspensao") {
      const acao = url.searchParams.get("acao");
      if (acao !== "por" && acao !== "tirar") return json({ ok: false, erro: "parametro_invalido" }, 400);
      await this.ctx.storage.put("suspensao", { suspenso: acao === "por", ate: null });
      return json({ ok: true, suspenso: acao === "por" });
    }
    if (url.pathname === "/indice") {
      return json({ ok: true, denuncias: (await this.ctx.storage.get("indice")) ?? [] });
    }
    if (url.pathname === "/indice-add") {
      const num = (n) => {
        const v = url.searchParams.get(n);
        const x = v === null || v === "" ? NaN : Number(v);
        return Number.isFinite(x) ? x : null;
      };
      const e = { uid: url.searchParams.get("uid") ?? "", por: url.searchParams.get("por") ?? "", em: agora,
        motivo: url.searchParams.get("motivo") ?? "", lat: num("lat"), lon: num("lon") };
      if (!UID_RE.test(e.uid) || !UID_RE.test(e.por) || !MOTIVOS_DENUNCIA.includes(e.motivo)) return json({ ok: false, erro: "parametro_invalido" }, 400);
      const l = (await this.ctx.storage.get("indice")) ?? [];
      l.push(e);
      if (l.length > MAX_INDICE) l.splice(0, l.length - MAX_INDICE);
      await this.ctx.storage.put("indice", l);
      return json({ ok: true });
    }

    if (url.pathname === "/bloqueios") {
      const b = (await this.ctx.storage.get("bloqueios")) ?? { bloqueados: [], bloqueadoPor: [] };
      return json({ ok: true, bloqueados: b.bloqueados, bloqueadoPor: b.bloqueadoPor });
    }
    if (url.pathname === "/denuncias") {
      return json({ ok: true, denuncias: (await this.ctx.storage.get("denuncias")) ?? [] });
    }

    if (url.pathname === "/bloquear") {
      // sentido "bloqueou": esta pessoa bloqueou `uid`; "bloqueadoPor": `uid` bloqueou esta pessoa.
      const alvo = url.searchParams.get("uid") ?? "";
      const sentido = url.searchParams.get("sentido") ?? "bloqueou";
      if (!UID_RE.test(alvo) || (sentido !== "bloqueou" && sentido !== "bloqueadoPor")) {
        return json({ ok: false, erro: "parametro_invalido" }, 400);
      }
      const b = (await this.ctx.storage.get("bloqueios")) ?? { bloqueados: [], bloqueadoPor: [] };
      const lista = sentido === "bloqueou" ? b.bloqueados : b.bloqueadoPor;
      if (!lista.includes(alvo)) {
        if (sentido === "bloqueou") {
          if (lista.length >= MAX_BLOQUEADOS) return json({ ok: false, erro: "limite" }, 409);
          lista.push(alvo);
        } else {
          if (lista.length >= MAX_BLOQUEADO_POR) return json({ ok: false, erro: "limite" }, 409);
          lista.push(alvo);
        }
        // `simular=1`: só confere os tetos (a Celula usa para responder igual sem gravar).
        if (url.searchParams.get("simular") !== "1") await this.ctx.storage.put("bloqueios", b);
      }
      return json({ ok: true });
    }

    if (url.pathname === "/cota-denuncia") {
      // Na Conta do denunciante: repetição do mesmo alvo em 24 h não gasta cota; máx. 5 por hora.
      // A consulta faz uma RESERVA provisória (atômica, sem corrida entre denúncias simultâneas) que
      // vale 30 s; só `confirmar=1` (depois da denúncia gravada) a torna definitiva por 24 h, e
      // `desfazer=1` a devolve. Uma reserva nunca confirmada some sozinha.
      const alvo = url.searchParams.get("alvo") ?? "";
      if (!UID_RE.test(alvo)) return json({ ok: false, erro: "parametro_invalido" }, 400);
      const viva = (d) => agora - d.em < (d.pendente ? RESERVA_MS : DENUNCIA_DEDUP_MS);
      const feitas = ((await this.ctx.storage.get("cotaDenuncias")) ?? []).filter(viva);
      if (url.searchParams.get("desfazer") === "1") {
        await this.ctx.storage.put("cotaDenuncias", feitas.filter((d) => !(d.alvo === alvo && d.pendente)));
        return json({ ok: true });
      }
      if (url.searchParams.get("confirmar") === "1") {
        for (const d of feitas) if (d.alvo === alvo && d.pendente) { d.pendente = false; d.em = agora; }
        await this.ctx.storage.put("cotaDenuncias", feitas);
        return json({ ok: true });
      }
      const ja = feitas.find((d) => d.alvo === alvo);
      if (ja) return json({ ok: true, duplicada: true, pendente: !!ja.pendente });
      const naHora = feitas.filter((d) => agora - d.em < COTA_JANELA_MS);
      if (naHora.length >= COTA_DENUNCIAS) {
        const restanteMs = naHora[0].em + COTA_JANELA_MS - agora;
        return json({ ok: false, erro: "espera", restanteMs }, 409);
      }
      feitas.push({ alvo, em: agora, pendente: true });
      await this.ctx.storage.put("cotaDenuncias", feitas);
      return json({ ok: true });
    }

    // /denunciar: na Conta do denunciado.
    const por = url.searchParams.get("por") ?? "";
    const motivo = url.searchParams.get("motivo") ?? "";
    if (!UID_RE.test(por) || !MOTIVOS_DENUNCIA.includes(motivo)) return json({ ok: false, erro: "parametro_invalido" }, 400);
    const coord = (nome, max) => {
      const v = url.searchParams.get(nome);
      const n = v === null || v === "" ? NaN : Number(v);
      return Number.isFinite(n) && Math.abs(n) <= max ? n : null;
    };
    const lista = (await this.ctx.storage.get("denuncias")) ?? [];
    if (lista.some((d) => d.por === por && agora - d.em < DENUNCIA_DEDUP_MS)) return json({ ok: true, duplicada: true });
    lista.push({ por, em: agora, motivo, lat: coord("lat", 90), lon: coord("lon", 180) });
    if (lista.length > MAX_DENUNCIAS) lista.splice(0, lista.length - MAX_DENUNCIAS);
    await this.ctx.storage.put("denuncias", lista);
    // Índice do dono (melhor esforço: falha aqui nunca derruba a denúncia).
    const uidAlvo = url.searchParams.get("uid");
    const ns = this.env?.CONTA;
    if (ns && uidAlvo && UID_RE.test(uidAlvo)) {
      const e = lista.at(-1);
      const q = new URLSearchParams({ uid: uidAlvo, por, motivo });
      if (e.lat !== null) q.set("lat", String(e.lat));
      if (e.lon !== null) q.set("lon", String(e.lon));
      // Não atrasa a resposta: a denúncia já está gravada (ctx.waitUntil mantém a chamada viva).
      const p = Promise.resolve()
        .then(() => ns.get(ns.idFromName(INDICE_UID)).fetch(`https://conta/indice-add?${q}`, { method: "POST" }))
        .catch((err) => console.error("falha ao gravar no índice", err?.message));
      this.ctx.waitUntil?.(p);
    }
    return json({ ok: true });
  }
}
