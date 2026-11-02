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
| baggage[].description | string | nao | <= 500 caracteres |

Erros:

- `400` baggage ausente/vazio, quantity <= 0, dimensao <= 0, type fora do enum, nome invalido
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
- A soma de peso/volume contra a capacidade da carona sera validada na S2-03.
