# Roteiro de testes manuais: Rolê por perto (etapa 1, ver quem procura)

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
