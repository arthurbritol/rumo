# Roteiro de testes manuais: conta e sincronização

Para fazer num celular de verdade, com o `google-services.json` no build (veja [FIREBASE.md](FIREBASE.md)). Os
testes automáticos cobrem a lógica; **tudo aqui é o que só se confirma no aparelho** (telas, Firebase de verdade,
rede, migração do banco). Marque cada item. Anote o que falhar com o passo e o que apareceu.

Preparação:
- Dois celulares (ou um celular e um emulador) para os itens de "dois aparelhos".
- Console do Firebase aberto em Authentication e Firestore, para conferir o que sobe.
- `adb logcat -s Rumo/sync` aberto: erros de sincronização aparecem ali.
- Contas de teste: uma conta Google e um e-mail/senha que você receba.

Convenções: "nuvem" = Firestore; "visitante" = sem conta.

## 1. Instalar do zero (instalação nova)

- [ ] Desinstale o Rumo. Instale o APK novo. Abra: aparecem as 4 telas de apresentação, com "Pular" em cada uma.
- [ ] Ao terminar (ou pular) a apresentação vem o login. Há "Continuar com Google", e-mail/senha, "Esqueci a senha" e "Pular".
- [ ] Depois do login/pular, as permissões vêm uma de cada vez, cada uma com "Agora não". Nenhuma pede localização "o tempo todo" (segundo plano).
- [ ] Por fim, o cadastro da moto (completo com conta, curto sem conta). Dá para "Preencher depois".
- [ ] Feche e abra o app: a apresentação **não** volta; vai direto ao mapa.
- [ ] Garagem > ícone de perfil > "Rever apresentação" abre só as 4 telas de apresentação, e ao terminar volta ao perfil. Vale para visitante e para logado: nunca mostra login, permissões nem cadastro da moto.

## 2. Login com Google

- [ ] Instalação nova, "Continuar com Google": abre a folha de contas, escolha uma conta. O app segue (permissões, moto).
- [ ] No console, Authentication mostra o usuário e Firestore tem `usuarios/{uid}` com o perfil.
- [ ] Cancelar a folha de contas não mostra erro nem trava a tela.
- [ ] Sem internet, o Google mostra mensagem clara e "Tentar de novo" funciona depois de ligar a internet.

## 3. Login com e-mail e confirmação

- [ ] "Criar conta" com nome, e-mail e senha (6+ caracteres): chega o e-mail de confirmação; a tela mostra o aviso e "Continuar".
- [ ] No perfil, enquanto o e-mail não foi confirmado, aparece o aviso e o botão "Reenviar e-mail de confirmação" (testar o reenvio).
- [ ] Depois de clicar no link do e-mail, reabra o app: o aviso some (pode ser preciso sair e entrar uma vez).
- [ ] "Esqueci a senha": chega o e-mail de redefinição e a nova senha funciona.
- [ ] Senha errada mostra "E-mail ou senha incorretos".
- [ ] Entrar com Google usando um e-mail que já tem conta de e-mail/senha pede a senha e vincula (uma conta só).

## 4. Usar como visitante e depois criar a conta

- [ ] Instalação nova, "Pular" no login. Cadastre km e tanque (curto), registre 2 abastecimentos, 1 item de manutenção, favorite um destino, grave/termine uma viagem curta.
- [ ] Perfil como visitante: mostra "sem conta, desinstalar apaga tudo" e o botão "Entrar". Não há "Sair" nem "Apagar conta".
- [ ] "Entrar" (perfil) e crie/entre numa conta: **todos os dados continuam** na tela, sem duplicados (abastecimentos, manutenção, favoritos, viagem).
- [ ] No console, os documentos estão em `usuarios/{uid}` (abastecimentos, manutenção, locais, viagens, config/hodometro).
- [ ] Repita entrando numa conta que **já tinha** dados na nuvem: os dados dos dois lados aparecem juntos, sem duplicar itens iguais (ex.: os mesmos itens de manutenção padrão).
- [ ] Hodômetro do visitante x o da conta no primeiro login: o mais recentemente alterado vence (último a escrever), e as viagens que vieram do outro lado e já tinham sido rodadas antes da leitura do painel **não** são somadas de novo. Ex.: reinstalar, "Pular", cadastro curto com o km do painel, rodar nada e então entrar na conta com histórico: a Garagem mostra exatamente o km digitado (não painel + histórico).

## 5. Desinstalar, reinstalar e recuperar

- [ ] Com a conta cheia (moto, abastecimentos, manutenção, favoritos, ao menos 2 viagens, voz desligada), espere sincronizar (perfil não mostra "ainda sincronizando" ao tentar sair) e desinstale.
- [ ] Reinstale e entre com a mesma conta: moto, hodômetro, abastecimentos, manutenção, favoritos e a preferência de voz voltam. Como a conta já tem moto, o cadastro da moto é pulado.
- [ ] As viagens voltam no histórico com estatísticas, **sem o mapa do trajeto** (esperado).
- [ ] O hodômetro mostrado na Garagem é **igual ao km que você tinha digitado** (as viagens restauradas entram na soma sem dobrar o valor). Compare antes e depois.
- [ ] A voz da navegação reflete a preferência da conta logo após o login (sem precisar reabrir o app).

## 6. Modo avião (offline)

- [ ] Logado e sincronizado, ligue o modo avião. Registre um abastecimento, edite um item de manutenção, ajuste o hodômetro, favorite um destino, termine uma viagem. Tudo funciona normalmente, sem travar.
- [ ] No perfil, "Sair" fica desabilitado com "Ainda sincronizando, tente de novo com internet."
- [ ] Desligue o modo avião: em até ~1 minuto as alterações aparecem no console e o perfil volta a permitir "Sair".
- [ ] Mate o app com alterações pendentes e abra de novo ainda offline: nada se perde; ao ter internet, sobe.
- [ ] Repita ligando a internet **durante** uma sincronização em andamento (ligar/desligar rápido): no fim tudo sobe.

## 7. Sair da conta

- [ ] Sem pendências: Perfil > "Sair". A confirmação tem **"Cancelar" como botão principal** e avisa que os trajetos completos (só neste celular) serão perdidos. Confirmando, o app volta a visitante e os dados do aparelho são limpos (garagem vazia).
- [ ] Os dados continuam na nuvem (console) e voltam ao entrar de novo.
- [ ] Com pendência (modo avião + um abastecimento novo): "Sair" está desabilitado e nada é apagado do aparelho.
- [ ] Uma viagem **em gravação** durante o "Sair" não quebra a gravação.

## 8. Apagar a conta

- [ ] Conta de e-mail: Perfil > "Apagar meus dados e minha conta": a confirmação pede digitar **APAGAR** (outras grafias não habilitam) e a senha. Senha errada não apaga nada.
- [ ] Com a senha certa: aparece o progresso; no console somem o perfil `usuarios/{uid}`, todas as subcoleções de dados e o hodômetro (fica só `config/exclusao` com `pending: true`) e o usuário em Authentication. O app vira visitante e os dados **locais** continuam, como no uso sem conta.
- [ ] Conta Google: mesma confirmação, sem campo de senha; o app pede a conta Google de novo (reautenticação) e então apaga.
- [ ] **Exclusão interrompida:** inicie a exclusão e ligue o modo avião logo em seguida (ou feche o app durante o progresso). O perfil mostra "Concluindo a exclusão da conta" e não permite sair/apagar. Ao reabrir o app com internet, ela conclui sozinha (ou use "Tentar concluir agora"). No console, a conta e os dados somem; só fica `usuarios/{uid}/config/exclusao` com `pending: true` (permanente, de propósito).
- [ ] Conta Google, na reautenticação escolha **outra** conta Google da folha: aparece um erro, **nada** é apagado (nem da conta logada, nem da outra) e o app continua logado na mesma conta.
- [ ] Entrar de novo na mesma conta depois de uma exclusão interrompida não ressuscita os dados da nuvem.

## 9. Atualizar por cima da versão atual (migração do banco v2 para v3)

Esse é o teste mais importante: usuários atuais **não podem perder dados**.
- [ ] Instale a versão **0.3.x** (a release anterior). Crie dados: moto/hodômetro, 3 abastecimentos, itens de manutenção, 2 favoritos e 2 viagens (uma com mapa).
- [ ] Instale o APK novo **por cima** (`adb install -r`, sem desinstalar). Abra: **não** aparece apresentação nem login; o app vai direto ao mapa com um aviso discreto "Crie uma conta para proteger seus dados" (que pode ser dispensado com "Agora não").
- [ ] Todos os dados anteriores estão lá: hodômetro, abastecimentos, manutenção, favoritos, viagens com mapa e estatísticas.
- [ ] Toque em "Criar conta" no aviso (ou no perfil), entre: os dados antigos sobem para a nuvem sem duplicar.
- [ ] Dispensar o aviso com "Agora não" e reabrir o app: ele não volta.

## 10. Dois aparelhos, mesma conta

- [ ] Entre com a mesma conta nos dois. Os dados aparecem nos dois.
- [ ] Offline nos dois, edite **o mesmo abastecimento** com valores diferentes (A e depois B, B mais recente). Ligue a internet nos dois: os dois acabam com o valor do que foi editado por último; um único abastecimento, sem duplicata.
- [ ] Crie um abastecimento no A: aparece no B (pode levar até um sincronismo: reabra o app ou mude de aba/volte a internet).
- [ ] Apague um item no A: some no B depois de sincronizar, e não ressuscita.
- [ ] Apague a conta no A: no B (ainda logado, com algo pendente), o próximo sync não recria dados na nuvem (console: só `config/exclusao`), e o B sai da conta sozinho e segue como visitante com os dados locais.

## 11. Permissões negadas

- [ ] Instalação nova: negue localização em "Agora não" e siga. O app abre; o mapa fica sem sua posição e **não há loop de pedidos**; é possível conceder depois pelas configurações do app.
- [ ] Negue a localização com "Não permitir" duas vezes (negação definitiva): aparece o botão que abre as configurações do app; ao voltar com a permissão dada, o app avança.
- [ ] Negue Bluetooth e notificações: o app segue; o Pod só deixa de conectar e os lembretes não notificam, sem erro.
- [ ] Nenhuma tela pede localização em segundo plano.

## 12. Rotação e ciclo de vida durante o login

- [ ] Na tela de login, digite e-mail e nome, gire o celular: texto preservado (a **senha** pode ser pedida de novo, por segurança).
- [ ] Gire durante "Aguarde…" de um login por e-mail: o login termina e o app avança.
- [ ] Gire com a folha de contas do Google aberta: o login Google conclui ou pode ser repetido, sem travar a tela nem fechar o app.
- [ ] Gire no cadastro da moto e na tela de permissões: não volta ao começo da apresentação.
- [ ] Mate o app durante a apresentação (instalação nova), inclusive depois de entrar numa conta (quando já há dados sincronizados) ou depois do cadastro da moto, e reabra: volta à apresentação (no passo em que parou ou no começo), sem cair direto no mapa e sem tratar a instalação como antiga (sem o aviso "Crie uma conta para proteger seus dados"). Ao terminar a apresentação, reabrir o app vai direto ao mapa.

## 13. Gravação em segundo plano sem conta (e com conta)

- [ ] Como visitante, inicie uma viagem e bloqueie a tela / abra outro app por alguns minutos: a viagem continua gravando e aparece completa no histórico (com mapa).
- [ ] Logado, repita: a viagem grava normal e, ao terminar, sobe o resumo para a nuvem sem afetar a gravação.
- [ ] Sair da conta com uma viagem em gravação não a interrompe.

## 14. Tripper Pod continua funcionando

- [ ] Conectar ao Pod, ver a bússola e iniciar uma navegação: manobras e distância aparecem no Pod, como antes.
- [ ] Com e sem conta, com e sem internet, o Pod segue funcionando; entrar/sair da conta não desconecta o Pod.

- [ ] O serviço de gravação de viagens roda no mesmo processo do app: `adb shell ps | grep companion` mostra um único `app.hunter.companion` (o disparo de sincronização ao terminar a viagem depende disso).

## 15. Sem Firebase (build sem `google-services.json`)

- [ ] Gere o APK sem o arquivo e instale: o login mostra "Login indisponível nesta versão", "Pular" funciona e todo o resto do app funciona como na versão 0.3. Nada sincroniza e nada trava. O perfil do visitante mostra o botão "Entrar" desabilitado.

## O que cada item cobre (apenas leitura no desenvolvimento)

Estes pontos foram verificados só por leitura do código e compilação, então dependem deste roteiro: telas de
perfil/login/apresentação, o roteamento da primeira abertura, o login Google (Credential Manager) e a
reautenticação, o observador que dispara sincronização, a migração real do Room v2 para v3 e a retomada da
exclusão após interrupção.
