// Testes das regras do social (amigos e rolê). Rodam no emulador, como rules.test.js.
const { test, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');

const ANA = 'ana';
const BETO = 'beto';
const CAIO = 'caio';
const DUDA = 'duda';
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
const visitante = () => env.unauthenticatedContext().firestore();
const semear = (fn) => env.withSecurityRulesDisabled((ctx) => fn(ctx.firestore()));
const par = (a, b) => (a < b ? `${a}_${b}` : `${b}_${a}`);

const buscaDoc = (nome = 'Ana') => ({
  displayName: nome, displayNameLower: nome.toLowerCase(), brand: 'Honda', model: 'CB 500X',
});
const perfilPub = () => ({
  displayName: 'Ana', displayNameLower: 'ana', brand: 'Honda', model: 'CB 500X',
  year: 2022, searchable: true,
});
const atividade = () => ({ weekStartMs: 1000, weekKm: 120.5 });
const amizade = (a, b) => { const [x, y] = a < b ? [a, b] : [b, a]; return { a: x, b: y }; };
const roleDoc = (criador, participantes, extra = {}) => ({
  nome: 'Serra', criadorUid: criador, participantes, expiraEm: 99999999, ...extra,
});

const semearAmizade = (a, b) => semear((d) => d.doc(`amizades/${par(a, b)}`).set(amizade(a, b)));

// ---------- busca ----------
test('busca: dono lê e escreve o próprio; estranho lê mas não escreve', async () => {
  const ref = db(ANA).doc(`busca/${ANA}`);
  await assertSucceeds(ref.set(buscaDoc()));
  await assertSucceeds(ref.get());
  await assertSucceeds(db(BETO).doc(`busca/${ANA}`).get());
  await assertFails(db(BETO).doc(`busca/${ANA}`).set(buscaDoc('Beto')));
  await assertFails(db(BETO).doc(`busca/${ANA}`).delete());
  await assertFails(visitante().doc(`busca/${ANA}`).get());
  await assertSucceeds(ref.delete());
});

test('busca: campo desconhecido ou nome fora do limite é negado', async () => {
  const ref = db(ANA).doc(`busca/${ANA}`);
  await assertFails(ref.set({ ...buscaDoc(), extra: 1 }));
  await assertFails(ref.set({ ...buscaDoc(), displayName: '' , displayNameLower: ''}));
  const longo = 'x'.repeat(61);
  await assertFails(ref.set({ ...buscaDoc(), displayName: longo, displayNameLower: longo }));
});

test('busca: lista só com limit <= 20', async () => {
  await semear((d) => d.doc(`busca/${ANA}`).set(buscaDoc()));
  const q = () => db(BETO).collection('busca')
    .where('displayNameLower', '>=', 'an').where('displayNameLower', '<', 'ao');
  await assertFails(q().get());
  await assertFails(q().limit(21).get());
  await assertSucceeds(q().limit(20).get());
  await assertSucceeds(q().limit(5).get());
  await assertFails(visitante().collection('busca').limit(5).get());
});

// ---------- perfisPublicos / atividade ----------
test('perfisPublicos e atividade: estranho não lê, amigo lê, dono escreve', async () => {
  await assertSucceeds(db(ANA).doc(`perfisPublicos/${ANA}`).set(perfilPub()));
  await assertSucceeds(db(ANA).doc(`atividade/${ANA}`).set(atividade()));
  await assertSucceeds(db(ANA).doc(`perfisPublicos/${ANA}`).get());
  await assertSucceeds(db(ANA).doc(`atividade/${ANA}`).get());
  await assertFails(db(BETO).doc(`perfisPublicos/${ANA}`).get());
  await assertFails(db(BETO).doc(`atividade/${ANA}`).get());
  await assertFails(db(BETO).doc(`perfisPublicos/${ANA}`).set(perfilPub()));
  await assertFails(db(BETO).doc(`atividade/${ANA}`).set(atividade()));
  await semearAmizade(ANA, BETO);
  await assertSucceeds(db(BETO).doc(`perfisPublicos/${ANA}`).get());
  await assertSucceeds(db(BETO).doc(`atividade/${ANA}`).get());
  await assertFails(db(CAIO).doc(`atividade/${ANA}`).get());
  await assertFails(db(BETO).doc(`atividade/${ANA}`).set(atividade()));
});

test('perfisPublicos e atividade: formato inválido é negado', async () => {
  await assertFails(db(ANA).doc(`perfisPublicos/${ANA}`).set({ ...perfilPub(), extra: 1 }));
  await assertFails(db(ANA).doc(`perfisPublicos/${ANA}`).set({ ...perfilPub(), searchable: 'sim' }));
  await assertFails(db(ANA).doc(`atividade/${ANA}`).set({ ...atividade(), extra: 1 }));
  await assertFails(db(ANA).doc(`atividade/${ANA}`).set({ weekStartMs: 'x', weekKm: 1 }));
});

// ---------- convites e amizades ----------
test('convite: cria quem é de; de diferente, id errado ou para=de é negado', async () => {
  await assertSucceeds(db(ANA).doc(`convites/${ANA}_${BETO}`).set({ de: ANA, para: BETO }));
  await assertFails(db(CAIO).doc(`convites/${ANA}_${CAIO}`).set({ de: ANA, para: CAIO }));
  await assertFails(db(ANA).doc(`convites/${ANA}_${DUDA}`).set({ de: ANA, para: CAIO }));
  await assertFails(db(ANA).doc(`convites/${ANA}_${ANA}`).set({ de: ANA, para: ANA }));
  await assertFails(db(ANA).doc(`convites/${ANA}_${BETO}`).update({ para: BETO }));
});

test('convite: lê e apaga de e para; estranho não', async () => {
  await semear((d) => d.doc(`convites/${ANA}_${BETO}`).set({ de: ANA, para: BETO }));
  await assertSucceeds(db(ANA).doc(`convites/${ANA}_${BETO}`).get());
  await assertSucceeds(db(BETO).doc(`convites/${ANA}_${BETO}`).get());
  await assertFails(db(CAIO).doc(`convites/${ANA}_${BETO}`).get());
  await assertFails(db(CAIO).doc(`convites/${ANA}_${BETO}`).delete());
  await assertSucceeds(db(ANA).doc(`convites/${ANA}_${BETO}`).delete());
});

test('amizade: remetente não forja; destinatário cria em lote que apaga o convite', async () => {
  await semear((d) => d.doc(`convites/${ANA}_${BETO}`).set({ de: ANA, para: BETO }));
  // remetente (ana) tenta criar a amizade, mesmo apagando o próprio convite
  const da = db(ANA);
  const forja = da.batch();
  forja.set(da.doc(`amizades/${par(ANA, BETO)}`), amizade(ANA, BETO));
  forja.delete(da.doc(`convites/${ANA}_${BETO}`));
  await assertFails(forja.commit());
  // sem apagar o convite: negado
  await assertFails(db(BETO).doc(`amizades/${par(ANA, BETO)}`).set(amizade(ANA, BETO)));
  // estranho
  await assertFails(db(CAIO).doc(`amizades/${par(ANA, BETO)}`).set(amizade(ANA, BETO)));
  // destinatário em lote
  const dbb = db(BETO);
  const ok = dbb.batch();
  ok.set(dbb.doc(`amizades/${par(ANA, BETO)}`), amizade(ANA, BETO));
  ok.delete(dbb.doc(`convites/${ANA}_${BETO}`));
  await assertSucceeds(ok.commit());
});

test('amizade: sem convite prévio é negada; lê e apaga só os membros', async () => {
  await assertFails(db(BETO).doc(`amizades/${par(ANA, BETO)}`).set(amizade(ANA, BETO)));
  await semearAmizade(ANA, BETO);
  await assertSucceeds(db(ANA).doc(`amizades/${par(ANA, BETO)}`).get());
  await assertSucceeds(db(BETO).doc(`amizades/${par(ANA, BETO)}`).get());
  await assertFails(db(CAIO).doc(`amizades/${par(ANA, BETO)}`).get());
  await assertFails(db(CAIO).doc(`amizades/${par(ANA, BETO)}`).delete());
  await assertFails(db(ANA).doc(`amizades/${par(ANA, BETO)}`).update({ a: 'x' }));
  await assertSucceeds(db(ANA).doc(`amizades/${par(ANA, BETO)}`).delete());
});

// ---------- bloqueios ----------
test('bloqueio: só quem bloqueia lê, cria e apaga', async () => {
  await assertSucceeds(db(ANA).doc(`bloqueios/${ANA}_${BETO}`).set({ criadoEm: 1 }));
  await assertSucceeds(db(ANA).doc(`bloqueios/${ANA}_${BETO}`).get());
  await assertFails(db(BETO).doc(`bloqueios/${ANA}_${BETO}`).get());
  await assertFails(db(BETO).doc(`bloqueios/${ANA}_${BETO}`).delete());
  await assertFails(db(BETO).doc(`bloqueios/${ANA}_${CAIO}`).set({ criadoEm: 1 }));
  await assertFails(db(BETO).doc(`bloqueios/${ANA}_${BETO}`).set({ criadoEm: 1 }));
  await assertSucceeds(db(ANA).doc(`bloqueios/${ANA}_${BETO}`).delete());
});

// ---------- roles ----------
test('role: criador cria com amigos; inclui não amigo, passa de 10 ou outro criador é negado', async () => {
  await semearAmizade(ANA, BETO);
  await assertSucceeds(db(ANA).doc('roles/r1').set(roleDoc(ANA, [ANA, BETO])));
  await assertFails(db(ANA).doc('roles/r2').set(roleDoc(ANA, [ANA, CAIO])));
  await assertFails(db(ANA).doc('roles/r3').set(roleDoc(ANA, [BETO])));
  await assertFails(db(BETO).doc('roles/r4').set(roleDoc(ANA, [ANA, BETO])));
  await assertFails(db(ANA).doc('roles/r5').set(roleDoc(ANA, [ANA, BETO], { extra: 1 })));
  await assertSucceeds(db(ANA).doc('roles/r6').set(roleDoc(ANA, [ANA])));
});

test('role: 10 participantes (9 amigos) cabe; 11 é negado', async () => {
  const amigos = [];
  for (let i = 0; i < 10; i++) amigos.push(`amigo${i}`);
  for (const f of amigos) await semearAmizade(ANA, f);
  await assertSucceeds(db(ANA).doc('roles/dez').set(roleDoc(ANA, [ANA, ...amigos.slice(0, 9)])));
  await assertFails(db(ANA).doc('roles/onze').set(roleDoc(ANA, [ANA, ...amigos])));
});

test('role: lê quem está em participantes; criador apaga; outro não', async () => {
  await semear((d) => d.doc('roles/r1').set(roleDoc(ANA, [ANA, BETO])));
  await assertSucceeds(db(ANA).doc('roles/r1').get());
  await assertSucceeds(db(BETO).doc('roles/r1').get());
  await assertFails(db(CAIO).doc('roles/r1').get());
  await assertFails(db(BETO).doc('roles/r1').delete());
  await assertSucceeds(db(ANA).doc('roles/r1').delete());
});

test('role: participante só remove a si mesmo; criador altera nome e remove', async () => {
  await semear((d) => d.doc('roles/r1').set(roleDoc(ANA, [ANA, BETO, CAIO])));
  const ref = (u) => db(u).doc('roles/r1');
  await assertFails(ref(BETO).update({ participantes: [ANA, BETO] })); // removeu o Caio
  await assertFails(ref(BETO).update({ nome: 'Outro' }));
  await assertFails(ref(BETO).update({ participantes: [ANA, BETO, CAIO, DUDA] }));
  await assertFails(ref(CAIO).update({ criadorUid: CAIO }));
  await assertSucceeds(ref(BETO).update({ participantes: [ANA, CAIO] }));
  await assertFails(ref(BETO).update({ participantes: [ANA] })); // já saiu
  await assertSucceeds(ref(ANA).update({ nome: 'Novo', expiraEm: 123 }));
  await assertSucceeds(ref(ANA).update({ participantes: [ANA] }));
  await assertFails(ref(ANA).update({ participantes: [ANA, DUDA] }));
  await assertFails(ref(ANA).update({ criadorUid: BETO }));
});

// ---------- membros ----------
const membro = (extra = {}) => ({
  estado: 'convidado', orcamento: null, custoPorKm: null, atualizadoEm: 1, nome: 'x', ...extra,
});

async function semearRole() {
  await semear(async (d) => {
    await d.doc('roles/r1').set(roleDoc(ANA, [ANA, BETO, CAIO]));
    await d.doc(`roles/r1/membros/${ANA}`).set(membro({ estado: 'aceito', orcamento: 500, nome: 'Ana' }));
    await d.doc(`roles/r1/membros/${BETO}`).set(membro({ estado: 'aceito', orcamento: 300, nome: 'Beto' }));
    await d.doc(`roles/r1/membros/${CAIO}`).set(membro({ nome: 'Caio' }));
  });
}

test('membros: criador cria convidados e o próprio doc na criação do rolê (lote)', async () => {
  await semearAmizade(ANA, BETO);
  const da = db(ANA);
  const b = da.batch();
  b.set(da.doc('roles/r1'), roleDoc(ANA, [ANA, BETO]));
  b.set(da.doc(`roles/r1/membros/${ANA}`), membro({ estado: 'aceito', nome: 'Ana' }));
  b.set(da.doc(`roles/r1/membros/${BETO}`), membro({ nome: 'Beto' }));
  await assertSucceeds(b.commit());
  // convidado não pode ser criado já como aceito, nem fora de participantes, nem por estranho
  await assertFails(db(ANA).doc(`roles/r1/membros/${BETO}`).set(membro({ estado: 'aceito' })));
  await assertFails(db(ANA).doc(`roles/r1/membros/${DUDA}`).set(membro()));
  await assertFails(db(CAIO).doc(`roles/r1/membros/${DUDA}`).set(membro()));
});

test('membros: convidado só lê o próprio; aceito lê os demais', async () => {
  await semearRole();
  await assertSucceeds(db(CAIO).doc(`roles/r1/membros/${CAIO}`).get());
  await assertFails(db(CAIO).doc(`roles/r1/membros/${BETO}`).get());
  await assertFails(db(CAIO).collection('roles/r1/membros').get());
  await assertSucceeds(db(BETO).doc(`roles/r1/membros/${ANA}`).get());
  await assertSucceeds(db(BETO).collection('roles/r1/membros').get());
  await assertFails(db(DUDA).doc(`roles/r1/membros/${ANA}`).get());
  await assertFails(visitante().doc(`roles/r1/membros/${ANA}`).get());
});

test('membros: dono aceita e edita o próprio; ninguém edita orçamento alheio', async () => {
  await semearRole();
  await assertSucceeds(db(CAIO).doc(`roles/r1/membros/${CAIO}`)
    .update({ estado: 'aceito', orcamento: 200, custoPorKm: 0.5, nome: 'Caio', atualizadoEm: 2 }));
  await assertSucceeds(db(CAIO).doc(`roles/r1/membros/${CAIO}`).update({ orcamento: null }));
  await assertFails(db(BETO).doc(`roles/r1/membros/${CAIO}`).update({ orcamento: 1 }));
  await assertFails(db(ANA).doc(`roles/r1/membros/${BETO}`).update({ orcamento: 1 }));
  await assertFails(db(BETO).doc(`roles/r1/membros/${BETO}`).update({ estado: 'convidado' }));
  await assertFails(db(BETO).doc(`roles/r1/membros/${BETO}`).update({ orcamento: 'muito' }));
  await assertFails(db(BETO).doc(`roles/r1/membros/${BETO}`).update({ extra: 1 }));
});

test('membros: criador apaga qualquer; dono apaga o próprio; outro não', async () => {
  await semearRole();
  await assertFails(db(BETO).doc(`roles/r1/membros/${CAIO}`).delete());
  await assertSucceeds(db(CAIO).doc(`roles/r1/membros/${CAIO}`).delete());
  await assertSucceeds(db(ANA).doc(`roles/r1/membros/${BETO}`).delete());
  await assertSucceeds(db(ANA).doc(`roles/r1/membros/${ANA}`).delete());
});

// ---------- exclusão de conta ----------
test('exclusão de conta: o dono apaga tudo que é seu em cada caminho', async () => {
  await semearRole();
  await semear(async (d) => {
    await d.doc(`busca/${BETO}`).set(buscaDoc('Beto'));
    await d.doc(`perfisPublicos/${BETO}`).set(perfilPub());
    await d.doc(`atividade/${BETO}`).set(atividade());
    await d.doc(`convites/${BETO}_${DUDA}`).set({ de: BETO, para: DUDA });
    await d.doc(`convites/${DUDA}_${BETO}`).set({ de: DUDA, para: BETO });
    await d.doc(`amizades/${par(ANA, BETO)}`).set(amizade(ANA, BETO));
    await d.doc(`bloqueios/${BETO}_${DUDA}`).set({ criadoEm: 1 });
  });
  const d = db(BETO);
  for (const p of [
    `busca/${BETO}`, `perfisPublicos/${BETO}`, `atividade/${BETO}`,
    `convites/${BETO}_${DUDA}`, `convites/${DUDA}_${BETO}`, `amizades/${par(ANA, BETO)}`,
    `bloqueios/${BETO}_${DUDA}`, `roles/r1/membros/${BETO}`,
  ]) {
    await assertSucceeds(d.doc(p).delete());
  }
  // sai do rolê que não criou
  await assertSucceeds(d.doc('roles/r1').update({ participantes: [ANA, CAIO] }));
  // criador apaga membros e depois o rolê
  const c = db(ANA);
  await assertSucceeds(c.doc(`roles/r1/membros/${CAIO}`).delete());
  await assertSucceeds(c.doc(`roles/r1/membros/${ANA}`).delete());
  await assertSucceeds(c.doc('roles/r1').delete());
});

test('caminhos fora do desenho continuam negados', async () => {
  await assertFails(db(ANA).doc('qualquer/coisa').set({ a: 1 }));
  await assertFails(db(ANA).doc(`roles/r1/outra/${ANA}`).set({ a: 1 }));
});

// ---------- rodada 1 da revisão ----------
test('I1: aceito removido do rolê não lista nem lê os demais; o próprio doc ele ainda lê e apaga', async () => {
  await semearRole();
  await assertSucceeds(db(ANA).doc('roles/r1').update({ participantes: [ANA, CAIO] })); // remove Beto
  await assertFails(db(BETO).collection('roles/r1/membros').get());
  await assertFails(db(BETO).doc(`roles/r1/membros/${ANA}`).get());
  await assertFails(db(BETO).doc(`roles/r1/membros/${BETO}`).update({ orcamento: 1 }));
  await assertSucceeds(db(BETO).doc(`roles/r1/membros/${BETO}`).get());
  await assertSucceeds(db(BETO).doc(`roles/r1/membros/${BETO}`).delete());
});

test('I1: quem saiu pela saída própria não lê os demais', async () => {
  await semearRole();
  await assertSucceeds(db(BETO).doc('roles/r1').update({ participantes: [ANA, CAIO] }));
  await assertFails(db(BETO).doc(`roles/r1/membros/${CAIO}`).get());
  await assertFails(db(BETO).collection('roles/r1/membros').get());
  await assertSucceeds(db(BETO).doc(`roles/r1/membros/${BETO}`).delete());
});

test('I1: convidado removido não vira aceito; apaga o próprio doc', async () => {
  await semearRole();
  await assertSucceeds(db(ANA).doc('roles/r1').update({ participantes: [ANA, BETO] })); // remove Caio
  await assertFails(db(CAIO).doc(`roles/r1/membros/${CAIO}`).update({ estado: 'aceito' }));
  await assertSucceeds(db(CAIO).doc(`roles/r1/membros/${CAIO}`).delete());
});

test('I1: rolê apagado não permite leitura cruzada; dono ainda apaga o próprio doc', async () => {
  await semearRole();
  await semear((d) => d.doc('roles/r1').delete());
  await assertFails(db(BETO).doc(`roles/r1/membros/${ANA}`).get());
  await assertFails(db(BETO).collection('roles/r1/membros').get());
  await assertFails(db(CAIO).doc(`roles/r1/membros/${CAIO}`).update({ estado: 'aceito' }));
  await assertSucceeds(db(BETO).doc(`roles/r1/membros/${BETO}`).get());
  await assertSucceeds(db(BETO).doc(`roles/r1/membros/${BETO}`).delete());
});

test('I2a: um único lote cria rolê + 9 amigos + 10 membros', async () => {
  const amigos = [];
  for (let i = 0; i < 9; i++) amigos.push(`amigo${i}`);
  for (const f of amigos) await semearAmizade(ANA, f);
  const da = db(ANA);
  const b = da.batch();
  b.set(da.doc('roles/big'), roleDoc(ANA, [ANA, ...amigos]));
  b.set(da.doc(`roles/big/membros/${ANA}`), membro({ estado: 'aceito', nome: 'Ana' }));
  for (const f of amigos) b.set(da.doc(`roles/big/membros/${f}`), { estado: 'convidado', nome: f, atualizadoEm: 1 });
  await assertSucceeds(b.commit());
});

test('I2b: lote do criador apaga o rolê e todos os membros', async () => {
  await semearRole();
  const da = db(ANA);
  const b = da.batch();
  b.delete(da.doc(`roles/r1/membros/${ANA}`));
  b.delete(da.doc(`roles/r1/membros/${BETO}`));
  b.delete(da.doc(`roles/r1/membros/${CAIO}`));
  b.delete(da.doc('roles/r1'));
  await assertSucceeds(b.commit());
});

test('I2c: lote de aceitar convite (amizade + apagar convite)', async () => {
  await semear((d) => d.doc(`convites/${CAIO}_${DUDA}`).set({ de: CAIO, para: DUDA }));
  const dd = db(DUDA);
  const b = dd.batch();
  b.set(dd.doc(`amizades/${par(CAIO, DUDA)}`), amizade(CAIO, DUDA));
  b.delete(dd.doc(`convites/${CAIO}_${DUDA}`));
  await assertSucceeds(b.commit());
});

test('M2: criador não sai pela saída própria', async () => {
  await semear((d) => d.doc('roles/r1').set(roleDoc(ANA, [ANA, BETO])));
  await assertFails(db(ANA).doc('roles/r1').update({ participantes: [BETO] }));
});

test('M3: convidado criado pelo criador só tem estado, nome e atualizadoEm', async () => {
  await semearAmizade(ANA, BETO);
  const da = db(ANA);
  const b = da.batch();
  b.set(da.doc('roles/r1'), roleDoc(ANA, [ANA, BETO]));
  b.set(da.doc(`roles/r1/membros/${BETO}`), membro({ orcamento: 5 }));
  await assertFails(b.commit());
  const b2 = da.batch();
  b2.set(da.doc('roles/r1'), roleDoc(ANA, [ANA, BETO]));
  b2.set(da.doc(`roles/r1/membros/${BETO}`), { estado: 'convidado', nome: 'Beto', atualizadoEm: 1 });
  await assertSucceeds(b2.commit());
});

test('M5: números negativos, enormes ou não finitos são negados', async () => {
  await assertFails(db(ANA).doc(`atividade/${ANA}`).set({ weekStartMs: 1, weekKm: -1 }));
  await assertFails(db(ANA).doc(`atividade/${ANA}`).set({ weekStartMs: 1, weekKm: 1e12 }));
  await assertFails(db(ANA).doc(`atividade/${ANA}`).set({ weekStartMs: -5, weekKm: 1 }));
  await assertFails(db(ANA).doc(`atividade/${ANA}`).set({ weekStartMs: 1, weekKm: Infinity }));
  await semearRole();
  const ref = db(BETO).doc(`roles/r1/membros/${BETO}`);
  await assertFails(ref.update({ orcamento: -1 }));
  await assertFails(ref.update({ custoPorKm: 1e12 }));
  await assertSucceeds(ref.update({ orcamento: 0, custoPorKm: null }));
});

test('M4: convite e bloqueio só com campos esperados e textos curtos', async () => {
  const longo = 'u'.repeat(129);
  await assertFails(db(longo).doc(`convites/${longo}_${BETO}`).set({ de: longo, para: BETO }));
  await assertFails(db(ANA).doc(`convites/${ANA}_${BETO}`).set({ de: ANA, para: BETO, a: 1, b: 2 }));
  await assertFails(db(ANA).doc(`bloqueios/${ANA}_${BETO}`).set({ criadoEm: 1, extra: 'x' }));
  await assertFails(db(longo).doc(`bloqueios/${longo}_${BETO}`).set({ criadoEm: 1 }));
  await assertSucceeds(db(ANA).doc(`convites/${ANA}_${BETO}`).set({ de: ANA, para: BETO, criadoEm: 1 }));
});

test('M7: amizade por quem não é a nem b é negada; duplicado em participantes é negado', async () => {
  await semear(async (d) => {
    await d.doc(`convites/${ANA}_${BETO}`).set({ de: ANA, para: BETO });
    await d.doc(`convites/${BETO}_${ANA}`).set({ de: BETO, para: ANA });
    await d.doc(`convites/${ANA}_${CAIO}`).set({ de: ANA, para: CAIO });
  });
  const dc = db(CAIO);
  const b = dc.batch();
  b.set(dc.doc(`amizades/${par(ANA, BETO)}`), amizade(ANA, BETO));
  b.delete(dc.doc(`convites/${ANA}_${CAIO}`));
  await assertFails(b.commit());
  await semear((d) => d.doc('roles/r1').set(roleDoc(ANA, [ANA, BETO])));
  await assertFails(db(ANA).doc('roles/r1').update({ participantes: [ANA, BETO, BETO] }));
  await assertFails(db(ANA).doc('roles/dup').set(roleDoc(ANA, [ANA, ANA])));
});

// ---------- rodada 2: valores realistas de tempo ----------
test('timestamps reais (Date.now e +7 dias) são aceitos; valor absurdo é negado', async () => {
  const agora = Date.now();
  const em7 = agora + 7 * 24 * 3600 * 1000;
  await assertSucceeds(db(ANA).doc(`atividade/${ANA}`).set({ weekStartMs: agora, weekKm: 120.5 }));
  await assertFails(db(ANA).doc(`atividade/${ANA}`).set({ weekStartMs: 1e14, weekKm: 1 }));
  await assertSucceeds(db(ANA).doc(`convites/${ANA}_${BETO}`).set({ de: ANA, para: BETO, criadoEm: agora }));
  await assertFails(db(ANA).doc(`convites/${ANA}_${CAIO}`).set({ de: ANA, para: CAIO, criadoEm: 1e14 }));
  await assertSucceeds(db(ANA).doc(`bloqueios/${ANA}_${BETO}`).set({ criadoEm: agora }));
  await semearAmizade(ANA, BETO);
  const da = db(ANA);
  const b = da.batch();
  b.set(da.doc('roles/rt'), roleDoc(ANA, [ANA, BETO], { expiraEm: em7, criadoEm: agora }));
  b.set(da.doc(`roles/rt/membros/${ANA}`), membro({ estado: 'aceito', atualizadoEm: agora, nome: 'Ana' }));
  b.set(da.doc(`roles/rt/membros/${BETO}`), { estado: 'convidado', nome: 'Beto', atualizadoEm: agora });
  await assertSucceeds(b.commit());
  await assertSucceeds(db(BETO).doc(`roles/rt/membros/${BETO}`)
    .update({ estado: 'aceito', atualizadoEm: agora + 5, orcamento: 250, custoPorKm: 0.6 }));
  await assertSucceeds(da.doc('roles/rt').update({ expiraEm: em7 + 1000 }));
  await assertFails(da.doc('roles/rt').update({ expiraEm: 1e14 }));
  await assertFails(db(BETO).doc(`roles/rt/membros/${BETO}`).update({ atualizadoEm: 1e14 }));
  await assertFails(da.doc('roles/rx').set(roleDoc(ANA, [ANA], { expiraEm: 1e14 })));
});
