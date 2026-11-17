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
//   POST /amigo?uid=<alvo>[&simular=1], GET /amigo-marca?uid=<quem pergunta>, GET /amigos-confirmados?eu=<uid>,
//     POST /amigo-entregue?uid=<outro> (ack do cliente) (Tarefa 19): marcas "quero ser amigo" de 24 h (até 50 ativas).
//     O uid só aparece em /amigos-confirmados quando as DUAS marcas existem e valem; a Celula entrega uma vez
//     (`entregue`). Bloquear (qualquer sentido) apaga a marca daquela pessoa.
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
export const MAX_MARCAS_AMIGO = 50; // marcas "quero ser amigo" ativas por pessoa
const MARCA_AMIGO_MS = 24 * 3_600_000;
const ENTREGA_MS = 72 * 3_600_000; // confirmação (duas marcas válidas) segue pendente de ack por até 72 h
const PRAZO_OUTRA_CONTA_MS = 2000; // prazo da pergunta à Conta do outro lado
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
    if (["/amigo", "/amigo-marca", "/amigo-confirmar", "/amigos-confirmados", "/amigo-entregue"].includes(url.pathname)) {
      return this.amigos(request, url);
    }
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

  // --- marcas "quero ser amigo" (só a Celula alcança) ---

  async amigos(request, url) {
    const leitura = url.pathname === "/amigo-marca" || url.pathname === "/amigos-confirmados";
    if (request.method !== (leitura ? "GET" : "POST")) return json({ ok: false, erro: "metodo" }, 405);
    const agora = this.now();
    // Marca viva = dentro das 24 h; reciprocidade já confirmada e ainda sem ack vale até ENTREGA_MS.
    const todas = ((await this.ctx.storage.get("amigos")) ?? []).filter((m) => m.ate > agora || (m.confirmadaEm && !m.entregue && agora - m.confirmadaEm < ENTREGA_MS));
    const marcas = todas.filter((m) => m.ate > agora); // as que contam como "marquei" e para o teto
    const b = (await this.ctx.storage.get("bloqueios")) ?? { bloqueados: [], bloqueadoPor: [] };
    const bloqueado = (u) => b.bloqueados.includes(u) || b.bloqueadoPor.includes(u);
    const param = url.pathname === "/amigos-confirmados" ? "eu" : "uid";
    const uid = url.searchParams.get(param) ?? "";
    if (!UID_RE.test(uid)) return json({ ok: false, erro: "parametro_invalido" }, 400);

    if (url.pathname === "/amigo-marca") {
      // `marcou`: marca viva ou reciprocidade já confirmada e pendente de ack; `bloqueado`: bloqueio nos dois sentidos aqui.
      const blq = bloqueado(uid);
      return json({ ok: true, bloqueado: blq, marcou: !blq && todas.some((m) => m.uid === uid) });
    }
    if (url.pathname === "/amigo-confirmar") {
      // A outra Conta viu as duas marcas: grava a confirmação aqui também (entrada ainda viva ou pendente).
      const m = todas.find((x) => x.uid === uid);
      if (m && !bloqueado(uid) && !m.confirmadaEm) {
        m.confirmadaEm = agora;
        await this.ctx.storage.put("amigos", todas);
      }
      return json({ ok: true });
    }
    if (url.pathname === "/amigo") {
      if (marcas.some((m) => m.uid === uid)) return json({ ok: true });
      // O teto vem ANTES do atalho de bloqueio: quem me bloqueou recebe o mesmo limite de um alvo normal.
      if (marcas.length >= MAX_MARCAS_AMIGO) return json({ ok: false, erro: "limite" }, 409);
      if (bloqueado(uid)) return json({ ok: true }); // não grava; a Celula responde igual
      if (url.searchParams.get("simular") !== "1") {
        // Entrada vencida mas pendente de ack (confirmada): renova a mesma, nunca cria uma segunda.
        const velha = todas.find((m) => m.uid === uid);
        if (velha) velha.ate = agora + MARCA_AMIGO_MS;
        else todas.push({ uid, ate: agora + MARCA_AMIGO_MS, entregue: false });
        await this.ctx.storage.put("amigos", todas);
      }
      return json({ ok: true });
    }
    if (url.pathname === "/amigo-entregue") {
      // Ack do cliente: só agora a confirmação deixa de ser reentregue.
      let mudou = false;
      for (const m of todas) if (m.uid === uid && !m.entregue) { m.entregue = true; mudou = true; }
      if (mudou) await this.ctx.storage.put("amigos", todas);
      return json({ ok: true });
    }

    // /amigos-confirmados: minhas marcas sem ack cuja outra ponta também marcou. A reciprocidade, uma vez
    // vista, fica gravada (confirmadaEm) e segue pendente até o ack, mesmo depois das 24 h da marca.
    const ns = this.env?.CONTA;
    if (!ns) return json({ ok: true, uids: [] });
    const candidatos = todas.filter((m) => !m.entregue && !bloqueado(m.uid));
    const perguntar = async (m) => {
      let timer;
      try {
        const chamada = (async () => {
          const r = await ns.get(ns.idFromName(m.uid)).fetch(`https://conta/amigo-marca?uid=${encodeURIComponent(uid)}`, { method: "GET" });
          return await r.json();
        })();
        chamada.catch(() => {});
        const limite = new Promise((_, rej) => { timer = setTimeout(() => rej(new Error("prazo")), PRAZO_OUTRA_CONTA_MS); });
        return await Promise.race([chamada, limite]);
      } catch {
        return null; // sem resposta: não entrega agora (falha fechada), a próxima consulta tenta de novo
      } finally {
        clearTimeout(timer);
      }
    };
    // Toda entrega reconsulta o outro lado: bloqueio dele (mesmo que a gravação simétrica tenha falhado) barra
    // até uma confirmação já guardada; sem confirmação guardada, ele precisa ter marca viva.
    const respostas = await Promise.all(candidatos.map(perguntar));
    const bloqueadosAgora = new Set();
    const novas = [];
    const vale = candidatos.map((m, i) => {
      const r = respostas[i];
      if (!r || r.ok !== true) return false;
      if (r.bloqueado === true) { bloqueadosAgora.add(m.uid); return false; }
      if (m.confirmadaEm) return true;
      if (r.marcou === true) { novas.push(m); return true; }
      return false;
    });
    let resto = todas;
    if (bloqueadosAgora.size > 0) resto = todas.filter((m) => !bloqueadosAgora.has(m.uid)); // bloqueio apaga a pendente
    for (const m of novas) m.confirmadaEm = agora;
    if (novas.length > 0 || resto !== todas) await this.ctx.storage.put("amigos", resto);
    // Grava a confirmação também na Conta do outro lado (melhor esforço; marca-r.marcou cobre a falha).
    await Promise.all(novas.map(async (m) => {
      try {
        await ns.get(ns.idFromName(m.uid)).fetch(`https://conta/amigo-confirmar?uid=${encodeURIComponent(uid)}`, { method: "POST" });
      } catch {
        // a consulta do outro lado também enxerga a minha entrada confirmada
      }
    }));
    const uids = [...new Set(candidatos.filter((_, i) => vale[i]).map((m) => m.uid))];
    return json({ ok: true, uids });
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
      // O teto vem antes de apagar marcas: uma recusa por limite não pode ter efeito colateral.
      if (!lista.includes(alvo) && lista.length >= (sentido === "bloqueou" ? MAX_BLOQUEADOS : MAX_BLOQUEADO_POR)) {
        return json({ ok: false, erro: "limite" }, 409);
      }
      if (url.searchParams.get("simular") !== "1") {
        const marcas = (await this.ctx.storage.get("amigos")) ?? [];
        if (marcas.some((m) => m.uid === alvo)) await this.ctx.storage.put("amigos", marcas.filter((m) => m.uid !== alvo));
      }
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
