// Testes das regras de permissões de localização e coordenadas em tempo real.
// Rodam no emulador do Firestore.
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
const amizade = (a, b) => { const [x, y] = a < b ? [a, b] : [b, a]; return { a: x, b: y }; };
const semearAmizade = (a, b) => semear((d) => d.doc(`amizades/${par(a, b)}`).set(amizade(a, b)));

const localizacaoValidaDoc = (extra = {}) => ({
  latitude: -23.55052,
  longitude: -46.633308,
  bearing: 180.5,
  speedKmh: 65.0,
  updatedAtMs: 1700000000000,
  ...extra,
});

const permissaoValidaDoc = (de, para, extra = {}) => ({
  de,
  para,
  criadoEm: 1700000000000,
  ...extra,
});

// ---------- localizacoes: CRUD pelo dono e bloqueio a estranhos ----------
test('localizacoes: dono lê, cria, atualiza e apaga a própria localização com dados válidos', async () => {
  const ref = db(ANA).doc(`localizacoes/${ANA}`);
  await assertSucceeds(ref.set(localizacaoValidaDoc()));
  await assertSucceeds(ref.get());
  await assertSucceeds(ref.update({ speedKmh: 80.0, updatedAtMs: 1700000001000 }));
  await assertSucceeds(ref.delete());
});

test('localizacoes: estranho não lê, não cria, não atualiza e não apaga', async () => {
  await semear((d) => d.doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
  await assertFails(db(BETO).doc(`localizacoes/${ANA}`).get());
  await assertFails(visitante().doc(`localizacoes/${ANA}`).get());
  await assertFails(db(BETO).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
  await assertFails(db(BETO).doc(`localizacoes/${ANA}`).update({ speedKmh: 0 }));
  await assertFails(db(BETO).doc(`localizacoes/${ANA}`).delete());
  await assertFails(visitante().doc(`localizacoes/${ANA}`).delete());
});

test('localizacoes: amigo tenta ler sem permissão em permissoesLocalizacao -> falha', async () => {
  await semearAmizade(ANA, BETO);
  await semear((d) => d.doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
  await assertFails(db(BETO).doc(`localizacoes/${ANA}`).get());
});

// ---------- permissoesLocalizacao: concessão, restrições e ciclo de vida ----------
test('permissoesLocalizacao: dono cria permissão para amigo -> sucesso; para quem não é amigo -> falha', async () => {
  await semearAmizade(ANA, BETO);
  // ANA concede para BETO (amigo) -> sucesso
  await assertSucceeds(
    db(ANA).doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO))
  );
  // ANA tenta conceder para CAIO (não amigo) -> falha
  await assertFails(
    db(ANA).doc(`permissoesLocalizacao/${ANA}_${CAIO}`).set(permissaoValidaDoc(ANA, CAIO))
  );
  // BETO tenta forjar permissão como se ANA tivesse concedido -> falha
  await assertFails(
    db(BETO).doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO))
  );
  // Para si mesmo -> falha
  await assertFails(
    db(ANA).doc(`permissoesLocalizacao/${ANA}_${ANA}`).set(permissaoValidaDoc(ANA, ANA))
  );
  // ID do documento não bate com de_para -> falha
  await assertFails(
    db(ANA).doc(`permissoesLocalizacao/${ANA}_outro`).set(permissaoValidaDoc(ANA, BETO))
  );
});

test('permissoesLocalizacao: campos extras ou atualização são negados', async () => {
  await semearAmizade(ANA, BETO);
  // Campos extras
  await assertFails(
    db(ANA).doc(`permissoesLocalizacao/${ANA}_${BETO}`).set({ ...permissaoValidaDoc(ANA, BETO), extra: 1 })
  );
  // Criado com sucesso
  await assertSucceeds(
    db(ANA).doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO))
  );
  // Atualização não é permitida
  await assertFails(
    db(ANA).doc(`permissoesLocalizacao/${ANA}_${BETO}`).update({ criadoEm: 1700000005000 })
  );
});

test('localizacoes: amigo com permissão e amizade ativa lê localização -> sucesso', async () => {
  await semearAmizade(ANA, BETO);
  await semear((d) => d.doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
  await semear((d) => d.doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO)));

  // BETO tem permissão e amizade -> lê com sucesso
  await assertSucceeds(db(BETO).doc(`localizacoes/${ANA}`).get());
  // CAIO não tem permissão nem amizade -> falha
  await assertFails(db(CAIO).doc(`localizacoes/${ANA}`).get());
});

test('permissoesLocalizacao: remetente e destinatário lêem e apagam; estranho não', async () => {
  await semearAmizade(ANA, BETO);
  await semear((d) => d.doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO)));

  // Remetente e destinatário lêem
  await assertSucceeds(db(ANA).doc(`permissoesLocalizacao/${ANA}_${BETO}`).get());
  await assertSucceeds(db(BETO).doc(`permissoesLocalizacao/${ANA}_${BETO}`).get());
  // Estranho não lê nem apaga
  await assertFails(db(CAIO).doc(`permissoesLocalizacao/${ANA}_${BETO}`).get());
  await assertFails(db(CAIO).doc(`permissoesLocalizacao/${ANA}_${BETO}`).delete());
  await assertFails(visitante().doc(`permissoesLocalizacao/${ANA}_${BETO}`).get());
});

test('permissoesLocalizacao: remetente apaga a permissão -> leitura subsequente da localização falha', async () => {
  await semearAmizade(ANA, BETO);
  await semear((d) => d.doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
  await semear((d) => d.doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO)));

  await assertSucceeds(db(BETO).doc(`localizacoes/${ANA}`).get());
  // ANA revoga a permissão
  await assertSucceeds(db(ANA).doc(`permissoesLocalizacao/${ANA}_${BETO}`).delete());
  // BETO não consegue mais ler a localização
  await assertFails(db(BETO).doc(`localizacoes/${ANA}`).get());
});

test('permissoesLocalizacao: destinatário apaga a permissão -> leitura subsequente da localização falha', async () => {
  await semearAmizade(ANA, BETO);
  await semear((d) => d.doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
  await semear((d) => d.doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO)));

  await assertSucceeds(db(BETO).doc(`localizacoes/${ANA}`).get());
  // BETO remove a permissão que recebeu
  await assertSucceeds(db(BETO).doc(`permissoesLocalizacao/${ANA}_${BETO}`).delete());
  // BETO não consegue mais ler a localização
  await assertFails(db(BETO).doc(`localizacoes/${ANA}`).get());
});

test('desfazer amizade: leitura de localizacoes/{uid} falha imediatamente mesmo com permissão ainda salva', async () => {
  await semearAmizade(ANA, BETO);
  await semear((d) => d.doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
  await semear((d) => d.doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO)));

  await assertSucceeds(db(BETO).doc(`localizacoes/${ANA}`).get());
  // Amizade desfeita
  await assertSucceeds(db(ANA).doc(`amizades/${par(ANA, BETO)}`).delete());
  // BETO perde acesso imediatamente
  await assertFails(db(BETO).doc(`localizacoes/${ANA}`).get());
});

test('I4: usuário com usuarios/{uid}/config/exclusao tenta gravar localização -> falha; apagar continua permitido', async () => {
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
  await assertSucceeds(db(ANA).doc(`usuarios/${ANA}/config/exclusao`).set({ pending: true }));

  // Gravações atrasadas negadas
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ speedKmh: 10 })));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).update({ speedKmh: 20 }));

  // Apagar para limpeza de conta é permitido
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).delete());
  // Não ressuscita depois de apagado
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc()));
});

// ---------- Validação de esquema de localizacaoValida ----------
test('localizacaoValida: limites de latitude e longitude são estritamente validados', async () => {
  // Latitude válida: -90 a 90
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ latitude: -90 })));
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ latitude: 90 })));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ latitude: -90.0001 })));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ latitude: 90.0001 })));

  // Longitude válida: -180 a 180
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ longitude: -180 })));
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ longitude: 180 })));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ longitude: -180.0001 })));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ longitude: 180.0001 })));
});

test('localizacaoValida: bearing (0..360 ou nulo/ausente) e speedKmh (>=0 ou nulo/ausente)', async () => {
  // bearing nulo ou ausente
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ bearing: null })));
  const { bearing, ...semBearing } = localizacaoValidaDoc();
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(semBearing));

  // bearing inválido
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ bearing: -0.1 })));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ bearing: 360.1 })));

  // speedKmh nulo ou ausente
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ speedKmh: null })));
  const { speedKmh, ...semSpeed } = localizacaoValidaDoc();
  await assertSucceeds(db(ANA).doc(`localizacoes/${ANA}`).set(semSpeed));

  // speedKmh inválido
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ speedKmh: -1 })));
});

test('localizacaoValida: updatedAtMs obrigatório e dentro do epoch válido; chaves extras negadas', async () => {
  const { updatedAtMs, ...semUpdated } = localizacaoValidaDoc();
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(semUpdated));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ updatedAtMs: -1 })));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ updatedAtMs: 1e14 })));
  await assertFails(db(ANA).doc(`localizacoes/${ANA}`).set(localizacaoValidaDoc({ invasor: 'hacker' })));
});

test('permissoesLocalizacao: consultas por de e por para funcionam apenas para o usuário envolvido', async () => {
  await semearAmizade(ANA, BETO);
  await semear((d) => d.doc(`permissoesLocalizacao/${ANA}_${BETO}`).set(permissaoValidaDoc(ANA, BETO)));

  // ANA consulta enviadas (de == ANA)
  const envAna = await assertSucceeds(
    db(ANA).collection('permissoesLocalizacao').where('de', '==', ANA).get()
  );
  if (envAna.size !== 1) throw new Error('esperava 1 permissão enviada');

  // BETO consulta recebidas (para == BETO)
  const recBeto = await assertSucceeds(
    db(BETO).collection('permissoesLocalizacao').where('para', '==', BETO).get()
  );
  if (recBeto.size !== 1) throw new Error('esperava 1 permissão recebida');

  // CAIO não pode consultar nem por de == ANA nem por para == BETO
  await assertFails(
    db(CAIO).collection('permissoesLocalizacao').where('de', '==', ANA).get()
  );
  await assertFails(
    db(CAIO).collection('permissoesLocalizacao').where('para', '==', BETO).get()
  );
});
