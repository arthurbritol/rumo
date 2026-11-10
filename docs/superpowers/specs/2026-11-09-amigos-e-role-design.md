# Amigos e rolê em grupo no Rumo — desenho

Projeto 2 de 7. O projeto 1 (conta, login, sincronização dos dados privados) já está no app (release 0.4.x). Este
projeto adiciona a parte social: amigos, km da semana e o cálculo de alcance de um rolê em grupo.
Fora deste documento: localização compartilhada (projeto 3), catálogo de conexões por marca (4), pessoas por perto
(5), preços de postos (6), Play Store (7) e a rodada de estilização/ícones (depois deste projeto).

## Por que

O dono quer que motociclistas amigos vejam o que cada um anda e consigam planejar um rolê que caiba no bolso de
todos. No domingo à noite, o grupo abre o app e vê quem rodou quanto na semana, quanto cada um pode gastar e até
onde dá para ir.

## Objetivos

1. **Aba "Amigos"** (só para quem tem conta; visitante vê um convite para entrar). Mostra: lista de amigos, convites
   recebidos, busca por nome e os rolês em grupo.
2. **Busca por nome**: qualquer pessoa com conta e com "Aparecer na busca" ligado (padrão ligado, opção no Perfil)
   pode ser encontrada. Busca por prefixo do nome, a partir de 3 letras, até 20 resultados, mostrando nome e moto.
3. **Convites**: envia convite, a outra pessoa aceita ou recusa; só depois de aceito vira amizade. Dá para cancelar um
   convite enviado, desfazer uma amizade e **bloquear** alguém (quem bloqueia some da busca do bloqueado e os
   convites do bloqueado são ignorados).
4. **Amigo vê**: nome, marca/modelo da moto e **km da semana** (segunda a domingo, fuso do aparelho de cada um).
   Os km vêm só das viagens gravadas pelo Rumo e terminadas, somadas no aparelho e publicadas quando uma viagem
   termina e na abertura do app.
5. **Rolê em grupo**: qualquer pessoa cria um rolê, escolhe amigos e dá um nome. Os convidados aceitam. Cada
   participante informa **quanto pode gastar** (R$). Todos do grupo veem o orçamento de cada um e a **média do
   rolê** (média dos orçamentos). Dessa média sai o **alcance sugerido** em km = média dos orçamentos ÷ custo por
   km médio das motos do grupo. Quem tem orçamento abaixo do que o alcance sugerido exige (km × seu custo por km)
   aparece com aviso "passa do que você pode gastar". O alcance sugerido é a referência; ninguém é impedido de
   participar.
6. **Custo por km** de cada participante: calculado no aparelho a partir da garagem (consumo km/l e preço do último
   abastecimento, as mesmas contas que o app já faz em `FuelMath`). Quem não tem dados de consumo entra no grupo
   como "sem dados de consumo", usa o custo médio do grupo para o aviso e a tela mostra isso claramente.
7. **Apagar a conta** passa a apagar também tudo o que é social: perfil público, atividade, convites, amizades,
   bloqueios e as participações em rolês (rolês criados pela pessoa são encerrados).
8. **Sem internet**: a aba mostra o último dado carregado nesta sessão e avisa que precisa de internet; nada disso
   entra no banco local (Room) nem no motor de sincronização do projeto 1.

## Fora do escopo

Localização de amigos, sugestão de pessoas por perto, chat, notificações push (sem servidor; o aviso de convite
aparece na aba ao abrir o app), ranking/placar entre amigos, fotos de perfil, preços reais de postos, denúncia com
moderação (entra no projeto 7), várias motos por conta, rolê com rota ou data marcada.

## Anti-objetivos (seria fracasso mesmo funcionando)

- Alguém que não é amigo ver km da semana, orçamento ou qualquer dado privado (garagem, hodômetro, abastecimentos,
  trajetos).
- Dados da conta voltarem a existir depois de "apagar minha conta", ou restarem convites/amizades com um usuário
  apagado.
- Gastar a cota gratuita do Firestore com leituras desnecessárias (nada de escutar coleções inteiras).
- O app piorar para quem nunca usa a aba Amigos (abertura mais lenta, permissões novas).

## Restrições

- **Firebase plano Spark**: sem Cloud Functions e sem Cloud Messaging com servidor. **Toda a segurança precisa estar
  nas regras do Firestore** (`firebase/firestore.rules`), com testes no emulador (como já existe). Atomicidade
  (aceitar convite = criar a amizade) por lote de escrita verificado com `getAfter()` nas regras.
- Consultas baratas: busca por prefixo com índice, listeners só no que é do usuário (convites recebidos, amizades,
  rolês em que participa); nada de ler todos os perfis.
- Reaproveitar o que existe: `AuthRepository`/`AccountGraph`, `FirestoreRemoteStore` (apagar conta em
  `deleteAllUserData` deve incluir o social), `FuelMath`, tema e componentes de `Components.kt`. A tela segue o estilo
  atual (a estilização nova vem depois).
- Kotlin 2.2.20, AGP 8.13.2, Compose BOM 2025.10.00, sem trocar versões. Textos em português do Brasil.
- Sem localização em segundo plano, sem permissões novas.
- Privacidade: o perfil público guarda só nome, nome em minúsculas, marca, modelo, ano (opcional) e a opção de
  busca; a atividade (km da semana) é um documento separado, legível só por amigos; orçamento e custo por km só
  dentro do rolê, legíveis só pelos participantes.

## Decisões do dono (como ele decidiu)

- Achar pessoas **pesquisando pelo nome**; sugestão de pessoas por perto só no projeto 5.
- Amigos veem o modelo da moto e quanto rodou na semana.
- Todos do grupo veem o orçamento de cada um e depois a média do rolê; **a média define o alcance sugerido**.
- Os km da semana contam as viagens gravadas pelo Rumo (decisão aceita pelo dono em conversa).

## Minhas decisões (o dono pode trocar)

- Estrutura no Firestore (nomes em português, como o resto): `busca/{uid}` (nome, nome em minúsculas, marca,
  modelo; legível por qualquer conta; existe só enquanto "Aparecer na busca" estiver ligado), `perfisPublicos/{uid}`
  (os mesmos dados mais ano e a opção de busca; legível só pelo dono, amigos e participantes de um rolê em comum),
  `atividade/{uid}` (`weekStartMs`, `weekKm`; legível só por amigos), `convites/{deUid_paraUid}`,
  `amizades/{uidA_uidB}` (ids em ordem alfabética), `bloqueios/{quemBloqueia_bloqueado}`, `roles/{id}` (com
  `participantes: [uid]`, `criadorUid`, `expiraEm`) e `roles/{id}/membros/{uid}` (`estado`, `orcamento`,
  `custoPorKm`, `atualizadoEm`).
- Limites: até 50 amigos, até 10 pessoas por rolê, rolê some depois de 7 dias sem atividade ou quando o criador
  encerra. Busca: 3 letras, 20 resultados.
- Semana: segunda 00:00 a domingo 23:59 no fuso do aparelho; o app regrava `weekKm` quando a semana muda.
- Aviso de convite: um marcador na aba (contagem de convites e rolês pendentes) ao abrir o app e ao voltar à frente.
- Quem criou o rolê define o nome e pode remover participantes e encerrar; cada participante só edita o próprio
  orçamento.
- A aba entra na barra inferior como quinta aba "Amigos" (ícone básico por enquanto).

## Decisões fechadas depois da revisão do desenho (minhas; o dono pode trocar)

- **Busca e privacidade:** desligar "Aparecer na busca" apaga o documento `busca/{uid}` (a pessoa some da busca de
  todos), mas os amigos continuam vendo o perfil completo. As regras não escondem dados por usuário, então quem
  foi bloqueado ainda consegue ler `busca/{uid}` de quem bloqueou, se a busca estiver ligada: são só nome e moto,
  que já são públicos nesse caso.
- **Quem já usa o app (0.4.x) não vira pesquisável sozinho:** na primeira vez que abre a aba Amigos aparece uma
  pergunta única "Quer aparecer na busca por nome?" (Sim/Não); para contas novas o padrão é Sim, mas a pergunta
  também aparece. O perfil público é criado/atualizado ao abrir a aba, ao editar o perfil e ao terminar uma viagem.
  `usuarios/{uid}` (projeto 1) não muda.
- **Convites:** a regra permite que o remetente crie `convites/{deUid_paraUid}` e que só o destinatário o aceite,
  num lote que cria a amizade e apaga o convite (checado com `getAfter()`). Recusar ou cancelar apaga o convite
  (pode convidar de novo; quem incomoda é bloqueado). Se A convidou B e B tenta convidar A, o app aceita o convite
  de A em vez de criar outro. As regras **não** consultam bloqueios (para não revelar o bloqueio): convites de
  bloqueados são aceitos pelas regras, o app os esconde e os apaga ao carregar. Bloquear um amigo apaga a amizade
  (qualquer um dos dois membros pode apagar `amizades/{par}`) e os convites entre os dois.
- **Rolê:** só o criador convida (amigos dele). Convidados têm `membros/{uid}` com `estado = convidado`; veem só o
  nome do rolê e quem é o criador. Depois de aceitar (`estado = aceito`) veem os orçamentos dos outros aceitos (a
  regra confere o próprio documento de membro). O app escuta `roles` com `participantes array-contains uid`.
  Qualquer participante pode sair (apaga o próprio `membros/{uid}` e se tira do array, em lote); se o criador sai
  ou apaga a conta, o rolê é encerrado (o cliente do criador apaga o rolê e os `membros`). Expiração: política de
  TTL do Firestore no campo `expiraEm` (7 dias após a última atividade, documentar o passo no console) mais um
  filtro no app para não mostrar rolês expirados.
- **km da semana:** conta a viagem pela data de início; vale para viagens do Room já terminadas (inclui as
  restauradas da nuvem); qualquer aparelho da conta pode publicar (o valor é o mesmo). Quem lê só mostra `weekKm`
  se `weekStartMs` for da semana atual; senão mostra 0 km.
- **Cálculo do rolê:** o custo por km médio é a média simples só dos participantes aceitos com dados de consumo;
  orçamento ainda não informado = fora da média; zero é um valor válido e entra; convidados pendentes não entram;
  se ninguém tem dados de consumo, não se calcula alcance e a tela explica. O `custoPorKm` de cada um é publicado
  no próprio `membros/{uid}` ao entrar no rolê, ao abrir o app e ao salvar um abastecimento.
- **Apagar conta (social):** a pessoa apaga os convites enviados e recebidos, as amizades de que faz parte, os
  próprios bloqueios, a participação em rolês e os rolês que criou (com todos os `membros`); entra no mesmo fluxo
  retomável de `deleteAllUserData`. Um bloqueio que outra pessoa colocou nela fica (guarda só um uid sem dado
  pessoal; aceito como exceção ao anti-objetivo).

## Deixado para quem for construir

Estrutura exata dos documentos e índices, regras de segurança e a forma de verificar a atomicidade, organização dos
módulos (a lógica de alcance/média/aviso e de semana deve ser Kotlin puro em `sync-core`, testada na JVM), telas
(Compose), estados de carregamento/erro, textos finais, política de repetição, e o plano de testes (inclui testes de
regras para cada caminho: estranho, amigo, bloqueado, visitante).

## Como saber que está pronto

- Testes: cálculo do alcance e do aviso (média, membros sem dados, orçamento zero, um só membro), semana segunda–
  domingo com virada de semana e de fuso, regras do Firestore para todos os caminhos novos incluindo acesso negado a
  não amigos e a bloqueados, apagar conta removendo todo o social, e busca limitada.
- Roteiro manual para o dono com duas contas em dois aparelhos: buscar, convidar, aceitar, ver km, bloquear,
  criar rolê, informar orçamentos, ver a média e os avisos, sair do grupo, apagar conta.
