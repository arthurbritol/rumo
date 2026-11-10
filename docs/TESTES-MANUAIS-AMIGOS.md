# Roteiro de testes manuais: amigos e rolê

Para fazer com **duas contas em dois aparelhos** (ou um celular e um emulador), com o `google-services.json` no build e as regras e o TTL publicados (veja [FIREBASE.md](FIREBASE.md#amigos-e-rolê-versão-05)). Os testes automáticos cobrem a lógica e as regras; **aqui está o que só se confirma no aparelho** (telas, Firebase de verdade, rede, virada de semana). Marque cada item e anote o que falhar, com o passo e o que apareceu.

Preparação:
- Aparelho A (conta **Ana**) e aparelho B (conta **Beto**), ambos com a release 0.5 instalada, cada um com moto cadastrada e pelo menos um abastecimento (para ter custo por km). Se possível, uma terceira conta (**Caio**) sem abastecimentos, para ver "sem dados de consumo".
- Console do Firebase aberto em Firestore Database, para conferir `busca`, `perfisPublicos`, `atividade`, `convites`, `amizades`, `bloqueios` e `roles`.
- `adb logcat -s Rumo/social Rumo/sync` aberto: erros aparecem ali.

## 0. Ordem de partida

- [ ] Visitante (sem conta): a aba **Amigos** mostra "Entre para usar amigos" e o botão Entrar; nada aparece em `busca`/`perfisPublicos` no console.
- [ ] Build sem `google-services.json` continua compilando e a aba mostra o mesmo convite para entrar.
- [ ] Logado: a aba abre a lista (vazia, com a busca).

## 1. Busca e a pergunta única

- [ ] Na primeira vez que Ana abre a aba, aparece **"Quer aparecer na busca?"** com Sim e Não. Ela escolhe **Sim**. A pergunta não volta (feche e abra o app, troque de aba).
- [ ] Console: existe `busca/{uidAna}` e `perfisPublicos/{uidAna}` (`searchable: true`) e `atividade/{uidAna}`.
- [ ] Beto responde **Não**. No console, `busca/{uidBeto}` não existe (e `perfisPublicos` fica com `searchable: false`).
- [ ] No aparelho de Ana, buscar "be" (2 letras) não pesquisa; com 3 letras ("bet") Beto **não** aparece (busca desligada).
- [ ] Beto liga **Aparecer na busca** no Perfil. Ana busca "bet": Beto aparece (nome e moto). Busca sem acento e sem diferenciar maiúscula ("BÉT" acha igual).
- [ ] Beto desliga de novo no Perfil: some da busca de Ana (pode levar alguns segundos).
- [ ] Visitante não tem esse interruptor no Perfil.

## 2. Convite, convite cruzado e aceitar

- [ ] Ana envia convite a Beto. Em A, o convite aparece em "enviados"; em B, em "recebidos", e a aba Amigos ganha o **marcador** de pendências. Console: `convites/{uidAna}_{uidBeto}`.
- [ ] Ana cancela o convite: some dos dois lados. Envie de novo.
- [ ] Beto **recusa**: some dos dois lados. Ana convida de novo.
- [ ] Beto aceita: os dois aparecem na lista de amigos um do outro, o convite some, console: `amizades/{par ordenado}`. O marcador de Beto some.
- [ ] **Convite cruzado:** com Ana e Caio sem amizade, Ana convida Caio e, antes de ele aceitar, Caio convida Ana. O app deve terminar com os dois como amigos (um aceita o convite do outro) e nenhum convite sobrando; nada de duplicado.
- [ ] Convidar quem já é amigo ou a si mesmo não cria nada.

## 3. Km da semana

- [ ] Em B, o card de Ana mostra a moto e **"X km na semana"**. Ana grava (ou tem) uma viagem finalizada; ao voltar o app para a frente (ou até ~1 min), o número em B sobe. A semana vai de **segunda a domingo**.
- [ ] Viagem finalizada só conta depois de terminar; viagem em andamento não entra.
- [ ] **Virada de semana:** perto da meia-noite de domingo para segunda (ou mudando a data do aparelho de Ana para a segunda seguinte e reabrindo o app), os km da semana voltam a 0 em A e em B (B recalcula pela data de início da semana, mesmo se Ana não publicar de novo).

## 4. Bloquear e desfazer amizade

- [ ] Ana abre o menu de Beto > **Desfazer amizade** (com confirmação): some dos dois lados e Ana não vê mais os km dele (leitura negada pelas regras). Reconvide e aceite para continuar.
- [ ] Ana > **Bloquear** Beto (com confirmação): ele some da lista dela, e console: `bloqueios/{uidAna}_{uidBeto}`. Beto não recebe aviso de bloqueio.
- [ ] Com o bloqueio ativo, Beto não aparece na busca de Ana, e um convite de Beto a Ana não aparece para ela.
- [ ] Não há tela de desbloqueio nesta versão: para seguir o roteiro, apague `bloqueios/{uidAna}_{uidBeto}` no console e refaça a amizade.

## 5. Rolê: criar, orçamento, média, alcance e avisos

Com Ana, Beto e Caio amigos entre si:
- [ ] Ana toca **Criar rolê**, dá um nome, informa o próprio orçamento (opcional) e escolhe Beto e Caio. Escolher mais de 9 amigos não é permitido (aviso). Console: `roles/{id}` com `participantes` e `expiraEm` ~7 dias à frente, e `roles/{id}/membros/*` (Ana `aceito`; os outros `convidado`).
- [ ] Beto e Caio veem o rolê como **convite pendente** (com marcador). Beto **aceita**; Caio **recusa**.
- [ ] Beto abre o rolê e informa o próprio orçamento. Em A e em B aparecem a **média** dos orçamentos e o **alcance sugerido** ("até N km", usando o custo por km de cada um).
- [ ] Quem não tem abastecimentos mostra **"sem dados de consumo"** e o aviso correspondente; o alcance não considera essa pessoa.
- [ ] Quem não informou orçamento aparece sem valor e fora da média.
- [ ] Cada pessoa só edita o **próprio** orçamento; o dos outros fica só para leitura.
- [ ] Beto altera o orçamento: Ana vê a média nova (após reabrir ou atualizar).
- [ ] Caio (que recusou) não consegue abrir o rolê.

## 6. Sair, remover, encerrar

- [ ] Beto **sai do rolê** (com confirmação): some da lista dele e dos participantes em A; a média é recalculada sem ele. Para ele voltar, crie um rolê novo.
- [ ] Num rolê novo, Ana (criadora) **remove um participante**: ele perde o rolê.
- [ ] Ana (criadora) **encerra o rolê** (com confirmação): some para todos e, no console, `roles/{id}` e `membros` desaparecem.
- [ ] A criadora não tem "sair": só encerrar.
- [ ] **Expiração:** o rolê tem `expiraEm` de 7 dias. Para conferir o TTL, mude `expiraEm` de um rolê de teste no console para uma data passada: o app o fecha ao abrir a aba (e o Firestore o apaga em até cerca de 24 h).

## 7. Apagar a conta

- [ ] Com Ana e Beto amigos, e Beto com um convite pendente de Ana e participando de um rolê de Ana, **Beto apaga a conta** (Perfil, digitando APAGAR).
- [ ] Console: somem `busca`, `perfisPublicos`, `atividade`, `convites`, `amizades`, `bloqueios` e a participação em rolês de Beto; fica só `usuarios/{uidBeto}/config/exclusao`.
- [ ] Em A, Beto **some** da lista de amigos e dos participantes do rolê (após atualizar); a média do rolê é recalculada.
- [ ] Interromper a exclusão (modo avião no meio) e reabrir o app: ela é retomada e conclui.

## 8. Sem internet

- [ ] Em A, ligue o modo avião e abra a aba Amigos: aparece o aviso de que **precisa de internet**, com o botão **Tentar de novo**, mantendo o último dado visível; nada novo é gravado no aparelho (o Room não guarda dados sociais).
- [ ] Ações sociais (convidar, criar rolê) sem internet mostram erro claro e não deixam nada pela metade.
- [ ] Religue a internet e toque em **Tentar de novo**: a lista carrega.
- [ ] Sem conta logada ou com exclusão pendente, a aba mostra só o convite para entrar.

## 9. Limites e regras (conferência rápida no console)

- [ ] Não é possível passar de **50 amigos** nem de **10 pessoas** por rolê (a interface avisa; as regras recusam o resto).
- [ ] Nenhum documento social aparece fora das coleções listadas, e um terceiro (sem amizade) não consegue ler `perfisPublicos` nem `atividade` de Ana (use o Playground de regras do console, se quiser).
