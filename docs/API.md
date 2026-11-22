# API - Raiji

## Busca de caronas

Endpoint para busca de caronas planejadas com vagas disponiveis.

### Endpoint

`GET /api/rides`

Autenticacao: Bearer token (qualquer usuario autenticado).

### Query parameters

| Param | Tipo | Obrigatorio | Descricao |
|---|---|---:|---|
| originCity | string | nao | Cidade de origem (contem, case-insensitive) |
| destinationCity | string | nao | Cidade de destino (contem, case-insensitive) |
| departureFrom | ISO datetime | nao | Data/hora minima de saida |
| departureTo | ISO datetime | nao | Data/hora maxima de saida |
| maxPrice | decimal | nao | Preco maximo |
| freeOnly | boolean | nao | Apenas caronas gratuitas (pricePerPassenger = null) |
| minSeats | int | nao | Vagas minimas disponiveis |
| page | int | nao (default 0) | Pagina |
| size | int | nao (default 20) | Tamanho da pagina |
| sort | string | nao (default departureDate,asc) | Ordenacao |

### Regras fixas

A busca sempre aplica:

- `status = PLANNED`
- `availableSeats > 0`
- `deleted_at IS NULL`

### Observacoes

- Nenhum campo de localizacao em tempo real e retornado (RN09).
- `freeOnly=true` retorna apenas caronas com `pricePerPassenger = null`.
- `maxPrice` filtra apenas caronas com `pricePerPassenger <= maxPrice`.
- `minSeats` filtra caronas com `availableSeats >= minSeats`.


---

## Solicitacao de vaga (S1-06 + S2-01)

### POST /api/rides/{rideId}/requests

Autenticacao: Bearer token com role `PASSENGER`.

A partir da S2-01 (RF18) o payload EXIGE a declaracao de bagagem
(`baggage` com pelo menos 1 item):

```json
{
  "passengerName": "João Silva",
  "baggage": [
    {
      "type": "mala",
      "quantity": 1,
      "lengthCm": 60,
      "widthCm": 40,
      "heightCm": 30,
      "description": "Mala de bordo"
    }
  ]
}
```

| Campo | Tipo | Obrigatorio | Regras |
|---|---|---:|---|
| passengerName | string | sim | 1..150 caracteres |
| baggage | array | sim | minimo 1 item |
| baggage[].type | string | sim | `mochila` \| `sacola` \| `mala` \| `caixa` |
| baggage[].quantity | int | sim | >= 1 |
| baggage[].lengthCm | int | sim | > 0 |
| baggage[].widthCm | int | sim | > 0 |
| baggage[].heightCm | int | sim | > 0 |
| baggage[].weightKg | decimal | nao | > 0 quando informado; ate 2 casas decimais (S2-03) |
| baggage[].description | string | nao | <= 500 caracteres |

Erros:

- `400` baggage ausente/vazio, quantity <= 0, dimensao <= 0, type fora do enum, nome invalido, weightKg <= 0 ou com mais de 2 casas
- `401` sem token · `403` sem role PASSENGER
- `404` carona inexistente
- `409` solicitacao duplicada, sem vagas, carona nao PLANNED ou partida passada

A resposta `201` ecoa a solicitacao com `status: PENDING` e a `baggage` declarada.

### GET /api/rides/{rideId}/requests (visao do motorista)

Autenticacao: Bearer token com role `DRIVER` (dono da carona).

Retorna as solicitacoes com a bagagem declarada — o motorista ve a bagagem
ANTES de aceitar/recusar:

```json
[
  {
    "id": "…",
    "rideId": "…",
    "passengerSub": "…",
    "passengerName": "Maria Silva",
    "status": "PENDING",
    "joinedAt": "…",
    "baggage": [
      { "type": "mala", "quantity": 1, "lengthCm": 60, "widthCm": 40, "heightCm": 30, "description": "Mala de bordo" }
    ]
  }
]
```

Observacoes:

- `type` e aceito em qualquer caixa na entrada e sempre devolvido em minusculas.
- Desde a S2-03 a soma de peso/volume das bagagens CONFIRMED e travada no
  aceite (PATCH abaixo); limites declarados em `cargoDetails.maxWeightKg`
  (carona) e `cargoVolumeLiters` (veiculo).

---

## Decisao do motorista (S2-02)

### PATCH /api/rides/{rideId}/requests/{requestId}

Autenticacao: Bearer token com role `DRIVER` (dono da carona).

O motorista decide apos ver a bagagem declarada (GET da S1-06/S2-01).
`ACCEPT` confirma o passageiro e decrementa `availableSeats`; `REJECT`
cancela a solicitacao com status `CANCELLED` (reutilizado — sem novo valor
de enum nem migration) e EXIGE `reason`. O passageiro e notificado nos
dois casos (NotificationService, S1-06).

Body (JSON):

```json
{ "decision": "ACCEPT" }
```

```json
{ "decision": "REJECT", "reason": "A bagagem excede a capacidade do veiculo." }
```

| Campo | Tipo | Obrigatorio | Regras |
|---|---|---:|---|
| decision | string | sim | `ACCEPT` \| `REJECT` (qualquer caixa na entrada) |
| reason | string | REJECT | 1..500 caracteres; obrigatorio quando REJECT |

Resposta `200`:

```json
{ "id": "…", "status": "CONFIRMED", "confirmedAt": "…" }
```

No REJECT: `{ "id": "…", "status": "CANCELLED", "confirmedAt": null }`.

Erros:

- `400` decision ausente/fora do enum; REJECT sem reason; reason > 500; peso/volume acima da capacidade (S2-03)
- `401` sem token · `403` sem role DRIVER ou motorista nao dono da carona
- `404` carona ou solicitacao inexistente (ou de outra carona)
- `409` solicitacao ja decidida (status != PENDING); ACCEPT sem vagas

Observacoes:

- S2-03 (RN03/RN12): o ACCEPT tambem trava capacidade — `409` quando nao
  ha vaga (decisoes simultaneas sao serializadas por lock pessimista na
  carona) e `400` quando a soma de peso das bagagens CONFIRMED excede
  `cargoDetails.maxWeightKg` ou o volume total (L x A x C x quantidade)
  excede o `cargoVolumeLiters` do veiculo. Detalhes em docs/ARQUITETURA.md.

---

## Cancelamento de reserva (S2-04 · RF09 / RN06)

### DELETE /api/rides/{rideId}/requests/{requestId}

Autenticacao: Bearer token com role `PASSENGER` (dono da reserva) **ou**
`DRIVER` (dono da carona). O corpo e **opcional**:

```json
{ "reason": "Imprevisto pessoal" }
```

| Campo  | Tipo   | Obrigatorio | Regras                    |
|--------|--------|-------------|---------------------------|
| reason | string | nao         | ate 500 caracteres         |

Cancela a reserva ANTES da viagem e libera a vaga imediatamente:

- reserva **CONFIRMED** -> `availableSeats++` na hora (`seatReleased: true`);
- reserva **PENDING**   -> vagas permanecem intactas (`seatReleased: false`);
- a reserva vai para `CANCELLED` com `cancellationReason` e `cancelledAt`;
- o **motorista e notificado** (NotificationService).

Resposta `200`:

```json
{
  "id": "…",
  "rideId": "…",
  "status": "CANCELLED",
  "cancelledAt": "…",
  "cancellationReason": "Imprevisto pessoal",
  "seatReleased": true
}
```

Erros:

- `401` sem token
- `403` ator nao e o passageiro da reserva nem o motorista da carona
- `404` carona ou reserva inexistente (ou reserva de outra carona)
- `409` reserva ja `CANCELLED`/`COMPLETED` **ou** carona nao esta `PLANNED`
  (viagem ja iniciada — RN06)

> O estorno automatico de caronas pagas canceladas e tratado na **S2-05**.

---

## Estorno automatico de caronas pagas (S2-05 · RN04)

O estorno e disparado automaticamente no **cancelamento** (DELETE da S2-04)
de uma reserva de carona **paga** (`pricePerPassenger != null`) que possua
`paymentIntentId` vinculado.

### Vinculando o pagamento no aceite

O `PATCH /api/rides/{rideId}/requests/{requestId}` (S2-02) aceita, de forma
**opcional**, o `paymentIntentId` no ACCEPT. Ele e gravado na reserva para
permitir o estorno futuro (a integracao de pagamento completa fica para
sprint futura):

```json
{ "decision": "ACCEPT", "paymentIntentId": "pi_1abc...DEF" }
```

### Comportamento no cancelamento

| Situacao                                   | Stripe        | Auditoria (`audit_log`)          |
|--------------------------------------------|---------------|----------------------------------|
| Carona paga + `paymentIntentId` + sucesso  | `refund(...)` | `ESTORNO_SUCESSO` (com refundId) |
| Carona paga + `paymentIntentId` + falha    | tenta         | `ESTORNO_FALHOU` (com o erro)    |
| Carona **gratuita** (`price == null`)      | **nao chama** | nenhum registro de estorno        |
| Carona paga **sem** `paymentIntentId`      | **nao chama** | nenhum registro de estorno        |

- **Falha no Stripe nao impede o cancelamento**: a reserva vai para
  `CANCELLED` e a vaga e liberada normalmente; o problema fica registrado como
  `ESTORNO_FALHOU`.
- Configuracao via `raiji.stripe.api-key` / `raiji.stripe.webhook-secret`
  (variaveis de ambiente `STRIPE_API_KEY` / `STRIPE_WEBHOOK_SECRET`). Sem
  chave, o Stripe e considerado "nao configurado" e nenhuma chamada real e
  feita (o estorno falha de forma controlada). **Nunca commitar chaves.**
- Valor estornado = `pricePerPassenger` convertido para centavos.


---

## Ciclo de vida das encomendas, confirmação e histórico (S4-06 — RF25/RN18, RF30)

### Confirmação de entrega

#### POST /api/deliveries/{id}/confirm

Autenticação: Bearer token com role `DRIVER` (motorista vinculado à encomenda).

Confirma a entrega mediante o código de confirmação enviado ao destinatário.

Body (JSON):

```json
{
  "confirmationCode": "123456",
  "recipientName": "Maria Silva",
  "observation": "Entregue em mãos ao destinatário."
}
```

| Campo | Tipo | Obrigatório | Regras |
|---|---|---:|---|
| confirmationCode | string | sim | Exatamente 6 dígitos numéricos |
| recipientName | string | sim | 1 a 120 caracteres |
| observation | string | não | Até 500 caracteres |

Comportamento:

- O código é gerado na criação da encomenda e enviado ao contato do destinatário por meio do simulador de notificação.
- O remetente recebe o código para acompanhar a entrega.
- O motorista deve informar o código correto para concluir a entrega.
- Após a confirmação, o status passa para `DELIVERED`, com registro de `deliveredAt`, nome de quem recebeu, observação e recompensa do motorista (`rewardCents`).
- A confirmação é permitida quando a encomenda está em `IN_TRANSIT`.
- Para frete exclusivo, também é permitida no status `CONFIRMED`, mediante justificativa.
- A confirmação gera registro no histórico de status e publica o evento `DeliveryDeliveredEvent`.

Tentativas e segurança:

- Após 5 tentativas inválidas, novas tentativas são bloqueadas.
- A tentativa seguinte retorna `423 Locked`.
- Uma encomenda não pode chegar a `DELIVERED` sem um código válido.

Resposta `200`: objeto `DeliveryResponse`, contendo, entre outros campos, `status`, `deliveredAt`, `rewardCents`, `confirmedRecipientName` e `deliveryObservation`.

Erros:

- `400` dados inválidos ou código incorreto
- `401` sem token
- `403` usuário não é o motorista vinculado à encomenda
- `404` encomenda inexistente
- `409` status incompatível com a confirmação ou regra de negócio não atendida
- `423` limite de tentativas de confirmação excedido

### Reenvio do código de confirmação

#### POST /api/deliveries/{id}/resend-code

Autenticação: Bearer token (remetente autorizado da encomenda).

Não exige corpo na requisição.

Solicita o reenvio do código de confirmação ao contato do destinatário, conforme as regras de autorização e status da encomenda.

Resposta: `200 OK`, sem corpo.

Erros:

- `401` sem token
- `403` usuário não autorizado como remetente
- `404` encomenda inexistente
- `409` status incompatível com o reenvio

### Timeline da encomenda

#### GET /api/deliveries/{id}/timeline

Autenticação: Bearer token (remetente ou motorista autorizado da encomenda).

Retorna o histórico cronológico de mudanças de status.

Exemplo de resposta:

```json
[
  {
    "fromStatus": "CONFIRMED",
    "toStatus": "IN_TRANSIT",
    "createdAt": "2026-11-22T12:00:00Z",
    "actorRole": "SYSTEM"
  },
  {
    "fromStatus": "IN_TRANSIT",
    "toStatus": "DELIVERED",
    "createdAt": "2026-11-22T14:30:00Z",
    "actorRole": "DRIVER"
  }
]
```

Cada registro contém:

| Campo | Tipo | Descrição |
|---|---|---|
| fromStatus | DeliveryStatus | Status anterior |
| toStatus | DeliveryStatus | Novo status |
| createdAt | Instant | Data e hora da alteração |
| actorRole | string | Papel do responsável pela alteração |

A timeline não expõe identificadores pessoais dos responsáveis.

Erros:

- `401` sem token
- `403` usuário não autorizado a consultar a encomenda
- `404` encomenda inexistente

### Histórico do motorista

#### GET /api/deliveries/driver/history

Autenticação: Bearer token com role `DRIVER`.

Retorna o histórico paginado das encomendas transportadas pelo motorista autenticado.

Query parameters:

| Parâmetro | Tipo | Obrigatório | Descrição |
|---|---|---:|---|
| status | DeliveryStatus | não | Filtra pelo status da encomenda |
| startDate | ISO datetime | não | Data/hora inicial da solicitação |
| endDate | ISO datetime | não | Data/hora final da solicitação |
| page | int | não (default 0) | Página |
| size | int | não (default 20) | Quantidade de registros por página |
| sort | string | não | Ordenação dos resultados |

Exemplo:

`GET /api/deliveries/driver/history?status=DELIVERED&startDate=2026-11-01T00:00:00Z&endDate=2026-11-30T23:59:59Z&page=0&size=20`

O histórico do motorista não retorna `confirmationCode`, `pickupCode` ou `deliveryKeyword`.

Resposta `200`: objeto paginado do Spring Data (`Page`), com os registros em `content` e informações de paginação.

Erros:

- `401` sem token
- `403` sem role DRIVER

### Histórico do remetente

#### GET /api/deliveries/sender/history

Autenticação: Bearer token (remetente autenticado).

Retorna o histórico paginado das encomendas enviadas pelo usuário e das empresas às quais pertence.

Aceita os mesmos parâmetros de consulta do histórico do motorista:

| Parâmetro | Tipo | Obrigatório | Descrição |
|---|---|---:|---|
| status | DeliveryStatus | não | Filtra pelo status |
| startDate | ISO datetime | não | Data/hora inicial da solicitação |
| endDate | ISO datetime | não | Data/hora final da solicitação |
| page | int | não (default 0) | Página |
| size | int | não (default 20) | Quantidade de registros por página |
| sort | string | não | Ordenação dos resultados |

Exemplo:

`GET /api/deliveries/sender/history?status=DELIVERED&page=0&size=20&sort=requestedAt,desc`

Resposta `200`: objeto paginado do Spring Data (`Page`), com os registros em `content` e informações de paginação.

O resumo do remetente inclui o `confirmationCode`, permitindo acompanhar e compartilhar o código necessário à confirmação da entrega.

Erros:

- `401` sem token
- `403` usuário sem autorização para consultar o histórico solicitado

### Regras gerais do ciclo de vida

- A transição automática de `CONFIRMED` para `IN_TRANSIT` ocorre quando a carona vinculada entra em `IN_PROGRESS`.
- O status `DELIVERED` exige confirmação por código válido, respeitadas as condições específicas de frete exclusivo.
- As alterações de status são registradas no histórico da encomenda.
- Os históricos são paginados e aceitam filtros por status e período.
- O código de confirmação é informação restrita ao remetente; não deve ser disponibilizado no histórico do motorista.
