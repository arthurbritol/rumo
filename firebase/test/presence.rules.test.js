// Testes das regras de "visto por último" (presenca/{uid}). Rodam no emulador do Firestore.
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
const semearAmizade = (a, b) => semear((d) => {
  const [x, y] = a < b ? [a, b] : [b, a];
  return d.doc(`amizades/${par(a, b)}`).set({ a: x, b: y });
});
const semearPermissao = (de, para) =>
  semear((d) => d.doc(`permissoesLocalizacao/${de}_${para}`).set({ de, para, criadoEm: 1700000000000 }));

test('presenca: dono grava, sobrescreve, lê e apaga o próprio documento', async () => {
  const ref = db(ANA).doc(`presenca/${ANA}`);
  await assertSucceeds(ref.set({ lastSeenMs: 1700000000000 }));
  await assertSucceeds(ref.set({ lastSeenMs: 1700000060000 }));
  await assertSucceeds(ref.get());
  await assertSucceeds(ref.delete());
});

test('presenca: só lastSeenMs numérico e dentro do limite', async () => {
  const ref = db(ANA).doc(`presenca/${ANA}`);
  await assertFails(ref.set({ lastSeenMs: 1700000000000, extra: 1 }));
  await assertFails(ref.set({ lastSeenMs: '1700000000000' }));
  await assertFails(ref.set({ lastSeenMs: -1 }));
  await assertFails(ref.set({ lastSeenMs: 1e15 }));
  await assertFails(ref.set({}));
});

test('presenca: ninguém grava no documento de outra pessoa nem sem login', async () => {
  await semearAmizade(ANA, BETO);
  await assertFails(db(BETO).doc(`presenca/${ANA}`).set({ lastSeenMs: 1700000000000 }));
  await assertFails(visitante().doc(`presenca/${ANA}`).set({ lastSeenMs: 1700000000000 }));
  await semear((d) => d.doc(`presenca/${ANA}`).set({ lastSeenMs: 1700000000000 }));
  await assertFails(db(BETO).doc(`presenca/${ANA}`).delete());
});

test('presenca: amigo COM autorização de localização lê; sem autorização, estranho e visitante não', async () => {
  await semearAmizade(ANA, BETO);
  await semearAmizade(ANA, CAIO);
  await semear((d) => d.doc(`presenca/${ANA}`).set({ lastSeenMs: 1700000000000 }));
  await semearPermissao(ANA, BETO); // ANA autorizou BETO; CAIO é amigo mas não foi autorizado

  await assertSucceeds(db(BETO).doc(`presenca/${ANA}`).get());
  await assertFails(db(CAIO).doc(`presenca/${ANA}`).get());
  await assertFails(db('estranho').doc(`presenca/${ANA}`).get());
  await assertFails(visitante().doc(`presenca/${ANA}`).get());
});

test('presenca: autorização sem amizade ativa não basta', async () => {
  await semear((d) => d.doc(`presenca/${ANA}`).set({ lastSeenMs: 1700000000000 }));
  await semearPermissao(ANA, BETO);
  await assertFails(db(BETO).doc(`presenca/${ANA}`).get());
});

test('presenca: com exclusão de conta pendente o dono não grava, mas apaga', async () => {
  await semear((d) => d.doc(`usuarios/${ANA}/config/exclusao`).set({ pending: true }));
  await assertFails(db(ANA).doc(`presenca/${ANA}`).set({ lastSeenMs: 1700000000000 }));
  await semear((d) => d.doc(`presenca/${ANA}`).set({ lastSeenMs: 1700000000000 }));
  await assertSucceeds(db(ANA).doc(`presenca/${ANA}`).delete());
});
