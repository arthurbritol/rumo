# Rolê por perto no Rumo — desenho

Projeto 5 do Rumo (continuação do app, não um produto novo). Data: 2026-11-11. Dono: Arthur. Idioma de todo texto da tela: pt-BR.

Fora deste documento: catálogo de conexões por marca (projeto 4, adiado), preparação para a Play Store (projeto 7), qualquer mudança na localização compartilhada entre amigos (projeto 3), que continua funcionando como está.

---

## 1. Por que

Hoje o Rumo ajuda quem já tem com quem pedalar: amigos aceitos, rolês em grupo, localização compartilhada entre amigos. Falta o caso de quem está sozinho, por exemplo numa praça, **procurando um rolê** e quer achar outros motociclistas que querem algo parecido e marcar de se encontrar. O dono quer isso como continuação do app e também como **projeto de faculdade** (prazo sem data fixa, talvez até o fim de novembro de 2026).

O ponto de partida técnico importa: no Firestore (que o Rumo usa), cada pessoa que escreve sua posição é lida por todos os vizinhos, então o custo cresce com **N²** (2 × N² leituras por minuto com 30 s entre posições). Com 100 pessoas na mesma região isso gasta a cota diária gratuita de 50 mil leituras em cerca de 3 minutos. Por isso a posição e os encontros ficam num servidor em tempo real na **Cloudflare**, onde o custo cresce com N, e o Firestore continua cuidando de conta, amigos, bloqueios entre amigos.

## 2. Objetivos

O motociclista logado, com "procurando rolê" ligado:

1. Escolhe de listas o **ritmo**, a **distância** do rolê e o **tipo de parada** que quer.
2. Aparece no mapa de quem também está procurando, **só como uma moto** (ícone visto de cima, igual para todos, gira com o rumo), sem nome, modelo, foto nem identificador.
3. Vê no mapa **todas** as motos que estão procurando dentro do raio escolhido, com as que combinam com a sua escolha **em destaque**. Raio inicial de **5 km**, ajustável até **20 km**.
4. Toca numa moto e vê só o tipo de rolê dela (ritmo, distância, parada), a distância aproximada, e pode **propor um encontro**.
5. **Propõe um encontro**: escolhe o ponto no mapa e **quantas pessoas cabem** (2 ou mais). A proposta fica **visível no mapa para quem está por perto**. Quem a vê e tem vaga **entra direto**, sem aprovação de quem propôs.
6. Todos os que entraram veem o **ponto**, a **moto uns dos outros se movendo** e, para cada pessoa, a **distância em km e o tempo** até o ponto.
7. Ao **chegarem**, o encontro termina. A partir daí os participantes podem virar **amigos** pelo app (se ambos quiserem), criar rolês juntos e usar o resto do projeto 2.
8. Pode **bloquear** e **denunciar** alguém sem saber quem é.

## 3. Fora do escopo

- Chat ou mensagens entre desconhecidos (o encontro é por ponto no mapa, não por conversa).
- Notificação com o app fechado (o Rumo não tem servidor de push; o aviso é dentro do app).
- Histórico de encontros, avaliação de pessoas, verificação de identidade.
- Ver quem é a pessoa antes do aceite de amizade (nome, moto, perfil).
- Moto em 3D com modelo de verdade (arquivo 3D no mapa). O ícone é uma imagem desenhada com volume, vista de cima.
- iOS, versão web, painel de moderação (as denúncias são lidas pelo dono; ver 8.7).
- Qualquer mudança na localização compartilhada entre amigos (projeto 3) e no Firestore dela.

## 4. Anti-objetivos (seria fracasso mesmo funcionando)

- **Não escalar**: a ideia tem de aguentar bem 100 pessoas ligadas ao mesmo tempo hoje e crescer depois, se o app for para a Play Store, sem reescrever a arquitetura.
- Qualquer pessoa que não seja participante de um encontro aceito conseguir a **posição exata** de alguém.
- Vazar para qualquer cliente o **uid**, e-mail, nome, modelo da moto ou qualquer dado que identifique a pessoa antes de ambos aceitarem virar amigos.
- A moto continuar aparecendo depois que a pessoa desligou "procurando rolê", fechou o app ou perdeu a conexão.
- Dar para **seguir** alguém (descobrir a posição real aos poucos por aproximações repetidas ou por propostas em série).
- Depender do Firestore para a posição em tempo real, ou exigir plano pago sem o dono ter escolhido.
- Quebrar quem usa o app sem conta ou sem o Tripper Pod: o rolê por perto só existe para quem está logado.

## 5. Restrições

- **Cloudflare Workers + Durable Objects com SQLite** (disponíveis no plano gratuito desde janeiro de 2026). Mensagens que o celular envia contam 20 para 1; as que o servidor devolve aos vizinhos não são cobradas. Passando do limite diário gratuito as operações **falham com erro** (e zeram à 00:00 UTC): o app deve mostrar "serviço de rolê por perto indisponível agora" em vez de travar. Os números exatos do plano gratuito devem ser reconferidos na página de preços da Cloudflare antes de implementar.
- A conta Cloudflare do dono já existe e há um Worker publicado (trânsito, `workers/traffic-proxy`) com o fluxo de publicação por GitHub Actions e segredos `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID`. O novo servidor segue o mesmo padrão (pasta `workers/`, testes com Node, workflow próprio).
- **Login**: Firebase Auth (projeto 1). O servidor precisa conferir o **ID token do Firebase** em cada conexão (JWT RS256 com as chaves públicas do Google). Sem login válido, sem conexão.
- **Firebase Spark** segue sendo o plano do app: o rolê por perto não pode exigir Cloud Functions nem aumentar leituras/escritas do Firestore de forma relevante.
- O app usa os serviços em primeiro plano que já existem para localização (`TrackingService`/`PodService`, tipo `LOCATION`). **Sem** permissão de localização em segundo plano.
- Tecnologias do app: Kotlin, Jetpack Compose, MapLibre, Room. Textos em pt-BR.
- Sem regressão: nada do que existe hoje (amigos, rolês, localização compartilhada, postos, trânsito) pode mudar de comportamento.

## 6. Abordagens consideradas

- **A. Tudo no Firestore (descartada).** Reaproveita o que existe, mas o custo cresce com N² (ver seção 1) e estoura o plano gratuito com cerca de 10 a 20 pessoas na mesma região. Mesmo no plano pago, o custo cresce com o quadrado.
- **B. Servidor em tempo real na Cloudflare (escolhida pelo dono).** Um Worker com Durable Objects recebe a posição de cada pessoa por WebSocket, filtra e reenvia aos vizinhos. Custo cresce com N, aguenta os 100 de agora e as dezenas de milhares com a mesma arquitetura.
- **C. Servidor próprio (VPS) (descartada).** Mais controle, mas é preciso manter máquina, certificados e atualizações, sem benefício aqui.

## 7. Decisões do dono (como ele decidiu)

- Escolhe o rolê por listas de **ritmo, distância e tipo de parada**.
- No mapa, **todas** as motos que estão procurando; as que combinam ficam **em destaque**.
- Alcance **5 km** de início, até **20 km**.
- Antes de entrar num encontro, a posição aparece **aproximada** (algumas centenas de metros). Dentro do encontro, os participantes continuam se vendo **aproximados** durante o trajeto (as motos se movem no mapa) e a posição **real** só aparece quando a pessoa chega a **1 km do ponto** (proteção contra quem proponha um encontro só para ver a posição real de desconhecidos).
- O **ponto de encontro** é escolhido no mapa por quem propõe; os outros aceitam.
- Quem propõe **define as vagas**. A proposta fica **visível no mapa** para quem está por perto, e quem tem vaga **entra direto** (suposição confirmada pelo dono).
- Todos veem a **moto uns dos outros se movendo** até o ponto e a **distância/tempo** de cada um; a posição real só aparece a menos de 1 km do ponto (decisão tomada com o dono depois da revisão do desenho).
- **Ordem de entrega** (o dono aceitou cortar o fim se o prazo apertar): ver a seção 11.
- Ao chegarem, **o encontro termina**; depois podem **virar amigos**, criar rolês e afins.
- **Bloquear e denunciar** sem saber quem é.
- Quem propõe pode **cancelar**; o encontro **expira** se ninguém chegar depois de um tempo (suposição confirmada).
- Ícone: **moto vista de cima** (guidão, tanque, capacete), cinza para os outros, laranja para o próprio usuário, girando com o rumo.
- Projeto de faculdade; prazo sem data fixa, talvez até o fim do mês.

## 8. Decisões do arquiteto (minhas; o dono pode trocar)

### 8.1 Visão geral

```
app (Kotlin) ──WebSocket + ID token Firebase──▶ Worker "rumo-roles" (Cloudflare)
                                                   │ roteia pela célula geográfica
                                                   ▼
                                        Durable Object "Celula" (uma por quadrado de 1° x 1°)
                                          · quem está procurando (sessão anônima + posição)
                                          · propostas e encontros
                                          · bloqueios e denúncias (SQLite)
Firestore (existente): conta, amigos, convites. Só o fim do encontro conversa com ele (ver 8.6).
```

- **Quadrados de 1° x 1°** (cerca de 111 km de lado), cada um um Durable Object. Uma pessoa com raio de 20 km toca no máximo 4 quadrados (perto de uma quina), em geral 1. O app abre uma conexão por quadrado tocado.
- **Estado global por pessoa** (espera de 5 min entre propostas, bloqueios, denúncias, suspensão, marcas de "quero ser amigo") vive num Durable Object **por `uid`** ("Conta"), não nos quadrados, porque vale em qualquer lugar. Cada quadrado consulta a Conta da pessoa ao ela ligar "procurando rolê" e ao propor ou entrar. Cada Conta só tem uma pessoa, então isso também escala.
- **Cada encontro vive no Durable Object do quadrado do ponto marcado**; todos os participantes se conectam nele para esse encontro, mesmo que estejam em outro quadrado. Assim há um dono claro por encontro.
- Uso de **WebSocket com hibernação** (Durable Objects "hibernation API") para não pagar tempo parado.
- Pessoa só existe no servidor enquanto "procurando rolê" estiver ligado ou enquanto estiver num encontro. O servidor expira qualquer pessoa sem sinal por **2 minutos** (celular desligou, app morto).

### 8.2 Identidade e privacidade

- O servidor conhece `uid` (do token) e cria um **identificador de sessão aleatório** novo a cada vez que a pessoa liga "procurando rolê". Os outros clientes só veem o id da sessão, nunca o `uid`.
- **Posição aproximada** (antes do aceite): o servidor soma à posição real um desvio de 200 a 400 m, **estável por pessoa e por dia** (calculado a partir do `uid`, da data e de um segredo do servidor; ligar e desligar várias vezes não dá desvios diferentes para tirar a média), e só envia essa posição aos vizinhos. O cliente nunca envia nem recebe posição exata de desconhecidos fora de um encontro.
- **Dentro do encontro**: os participantes recebem a posição **aproximada** (mesmo desvio estável) uns dos outros enquanto estão a mais de 1 km do ponto; quando uma pessoa chega a **1 km do ponto**, os outros passam a receber a posição **real** dela, só enquanto o encontro durar. Distância e tempo até o ponto continuam sendo mostrados o tempo todo.
- **Anti-seguimento**: máximo de **1 proposta aberta** por pessoa; recusa/expiração gera espera de **5 min** para nova proposta; sessões novas (a pessoa liga e desliga) também respeitam esse limite por `uid`. O servidor limita a frequência de mensagens por conexão.
- Quem foi **bloqueado** (por `uid`, no servidor) fica invisível para quem bloqueou e vice-versa: não aparece no mapa, nas propostas nem em encontros.

### 8.3 Protocolo entre app e servidor

Uma conexão por quadrado. Mensagens JSON (estrutura exata fica com quem constrói; os tipos abaixo são o contrato):

- `procurar` (ritmo, distância, parada, raio) · `parar`
- `posicao` (latitude, longitude, rumo, velocidade; no servidor vira posição aproximada para os vizinhos)
- `proposta` (ponto, vagas, mensagem curta opcional **fora do escopo**) · `cancelar` · `entrar` · `sair`
- Do servidor para o app: `vizinhos` (lista de sessões com posição aproximada, rumo, rolê desejado, e um sinal de "combina"), `propostas` (lista com ponto, vagas livres, rolê), `encontro` (participantes com posição real, distância/tempo de cada um), `fim` (motivo: chegaram, cancelado, expirou, ninguém chegou), `erro`.
- Frequência: **a cada 30 s** enquanto procurando; **a cada 10 s** enquanto em encontro. Envia antes se andou mais de 100 m. Piso de 10 s.
- Distância e tempo até o ponto: cada app calcula os **seus** (motor de rotas que o Rumo já usa, com reserva em linha reta) e inclui no `posicao`; o servidor repassa aos outros participantes.

### 8.4 Encontro

- Estados: `aberta` (proposta publicada) → `em andamento` (pelo menos uma pessoa além de quem propôs entrou) → `encerrada` (chegaram, cancelado, expirou).
- Vagas: de 2 a **8** pessoas incluindo quem propôs (o dono pode trocar o teto). Quem entra ocupa 1 vaga; cheio, a proposta some do mapa dos outros.
- **Chegada**: a pessoa está a menos de **100 m** do ponto. O encontro termina quando todos os que ainda estão nele chegaram ou quando passam **60 min** sem ninguém chegar (valor meu; o dono pode trocar). Proposta sem ninguém entrando expira em **30 min**.
- Quem propõe pode cancelar a qualquer momento; quem entrou pode sair.

### 8.5 Combinação ("parecido")

- Listas fixas, textos em pt-BR (valores iniciais sugeridos; o dono ajusta):
  - Ritmo: tranquilo, moderado, animado.
  - Distância: até 50 km, 50 a 150 km, mais de 150 km.
  - Parada: café/lanche, mirante, só estrada, sem parada.
- "Combina" é uma regra simples e explicável (mesmo ritmo, distância e parada compatíveis). Não é ranking nem pontuação; só decide o destaque no mapa.

### 8.6 Depois do encontro: virar amigo

- Ao fim, cada participante pode marcar **"quero ser amigo"** em quem esteve no encontro. Só quando **ambos** marcaram, o servidor entrega o `uid` de cada um ao outro, e o app usa o **convite de amizade que já existe** no projeto 2. Antes disso ninguém aprende o uid de ninguém.
- É a única comunicação do servidor com o resto do Rumo; o Firestore e as regras dele **não mudam**.

### 8.7 Bloqueio e denúncia

- **Bloquear** e **denunciar** aparecem no cartão da moto e na tela do encontro. O app só manda o identificador da sessão; o servidor resolve para o `uid` e grava o bloqueio no SQLite do Durable Object (persistente entre sessões e quadrados, por meio de um índice por `uid`).
- **Denúncia**: o servidor guarda `uid` de quem denunciou, `uid` de quem foi denunciado, horário, motivo escolhido numa lista curta e a posição aproximada. O dono consulta numa rota protegida por segredo (formato e ferramenta ficam com quem constrói; um painel não está no escopo). Três denúncias de pessoas diferentes em 7 dias suspendem a pessoa de "procurando rolê" até o dono decidir (regra minha; o dono pode trocar).

### 8.8 No aplicativo

- Novo pacote no `app` para o cliente (WebSocket, estado, mapeamento) e camadas no `HunterMap` para motos de desconhecidos (ícone A rotacionado pelo rumo, destaque quando combina) e para pontos de proposta (com vagas, ex.: "2 de 4").
- Telas: ligar "procurando rolê" com as três listas e o raio; cartão da moto ou da proposta; tela de encontro com ponto, participantes, distância/tempo; fim do encontro com "quero ser amigo", bloquear e denunciar.
- O botão de ligar fica visível só para quem está logado. Sem internet, mostra a mensagem e não liga.
- Publicação: servidor publicado por workflow próprio (testes primeiro, depois `wrangler deploy`), e o endereço entra no app como já é feito com o trânsito (variável `NEARBY_URL`).

### 8.10 Decisões do arquiteto depois da revisão do desenho

Marcadas como minhas; o dono pode trocar.

- **Quem confirma a chegada e a distância**: o servidor decide a chegada a partir da posição enviada pelo app (menos de 100 m do ponto). Rejeita posições com velocidade ou salto impossível (por exemplo, mais de 250 km/h ou mudança de centenas de km em segundos). Mentir a posição por ferramenta de GPS falso **não é totalmente evitável** nesta versão; fica registrado no modelo de ameaças da documentação.
- **Marca "quero ser amigo"**: guardada na Conta de cada pessoa por **24 horas** depois do fim do encontro. Ao abrir o app, ele consulta se há par completo. Se ambos marcaram, o app recebe o `uid` do outro e usa o convite de amizade que já existe (`SocialEngine.invite(to)`, projeto 2). Sem notificação com o app fechado.
- **Bloqueio**: vale por `uid`, para todas as sessões futuras e todos os quadrados. Não há tela para desbloquear nesta versão (limitação documentada). Fica na Conta de quem bloqueou, que replica o bloqueio para a pessoa bloqueada.
- **Suspensão**: pessoa suspensa vê "Recurso indisponível por denúncias. Fale com o dono do Rumo." O dono lista as denúncias e tira a suspensão por rotas protegidas por segredo (sem painel). A regra "3 denúncias de pessoas diferentes em 7 dias" é minha.
- **Regra de "combina"**: mesmo ritmo **e** distância no mesmo grupo ou em grupo vizinho **e** parada igual (ou uma delas ser "sem parada" e a outra "só estrada").
- **Limites da Cloudflare**: antes de construir, conferir os números na página de preços. Se forem bem menores que o suposto, **parar e perguntar ao dono**. Se o limite diário acabar com encontros em andamento, as conexões caem com `erro`, e o app mostra "serviço indisponível agora" e tenta de novo.

### 8.9 Capacidade e custo (para o relatório da faculdade)

- 100 pessoas procurando, 3 h por dia: cada uma envia 2 mensagens por minuto, ou 36.000 mensagens por dia, equivalentes a cerca de **1.800 requisições** (20 para 1). O limite diário gratuito típico é de ordem de 100 mil. Mesmo com 10 vezes mais pessoas, cabe.
- Os vizinhos recebem as posições por mensagens que o servidor envia, que **não são cobradas**.
- A confirmação dos limites exatos do plano gratuito e a medição real (número de mensagens, tempo ativo) fazem parte do trabalho e vão para a documentação.

## 9. Deixado para quem for construir

- Estrutura exata das mensagens JSON, nomes dos campos, forma de reconexão e de contagem de atualizações.
- Como conferir o token do Firebase no Worker (biblioteca ou implementação própria) e como guardar em cache as chaves públicas do Google.
- Detalhes de desenho do ícone (tamanhos, sombra, destaque), cores do destaque de "combina" e da proposta no mapa.
- Estratégia de testes (lógica pura em Node, objeto durável por ambiente de teste da Cloudflare, e testes do app com um servidor falso).
- Formato do armazenamento no SQLite do Durable Object e do relatório de denúncias.
- Documentação para a faculdade: diagrama de arquitetura, análise de custo, modelo de ameaças e como foi testado.

## 10. Como saber que está pronto

- Duas contas diferentes em dois celulares (ou um celular e um emulador): ambas ligam "procurando rolê", se veem como motos anônimas, uma propõe um ponto com 2 vagas, a outra entra, ambas veem a moto da outra se mexendo e distância/tempo, chegam e o encontro termina.
- O servidor **nunca** envia `uid`, nome, modelo nem posição exata a quem não está no mesmo encontro; há teste automatizado para isso.
- Desligar "procurando rolê", perder a internet por mais de 2 minutos ou fechar o app faz a moto sumir do mapa dos outros.
- Bloquear alguém torna os dois invisíveis um para o outro; denunciar grava a denúncia.
- Teste de carga simples com cerca de **100 conexões simuladas** na mesma região, medindo mensagens por minuto, sem erro e com a conta de custo documentada.
- Nada que existe hoje (amigos, rolês, localização compartilhada, postos, trânsito) mudou de comportamento; testes antigos continuam passando.

---

## 11. Ordem de entrega e corte

O dono aceitou esta ordem; se o prazo apertar (talvez até o fim de novembro de 2026), os itens 4 e 5 ficam para uma segunda etapa. As proteções do item 3 **não** são cortadas.

1. Procurar rolê e ver as motos anônimas no mapa (servidor, conexão, ícone, destaque de "combina", raio).
2. Propor, entrar, encontro com distância e tempo, chegada e fim.
3. Bloquear e denunciar.
4. Virar amigo no fim do encontro.
5. Suspensão automática por denúncias.

Cada etapa deve funcionar sozinha, com testes, antes de começar a seguinte.
