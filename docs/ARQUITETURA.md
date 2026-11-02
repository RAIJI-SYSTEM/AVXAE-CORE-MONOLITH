# Arquitetura — decisões registradas

## S2-03 · Trava de capacidade de assentos e carga (RN03 / RN12)

### Problema

Reservas confirmadas nunca podem exceder os assentos da carona nem a
capacidade de carga (peso e volume), **mesmo sob concorrência**: dois aceites
simultâneos da última vaga não podem ambos confirmar (a issue #10 pede
explicitamente testes de concorrência com ExecutorService).

### Decisão — lock pessimista na linha da carona

`RidePassengerService.decide()` (PATCH
`/api/rides/{rideId}/requests/{requestId}`) carrega a carona com
`RideRepository.findByIdForUpdate(...)`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select r from Ride r where r.id = :id")
Optional<Ride> findByIdForUpdate(@Param("id") UUID id);
```

- `SELECT ... FOR UPDATE` serializa as decisões sobre a mesma carona: a
  segunda transação aguarda o commit da primeira e então **relê**
  `available_seats` já decrementado → responde **409** em vez de
  sobrescrever o contador.
- Escolhido pela **simplicidade** (observação da issue #10): transação curta,
  uma única linha "quente" (a carona), sem necessidade de retry
  application-level sobre `OptimisticLockException`.
- O **lock otimista** (`@Version` herdado de `AuditableEntity`) permanece
  ativo em todas as entidades como rede de segurança contra escrita perdida
  nos demais fluxos (ex.: edição de carona), mas não é a linha de defesa do
  aceite — quem garante a serialização é o `FOR UPDATE`.

### Regras de capacidade aplicadas no ACCEPT

| Trava | Regra | Falha |
|---|---|---|
| Assentos | `rides.available_seats > 0` antes de decrementar | 409 |
| Peso | Σ `weight_kg × quantity` das bagagens **CONFIRMED** (+ as do candidato) ≤ `rides.cargo_max_weight_kg` | 400 |
| Volume | Σ `length_cm × width_cm × height_cm × quantity ÷ 1000` (litros) ≤ `vehicles.cargo_volume_liters` do veículo da carona | 400 |

- Peso e volume só são verificados **se existir** limite declarado
  (`cargoDetails.maxWeightKg > 0` na carona / `cargoVolumeLiters > 0` no
  veículo) — sem limite declarado, a trava é ignorada (caronas e veículos
  antigos continuam válidos).
- `weightKg` é **opcional** na declaração de bagagem (contrato da S2-01
  permanece retrocompatível); item sem peso contribui 0 kg na soma.
- Solicitações PENDING **não** reservam capacidade: entram na soma apenas as
  bagagens CONFIRMED e as da solicitação em aceite.
- Exceder o limite é erro de requisição (**400**, critério de aceite da
  issue); esgotar assentos é conflito de estado (**409**).

### Consistência

O `decide()` roda em uma única transação: lock → validações (dono, PENDING,
vagas, peso, volume) → decremento de vagas → `confirm()`/`cancel()` → evento
de decisão (notificação). Qualquer falha faz **rollback completo** — nenhuma
reserva fica em estado inconsistente e `available_seats` nunca fica negativo.

### Testes

- `CapacityLockApiIT.confirmacoesSimultaneasNaoEstouramLotacao`:
  `ExecutorService` + `CyclicBarrier` disparam 2 aceites simultâneos sobre a
  última vaga → exatamente 1× 200 e 1× 409, `available_seats = 0`, 1 reserva
  CONFIRMED.
- Limites: peso/volume **exatamente no limite** (aceito) e **1 acima**
  (400), além da soma acumulada entre passageiros CONFIRMED.
- Unitários (`RidePassengerServiceTest`): mesmas fronteiras com mocks +
  verificação do uso de `findByIdForUpdate`.
