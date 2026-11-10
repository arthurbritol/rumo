// Reproduz as consultas e gravações EXATAS do FirestoreSocialStore (app/.../social/) contra as regras.
// Cada teste cita o método do adaptador. Rodam no emulador, como os demais.
const { test, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const firebase = require('firebase/compat/app');
require('firebase/compat/firestore');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');

const { Timestamp, FieldValue } = firebase.firestore;
// uids no formato do Firebase Auth (28 caracteres); ANA < BETO < CAIO < DUDA na ordem alfabética.
const ANA = 'Ana4kQ9zXbT2mLw0RvN7pYcE5sHd';
const BETO = 'Beto8hJ3fUaG6nZq1WxK4oTiM2rS';
const CAIO = 'Caio2dP7yVeB9cLs5QfH0uXjA3gN';
const DUDA = 'Duda6mR1tKoW8zYb4NhE7vCqL9xF';
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-rumo',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const db = (uid) => env.authenticatedContext(uid).firestore();
const semear = (fn) => env.withSecurityRulesDisabled((ctx) => fn(ctx.firestore()));
const par = (a, b) => (a < b ? `${a}_${b}` : `${b}_${a}`);
const agora = () => Date.now();
const amizadeDoc = (x, y) => ({ a: x < y ? x : y, b: x < y ? y : x, criadoEm: agora() });
const semearAmizade = (x, y) => semear((d) => d.doc(`amizades/${par(x, y)}`).set(amizadeDoc(x, y)));
const semearConvite = (de, para) =>
  semear((d) => d.doc(`convites/${de}_${para}`).set({ de, para, criadoEm: agora() }));
const ouvinte = (query) => new Promise((res, rej) => {
  const un = query.onSnapshot(() => res(un), rej);
});

// ---------- (1) convites ----------
test('convites: inviteRefs (de == eu e para == outro) cancela; (para == eu e de == outro) recusa', async () => {
  await semearConvite(ANA, BETO);
  const cancelar = await assertSucceeds(
    db(ANA).collection('convites').where('de', '==', ANA).where('para', '==', BETO).get());
  if (cancelar.size !== 1) throw new Error('cancelar: esperava 1');
  const recusar = await assertSucceeds(
    db(BETO).collection('convites').where('de', '==', ANA).where('para', '==', BETO).get());
  if (recusar.size !== 1) throw new Error('recusar: esperava 1');
  // o apagamento em lote que o adaptador faz (deleteAll)
  const d = db(ANA);
  const b = d.batch();
  cancelar.docs.forEach((x) => b.delete(d.doc(x.ref.path)));
  await assertSucceeds(b.commit());
  // convite que não existe: a consulta volta vazia (permitida) e nada é apagado
  const vazio = await assertSucceeds(
    db(ANA).collection('convites').where('de', '==', ANA).where('para', '==', BETO).get());
  if (vazio.size !== 0) throw new Error('esperava vazio');
});

test('convites: recebidos (para == eu) e enviados (de == eu), inclusive em ouvinte', async () => {
  await semearConvite(ANA, BETO);
  await semearConvite(CAIO, BETO);
  await semearConvite(BETO, DUDA);
  const rec = await assertSucceeds(db(BETO).collection('convites').where('para', '==', BETO).get());
  if (rec.size !== 2) throw new Error('recebidos: esperava 2');
  const enviados = await assertSucceeds(db(BETO).collection('convites').where('de', '==', BETO).get());
  if (enviados.size !== 1) throw new Error('enviados: esperava 1');
  const un = await assertSucceeds(ouvinte(db(BETO).collection('convites').where('para', '==', BETO)));
  un();
});

test('convites NEGATIVO: quem não é do convite não consulta por de/para alheios', async () => {
  await semearConvite(ANA, BETO);
  const c = db(CAIO).collection('convites');
  await assertFails(c.where('de', '==', ANA).where('para', '==', BETO).get());
  await assertFails(c.where('para', '==', BETO).get());
  await assertFails(c.where('de', '==', ANA).get());
  await assertFails(c.get());
  await assertFails(ouvinte(c.where('para', '==', BETO)));
  await assertFails(db(CAIO).doc(`convites/${ANA}_${BETO}`).delete());
});

// ---------- (2) amizades ----------
test('amizades: friendshipRefs (a == min e b == max) como a e como b; a == eu; b == eu', async () => {
  await semearAmizade(ANA, BETO); // ANA = a, BETO = b
  await semearAmizade(ANA, CAIO); // ANA = a, CAIO = b
  await semearAmizade(DUDA, BETO); // BETO = a, DUDA = b
  for (const [eu, outro] of [[ANA, BETO], [BETO, ANA]]) {
    const lo = eu < outro ? eu : outro;
    const hi = eu < outro ? outro : eu;
    const s = await assertSucceeds(
      db(eu).collection('amizades').where('a', '==', lo).where('b', '==', hi).get());
    if (s.size !== 1 || s.docs[0].id !== par(eu, outro)) throw new Error('par errado');
  }
  const comoA = await assertSucceeds(db(ANA).collection('amizades').where('a', '==', ANA).get());
  if (comoA.size !== 2) throw new Error('ANA como a: esperava 2');
  const comoB = await assertSucceeds(db(ANA).collection('amizades').where('b', '==', ANA).get());
  if (comoB.size !== 0) throw new Error('ANA como b: esperava 0');
  const betoB = await assertSucceeds(db(BETO).collection('amizades').where('b', '==', BETO).get());
  if (betoB.size !== 1) throw new Error('BETO como b: esperava 1');
  const betoA = await assertSucceeds(db(BETO).collection('amizades').where('a', '==', BETO).get());
  if (betoA.size !== 1) throw new Error('BETO como a: esperava 1');
  // observeFriends: ouvintes das duas consultas
  for (const campo of ['a', 'b']) {
    const un = await assertSucceeds(ouvinte(db(BETO).collection('amizades').where(campo, '==', BETO)));
    un();
  }
  // unfriend: apaga o doc encontrado, por qualquer um dos dois
  await assertSucceeds(db(BETO).doc(`amizades/${par(ANA, BETO)}`).delete());
});

test('amizades NEGATIVO: quem não é do par não consulta nem apaga', async () => {
  await semearAmizade(ANA, BETO);
  const c = db(CAIO).collection('amizades');
  await assertFails(c.where('a', '==', ANA).where('b', '==', BETO).get());
  await assertFails(c.where('a', '==', ANA).get());
  await assertFails(c.where('b', '==', BETO).get());
  await assertFails(c.get());
  await assertFails(db(CAIO).doc(`amizades/${par(ANA, BETO)}`).delete());
  // consulta própria, sem amizades, é permitida (volta vazia)
  const s = await assertSucceeds(c.where('a', '==', CAIO).get());
  if (s.size !== 0) throw new Error('esperava vazio');
});

// ---------- (3) bloqueios ----------
test('bloqueios: blocked (quem == eu); negativo para outro uid e para o bloqueado', async () => {
  await semear(async (d) => {
    await d.doc(`bloqueios/${ANA}_${BETO}`).set({ criadoEm: agora(), quem: ANA });
    await d.doc(`bloqueios/${ANA}_${CAIO}`).set({ criadoEm: agora(), quem: ANA });
  });
  const s = await assertSucceeds(db(ANA).collection('bloqueios').where('quem', '==', ANA).get());
  if (s.size !== 2) throw new Error('esperava 2');
  await assertFails(db(BETO).collection('bloqueios').where('quem', '==', ANA).get());
  await assertFails(db(ANA).collection('bloqueios').where('quem', '==', BETO).get());
  await assertFails(db(ANA).collection('bloqueios').get());
  const vazio = await assertSucceeds(db(BETO).collection('bloqueios').where('quem', '==', BETO).get());
  if (vazio.size !== 0) throw new Error('bloqueado não pode ver o bloqueio');
  await assertSucceeds(db(ANA).doc(`bloqueios/${ANA}_${BETO}`).delete()); // unblock
});

// ---------- (4) busca ----------
test('busca: orderBy + startAt/endAt(prefixo + U+F8FF) + limit(20), segunda página com startAfter', async () => {
  await semear(async (d) => {
    for (let i = 0; i < 45; i++) {
      const n = String(i).padStart(2, '0');
      await d.doc(`busca/u${n}`).set({
        displayName: `Joao ${n}`, displayNameLower: `joao ${n}`, brand: 'Honda', model: null,
      });
    }
    await d.doc('busca/outro').set({ displayName: 'Maria', displayNameLower: 'maria', brand: null, model: null });
  });
  const prefixo = 'joao';
  const fim = prefixo + '';
  const eu = db(CAIO);
  const base = () => eu.collection('busca').orderBy('displayNameLower');
  const p1 = await assertSucceeds(base().startAt(prefixo).endAt(fim).limit(20).get());
  if (p1.size !== 20) throw new Error(`p1 ${p1.size}`);
  const p2 = await assertSucceeds(base().startAfter(p1.docs[19]).endAt(fim).limit(20).get());
  if (p2.size !== 20) throw new Error(`p2 ${p2.size}`);
  const p3 = await assertSucceeds(base().startAfter(p2.docs[19]).endAt(fim).limit(5).get());
  if (p3.size !== 5) throw new Error(`p3 ${p3.size}`);
  // NEGATIVO: sem limit, acima de 20 ou sem login
  await assertFails(base().startAt(prefixo).endAt(fim).get());
  await assertFails(base().startAt(prefixo).endAt(fim).limit(21).get());
  await assertFails(env.unauthenticatedContext().firestore().collection('busca')
    .orderBy('displayNameLower').startAt(prefixo).endAt(fim).limit(20).get());
});

test('publishProfile: lote perfisPublicos + busca (ou delete de busca) pelo dono; outro não', async () => {
  const d = db(ANA);
  const b = d.batch();
  b.set(d.doc(`perfisPublicos/${ANA}`), {
    displayName: 'Ágata Souza', displayNameLower: 'agata souza', brand: 'Honda', model: null, year: 2021, searchable: true,
  });
  b.set(d.doc(`busca/${ANA}`), {
    displayName: 'Ágata Souza', displayNameLower: 'agata souza', brand: 'Honda', model: null,
  });
  await assertSucceeds(b.commit());
  const b2 = d.batch();
  b2.set(d.doc(`perfisPublicos/${ANA}`), { displayName: 'Ágata Souza', brand: null, model: null, searchable: false });
  b2.delete(d.doc(`busca/${ANA}`));
  await assertSucceeds(b2.commit());
  await assertSucceeds(d.doc(`atividade/${ANA}`).set({ weekStartMs: agora(), weekKm: 123.4 }));
  const o = db(BETO);
  const b3 = o.batch();
  b3.set(o.doc(`perfisPublicos/${ANA}`), { displayName: 'X', searchable: true });
  b3.set(o.doc(`busca/${ANA}`), { displayName: 'X', displayNameLower: 'x' });
  await assertFails(b3.commit());
});

// ---------- (5) e (6) roles e membros ----------
const roleDoc = (criador, participantes) => ({
  nome: 'Serra da Rocha', criadorUid: criador, participantes,
  expiraEm: Timestamp.fromMillis(agora() + 7 * 24 * 3600 * 1000), criadoEm: agora(),
});

async function semearRole() {
  await semear(async (d) => {
    await d.doc('roles/r1').set(roleDoc(ANA, [ANA, BETO, CAIO]));
    await d.doc(`roles/r1/membros/${ANA}`).set({
      estado: 'aceito', nome: 'Ana', orcamento: 500, custoPorKm: 0.4, atualizadoEm: agora(),
    });
    await d.doc(`roles/r1/membros/${BETO}`).set({
      estado: 'aceito', nome: 'Beto', orcamento: 300, custoPorKm: null, atualizadoEm: agora(),
    });
    await d.doc(`roles/r1/membros/${CAIO}`).set({ estado: 'convidado', nome: 'Caio', atualizadoEm: agora() });
    await d.doc('roles/r2').set(roleDoc(DUDA, [DUDA]));
    await d.doc(`roles/r2/membros/${DUDA}`).set({ estado: 'aceito', nome: 'Duda', atualizadoEm: agora() });
  });
}

test('roles: participantes array-contains eu (roles e observeRoles); negativo para uid alheio', async () => {
  await semearRole();
  for (const uid of [ANA, BETO, CAIO, DUDA]) {
    const s = await assertSucceeds(db(uid).collection('roles').where('participantes', 'array-contains', uid).get());
    if (s.size !== 1) throw new Error(`${uid}: ${s.size}`);
  }
  const un = await assertSucceeds(ouvinte(db(ANA).collection('roles').where('participantes', 'array-contains', ANA)));
  un();
  await assertFails(db(CAIO).collection('roles').where('participantes', 'array-contains', ANA).get());
  await assertFails(db(CAIO).collection('roles').get());
  await assertFails(db(DUDA).collection('roles').where('participantes', 'array-contains', ANA).get());
});

test('membros: list (members / observeMembers) por aceito permitido, por convidado e estranho negado', async () => {
  await semearRole();
  for (const uid of [ANA, BETO]) {
    const s = await assertSucceeds(db(uid).collection('roles/r1/membros').get());
    if (s.size !== 3) throw new Error(`${uid}: ${s.size}`);
  }
  const un = await assertSucceeds(ouvinte(db(BETO).collection('roles/r1/membros')));
  un();
  await assertFails(db(CAIO).collection('roles/r1/membros').get()); // convidado
  await assertFails(db(DUDA).collection('roles/r1/membros').get()); // aceito em outro rolê
  await assertFails(db(ANA).collection('roles/r2/membros').get()); // aceito de outro rolê
  // acceptRole e setMemberFigures pelo próprio convidado; depois ele lista
  await assertSucceeds(db(CAIO).doc(`roles/r1/membros/${CAIO}`).update({ estado: 'aceito', atualizadoEm: agora() }));
  await assertSucceeds(db(CAIO).collection('roles/r1/membros').get());
  await assertSucceeds(db(CAIO).doc(`roles/r1/membros/${CAIO}`)
    .update({ orcamento: 150.5, custoPorKm: null, atualizadoEm: agora() }));
  await assertFails(db(BETO).doc(`roles/r1/membros/${CAIO}`)
    .update({ orcamento: 1, custoPorKm: null, atualizadoEm: agora() }));
});

// ---------- (7) lotes ----------
test('leaveRole: lote apaga o próprio membro + arrayRemove(uid); negativos', async () => {
  await semearRole();
  const d = db(BETO);
  const b = d.batch();
  b.delete(d.doc(`roles/r1/membros/${BETO}`));
  b.update(d.doc('roles/r1'), 'participantes', FieldValue.arrayRemove(BETO));
  await assertSucceeds(b.commit());
  // quem não participa não mexe em rolê alheio
  const e = db(DUDA);
  const b2 = e.batch();
  b2.delete(e.doc(`roles/r1/membros/${DUDA}`));
  b2.update(e.doc('roles/r1'), 'participantes', FieldValue.arrayRemove(ANA));
  await assertFails(b2.commit());
  // participante não remove outro
  const f = db(CAIO);
  const b3 = f.batch();
  b3.delete(f.doc(`roles/r1/membros/${CAIO}`));
  b3.update(f.doc('roles/r1'), 'participantes', FieldValue.arrayRemove(ANA));
  await assertFails(b3.commit());
});

test('closeRole: lote do criador apaga membros pelos ids de participantes + o rolê; não criador negado', async () => {
  await semearRole();
  const naoCriador = db(BETO);
  const bn = naoCriador.batch();
  for (const id of [ANA, BETO, CAIO]) bn.delete(naoCriador.doc(`roles/r1/membros/${id}`));
  bn.delete(naoCriador.doc('roles/r1'));
  await assertFails(bn.commit());
  const d = db(ANA);
  const snap = await d.doc('roles/r1').get();
  const ids = snap.get('participantes');
  const b = d.batch();
  ids.forEach((id) => b.delete(d.doc(`roles/r1/membros/${id}`)));
  b.delete(d.doc('roles/r1'));
  await assertSucceeds(b.commit());
});

test('block: lote bloqueios {criadoEm, quem} + delete amizade + os dois convites existentes', async () => {
  await semearAmizade(ANA, BETO);
  await semearConvite(ANA, BETO);
  await semearConvite(BETO, ANA);
  const d = db(ANA);
  // o adaptador consulta antes (friendshipRefs + inviteRefs nos dois sentidos)
  const am = await assertSucceeds(d.collection('amizades').where('a', '==', ANA).where('b', '==', BETO).get());
  const c1 = await assertSucceeds(d.collection('convites').where('de', '==', ANA).where('para', '==', BETO).get());
  const c2 = await assertSucceeds(d.collection('convites').where('de', '==', BETO).where('para', '==', ANA).get());
  const b = d.batch();
  b.set(d.doc(`bloqueios/${ANA}_${BETO}`), { criadoEm: agora(), quem: ANA });
  [...am.docs, ...c1.docs, ...c2.docs].forEach((x) => b.delete(d.doc(x.ref.path)));
  await assertSucceeds(b.commit());
  // NEGATIVO: terceiro não apaga amizade/convites alheios nem bloqueia em nome de outro
  await semearAmizade(ANA, BETO);
  await semearConvite(ANA, BETO);
  const e = db(CAIO);
  const b2 = e.batch();
  b2.set(e.doc(`bloqueios/${CAIO}_${ANA}`), { criadoEm: agora(), quem: CAIO });
  b2.delete(e.doc(`amizades/${par(ANA, BETO)}`));
  await assertFails(b2.commit());
  const b3 = e.batch();
  b3.set(e.doc(`bloqueios/${ANA}_${CAIO}`), { criadoEm: agora(), quem: ANA });
  await assertFails(b3.commit());
});

test('acceptInvite: lote amizades + delete do convite pelo destinatário; remetente e terceiro negados', async () => {
  await semearConvite(ANA, BETO);
  const remetente = db(ANA);
  const br = remetente.batch();
  br.set(remetente.doc(`amizades/${par(ANA, BETO)}`), amizadeDoc(ANA, BETO));
  br.delete(remetente.doc(`convites/${ANA}_${BETO}`));
  await assertFails(br.commit());
  const terceiro = db(CAIO);
  const bt = terceiro.batch();
  bt.set(terceiro.doc(`amizades/${par(ANA, BETO)}`), amizadeDoc(ANA, BETO));
  bt.delete(terceiro.doc(`convites/${ANA}_${BETO}`));
  await assertFails(bt.commit());
  const d = db(BETO);
  const b = d.batch();
  b.set(d.doc(`amizades/${par(ANA, BETO)}`), amizadeDoc(ANA, BETO));
  b.delete(d.doc(`convites/${ANA}_${BETO}`));
  await assertSucceeds(b.commit());
  // sendInvite (criadoEm numérico) e o NEGATIVO de de != eu
  await assertSucceeds(db(CAIO).doc(`convites/${CAIO}_${DUDA}`).set({ de: CAIO, para: DUDA, criadoEm: agora() }));
  await assertFails(db(BETO).doc(`convites/${CAIO}_${DUDA}`).set({ de: CAIO, para: DUDA, criadoEm: agora() }));
});

test('createRole: lote com rolê (expiraEm Timestamp) + membro do criador + 9 convidados', async () => {
  const amigos = [];
  for (let i = 0; i < 9; i++) amigos.push(`Amigo${i}x7Kq2LmZ9wRtB4nVcE1yHsJd`);
  for (const f of amigos) await semearAmizade(ANA, f);
  const d = db(ANA);
  const b = d.batch();
  b.set(d.doc('roles/novo'), roleDoc(ANA, [ANA, ...amigos]));
  b.set(d.doc(`roles/novo/membros/${ANA}`), {
    estado: 'aceito', nome: 'Ana', orcamento: null, custoPorKm: null, atualizadoEm: agora(),
  });
  amigos.forEach((f) => b.set(d.doc(`roles/novo/membros/${f}`), {
    estado: 'convidado', nome: f.slice(0, 6), atualizadoEm: agora(),
  }));
  await assertSucceeds(b.commit());
  // NEGATIVO: convidar quem não é amigo
  const b2 = d.batch();
  b2.set(d.doc('roles/estranho'), roleDoc(ANA, [ANA, CAIO]));
  b2.set(d.doc(`roles/estranho/membros/${ANA}`), {
    estado: 'aceito', nome: 'Ana', orcamento: null, custoPorKm: null, atualizadoEm: agora(),
  });
  b2.set(d.doc(`roles/estranho/membros/${CAIO}`), { estado: 'convidado', nome: 'Caio', atualizadoEm: agora() });
  await assertFails(b2.commit());
});

// ---------- (8) perfil e atividade de amigos ----------
test('friends: get de perfisPublicos e atividade por amigo permitido, por estranho negado', async () => {
  await semearAmizade(ANA, BETO);
  await semear(async (d) => {
    await d.doc(`perfisPublicos/${ANA}`).set({ displayName: 'Ana', brand: null, model: null, searchable: false });
    await d.doc(`atividade/${ANA}`).set({ weekStartMs: agora(), weekKm: 80 });
  });
  const amigo = db(BETO);
  const p = await assertSucceeds(amigo.doc(`perfisPublicos/${ANA}`).get());
  const a = await assertSucceeds(amigo.doc(`atividade/${ANA}`).get());
  if (!p.exists || !a.exists) throw new Error('docs deveriam existir');
  // amigo sem atividade publicada: get de documento inexistente também é permitido
  const sem = await assertSucceeds(db(ANA).doc(`atividade/${BETO}`).get());
  if (sem.exists) throw new Error('inexistente');
  await assertFails(db(CAIO).doc(`perfisPublicos/${ANA}`).get());
  await assertFails(db(CAIO).doc(`atividade/${ANA}`).get());
  await semearAmizade(CAIO, DUDA);
  await assertFails(db(DUDA).doc(`atividade/${ANA}`).get());
});
