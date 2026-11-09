# Conta e entrada no Rumo — desenho

Projeto 1 de 7 da evolução do Rumo (app Android para motos; Kotlin, Compose, Room, sem backend até aqui).
Os outros seis (amigos e rolê em grupo, localização compartilhada, catálogo de conexões por marca, rolê com quem
está por perto, preços de postos, preparação para a Play Store) ficam **fora** deste documento e serão desenhados
depois, um de cada vez. Este projeto é a base deles.

## Por que

O Rumo hoje não tem conta: tudo vive no celular e some na desinstalação. O dono quer publicar na Play Store
quando estiver testado e sem bugs, com amigos, rolê em grupo e conexão com mais painéis. Nada disso existe sem
identidade de usuário e sem dados na nuvem. Este projeto entrega: a primeira experiência no app (apresentação,
login, permissões), a conta (Firebase), o cadastro da moto e a sincronização dos dados do usuário, de modo que
quem reinstala e entra de novo recupera o que tinha.

## Objetivos

1. **Primeira abertura:** quatro telas de apresentação (navegação; viagens e garagem; rolê com amigos; conexão
   com o painel), depois login, depois pedido de todas as permissões, depois cadastro da moto. Cada tela de
   apresentação tem "Pular". Só aparece na primeira vez; pode ser revista num menu de ajuda.
2. **Login opcional, mas recomendado.** Métodos: Google e e-mail com senha (com "esqueci a senha" e confirmação
   de e-mail). "Pular" leva ao mapa; dá para entrar depois, pelo perfil ou por um aviso discreto. Sem conta, o app
   inteiro funciona (mapa, navegação, viagens, garagem, Pod); só ficam de fora recursos que dependem de outras
   pessoas (projetos 2, 3 e 5) e o backup.
3. **Cadastro da moto logo depois do login** (opção A escolhida): marca e modelo, km atual (hodômetro), ano
   (opcional) e o que o cálculo de combustível precisa. Quem pulou o login faz uma versão curta, só com o que o
   combustível exige, e pode completar depois no perfil.
4. **Dados na conta (Firebase), sincronizados:** perfil e moto, hodômetro, abastecimentos, itens de manutenção,
   locais favoritos e recentes, preferências e o **resumo** de cada viagem (data, distância, tempo, tempo parado,
   velocidade média e máxima, altimetria, e o nome/destino se houver). Quem reinstala e entra de novo recupera tudo
   isso; as viagens voltam como resumo, sem o desenho do trajeto, e o app diz isso.
5. **Trajeto completo só no celular** (decisão do dono): os pontos de GPS de cada viagem nunca vão para a nuvem.
   Ficam guardados de forma compacta e só são carregados ao abrir a viagem, para não pesar no app.
6. **Funciona sem internet.** O celular é a fonte primária; a sincronização acontece quando houver conexão.
7. **Conta apagável** dentro do app (exigência da Play): apaga a conta e todos os dados dela na nuvem.
8. **Quem usou sem conta e depois cria uma** mantém o que já tinha: os dados locais sobem para a conta nova.
9. **Aviso claro, sem conta:** o app deixa visível (no perfil e na primeira abertura) que, sem login, desinstalar
   apaga tudo.

## Fora do escopo (agora)

Amigos, busca por nome, ranking semanal, rolê em grupo, compartilhar localização, descoberta de pessoas por
perto, catálogo de conexões por marca (aqui só se guarda marca e modelo escolhidos), preços de postos, iOS,
telas de Play Store, política de privacidade publicada, servidores próprios de rota/busca, atualizador (já existe;
só a variante da loja o remove, no projeto 7). Também não entra login por telefone, Facebook ou outros.

## Anti-objetivos (seria fracasso mesmo funcionando)

- Perder ou duplicar dados do usuário na sincronização, ou apagar dados locais sem confirmação explícita.
- Obrigar login para usar mapa e navegação.
- Qualquer dado de outra pessoa ficar legível por quem não é o dono (as regras do Firestore são parte da entrega).
- O app ficar mais lento ou travar na abertura por causa da sincronização ou das telas novas.
- Cobrar dinheiro: tudo deve caber no plano gratuito do Firebase.

## Restrições

- **Firebase, plano gratuito (Spark):** apenas Authentication e Firestore. Sem Cloud Storage e sem Cloud
  Functions (exigem plano pago). Qualquer lógica de servidor (por exemplo apagar a conta) roda no app.
- **Projeto existente:** Kotlin 2.2.20, AGP 8.13.2, Gradle 8.14.3, Compose BOM 2025.10.00, Room 2.8.5, compileSdk
  36, minSdk 26. A migração para AGP 9/Kotlin 2.4 foi adiada de propósito: as bibliotecas do Firebase escolhidas
  precisam compilar nesta versão (conferir a compatibilidade antes de fixar versões).
- **Room hoje usa ids numéricos automáticos** (banco v2, `hunter.db`; entidades de viagem, abastecimento,
  manutenção, hodômetro e locais). Para sincronizar é preciso um identificador global estável por registro e uma
  migração de banco que não perca os dados dos usuários atuais.
- **Assinatura fixa já existe** (chave do repositório, alias `rumo`): o SHA-1/SHA-256 dela precisa ser cadastrado
  no projeto Firebase para o login Google funcionar nos APKs do GitHub. A chave de upload/assinatura da Play
  entra só no projeto 7.
- O CI (`release.yml`) precisa continuar compilando e publicando sem segredos do desenvolvedor local;
  `google-services.json` não é segredo e pode ir no repositório (a chave de API deve ser restrita ao app).
- O pacote ainda é `app.hunter.companion`; **não trocar agora** (trocar quebra atualizações). A decisão de
  renomear antes da primeira publicação é do projeto 7.
- Texto da interface em português do Brasil.
- Privacidade: os dados do usuário na nuvem são só dele. Sem analytics e sem anúncios.

## Decisões do dono (como ele decidiu)

- Backend: Firebase.
- Fluxo: apresentação → login → permissões → cadastro da moto.
- Login opcional; recomendado para não perder dados; sem ele, tudo funciona menos o que depende de amigos.
- Login por Google e por e-mail com senha.
- Dados na conta: "tudo", exceto o trajeto completo, que fica só no celular.
- Moto sem conexão suportada: o usuário informa os dados na mão e vai atualizando.
- Sair da conta tira os dados dela do celular, mas **nunca apaga a nuvem**: tudo continua salvo na conta até o
  usuário pedir "apagar meus dados e minha conta". O aviso antes de sair diz que os trajetos completos, que só
  existem no aparelho, serão perdidos. Quem nunca teve conta continua sem nuvem: desinstalar apaga tudo.
- Publicação futura na Play Store, e o app não pode ter bug nas funções descritas.

## Minhas decisões (o dono pode trocar)

- **Sincronização:** o Room continua sendo a base local. Um módulo de sincronização grava no Firestore e lê de lá,
  com id global por registro, `atualizadoEm` e marca de exclusão; em conflito vence o mais recente. Primeiro login
  em um aparelho: baixa o que existe na conta, une com o que há local por id e sobe o que só existe local.
- **Estrutura no Firestore:** `usuarios/{uid}` (perfil e moto) com subcoleções para abastecimentos, manutenção,
  hodômetro, locais, viagens (resumos) e preferências. Regras: só o dono lê e escreve o próprio caminho, com
  validação de formato dos campos e limite de tamanho.
- **Nome de exibição obrigatório** no perfil (vem do Google ou é pedido no cadastro por e-mail), guardado também
  em minúsculas para a busca por nome do projeto 2.
- **Catálogo de motos:** lista fixa dentro do app (Royal Enfield, Yamaha, Honda, Kawasaki, Triumph, BMW, KTM, TVS,
  Bajaj, Harley-Davidson e "Outra", com os modelos mais comuns de cada uma), sem servidor; "Outra" aceita texto
  livre. Quando o projeto 4 existir, ele marca quais têm conexão.
- **Sair da conta** (decisão do dono, acima): o botão principal da confirmação é "Cancelar", como padrão seguro.
  Antes de limpar o celular, o app confirma que tudo o que é sincronizável já subiu (se houver pendência sem
  internet, avisa e não limpa). Entrar de novo, no mesmo ou em outro aparelho, traz tudo de volta da nuvem,
  menos os trajetos completos.
- **Apagar a conta** pede nova autenticação, apaga todas as subcoleções no app (sem Cloud Functions), depois a
  conta de autenticação; os dados locais permanecem como uso sem conta.
- **Primeira abertura detectada** por uma preferência local; reinstalar mostra a apresentação de novo, mas quem
  entra com uma conta que já tem moto cadastrada pula o cadastro.
- **Permissões:** pedidas em sequência na tela 6 (localização, Bluetooth e notificações), com "Agora não". Cada
  recurso pede de novo, com explicação, quando for usar sem a permissão. **Não se pede localização em segundo
  plano:** hoje o app não a usa (a gravação e o Pod rodam em serviço em primeiro plano, iniciado com o app aberto),
  e pedi-la só traria atrito e risco na Play. Se um projeto futuro precisar, ele pede.
- **Uma moto só:** o app hoje tem um hodômetro único (linha fixa, com a diferença em km e a capacidade do tanque).
  Este projeto mantém uma moto por conta; várias motos ficam para depois. O hodômetro é um documento único, em que
  vence a última escrita; abastecimentos são registros individuais com o próprio km.
- **Mesclar sem duplicar (conta nova em aparelho com dados locais):** une os dois lados e remove duplicatas pelo
  conteúdo: abastecimento (data, km, litros, valor), manutenção (nome e intervalos), local (coordenadas e nome) e
  viagem (início e distância). Sem tela de escolha.
- **Preferências:** só `voz` sincroniza. `bottomMode`, o aparelho do Pod (`mac`), os marcadores de atualização e os
  de sugestões iniciais são do aparelho e não sobem.
- **Recentes e escritas:** favoritos sincronizam; recentes ficam só no aparelho (limite de 20). Escritas em lote
  com atraso de alguns segundos, e o resumo da viagem sobe uma vez, ao terminar.
- **E-mail com senha:** envia o e-mail de confirmação; a conta funciona e sincroniza sem confirmar. Recursos de
  amigos (projeto 2) exigirão e-mail confirmado. Google já vem confirmado.
- **Mesmo e-mail em Google e senha:** as contas são vinculadas (pede a senha uma vez).
- **Apagar conta, falha no meio:** grava primeiro uma marca de "exclusão pendente" na conta; se o app fechar no
  meio, a próxima abertura continua a exclusão até terminar. A nova autenticação usa o método da conta (senha ou
  Google).

## Deixado para quem for construir

Versões exatas das bibliotecas do Firebase e do login Google (Credential Manager), organização dos módulos e do
código de sincronização, formato interno do arquivo de trajetos locais, detalhes visuais das telas (o fluxo e o
conteúdo estão nos mockups aprovados em `.superpowers/brainstorm/`), textos finais, política de repetição e
tratamento de erro de rede, e o plano de testes.

## Como saber que está pronto

- Testes automáticos: lógica de mesclagem da sincronização (JVM), migração do Room com dados reais de versões
  anteriores, e as regras do Firestore contra o emulador (dono acessa; outro usuário e visitante não).
- Teste manual do dono em aparelho real, com uma lista de conferência entregue junto: instalar do zero; entrar com
  Google e com e-mail; usar sem conta e depois criar conta; desinstalar e reinstalar e recuperar; modo avião;
  sair da conta; apagar a conta; atualizar sobre a versão instalada hoje sem perder nada.
- O dono precisa criar o projeto Firebase e fornecer o `google-services.json`; quem construir deve entregar o
  passo a passo exato (ativar Google e e-mail/senha, Firestore, regras, SHA-1/SHA-256).
