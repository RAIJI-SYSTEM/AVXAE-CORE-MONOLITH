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
