# Roteiro de testes manuais: navegação enxuta

Para fazer num aparelho, com GPS, saindo de casa (ou com uma localização simulada). A lógica de visibilidade é testada em JVM (`NavLayoutTest`); **aqui está o que só se confirma na tela**: tamanhos, sobreposições, áreas seguras e a rotação. Marque cada item e anote o que falhar, com a orientação e o que apareceu.

Preparação: Rumo instalado, localização permitida; se possível, conta logada com o botão do rolê visível, trânsito e postos ligados (para ver que tudo some na navegação).

## 1. Retrato (celular em pé)

- [ ] Antes de navegar: busca "Para onde?", botões de postos, trânsito e rolê, aba inferior e painel "Pronto para rodar" aparecem como sempre.
- [ ] Escolha um destino e toque **Iniciar navegação**. A barra de abas, a busca, as faixas, os botões de postos/trânsito/rolê e o painel "Pronto para rodar" **somem**.
- [ ] No topo há **só** um banner baixo: ícone da manobra, distância ("350 m") grande e o nome da via/instrução (até 2 linhas). Nada de linha "Depois".
- [ ] Embaixo há **só** uma barra fina: velocidade (km/h) grande, horário de chegada, "N min · X km", botão **Voz/Mudo** e **Encerrar**.
- [ ] O mapa ocupa o meio da tela; a moto e a rota à frente ficam visíveis (não escondidas por banner ou barra); a barra não invade a barra de gestos do sistema.
- [ ] Mexa no mapa com o dedo: aparece só o botão redondo **Centralizar**, acima da barra inferior; tocar nele volta a seguir a moto e ele some.
- [ ] Desvie da rota: o banner vira "Recalculando rota…" e depois volta à manobra.
- [ ] **Voz/Mudo** liga e desliga o aviso falado. **Encerrar** pede confirmação ("Encerrar navegação?"); **Continuar** mantém tudo; **Encerrar** volta à tela normal (abas, busca e botões voltam sozinhos).
- [ ] Com um encontro do rolê ativo: durante a navegação só aparece a pílula "Encontro · X km · Y min" acima da barra (nenhum cartão).

## 2. Paisagem (celular deitado)

- [ ] Durante a navegação, **uma coluna estreita à esquerda** (cerca de um quarto da largura) com: ícone e distância da manobra, nome da via, velocidade, horário de chegada e "N min · X km", **Voz/Mudo** e **Encerrar**. Nada de faixa larga cobrindo a altura.
- [ ] O mapa ocupa o resto da largura e **toda a altura**; a moto aparece centrada na parte do mapa que sobrou (não escondida atrás da coluna).
- [ ] Em aparelhos com recorte da câmera (notch) ou barra de navegação lateral, a coluna e os botões não ficam sob eles.
- [ ] Todo o texto da coluna cabe (ou rola) sem cortar o botão **Encerrar**, mesmo com a barra de status.
- [ ] Mexa no mapa: surge o botão **Centralizar** no canto inferior direito; some ao tocar.
- [ ] Com encontro ativo: a pílula do encontro fica no canto superior direito.
- [ ] **Encerrar** (com confirmação) volta à tela normal, também em paisagem.

## 3. Girar durante a navegação

- [ ] Gire de retrato para paisagem e de volta no meio de uma navegação: ela continua (rota, voz, gravação); o layout troca entre banner+barra e coluna lateral sem travar.
- [ ] Ao chegar ao destino aparece "Você chegou!" com **Concluir viagem**, nas duas orientações.

## 4. Movimento do mapa (estilo Waze)

Teste em movimento real (moto ou carro), com a navegação ativa e rotação ligada.

- [ ] **Fluidez**: a moto e o mapa deslizam continuamente (sem "pulinhos" de 1 em 1 segundo); o mapa não fica atrás da moto nem a moto "corre" à frente do mapa.
- [ ] **Curva fechada** (retorno, cotovelo de 90° ou rotatória): o mapa gira pelo caminho curto, sem dar a volta longa e sem estalo; a seta fica sobre o traçado da rota e aponta no sentido da rota.
- [ ] **Parar no semáforo e voltar**: parado, o mapa e a seta ficam quietos (não giram nem andam com a deriva do GPS); ao arrancar, voltam a andar e girar sem solavanco.
- [ ] **Zoom**: devagar ou parado o mapa fica mais perto (cerca de zoom 18); a ~90 km/h fica mais aberto (cerca de zoom 16), mudando devagar. A moto fica no terço inferior da tela, mais à frente quanto mais rápido; banner e barra não a cobrem.
- [ ] **Túnel / sem GPS**: ao perder o sinal o marcador anda no máximo ~1,5 s à frente e para (não "voa"); ao voltar o sinal, ele retoma sem atravessar o mapa em disparada.
- [ ] **Rumo ausente**: ao sair de um estacionamento ou parado a seta mantém a última direção (não vira para o norte).
- [ ] **Rotação desligada**: com a rotação do mapa desligada, o norte fica para cima, o mapa só desloca (sem girar) e a seta gira sozinha.
- [ ] **Longe da rota** (mais de ~25 m, ou fora da rota): a seta segue o GPS suavizado e não fica "grudada" no traçado; ao recalcular a rota ela volta a seguir o novo traçado.
- [ ] **Mexer no mapa**: arrastar o mapa para de seguir (aparece **Centralizar**); tocar em **Centralizar** volta a seguir.
- [ ] **Segundo plano**: ir para outro app e voltar não trava nem deixa a câmera presa; o app não consome CPU em segundo plano (o laço de quadros para).
- [ ] **Desempenho e bateria**: em uma hora de navegação o aparelho não esquenta anormalmente, o mapa mantém ~60 fps e a bateria cai em ritmo parecido com o de outro app de navegação.
