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
