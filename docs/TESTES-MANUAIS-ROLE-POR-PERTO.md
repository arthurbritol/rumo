# Roteiro de testes manuais: Rolê por perto (etapas 1 a 5)

Para executar com **dois aparelhos, cada um com uma conta diferente** (Ana e Beto), com o app instalado de um build que tenha `NEARBY_URL` configurado (o servidor `workers/nearby` publicado; veja `docs/ROLE-POR-PERTO.md`) e o `google-services.json`. Os testes automatizados cobrem a lógica (quadrados, estado, ViewModel, mapeamento); este roteiro valida o que só o aparelho mostra: serviço em primeiro plano, notificação, marcadores no MapLibre e o desaparecimento de quem para.

Os dois aparelhos precisam estar a poucos quilômetros um do outro (ou use localização simulada nos dois).

---

## Preparação

1. Aparelho A (Ana) e aparelho B (Beto), ambos logados, com a localização permitida ao app.
2. Em um terceiro lugar (opcional): `adb logcat -s NearbyService` para ver falhas do serviço. O app nunca registra posição nem token.

## Cenário 1: o botão só aparece quando deve

- [ ] 1.1 Logada, com o servidor configurado: o mapa mostra o botão da moto na coluna da direita, abaixo do de trânsito.
- [ ] 1.2 Saia da conta (Perfil): o botão some. Entre de novo: ele volta.
- [ ] 1.3 Em um build sem `NEARBY_URL`, o botão não existe nem logada.

## Cenário 2: ligar e se ver

- [ ] 2.1 No aparelho A, toque no botão da moto. A folha "Procurando rolê" mostra três listas (Ritmo, Distância, Parada), o raio (5 km por padrão) e o botão **Procurar rolê**.
- [ ] 2.2 Escolha Moderado, Até 50 km, Café ou lanche e toque em **Procurar rolê**. Se pedir localização, permita.
- [ ] 2.3 Aparece a notificação contínua **Procurando rolê** com o botão **Parar**. O botão da moto fica destacado e a faixa no mapa diz "Procurando rolê · ninguém por perto ainda".
- [ ] 2.4 No aparelho B, faça o mesmo com **as mesmas escolhas**. Em até 30 s (um intervalo de posição) cada aparelho mostra uma moto cinza com **anel laranja claro** (combina) a poucos quilômetros, e a faixa muda para "1 pessoa por perto".
- [ ] 2.5 No aparelho B, mude para Animado e Sem parada (pare e procure de novo). No A, a moto de B passa a ser cinza **sem** anel.
- [ ] 2.6 Toque na moto de B no aparelho A: aparece o cartão com a distância arredondada ("a 1,2 km" ou "a menos de 1 km"), as três escolhas e, quando combinam, "combina com você". **Nada de nome, modelo ou foto.** A posição fica a algumas centenas de metros da real (desvio proposital).
- [ ] 2.7 Ande com B: a moto gira para o lado em que B anda (rumo) e se move a cada ~30 s (antes, se andou mais de 100 m).

## Cenário 3: raio

- [ ] 3.1 Com a procura ligada no A, abra a folha e arraste o raio para 20 km: sem mensagem de erro, a faixa continua e, se B estiver entre 5 e 20 km, B passa a aparecer.
- [ ] 3.2 Volte para 5 km: B some de A se estiver além.

## Cenário 4: segundo plano

- [ ] 4.1 No A, com a procura ligada, apague a tela por 2 minutos ou abra outro app. A notificação continua. No B, a moto de A continua no mapa (atualizada).
- [ ] 4.2 Volte ao app: a faixa continua certa.

## Cenário 5: parar e sumir

- [ ] 5.1 No A, toque em **Parar** na notificação. A notificação some, o botão da moto volta ao normal e a faixa some.
- [ ] 5.2 No B, a moto de A **desaparece** em até alguns segundos (o servidor tira quem fechou a conexão; no pior caso, até 2 minutos).
- [ ] 5.3 Repita ligando de novo e parando pela folha (**Parar de procurar rolê**): mesmo resultado.
- [ ] 5.4 Ligue e mate o app (deslizar nos recentes): a procura acaba e a moto some no outro aparelho em até 2 minutos.

## Cenário 6: falhas

- [ ] 6.1 Ligue o modo avião no A com a procura ligada: após alguns segundos a faixa vira "Rolê por perto indisponível agora" (sem piscar). Desligue o modo avião: volta a "Procurando rolê · …" sozinho.
- [ ] 6.2 Com a procura desligada e sem internet, toque em **Procurar rolê**: a tela não trava; mostra "Ligando…" e depois a mensagem de indisponível.
- [ ] 6.3 Revogue a localização do app e tente procurar: o app pede a permissão; sem ela, não liga.
- [ ] 6.4 Com A e B procurando e se vendo, ligue o modo avião no A por mais de 2 minutos (ou perca o sinal): a moto de A **some** do mapa de B em até cerca de 2 min depois do último sinal. Desligue o modo avião: A volta sozinho e reaparece no B.

## Cenário 7: encontros (etapa 2, lado do app)

Precisa do servidor com a etapa 2 publicada e dois aparelhos (A propõe, B entra), ambos procurando rolê.

- [ ] 7.1 No A, segure o dedo no mapa, dentro do raio de busca: aparece o cartão "Propor encontro neste ponto" com os números 2 a 8 (4 por padrão) e o ponto marcado no mapa. Segurar fora de qualquer quadrado da busca e propor mostra "Escolha um ponto dentro da sua área de busca".
- [ ] 7.2 Toque em **Propor encontro**: o botão mostra "Propondo…" e, em segundos, vira o cartão "Encontro em andamento" com "Você" e as vagas livres. O ponto fica no mapa como "Ponto do encontro".
- [ ] 7.3 No B (a alguns km), o ponto aparece com "1 de 4" (ocupadas de total). Toque nele: cartão com ritmo, distância, parada, "3 vagas livres" e, se combina, "combina com você". **Sem nome ou foto.**
- [ ] 7.4 No B, toque em **Entrar**: o cartão de encontro aparece com "Você" e "Motociclista 1", cada um com "X km · Y min". No A, o mesmo, e a proposta passa a "2 de 4" no mapa dos outros.
- [ ] 7.5 Ande com os dois: a moto do outro se move a cada ~10 s; longe do ponto a posição fica aproximada (algumas centenas de metros), a menos de 1 km vira a real; "X km · Y min" diminui.
- [ ] 7.6 Com B no encontro, uma terceira conta tenta entrar na última vaga ao mesmo tempo que outra: só uma entra; a outra vê "Esse encontro já está cheio".
- [ ] 7.7 No A, tente propor de novo depois de cancelar: "Espere 5 min para propor de novo" (o tempo diminui a cada tentativa).
- [ ] 7.8 No B, toque em **Sair do encontro**: aparece "Você saiu do encontro". No A, a lista de participantes perde o B. No A, **Cancelar encontro** mostra "O encontro foi cancelado" e, no B, o mesmo aviso.
- [ ] 7.9 Chegue a menos de 100 m do ponto com os dois: aparece "Chegou" ao lado de cada nome e, quando todos chegam, "Todo mundo chegou ao ponto. Bom rolê!".
- [ ] 7.10 Sem ninguém entrar, a proposta some em 30 min ("Ninguém entrou a tempo e a proposta expirou"). Desligue a internet do A no meio do encontro: o encontro não trava a tela; B continua vendo os outros e A sai dele em até 2 min.
- [ ] 7.11 Perto da divisa de quadrados (ex.: latitude -21,0): a mesma proposta aparece uma vez só, e a lista de motos não duplica quando B entra.

## Cenário 8: bloquear e denunciar (etapa 3, lado do app)

Precisa do servidor com as tarefas 15 e 16 publicadas e dois aparelhos (A e B), ambos procurando rolê.

- [ ] 8.1 No A, toque na moto do B: o cartão mostra **Bloquear** e **Denunciar**. Toque em **Bloquear**: aparece "Você não verá mais essa pessoa e ela não verá você". **Cancelar** não faz nada.
- [ ] 8.2 Confirme: a moto do B some do mapa do A na hora, e a faixa passa a contar uma pessoa a menos. Ela não volta enquanto a busca do A estiver ligada, mesmo com o B se mexendo.
- [ ] 8.3 No B, a moto do A também some (em até alguns segundos) e as propostas do A deixam de aparecer. O B não recebe nenhum aviso de que foi bloqueado.
- [ ] 8.4 Pare e ligue a busca no A: a moto do B continua invisível nos dois lados (o servidor guarda o bloqueio; o app não guarda nada).
- [ ] 8.5 Com um terceiro aparelho C, toque na moto do C e em **Denunciar**: aparece a lista Incômodo, Comportamento, Perfil falso, Outro. Escolha uma: aparece "Denúncia enviada" e a moto do C continua no mapa. Os botões ficam desabilitados enquanto envia.
- [ ] 8.6 Entre num encontro com C. Na tela do encontro, cada participante (menos você) tem **Bloquear** e **Denunciar**. Bloqueie o C: ele some da lista do encontro e sai do encontro (quem bloqueou o dono do encontro sai dele).
- [ ] 8.7 **Denunciar no fim do encontro**: termine um encontro com mais gente (chegaram, cancelado ou saiu). No cartão "Encontro encerrado" aparecem "Motociclista 1…" com **Bloquear** e **Denunciar**. Denuncie um deles: "Denúncia enviada". Vale por 24 h depois do encontro; depois disso o aviso é "Não foi possível encontrar essa pessoa. Ela pode ter saído".
- [ ] 8.8 Denunciar a mesma pessoa de novo em seguida não gasta cota nem dá erro; muitas denúncias seguidas mostram "Espere um instante…" ou "Muitas denúncias seguidas…".
- [ ] 8.9 Conta suspensa (o dono usa a rota de suspensão do servidor): ao ligar a busca, ou quando for suspensa com ela ligada, a busca desliga e aparece "Recurso indisponível por denúncias. Fale com o dono do Rumo.".
- [ ] 8.10 Sem internet, toque em **Bloquear**: a moto some, mas depois de ~15 s aparece "O servidor não respondeu. Tente de novo" e a moto volta (o bloqueio não chegou ao servidor).

## Cenário 9: virar amigo (etapa 4)

Precisa do servidor com a tarefa 19 publicada e dois aparelhos (A e B), com contas diferentes que ainda não são amigas e a busca ligada.

- [ ] 9.1 Faça A e B participarem de um mesmo encontro e termine (chegaram, saiu ou cancelado). No cartão "Encontro encerrado" cada participante mostra **Quero ser amigo**.
- [ ] 9.2 No A, toque em **Quero ser amigo** na pessoa do B: o botão vira "Marcado". Nada indica se o B já marcou. Em Amigos do A não aparece nada ainda.
- [ ] 9.3 No B, toque em **Quero ser amigo** na pessoa do A: em até alguns segundos aparece "Convite de amizade enviado" (ou "Vocês agora são amigos" se um já tivesse convidado o outro). O A recebe o convite em Amigos.
- [ ] 9.4 Aceite o convite no outro aparelho: viram amigos. Reabrir a busca não gera convite repetido.
- [ ] 9.5 Marque só de um lado e espere 24 h: a marca expira e nada acontece. Quem marcou pode marcar de novo se o outro também marcar dentro do prazo do encontro.
- [ ] 9.6 Se A bloqueou B (cenário 8) e depois tenta marcar B, ou B marca A, o app mostra o mesmo "Marcado" e nunca vira amizade; nenhum aviso revela o bloqueio.
- [ ] 9.7 Quem já é amigo, ou foi bloqueado, não recebe convite novo mesmo que as duas marcas existam.
- [ ] 9.8 Com a busca desligada no B ao marcar do A, a confirmação só chega ao ligar a busca do B (até 72 h).

## Cenário 10: suspensão automática por 3 denúncias (etapa 5)

Precisa do servidor com a tarefa 22 publicada, quatro aparelhos ou contas (A, B, C e D; D é quem será denunciado), a rota `/admin` do dono e o `ADMIN_TOKEN`.

- [ ] 10.1 Faça D participar de um mesmo encontro com A, B e C (todos entram e o encontro termina: chegaram, saiu ou cancelado). No cartão "Encontro encerrado" de cada um aparece D como "Motociclista N".
- [ ] 10.2 No A, toque em **Denunciar** em D e escolha um motivo: "Denúncia enviada". Repita no B: "Denúncia enviada". D continua procurando normalmente (2 denúncias não bastam).
- [ ] 10.3 Denuncie de novo com o A (mesma pessoa): nada muda. Só denúncias de pessoas diferentes contam.
- [ ] 10.4 No C, denuncie D: a 3ª denúncia de pessoas diferentes suspende D. No aparelho de D, a busca desliga (ou, se estiver ligada, em segundos) e aparece "Recurso indisponível por denúncias. Fale com o dono do Rumo.". D sai do mapa e do encontro dos outros.
- [ ] 10.5 D tenta ligar a busca de novo: a mesma mensagem, sem ligar.
- [ ] 10.6 Denúncias feitas só pelo mapa (sem ter estado em encontro com D) são registradas para o dono mas **não** contam para a suspensão: repita com três contas que não estiveram em encontro com D e confirme que D não é suspenso.
- [ ] 10.7 O dono lista as denúncias (`GET /admin/denuncias?uid=<uid de D>` com `Authorization: Bearer <ADMIN_TOKEN>`) e reverte com `POST /admin/suspensao?uid=<uid de D>&acao=tirar`: responde `{"uid":"...","suspenso":false}`. Em até 5 min (ou ao reconectar) D volta a poder procurar. As denúncias antigas deixam de contar: só novas denúncias podem suspender de novo.
- [ ] 10.8 Sem o token ou com token errado, as rotas `/admin` respondem 401.

## Cenário 11: proposta visível, aviso ao propor longe e ir até o ponto (pacote 1 pós-campo)

Dois aparelhos (A e B), contas diferentes, os dois procurando, a menos de 5 km um do outro.

- [ ] 11.1 O A segura no mapa perto dele e propõe: no B aparece o pino da proposta **com o rótulo "1 de N"** (ícone e texto). Se o rótulo não aparecer, anote o modelo e a versão do Android.
- [ ] 11.2 O A segura num ponto a mais de 5 km dele (raio padrão): o cartão mostra "Esse ponto fica a X km de você. Só quem estiver a até 5 km do ponto vê o encontro.", e ainda deixa propor. Num ponto a menos de 5 km, sem aviso.
- [ ] 11.3 Com o encontro em andamento, no cartão há **Ir até o ponto**: toque e a prévia de rota até o ponto abre; "Iniciar" começa a navegação e o cartão vira a pílula "Encontro · X km · Y min".
