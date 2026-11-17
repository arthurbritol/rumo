// Protocolo cliente -> servidor do "Rolê por perto": validação estrita, que recusa na dúvida.
// Para acrescentar um tipo de mensagem, basta uma entrada em VALIDADORES.

import { RITMOS, DISTANCIAS, PARADAS } from "./match.js";

export const MAX_BYTES = 2048;

const encoder = new TextEncoder();

// Construtores de regras de campo: cada regra devolve true se o valor é aceito.
const numero = (min, max) => (v) =>
  typeof v === "number" && Number.isFinite(v) && v >= min && v <= max;
const numeroOuNulo = (min, max) => {
  const n = numero(min, max);
  return (v) => v === null || n(v);
};
const inteiro = (min, max) => (v) => Number.isInteger(v) && v >= min && v <= max;
// Id de encontro: 16 hex gerados pelo servidor (Encounters).
export const ID_ENCONTRO_RE = /^[0-9a-f]{16}$/;
const idEncontro = (v) => typeof v === "string" && ID_ENCONTRO_RE.test(v);
const daLista = (lista) => (v) => typeof v === "string" && lista.includes(v);

// Tabela de validadores: tipo -> { campo: regra }. Só os campos listados são devolvidos.
const VALIDADORES = {
  procurar: {
    ritmo: daLista(RITMOS),
    distancia: daLista(DISTANCIAS),
    parada: daLista(PARADAS),
    raioKm: numero(5, 20),
  },
  parar: {},
  posicao: {
    lat: numero(-90, 90),
    lon: numero(-180, 180),
    rumo: numeroOuNulo(0, 360),
    vel: numeroOuNulo(0, Infinity),
    distKm: numeroOuNulo(0, Infinity),
    etaMin: numeroOuNulo(0, Infinity),
  },
  // Encontros: o ponto marcado, vagas de 2 a 8 (com quem propõe) e o id de quem entra.
  proposta: { lat: numero(-90, 90), lon: numero(-180, 180), vagas: inteiro(2, 8) },
  cancelar: {},
  entrar: { id: idEncontro },
  sair: {},
  // Segurança: o app só conhece o id opaco de sessão (o servidor resolve para o uid).
  bloquear: { sessao: idEncontro },
  amigo: { sessao: idEncontro }, // "quero ser amigo": sessão opaca de quem esteve no mesmo encontro
  amigo_ack: { uid: (v) => typeof v === "string" && /^[A-Za-z0-9_.:-]{1,128}$/.test(v) }, // ack da entrega de amigo_confirmado
  denunciar: { sessao: idEncontro, motivo: daLista(["incomodo", "comportamento", "falso", "outro"]) },
};

const falha = (error) => ({ ok: false, error });

export function parseClientMessage(raw) {
  if (typeof raw !== "string") return falha("mensagem inválida");
  if (encoder.encode(raw).length > MAX_BYTES) return falha("mensagem grande demais");

  let obj;
  try {
    obj = JSON.parse(raw);
  } catch {
    return falha("JSON inválido");
  }
  if (obj === null || typeof obj !== "object" || Array.isArray(obj)) {
    return falha("mensagem inválida");
  }

  const t = obj.t;
  if (typeof t !== "string" || !Object.hasOwn(VALIDADORES, t)) {
    return falha("tipo de mensagem desconhecido");
  }

  const msg = { t };
  for (const [campo, regra] of Object.entries(VALIDADORES[t])) {
    // Campo ausente vira undefined e é recusado por todas as regras (null precisa ser explícito).
    const valor = Object.hasOwn(obj, campo) ? obj[campo] : undefined;
    if (!regra(valor)) return falha(`campo inválido: ${campo}`);
    msg[campo] = valor;
  }
  return { ok: true, msg };
}
