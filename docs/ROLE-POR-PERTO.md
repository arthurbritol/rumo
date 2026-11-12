# Rolê por perto

Rascunho. O "Rolê por perto" mostra, em tempo quase real, outros motoristas do Rumo que estão por perto e querem o mesmo tipo de rolê (ritmo, distância e parada que combinam), sem revelar quem são nem onde estão de verdade: cada pessoa aparece só com um id aleatório de sessão e uma posição com desvio de 200 a 400 m. O servidor é um Cloudflare Worker com Durable Objects (pasta `workers/nearby`); o app abre uma conexão WebSocket por quadrado de 1° x 1° que o raio escolhido toca.

## Arquitetura

```
App (Android)
  |  uma conexão WebSocket por quadrado tocado pelo raio
  |  GET /ws?cell=<lat>:<lon>&t=<ID token do Firebase>
  v
Worker "rumo-nearby" (src/index.js)
  |  confere o token, valida o quadrado (formato e Brasil)
  |  define x-rumo-uid / x-rumo-exp a partir do token
  v
Durable Object Celula (um por quadrado)   Durable Object Conta (um por pessoa, etapa posterior)
  presença, combinação e lista de vizinhos
```

O servidor nunca envia uid, nome, e-mail nem posição exata a outros clientes.

## Endpoints

- `GET /health`: responde `200 ok`.
- `GET /ws?cell=<id>&t=<token>` (com `Upgrade: websocket`): `cell` é o quadrado no formato `lat:lon` com graus inteiros (ex.: `-21:-41`), `t` é o ID token do Firebase. Respostas: 401 token ausente ou inválido, 404 quadrado malformado ou fora do Brasil, 426 sem `Upgrade`, 500 `server not configured` se o servidor estiver sem configuração.

## O que o dono precisa fazer

1. No GitHub, em Settings > Secrets and variables > Actions > Secrets, criar dois segredos com textos longos e aleatórios (por exemplo 40 caracteres ou mais):
   - `NEARBY_SECRET`
   - `ADMIN_TOKEN`

   Os segredos `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID` são os mesmos já usados no servidor de trânsito.
2. Em Actions, rodar o workflow "Servidor de rolê por perto" (Run workflow). Ao final, o log imprime o endereço do servidor (algo como `https://rumo-nearby.<conta>.workers.dev`).
3. Em Settings > Secrets and variables > Actions > Variables, criar a variável de repositório `NEARBY_URL` com esse endereço, sem barra no final.

Sem os segredos o workflow roda os testes e pula a publicação, com um aviso.
