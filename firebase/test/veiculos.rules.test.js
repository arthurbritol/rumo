// Testes das regras de veículos: usuarios/{uid}/veiculos, hodometros, veiculoAtivoId no perfil e veiculoId nos
// documentos de sincronização (rodam no emulador: veja docs/FIREBASE.md).
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
const veiculo = (id) => db(DONO).doc(`usuarios/${DONO}/veiculos/${id}`);

const veiculoValido = (extra = {}) => ({
  tipo: 'MOTO',
  apelido: 'Minha moto',
  marca: 'Honda',
  modelo: 'Hunter 350',
  ano: 2023,
  tanqueLitros: 13,
  consumoPadraoKmL: null,
  criadoEm: 1000,
  removidoEm: null,
  updatedAtMs: 1000,
  deleted: false,
  ...extra,
});

const perfilValido = (extra = {}) => ({
  displayName: 'Arthur',
  displayNameLower: 'arthur',
  voice: true,
  updatedAtMs: 1000,
  ...extra,
});

// ---------- veículos ----------

test('dono grava, lê, atualiza e apaga o veículo (moto e carro)', async () => {
  await assertSucceeds(veiculo('v1').set(veiculoValido()));
  await assertSucceeds(veiculo('v1').get());
  await assertSucceeds(veiculo('v1').update({ removidoEm: 5000, updatedAtMs: 5000 }));
  await assertSucceeds(veiculo('v2').set(veiculoValido({ tipo: 'CARRO', tanqueLitros: 45, consumoPadraoKmL: 11.5 })));
  await assertSucceeds(db(DONO).collection(`usuarios/${DONO}/veiculos`).get());
  await assertSucceeds(veiculo('v1').delete());
});

test('veículo mínimo (sem marca, modelo, ano, consumo e removidoEm) é aceito', async () => {
  await assertSucceeds(
    veiculo('v1').set({ tipo: 'CARRO', apelido: '', tanqueLitros: 0, criadoEm: 1, updatedAtMs: 1, deleted: false }),
  );
});

test('outro usuário e visitante não leem, escrevem nem apagam veículos', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().doc(`usuarios/${DONO}/veiculos/v1`).set(veiculoValido());
  });
  for (const ctx of [db(OUTRO), visitante()]) {
    await assertFails(ctx.doc(`usuarios/${DONO}/veiculos/v1`).get());
    await assertFails(ctx.doc(`usuarios/${DONO}/veiculos/v2`).set(veiculoValido()));
    await assertFails(ctx.doc(`usuarios/${DONO}/veiculos/v1`).delete());
    await assertFails(ctx.collection(`usuarios/${DONO}/veiculos`).get());
  }
});

test('tipo só aceita MOTO ou CARRO', async () => {
  for (const tipo of ['CAMINHAO', 'moto', '', 1, null]) {
    await assertFails(veiculo('v1').set(veiculoValido({ tipo })));
  }
  await assertSucceeds(veiculo('v1').set(veiculoValido({ tipo: 'CARRO' })));
});

test('textos têm tamanho máximo (apelido, marca e modelo: 60)', async () => {
  const ok = 'a'.repeat(60);
  const longo = 'a'.repeat(61);
  await assertSucceeds(veiculo('v1').set(veiculoValido({ apelido: ok, marca: ok, modelo: ok })));
  await assertFails(veiculo('v1').set(veiculoValido({ apelido: longo })));
  await assertFails(veiculo('v1').set(veiculoValido({ marca: longo })));
  await assertFails(veiculo('v1').set(veiculoValido({ modelo: longo })));
  await assertFails(veiculo('v1').set(veiculoValido({ apelido: 5 })));
  await assertFails(veiculo('v1').set(veiculoValido({ marca: 5 })));
});

test('tanque entre 0 e 200 litros', async () => {
  await assertSucceeds(veiculo('v1').set(veiculoValido({ tanqueLitros: 0 })));
  await assertSucceeds(veiculo('v1').set(veiculoValido({ tanqueLitros: 200 })));
  await assertFails(veiculo('v1').set(veiculoValido({ tanqueLitros: 200.5 })));
  await assertFails(veiculo('v1').set(veiculoValido({ tanqueLitros: -1 })));
  await assertFails(veiculo('v1').set(veiculoValido({ tanqueLitros: '13' })));
  await assertFails(veiculo('v1').set(veiculoValido({ tanqueLitros: null })));
});

test('ano plausível: inteiro de 1900 a 2100 (ou ausente/nulo)', async () => {
  await assertSucceeds(veiculo('v1').set(veiculoValido({ ano: 1900 })));
  await assertSucceeds(veiculo('v1').set(veiculoValido({ ano: 2100 })));
  await assertSucceeds(veiculo('v1').set(veiculoValido({ ano: null })));
  await assertFails(veiculo('v1').set(veiculoValido({ ano: 1899 })));
  await assertFails(veiculo('v1').set(veiculoValido({ ano: 2101 })));
  await assertFails(veiculo('v1').set(veiculoValido({ ano: 2020.5 })));
  await assertFails(veiculo('v1').set(veiculoValido({ ano: '2020' })));
});

test('consumo padrão: número de 0 (exclusivo) a 100, ou nulo', async () => {
  await assertSucceeds(veiculo('v1').set(veiculoValido({ consumoPadraoKmL: 12.5 })));
  await assertFails(veiculo('v1').set(veiculoValido({ consumoPadraoKmL: 0 })));
  await assertFails(veiculo('v1').set(veiculoValido({ consumoPadraoKmL: 101 })));
  await assertFails(veiculo('v1').set(veiculoValido({ consumoPadraoKmL: 'x' })));
});

test('criadoEm, removidoEm, updatedAtMs e deleted são tipados', async () => {
  await assertFails(veiculo('v1').set(veiculoValido({ criadoEm: '1' })));
  await assertFails(veiculo('v1').set(veiculoValido({ removidoEm: 'ontem' })));
  await assertFails(veiculo('v1').set(veiculoValido({ updatedAtMs: '1' })));
  await assertFails(veiculo('v1').set(veiculoValido({ deleted: 'nao' })));
  await assertSucceeds(veiculo('v1').set(veiculoValido({ removidoEm: 2000 })));
});

test('campos obrigatórios e campo desconhecido', async () => {
  for (const campo of ['tipo', 'apelido', 'tanqueLitros', 'criadoEm', 'updatedAtMs', 'deleted']) {
    const v = veiculoValido();
    delete v[campo];
    await assertFails(veiculo('v1').set(v));
  }
  await assertFails(veiculo('v1').set(veiculoValido({ placa: 'ABC1D23' })));
  await assertFails(veiculo('v1').update({ extra: 1 }));
});

// ---------- hodômetro por veículo ----------

const hodo = (id) => db(DONO).doc(`usuarios/${DONO}/hodometros/${id}`);

test('hodômetro por veículo: só campos conhecidos e veiculoId igual ao id do caminho', async () => {
  await assertSucceeds(hodo('v1').set({ offsetKm: 100, tankLiters: 13, updatedAtMs: 1, veiculoId: 'v1' }));
  await assertSucceeds(hodo('v1').get());
  await assertFails(hodo('v1').set({ offsetKm: 100, tankLiters: 13, updatedAtMs: 1 })); // sem veiculoId
  await assertFails(hodo('v1').set({ offsetKm: 100, tankLiters: 13, updatedAtMs: 1, veiculoId: 'v2' })); // outro id
  await assertFails(hodo('v1').set({ offsetKm: 'a', tankLiters: 13, updatedAtMs: 1, veiculoId: 'v1' }));
  await assertFails(hodo('v1').set({ offsetKm: 1, tankLiters: 13, updatedAtMs: 1, veiculoId: 'v1', extra: 1 }));
  await assertSucceeds(hodo('v1').delete());
});

test('hodômetro por veículo: só o dono', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().doc(`usuarios/${DONO}/hodometros/v1`).set({ offsetKm: 1, tankLiters: 1, updatedAtMs: 1, veiculoId: 'v1' });
  });
  for (const ctx of [db(OUTRO), visitante()]) {
    await assertFails(ctx.doc(`usuarios/${DONO}/hodometros/v1`).get());
    await assertFails(ctx.doc(`usuarios/${DONO}/hodometros/v1`).delete());
    await assertFails(ctx.doc(`usuarios/${DONO}/hodometros/v2`).set({ offsetKm: 1, tankLiters: 1, updatedAtMs: 1, veiculoId: 'v2' }));
  }
});

test('documento antigo config/hodometro continua aceito, com veiculoId opcional', async () => {
  const ref = db(DONO).doc(`usuarios/${DONO}/config/hodometro`);
  await assertSucceeds(ref.set({ offsetKm: 1, tankLiters: 13, updatedAtMs: 1 }));
  await assertSucceeds(ref.set({ offsetKm: 1, tankLiters: 13, updatedAtMs: 1, veiculoId: 'v1' }));
  await assertFails(ref.set({ offsetKm: 1, tankLiters: 13, updatedAtMs: 1, veiculoId: 7 }));
  await assertFails(ref.set({ offsetKm: 1, tankLiters: 13, updatedAtMs: 1, veiculoId: 'x'.repeat(65) }));
  await assertFails(ref.set({ offsetKm: 1, tankLiters: 13, updatedAtMs: 1, extra: 1 }));
});

// ---------- perfil e documentos de sincronização ----------

test('perfil aceita veiculoAtivoId (texto até 64) e continua aceitando sem ele', async () => {
  const ref = db(DONO).doc(`usuarios/${DONO}`);
  await assertSucceeds(ref.set(perfilValido()));
  await assertSucceeds(ref.set(perfilValido({ veiculoAtivoId: 'a'.repeat(36) })));
  await assertSucceeds(ref.set(perfilValido({ veiculoAtivoId: null })));
  await assertSucceeds(ref.set(perfilValido({ veiculoAtivoId: 'a'.repeat(64) })));
  await assertFails(ref.set(perfilValido({ veiculoAtivoId: 'a'.repeat(65) })));
  await assertFails(ref.set(perfilValido({ veiculoAtivoId: 5 })));
  await assertFails(ref.set(perfilValido({ extra: 1 })));
});

test('abastecimento, manutenção e viagem aceitam veiculoId de até 64 caracteres', async () => {
  for (const sub of ['abastecimentos', 'manutencao', 'viagens']) {
    const ref = db(DONO).doc(`usuarios/${DONO}/${sub}/g1`);
    const base = { updatedAtMs: 1, deleted: false, litros: 5 };
    await assertSucceeds(ref.set(base)); // documento antigo, sem veiculoId
    await assertSucceeds(ref.set({ ...base, veiculoId: 'v'.repeat(36) }));
    await assertSucceeds(ref.set({ ...base, veiculoId: null }));
    await assertFails(ref.set({ ...base, veiculoId: 'v'.repeat(65) }));
    await assertFails(ref.set({ ...base, veiculoId: 9 }));
  }
});

test('o limite de 20 chaves continua valendo com veiculoId', async () => {
  const campos = (n) => {
    const o = { updatedAtMs: 1, deleted: false, veiculoId: 'v1' };
    for (let i = 0; i < n - 3; i++) o[`c${i}`] = i;
    return o;
  };
  const ref = db(DONO).doc(`usuarios/${DONO}/abastecimentos/g1`);
  await assertSucceeds(ref.set(campos(20)));
  await assertFails(ref.set(campos(21)));
});

test('dono apaga veículos e hodômetros na exclusão de conta', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const a = ctx.firestore();
    await a.doc(`usuarios/${DONO}/veiculos/v1`).set(veiculoValido());
    await a.doc(`usuarios/${DONO}/hodometros/v1`).set({ offsetKm: 1, tankLiters: 1, updatedAtMs: 1, veiculoId: 'v1' });
  });
  await assertSucceeds(veiculo('v1').delete());
  await assertSucceeds(hodo('v1').delete());
});
