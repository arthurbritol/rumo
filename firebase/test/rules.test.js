// Testes das regras do Firestore (rodam no emulador: veja docs/FIREBASE.md).
const { test, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');

const DONO = 'dono123';
const OUTRO = 'outro456';
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

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

const db = (uid) => env.authenticatedContext(uid).firestore();
const visitante = () => env.unauthenticatedContext().firestore();

const perfilValido = () => ({
  displayName: 'Arthur',
  displayNameLower: 'arthur',
  brand: 'Honda',
  model: 'CB 500X',
  year: 2022,
  voice: true,
  updatedAtMs: 1000,
});

const docSync = (extra = {}) => ({ updatedAtMs: 1000, deleted: false, litros: 12.5, ...extra });

const SUBCOLECOES = ['abastecimentos', 'manutencao', 'locais', 'viagens'];

test('dono lê e escreve o próprio perfil e subcoleções', async () => {
  const d = db(DONO);
  await assertSucceeds(d.doc(`usuarios/${DONO}`).set(perfilValido()));
  await assertSucceeds(d.doc(`usuarios/${DONO}`).get());
  for (const sub of SUBCOLECOES) {
    const ref = d.doc(`usuarios/${DONO}/${sub}/gid-1`);
    await assertSucceeds(ref.set(docSync()));
    await assertSucceeds(ref.get());
    await assertSucceeds(ref.update({ updatedAtMs: 2000, deleted: true }));
  }
  const hodo = d.doc(`usuarios/${DONO}/config/hodometro`);
  await assertSucceeds(hodo.set({ offsetKm: 120, tankLiters: 17.5, updatedAtMs: 1000 }));
  await assertSucceeds(hodo.get());
  const exclusao = d.doc(`usuarios/${DONO}/config/exclusao`);
  await assertSucceeds(exclusao.set({ pending: true }));
  await assertSucceeds(exclusao.get());
  await assertSucceeds(d.doc(`usuarios/${DONO}`).delete());
  await assertSucceeds(d.doc(`usuarios/${DONO}/abastecimentos/gid-1`).delete());
});

test('perfil parcial (só displayName) é aceito', async () => {
  await assertSucceeds(
    db(DONO).doc(`usuarios/${DONO}`).set({ displayName: 'Ana', updatedAtMs: 1 }),
  );
});

test('outro usuário não lê nem escreve', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const a = ctx.firestore();
    await a.doc(`usuarios/${DONO}`).set(perfilValido());
    await a.doc(`usuarios/${DONO}/abastecimentos/g1`).set(docSync());
    await a
      .doc(`usuarios/${DONO}/config/hodometro`)
      .set({ offsetKm: 1, tankLiters: 1, updatedAtMs: 1 });
  });
  const o = db(OUTRO);
  await assertFails(o.doc(`usuarios/${DONO}`).get());
  await assertFails(o.doc(`usuarios/${DONO}`).set(perfilValido()));
  await assertFails(o.doc(`usuarios/${DONO}/abastecimentos/g1`).get());
  await assertFails(o.doc(`usuarios/${DONO}/abastecimentos/g2`).set(docSync()));
  await assertFails(o.doc(`usuarios/${DONO}/config/hodometro`).get());
  await assertFails(o.doc(`usuarios/${DONO}/config/exclusao`).set({ pending: true }));
  await assertFails(o.collection(`usuarios/${DONO}/abastecimentos`).get());
});

test('visitante (sem login) não acessa nada', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().doc(`usuarios/${DONO}`).set(perfilValido());
  });
  const v = visitante();
  await assertFails(v.doc(`usuarios/${DONO}`).get());
  await assertFails(v.doc(`usuarios/${DONO}`).set(perfilValido()));
  await assertFails(v.doc(`usuarios/${DONO}/viagens/g1`).get());
  await assertFails(v.doc(`usuarios/${DONO}/viagens/g1`).set(docSync()));
  await assertFails(v.doc(`usuarios/${DONO}/config/exclusao`).set({ pending: true }));
});

test('campo desconhecido no perfil é recusado', async () => {
  await assertFails(
    db(DONO).doc(`usuarios/${DONO}`).set({ ...perfilValido(), deletionPending: true }),
  );
  await assertFails(db(DONO).doc(`usuarios/${DONO}`).set({ ...perfilValido(), extra: 1 }));
});

test('displayName vazio ou maior que 60 caracteres é recusado', async () => {
  const ref = db(DONO).doc(`usuarios/${DONO}`);
  await assertFails(ref.set({ ...perfilValido(), displayName: '', displayNameLower: '' }));
  const longo = 'a'.repeat(61);
  await assertFails(ref.set({ ...perfilValido(), displayName: longo, displayNameLower: longo }));
  const limite = 'a'.repeat(60);
  await assertSucceeds(
    ref.set({ ...perfilValido(), displayName: limite, displayNameLower: limite }),
  );
});

test('displayNameLower deve ser o displayName em minúsculas', async () => {
  await assertFails(
    db(DONO).doc(`usuarios/${DONO}`).set({ ...perfilValido(), displayNameLower: 'outro' }),
  );
});

test('voice do perfil: booleano ou nulo; texto é recusado', async () => {
  const ref = db(DONO).doc(`usuarios/${DONO}`);
  await assertSucceeds(ref.set({ ...perfilValido(), voice: false }));
  await assertSucceeds(ref.set({ ...perfilValido(), voice: null }));
  await assertFails(ref.set({ ...perfilValido(), voice: 'true' }));
});

test('ano do perfil: inteiro entre 1950 e 2100', async () => {
  const ref = db(DONO).doc(`usuarios/${DONO}`);
  await assertFails(ref.set({ ...perfilValido(), year: 1949 }));
  await assertFails(ref.set({ ...perfilValido(), year: 2101 }));
  await assertFails(ref.set({ ...perfilValido(), year: 2020.5 }));
  await assertFails(ref.set({ ...perfilValido(), year: '2020' }));
  await assertSucceeds(ref.set({ ...perfilValido(), year: 1950 }));
});

test('documento de subcoleção com mais de 20 campos é recusado', async () => {
  const campos = (n) => {
    const o = { updatedAtMs: 1, deleted: false };
    for (let i = 0; i < n - 2; i++) o[`c${i}`] = i;
    return o;
  };
  for (const sub of SUBCOLECOES) {
    const ref = db(DONO).doc(`usuarios/${DONO}/${sub}/g1`);
    await assertSucceeds(ref.set(campos(20)));
    await assertFails(ref.set(campos(21)));
  }
});

test('documento de sincronização exige updatedAtMs numérico e deleted booleano', async () => {
  const ref = db(DONO).doc(`usuarios/${DONO}/locais/g1`);
  await assertFails(ref.set({ deleted: false, nome: 'x' }));
  await assertFails(ref.set({ updatedAtMs: 1, nome: 'x' }));
  await assertFails(ref.set({ updatedAtMs: '1', deleted: false }));
  await assertFails(ref.set({ updatedAtMs: 1, deleted: 'nao' }));
});

test('`pending` em config/exclusao só aceita booleano', async () => {
  const ref = db(DONO).doc(`usuarios/${DONO}/config/exclusao`);
  await assertSucceeds(ref.set({ pending: false }));
  await assertFails(ref.set({ pending: 'sim' }));
  await assertFails(ref.set({ pending: 1 }));
  await assertFails(ref.set({ pending: true, extra: 1 }));
  await assertFails(ref.set({}));
});

test('hodômetro só aceita campos numéricos conhecidos', async () => {
  const ref = db(DONO).doc(`usuarios/${DONO}/config/hodometro`);
  await assertFails(ref.set({ offsetKm: 'a', tankLiters: 1, updatedAtMs: 1 }));
  await assertFails(ref.set({ offsetKm: 1, tankLiters: 1, updatedAtMs: 1, extra: 1 }));
});

test('um caminho desconhecido é negado', async () => {
  const d = db(DONO);
  await assertFails(d.doc(`usuarios/${DONO}/outra/x`).set({ a: 1 }));
  await assertFails(d.doc(`usuarios/${DONO}/outra/x`).get());
  await assertFails(d.doc('outros/x').set({ a: 1 }));
});

test('config desconhecido é negado', async () => {
  const d = db(DONO);
  await assertFails(d.doc(`usuarios/${DONO}/config/qualquer`).set({ a: 1 }));
  await assertFails(d.doc(`usuarios/${DONO}/config/qualquer`).get());
});

const TODOS_CAMINHOS = () => [
  `usuarios/${DONO}`,
  ...SUBCOLECOES.map((sub) => `usuarios/${DONO}/${sub}/g1`),
  `usuarios/${DONO}/config/hodometro`,
  `usuarios/${DONO}/config/exclusao`,
];

async function semear() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const a = ctx.firestore();
    await a.doc(`usuarios/${DONO}`).set(perfilValido());
    for (const sub of SUBCOLECOES) await a.doc(`usuarios/${DONO}/${sub}/g1`).set(docSync());
    await a
      .doc(`usuarios/${DONO}/config/hodometro`)
      .set({ offsetKm: 1, tankLiters: 1, updatedAtMs: 1 });
    await a.doc(`usuarios/${DONO}/config/exclusao`).set({ pending: true });
  });
}

test('outro usuário e visitante não conseguem apagar nada', async () => {
  await semear();
  for (const ctx of [db(OUTRO), visitante()]) {
    for (const caminho of TODOS_CAMINHOS()) {
      await assertFails(ctx.doc(caminho).delete());
    }
  }
});

test('dono apaga todos os caminhos usados na exclusão de conta', async () => {
  await semear();
  const d = db(DONO);
  for (const caminho of TODOS_CAMINHOS()) {
    await assertSucceeds(d.doc(caminho).delete());
  }
});

test('dono lista uma subcoleção', async () => {
  await semear();
  for (const sub of SUBCOLECOES) {
    await assertSucceeds(db(DONO).collection(`usuarios/${DONO}/${sub}`).get());
  }
});

test('update ou merge que adiciona campo desconhecido ao perfil é negado', async () => {
  await semear();
  const ref = db(DONO).doc(`usuarios/${DONO}`);
  await assertFails(ref.update({ extra: 1 }));
  await assertFails(ref.set({ extra: 1 }, { merge: true }));
  await assertSucceeds(ref.update({ brand: 'Yamaha' }));
});

test('update que faz documento de sincronização passar de 20 campos é negado', async () => {
  const o = { updatedAtMs: 1, deleted: false };
  for (let i = 0; i < 18; i++) o[`c${i}`] = i;
  const ref = db(DONO).doc(`usuarios/${DONO}/viagens/g9`);
  await assertSucceeds(ref.set(o));
  await assertFails(ref.update({ novo: 1 }));
});
