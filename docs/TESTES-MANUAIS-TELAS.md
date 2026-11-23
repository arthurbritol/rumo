# Roteiro de testes manuais: telas em pé e deitado

Para cada item, teste com o celular em pé (retrato) e deitado (paisagem, nos dois sentidos de giro, para ver o recorte da câmera
de cada lado). Se possível, repita num tablet ou no emulador "Tablet" e com a letra grande do sistema. Tema escuro e claro.

Critérios gerais: nada sobre a barra de status (bateria, wifi) nem sob a barra de gestos ou de botões; nada cortado; nada
sobreposto; todo botão com pelo menos 48 dp; textos legíveis.

## Mapa em repouso (aba Mapa)
Retrato
- [ ] Barra "Para onde?" no topo, abaixo da barra de status, largura total.
- [ ] Faixa de atalhos (favoritos e recentes) logo abaixo da barra, rolável de lado, sem encostar na barra.
- [ ] Coluna de botões à direita, abaixo da faixa: postos, trânsito, rolê (com conta e servidor) e, ao mexer no mapa, localização laranja; mesmo tamanho e espaço entre eles.
- [ ] Embaixo, "Pronto para rodar" em uma linha com "Gravar viagem"; o mapa continua visível acima.
- [ ] Barra de abas embaixo (Mapa, Viagens, Garagem, Amigos, Pod) acima da barra de gestos.
Paisagem
- [ ] Coluna esquerda de ~300 dp, rolável: busca, no máximo 3 atalhos, faixas de estado (sem espaço sobrando quando vazias) e "Pronto para rodar" compacto com "Gravar viagem" visível sem rolar (celular com ~336 dp de altura útil).
- [ ] Mapa ocupa o resto da largura e toda a altura; a moto fica no meio da parte livre do mapa.
- [ ] Botões na borda direita, no alto, dentro da área segura; nenhum toca a barra de status.
- [ ] Trilho de abas vertical na borda esquerda (72 dp), todas as 5 abas à vista; o selo do Amigos aparece.
- [ ] Segurar no mapa (prévia de rota), tocar num posto, numa moto ou numa proposta: o cartão aparece NO TOPO da coluna (os atalhos somem), não por cima do mapa; com a coluna rolada, ela volta ao topo. A prévia rola junto com a coluna (uma só rolagem).
- [ ] Gravando: o cartão de gravação (velocidade, métricas, "Encerrar viagem") fica na coluna e o aviso de radar/lombada aparece no canto de baixo, ao lado da coluna.
- [ ] Rolê ligado, com encontro ativo: o cartão do encontro fica na coluna; minimizado vira pílula na coluna.

Janela estreita
- [ ] Tela dividida ou janela deitada com menos de 600 dp de largura (ex.: 411 x 380): usa o layout de retrato (barra de abas embaixo), sem trilho nem coluna.
Retrato baixo (~640 dp)
- [ ] Gravando, ou com cartão de moto/proposta/posto aberto: a coluna de botões mostra menos botões (um e o "mais") e não encosta no cartão de baixo; o "mais" abre um mini menu com o resto.

## Navegação (enxuta)
- [ ] Retrato: banner de manobra no topo (respeita a barra de status), barra fina embaixo; sem abas, sem botões, sem busca.
- [ ] Paisagem: painel esquerdo com a mesma largura da coluna do mapa em repouso: manobra grande, "Depois", velocidade grande, chegada/tempo/distância; Voz/Mudo e Encerrar fixos embaixo. Mapa no resto.
- [ ] Painel respeita o recorte da câmera à esquerda (gire para os dois lados).
- [ ] Cartão de radar/lombada aparece no canto de baixo ao lado do painel (paisagem) ou acima da barra (retrato), sem tocar o botão Centralizar (canto de baixo à direita, só ao mexer no mapa).
- [ ] Pílula do encontro: retrato abaixo do banner (sem encostar nele); paisagem canto superior direito.
- [ ] Girar o celular durante a navegação mantém a rota e a voz (a tela é recriada; o estado da navegação continua).

## Outras abas
Para cada uma: rola até o fim, nada fica sob a barra de gestos nem sob o trilho, texto não corta, largura do conteúdo limitada (~640 dp) e centralizada em telas largas.
- [ ] Viagens: lista, detalhe de uma viagem (o mapa do detalhe fica mais baixo deitado e a rolagem mostra métricas e gráfico), apagar e renomear.
- [ ] Garagem: cabeçalho, odômetro, abas internas, abastecimento (diálogo), manutenção, offline.
- [ ] Amigos: convites, lista, busca, papéis; abrir uma subtela e voltar.
- [ ] Pod: conectar, PIN, bússola.
- [ ] Perfil (avatar na Garagem ou Amigos): rolagem, edição com teclado aberto (o campo continua visível), voltar.
- [ ] Login (criar conta) e apresentação (Perfil > Rever apresentação) em paisagem: conteúdo rolável, largura limitada, "Pular" fora da barra de status, nada sob a barra de gestos.
- [ ] Permissões e "Sua moto" (primeira abertura): rolam em paisagem.
- [ ] Aviso de versão e "Crie uma conta": em paisagem formam uma pilha embaixo, ao lado da coluna, sem cobrir a coluna nem os botões da direita; em retrato o banner fica no topo e o aviso de conta acima dos cartões de baixo. Somem navegando e gravando.

- [ ] Paisagem com encontro ativo ou encerrado (rolê): o cartão aparece no topo da coluna, sem fechar o app, e a coluna rola.

## Rotação e estados
- [ ] Girar em cada aba: não trava nem perde a aba; o mapa reaparece com a câmera seguindo.
- [ ] Girar com a moto ou a proposta tocada, ou com um ponto marcado: continuam abertos. Com a busca aberta: o modo de busca continua, mas o texto digitado e os resultados reiniciam. O diálogo de preços do posto fecha e o encontro minimizado reabre expandido.
- [ ] Barra de navegação por 3 botões (no lado da tela em paisagem): nada fica sob ela.

## Conhecido
- A tela é recriada ao girar (o app não trata a rotação por conta própria). Continuam: a navegação, a gravação e o rolê (ficam no ViewModel e no serviço), a moto e a proposta tocadas, o ponto marcado, a folha de rolê e o seguir. Reiniciam: o texto da busca, o diálogo de preços do posto e o encontro minimizado.
- Viagens, Garagem e Amigos não têm dois painéis (lista e detalhe) em paisagem: só limite de largura.
