package com.raiji.deliveries.service;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.deliveries.Delivery;
import com.raiji.deliveries.DeliveryStatusHistory;
import com.raiji.deliveries.PackageDetails;
import com.raiji.deliveries.dto.*;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.deliveries.repository.DeliveryStatusHistoryRepository;
import com.raiji.rides.cargo.CargoCapacityService;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.geo.Place;
import com.raiji.rides.pricing.SegmentPricing;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.repository.RideRouteMatch;
import com.raiji.users.company.CompanyMembership;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.raiji.deliveries.DeliveryMode;
import com.raiji.rides.cargo.CargoLoad;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * Encomendas como espaço de carga numa carona.
 *
 * PENDING -> CONFIRMED -> IN_TRANSIT -> DELIVERED
 * Recusa/cancelamento -> CANCELLED.
 */
@Service
public class DeliveryService {

    static final double MAX_DETOUR_METERS = 5_000;

    private final DeliveryRepository deliveries;
    private final DeliveryStatusHistoryRepository history;
    private final RideRepository rides;
    private final CargoCapacityService capacity;
    private final CompanyMembership membership;
    private final DeliveryNotifier notifier;
    private final Clock clock;
    private final TrackingCodeGenerator trackingCodeGenerator;
    private final VehicleRepository vehicles;


    public DeliveryService(
            DeliveryRepository deliveries,
            DeliveryStatusHistoryRepository history,
            RideRepository rides,
            CargoCapacityService capacity,
            CompanyMembership membership,
            DeliveryNotifier notifier,
            Clock clock,
            TrackingCodeGenerator trackingCodeGenerator,
            VehicleRepository vehicles) {

        this.deliveries = deliveries;
        this.history = history;
        this.rides = rides;
        this.capacity = capacity;
        this.membership = membership;
        this.notifier = notifier;
        this.clock = clock;
        this.trackingCodeGenerator = trackingCodeGenerator;
        this.vehicles = vehicles;
    }

    // ---------------------------------------------------------
    // Solicitar encomenda
    // ---------------------------------------------------------

    @Transactional
    public DeliveryResponse request(
            UUID rideId,
            String senderSub,
            String tokenName,
            DeliveryCreateRequest body) {

        Ride ride = rides.findById(rideId)
                .filter(r -> r.getDeletedAt() == null
                        && r.getVisibility() == RideVisibility.PUBLIC)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Carona não encontrada."));

        if (ride.getDriverSub().equals(senderSub)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Você não pode enviar encomenda na sua própria carona.");
        }

        if (!ride.carriesPackages()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta carona não leva encomendas.");
        }

        if (!ride.openForBookings()
                || !ride.getDepartureDate().isAfter(LocalDateTime.now(clock))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta carona não está aceitando encomendas.");
        }

        if (body.companyId() != null) {
            membership.requireActiveMember(body.companyId(), senderSub);
        }

        PackageDetails pkg = Packages.from(body.pkg());

        capacity.assertItemDimensions(
                ride,
                pkg.getLengthCm(),
                pkg.getWidthCm(),
                pkg.getHeightCm());

        capacity.assertFits(ride, Packages.load(pkg), null);

        Delivery d = new Delivery();

        d.setTrackingCode(DeliveryCodes.trackingCode());
        d.setRideId(rideId);
        d.setSenderSub(senderSub);
        d.setSenderName(nome(body.senderName(), tokenName));
        d.setCompanyId(body.companyId());
        d.setPkg(pkg);
        d.setPlatformFeePercent(ride.getPlatformFeePercent());

        aplicarTrecho(ride, d, body.pickup(), body.dropoff());

        d.setPickupCode(DeliveryCodes.pickupCode());
        d.setDeliveryKeyword(DeliveryCodes.keyword());

        // Salva primeiro para obter o ID da encomenda.
        d = deliveries.save(d);

        // Histórico inicial: null -> PENDING.
        registrarHistorico(
                d,
                null,
                senderSub,
                "Encomenda solicitada.");

        notifier.driver(
                ride.getDriverSub(),
                "DELIVERY_REQUEST",
                ride.getId(),
                d.getId(),
                "Novo pedido de encomenda (" + pkg.getSize()
                        + ", " + pkg.getWeightKg().stripTrailingZeros().toPlainString()
                        + " kg) na sua carona para "
                        + ride.getDestinationCity() + ".");

        return DeliveryResponse.of(d, ride, true);
    }

    // ---------------------------------------------------------
    // Listar encomendas da carona
    // ---------------------------------------------------------

    /**
     * Visão do motorista: encomendas da carona, sem os códigos.
     */
    @Transactional(readOnly = true)
    public List<DeliveryResponse> listForDriver(UUID rideId, String driverSub) {
        Ride ride = rideDoMotorista(rideId, driverSub, false);

        return deliveries.findByRideIdAndDeletedAtIsNull(rideId)
                .stream()
                .map(d -> DeliveryResponse.of(d, ride, false))
                .toList();
    }

    // ---------------------------------------------------------
    // Aceitar ou recusar
    // ---------------------------------------------------------

    @Transactional
    public DeliveryResponse decide(
            UUID rideId,
            UUID deliveryId,
            String driverSub,
            boolean accept,
            String reason) {

        Ride ride = rideDoMotorista(rideId, driverSub, true);

        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A viagem já começou.");
        }

        Delivery d = daCarona(rideId, deliveryId);

        if (d.getStatus() != DeliveryStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Este pedido já foi decidido.");
        }

        DeliveryStatus statusAnterior = d.getStatus();
        String motivoHistorico;

        if (accept) {
            capacity.assertFits(ride, Packages.load(d.getPkg()), null);

            d.setDriverSub(driverSub);
            d.setRideId(ride.getId());

            d.confirm();

            motivoHistorico = "Encomenda aceita pelo motorista.";

            notifier.sender(
                    d,
                    "DELIVERY_ACCEPTED",
                    ride.getDriverName()
                            + " aceitou levar sua encomenda para "
                            + d.getPkg().getRecipientName()
                            + ". Na coleta, mostre o código "
                            + d.getPickupCode() + ".");

        } else {
            if (reason == null || reason.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Informe o motivo da recusa.");
            }

            motivoHistorico = reason.trim();

            d.cancel(motivoHistorico);

            notifier.sender(
                    d,
                    "DELIVERY_REJECTED",
                    ride.getDriverName()
                            + " não pode levar sua encomenda. Motivo: "
                            + motivoHistorico);
        }

        d = deliveries.save(d);

        registrarHistorico(
                d,
                statusAnterior,
                driverSub,
                motivoHistorico);

        return DeliveryResponse.of(d, ride, false);
    }
    @Transactional
    public DeliveryAcceptanceResponse accept(
            UUID deliveryId,
            String driverSub,
            DeliveryAcceptanceRequest body) {

        Delivery delivery = deliveries.findByIdForUpdate(deliveryId)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);

        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta encomenda não está mais disponível.");
        }

        if (delivery.getSenderSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não pode aceitar sua própria encomenda.");
        }

        Ride ride = null;
        Vehicle vehicle;
        DeliveryMode mode;

        if (body.rideId() != null) {
            // Modalidade ON_ROUTE.
            ride = rideDoMotorista(body.rideId(), driverSub, true);

            if (ride.getStatus() != RideStatus.PLANNED
                    || !ride.getDepartureDate().isAfter(LocalDateTime.now(clock))) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A carona já começou ou não está mais disponível.");
            }

            if (!ride.carriesPackages()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Esta carona não está habilitada para transportar encomendas.");
            }

            if (!sameText(delivery.getOriginCity(), ride.getOriginCity())
                    || !sameText(delivery.getOriginState(), ride.getOriginState())
                    || !sameText(delivery.getDestinationCity(), ride.getDestinationCity())
                    || !sameText(delivery.getDestinationState(), ride.getDestinationState())) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "A origem e o destino da encomenda não correspondem à rota da carona.");
            }

            vehicle = veiculoDoMotorista(ride.getVehicleId(), driverSub);

            if (body.vehicleId() != null && !body.vehicleId().equals(vehicle.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "O veículo informado não é o veículo utilizado pela carona.");
            }

            try {
                capacity.assertItemDimensions(
                        ride,
                        delivery.getPkg().getLengthCm(),
                        delivery.getPkg().getWidthCm(),
                        delivery.getPkg().getHeightCm());

                capacity.assertFits(
                        ride,
                        Packages.load(delivery.getPkg()),
                        null);
            } catch (ResponseStatusException exception) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        exception.getReason() == null
                                ? "A encomenda excede a capacidade disponível da carona."
                                : exception.getReason(),
                        exception);
            }

            mode = DeliveryMode.ON_ROUTE;

        } else {
            // Modalidade EXCLUSIVE.
            if (body.vehicleId() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Informe o veículo para realizar o frete exclusivo.");
            }

            vehicle = veiculoDoMotorista(body.vehicleId(), driverSub);

            Integer availableVolume = vehicle.getCargoVolumeLiters();

            if (availableVolume == null || availableVolume <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O veículo não possui capacidade de carga cadastrada.");
            }

            CargoLoad packageLoad = Packages.load(delivery.getPkg());

            if (packageLoad.volumeLiters().compareTo(
                    java.math.BigDecimal.valueOf(availableVolume)) > 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O volume da encomenda ("
                                + packageLoad.volumeLiters().stripTrailingZeros().toPlainString()
                                + " L) excede a capacidade do veículo ("
                                + availableVolume + " L).");
            }

            mode = DeliveryMode.EXCLUSIVE;
        }

        DeliveryStatus previousStatus = delivery.getStatus();

        delivery.setDriverSub(driverSub);
        delivery.setVehicleId(vehicle.getId());
        delivery.setRideId(ride == null ? null : ride.getId());
        delivery.setMode(mode);
        delivery.setAcceptedAt(clock.instant());

        delivery.confirm();

        delivery = deliveries.save(delivery);

        registrarHistorico(
                delivery,
                previousStatus,
                driverSub,
                mode == DeliveryMode.ON_ROUTE
                        ? "Encomenda aceita para transporte junto a uma carona."
                        : "Encomenda aceita para frete exclusivo.");

        notifier.sender(
                delivery,
                "DELIVERY_ACCEPTED",
                "Seu pedido foi aceito pelo motorista."
                        + (mode == DeliveryMode.EXCLUSIVE
                        ? " O transporte será realizado como frete exclusivo."
                        : " O transporte será realizado junto a uma carona."));

        return DeliveryAcceptanceResponse.of(delivery);
    }

    @Transactional
    public DeliveryAcceptanceResponse decline(
            UUID deliveryId,
            String driverSub,
            String reason) {

        Delivery delivery = deliveries.findByIdForUpdate(deliveryId)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);

        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta encomenda não está mais disponível.");
        }

        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Informe o motivo da recusa.");
        }

        String normalizedReason = reason.trim();

        registrarHistorico(
                delivery,
                DeliveryStatus.PENDING,
                driverSub,
                "Pedido recusado pelo motorista: " + normalizedReason);

        notifier.sender(
                delivery,
                "DELIVERY_REJECTED",
                "Um motorista recusou sua encomenda. Motivo: " + normalizedReason);

        return DeliveryAcceptanceResponse.of(delivery);
    }

    // ---------------------------------------------------------
    // Coletar encomenda
    // ---------------------------------------------------------

    /**
     * O motorista informa o código de coleta.
     *
     * noRollbackFor preserva tentativas erradas de código.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public DeliveryResponse pickUp(
            UUID deliveryId,
            String driverSub,
            String code) {

        Delivery d = deliveries.findById(deliveryId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);

        Ride ride = null;

        if (d.getRideId() != null) {
            // Encomenda vinculada a uma carona.
            ride = rideDoMotorista(d.getRideId(), driverSub, false);

            if (ride.getStatus() != RideStatus.PLANNED
                    && ride.getStatus() != RideStatus.IN_PROGRESS) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Esta viagem não está ativa.");
            }

        } else {
            // Encomenda de frete exclusivo.
            if (d.getDriverSub() == null
                    || !d.getDriverSub().equals(driverSub)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Esta encomenda não pertence a este motorista.");
            }
        }

        if (d.getStatus() != DeliveryStatus.CONFIRMED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta encomenda não está aguardando coleta.");
        }

        conferirCodigo(
                d,
                d.getPickupCode(),
                code,
                "Código de coleta incorreto.");

        DeliveryStatus statusAnterior = d.getStatus();

        d.pickUp();

        d = deliveries.save(d);

        registrarHistorico(
                d,
                statusAnterior,
                driverSub,
                "Coleta confirmada pelo motorista.");

        notifier.sender(
                d,
                "DELIVERY_PICKED_UP",
                "Sua encomenda foi coletada pelo motorista e está a caminho.");

        return DeliveryResponse.of(d, ride, false);
    }

    // ---------------------------------------------------------
    // Entregar encomenda
    // ---------------------------------------------------------

    /**
     * O motorista informa a palavra-chave do destinatário.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public DeliveryResponse deliver(
            UUID deliveryId,
            String driverSub,
            String keyword) {

        Delivery d = deliveries.findById(deliveryId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);

        Ride ride = null;

        if (d.getRideId() != null) {
            // Encomenda vinculada a uma carona.
            ride = rideDoMotorista(d.getRideId(), driverSub, false);

        } else {
            // Encomenda de frete exclusivo.
            if (d.getDriverSub() == null
                    || !d.getDriverSub().equals(driverSub)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Esta encomenda não pertence a este motorista.");
            }
        }

        if (d.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta encomenda não está em trânsito.");
        }

        conferirCodigo(
                d,
                d.getDeliveryKeyword(),
                keyword,
                "Palavra-chave incorreta.");

        DeliveryStatus statusAnterior = d.getStatus();

        d.deliver();

        d = deliveries.save(d);

        registrarHistorico(
                d,
                statusAnterior,
                driverSub,
                "Entrega confirmada pelo motorista.");

        notifier.sender(
                d,
                "DELIVERY_DELIVERED",
                "Sua encomenda foi entregue para "
                        + d.getPkg().getRecipientName() + ".");

        return DeliveryResponse.of(d, ride, false);
    }

    // ---------------------------------------------------------
    // Cancelar encomenda
    // ---------------------------------------------------------

    /**
     * Cancelar antes da coleta:
     * remetente, membro ativo da empresa ou motorista.
     */
    @Transactional
    public DeliveryResponse cancel(
            UUID deliveryId,
            String actorSub,
            String reason) {

        Delivery d = deliveries.findById(deliveryId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);

        Ride ride = null;

        boolean motorista;

        if (d.getRideId() != null) {
            ride = rides.findById(d.getRideId())
                    .orElseThrow(this::naoEncontrada);

            motorista = ride.getDriverSub().equals(actorSub);

        } else {
            motorista = d.getDriverSub() != null
                    && d.getDriverSub().equals(actorSub);
        }

        boolean remetente = podeVerComoRemetente(d, actorSub);

        if (!remetente && !motorista) {
            throw naoEncontrada();
        }

        if (d.getStatus() != DeliveryStatus.PENDING
                && d.getStatus() != DeliveryStatus.CONFIRMED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Só dá para cancelar antes da coleta (status atual: "
                            + d.getStatus() + ").");
        }

        String motivo = reason == null || reason.isBlank()
                ? null
                : reason.trim();

        DeliveryStatus statusAnterior = d.getStatus();

        d.cancel(motivo);

        d = deliveries.save(d);

        registrarHistorico(
                d,
                statusAnterior,
                actorSub,
                motivo != null ? motivo : "Encomenda cancelada.");

        if (motorista && !remetente) {
            notifier.sender(
                    d, "DELIVERY_CANCELLED",
                    "O motorista cancelou o transporte da sua encomenda."
                            + (motivo == null ? "" : " Motivo: " + motivo));
        } else if (remetente) {
            String motoristaSub = d.getDriverSub() != null
                    ? d.getDriverSub()
                    : (ride != null ? ride.getDriverSub() : null);

            if (motoristaSub != null) {
                notifier.driver(
                        motoristaSub,
                        "DELIVERY_CANCELLED",
                        d.getRideId(),
                        d.getId(),
                        "Uma encomenda do seu transporte foi cancelada pelo remetente."
                                + (motivo == null ? "" : " Motivo: " + motivo));
            }
        }

        return DeliveryResponse.of(d, ride, remetente);
    }

    // ---------------------------------------------------------
    // Consultar encomenda
    // ---------------------------------------------------------

    @Transactional(readOnly = true)
    public DeliveryResponse get(UUID deliveryId, String viewerSub) {
        Delivery d = deliveries.findById(deliveryId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);

        // Entregas independentes: somente o remetente.
        if (d.getRideId() == null) {
            if (!d.getSenderSub().equals(viewerSub)) {
                throw naoEncontrada();
            }

            return DeliveryResponse.of(d, null, true);
        }

        // Entregas vinculadas a caronas: mantém as regras antigas.
        Ride ride = rides.findById(d.getRideId()).orElse(null);

        boolean remetente = podeVerComoRemetente(d, viewerSub);
        boolean motorista = ride != null
                && ride.getDriverSub().equals(viewerSub);

        if (!remetente && !motorista) {
            throw naoEncontrada();
        }

        return DeliveryResponse.of(d, ride, remetente);
    }

    // ---------------------------------------------------------
    // Minhas encomendas
    // ---------------------------------------------------------

    /**
     * Minhas encomendas como remetente, incluindo as empresas.
     */
    @Transactional(readOnly = true)
    public List<DeliveryResponse> mine(String senderSub) {
        List<UUID> empresas = membership.companyIdsOf(senderSub);

        List<Delivery> lista = deliveries.findMine(
                senderSub,
                empresas.isEmpty()
                        ? List.of(new UUID(0, 0))
                        : empresas);

        Map<UUID, Ride> porId = ridesDe(lista);

        return lista.stream()
                .map(d -> DeliveryResponse.of(
                        d,
                        porId.get(d.getRideId()),
                        true))
                .toList();
    }

    // ---------------------------------------------------------
    // Encomendas transportadas pelo motorista
    // ---------------------------------------------------------

    /**
     * Encomendas que eu levo como motorista.
     */
    @Transactional(readOnly = true)
    public List<DeliveryResponse> driving(String driverSub) {
        List<UUID> minhas = rides
                .findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                        driverSub,
                        List.of(
                                RideStatus.PLANNED,
                                RideStatus.IN_PROGRESS,
                                RideStatus.COMPLETED))
                .stream()
                .map(Ride::getId)
                .toList();

        if (minhas.isEmpty()) {
            return List.of();
        }

        List<Delivery> lista = deliveries.findByRideIds(minhas);
        Map<UUID, Ride> porId = ridesDe(lista);

        return lista.stream()
                .map(d -> DeliveryResponse.of(
                        d,
                        porId.get(d.getRideId()),
                        false))
                .toList();
    }

    // ---------------------------------------------------------
    // Histórico de status
    // ---------------------------------------------------------

    /**
     * Registra uma alteração de status.
     */
    private void registrarHistorico(
            Delivery delivery,
            DeliveryStatus statusAnterior,
            String actorSub,
            String reason) {

        history.save(
                new DeliveryStatusHistory(
                        delivery.getId(),
                        statusAnterior,
                        delivery.getStatus(),
                        actorSub,
                        reason));
    }

    // ---------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------

    /**
     * Remetente: quem enviou ou membro ativo da empresa.
     */
    boolean podeVerComoRemetente(Delivery d, String sub) {
        return d.getSenderSub().equals(sub)
                || (d.getCompanyId() != null
                && membership.isActiveMember(d.getCompanyId(), sub));
    }

    private void conferirCodigo(
            Delivery d,
            String esperado,
            String digitado,
            String mensagemErro) {

        if (d.getFailedCodeAttempts()
                >= DeliveryCodes.MAX_FAILED_ATTEMPTS) {

            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas erradas. Fale com o remetente pelo chat ou com o suporte.");
        }

        if (!DeliveryCodes.matches(esperado, digitado)) {
            d.setFailedCodeAttempts(
                    d.getFailedCodeAttempts() + 1);

            deliveries.saveAndFlush(d);

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    mensagemErro);
        }
    }

    private void aplicarTrecho(
            Ride ride,
            Delivery d,
            Place pickup,
            Place dropoff) {

        d.setFullRoute(true);
        d.setPrice(ride.getPricePerPackage());

        if (pickup == null && dropoff == null) {
            return;
        }

        if (ride.getRoute() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Esta carona não tem rota no mapa: a coleta e a entrega são na origem e no destino.");
        }

        double pLat = pickup != null
                ? pickup.latitude()
                : ride.getOriginLocation().getY();

        double pLng = pickup != null
                ? pickup.longitude()
                : ride.getOriginLocation().getX();

        double dLat = dropoff != null
                ? dropoff.latitude()
                : ride.getDestinationLocation().getY();

        double dLng = dropoff != null
                ? dropoff.longitude()
                : ride.getDestinationLocation().getX();

        RideRouteMatch m = rides.locateOnRoute(
                        ride.getId(),
                        pLat,
                        pLng,
                        dLat,
                        dLng)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Rota da carona indisponível."));

        if (m.getPickupDistance() > MAX_DETOUR_METERS
                || m.getDropoffDistance() > MAX_DETOUR_METERS) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A coleta ou a entrega fica longe demais do caminho desta carona (máximo 5 km).");
        }

        if (m.getDropoffFraction() <= m.getPickupFraction()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A entrega precisa vir depois da coleta, no sentido da viagem.");
        }

        double fraction = SegmentPricing.fraction(
                m.getPickupFraction(),
                m.getDropoffFraction());

        d.setFullRoute(SegmentPricing.isFullRoute(fraction));
        d.setPrice(SegmentPricing.price(
                ride.getPricePerPackage(),
                fraction));

        if (pickup != null) {
            d.setPickupLocation(
                    GeoSupport.point(
                            pickup.latitude(),
                            pickup.longitude()));

            d.setPickupAddress(pickup.address());
        }

        if (dropoff != null) {
            d.setDropoffLocation(
                    GeoSupport.point(
                            dropoff.latitude(),
                            dropoff.longitude()));

            d.setDropoffAddress(dropoff.address());
        }
    }

    private Ride rideDoMotorista(
            UUID rideId,
            String driverSub,
            boolean lock) {

        Ride ride = (lock
                ? rides.findByIdForUpdate(rideId)
                : rides.findById(rideId))
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Esta carona é de outro motorista.");
        }

        return ride;
    }

    private Delivery daCarona(
            UUID rideId,
            UUID deliveryId) {

        return deliveries.findById(deliveryId)
                .filter(d -> d.getDeletedAt() == null
                        && d.getRideId().equals(rideId))
                .orElseThrow(this::naoEncontrada);
    }

    private Map<UUID, Ride> ridesDe(List<Delivery> lista) {
        return rides.findAllById(
                        lista.stream()
                                .map(Delivery::getRideId)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(
                        Ride::getId,
                        Function.identity()));
    }

    private static String nome(
            String informado,
            String doToken) {

        String n = informado != null && !informado.isBlank()
                ? informado
                : doToken;

        return n == null ? null : n.trim();
    }

    private ResponseStatusException naoEncontrada() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Encomenda não encontrada.");
    }
    private Vehicle veiculoDoMotorista(UUID vehicleId, String driverSub) {

        Vehicle vehicle = vehicles.findByIdForUpdate(vehicleId)
                .filter(v -> v.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Veículo não encontrado."));

        if (!vehicle.getOwnerSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Este veículo pertence a outro motorista.");
        }

        if (!vehicle.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O veículo está inativo.");
        }

        return vehicle;
    }
    // ---------------------------------------------------------
// Criar entrega independente de carona
// ---------------------------------------------------------
    @Transactional
    public DeliverySummaryResponse create(
            String senderSub,
            String tokenName,
            DeliveryRequest body) {

        String originCity = body.originCity().trim();
        String originState = body.originState().trim().toUpperCase(Locale.ROOT);
        String originAddress = body.originAddress().trim();

        String destinationCity = body.destinationCity().trim();
        String destinationState = body.destinationState().trim().toUpperCase(Locale.ROOT);
        String destinationAddress = body.destinationAddress().trim();

        // Impede uma entrega com origem e destino iguais.
        if (originCity.equalsIgnoreCase(destinationCity)
                && originState.equals(destinationState)
                && originAddress.equalsIgnoreCase(destinationAddress)) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A origem e o destino da entrega não podem ser iguais.");
        }

        PackageDetails pkg = new PackageDetails();

        pkg.setSize(null);
        pkg.setDescription(body.description());
        pkg.setWeightKg(body.weightKg());
        pkg.setLengthCm(body.lengthCm());
        pkg.setWidthCm(body.widthCm());
        pkg.setHeightCm(body.heightCm());
        pkg.setFragile(false);
        pkg.setDeclaredValue(null);
        pkg.setRecipientName(body.recipientName());
        pkg.setRecipientPhone(body.recipientContact());

        Delivery delivery = new Delivery();

        delivery.setTrackingCode(trackingCodeGenerator.generate());
        delivery.setRequestedAt(clock.instant());

        // Entrega independente: ainda não possui carona.
        delivery.setRideId(null);
        delivery.setMode(body.mode());

        delivery.setOriginCity(originCity);
        delivery.setOriginState(originState);
        delivery.setOriginAddress(originAddress);

        delivery.setDestinationCity(destinationCity);
        delivery.setDestinationState(destinationState);
        delivery.setDestinationAddress(destinationAddress);

        delivery.setPickupAddress(originAddress);
        delivery.setDropoffAddress(destinationAddress);

        delivery.setSenderSub(senderSub);
        delivery.setSenderName(nome(null, tokenName));
        delivery.setPkg(pkg);

        delivery.setPickupCode(DeliveryCodes.pickupCode());
        delivery.setDeliveryKeyword(DeliveryCodes.keyword());

        delivery.setPlatformFeePercent(java.math.BigDecimal.ZERO);
        delivery.setFullRoute(true);

        // Cotação será preenchida pela integração de distância e preço.
        delivery.setDistanceKm(null);
        delivery.setQuotedPriceCents(null);

        delivery = deliveries.save(delivery);

        registrarHistorico(
                delivery,
                null,
                senderSub,
                "Entrega independente solicitada.");

        return DeliverySummaryResponse.of(delivery);
    }

    @Transactional(readOnly = true)
    public Page<DeliverySummaryResponse> mine(
            String senderSub,
            Pageable pageable) {

        return deliveries
                .findBySenderSubAndDeletedAtIsNullOrderByRequestedAtDesc(
                        senderSub,
                        pageable)
                .map(DeliverySummaryResponse::of);
    }
    @Transactional(readOnly = true)
    public List<AvailableDeliveryResponse> available(String driverSub) {

        LocalDateTime now = LocalDateTime.now(clock);

        List<Ride> driverRides = rides
                .findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                        driverSub,
                        List.of(RideStatus.PLANNED))
                .stream()
                .filter(Ride::openForBookings)
                .filter(ride -> ride.getType() == RideType.CARGO
                        || ride.getType() == RideType.MIXED)
                .filter(ride -> ride.getDepartureDate() != null
                        && ride.getDepartureDate().isAfter(now))
                .toList();

        if (driverRides.isEmpty()) {
            return List.of();
        }

        List<Delivery> pendingDeliveries = deliveries
                .findByStatusAndRideIdIsNullAndDeletedAtIsNullOrderByRequestedAtDesc(
                        DeliveryStatus.PENDING);

        return pendingDeliveries.stream()
                .map(delivery -> {
                    List<AvailableDeliveryResponse.RideMatch> matches =
                            driverRides.stream()
                                    .filter(ride -> matchesRoute(delivery, ride))
                                    .filter(ride -> fitsCapacity(delivery, ride))
                                    .map(ride -> new AvailableDeliveryResponse.RideMatch(
                                            ride.getId(),
                                            ride.getOriginCity(),
                                            ride.getOriginState(),
                                            ride.getDestinationCity(),
                                            ride.getDestinationState(),
                                            ride.getDepartureDate()))
                                    .toList();

                    return matches.isEmpty()
                            ? null
                            : AvailableDeliveryResponse.of(delivery, matches);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private boolean matchesRoute(Delivery delivery, Ride ride) {
        return sameText(delivery.getOriginCity(), ride.getOriginCity())
                && sameText(delivery.getOriginState(), ride.getOriginState())
                && sameText(delivery.getDestinationCity(), ride.getDestinationCity())
                && sameText(delivery.getDestinationState(), ride.getDestinationState());
    }

    private boolean sameText(String first, String second) {
        return first != null
                && second != null
                && first.trim().equalsIgnoreCase(second.trim());
    }

    private boolean fitsCapacity(Delivery delivery, Ride ride) {
        PackageDetails pkg = delivery.getPkg();

        try {
            capacity.assertItemDimensions(
                    ride,
                    pkg.getLengthCm(),
                    pkg.getWidthCm(),
                    pkg.getHeightCm());

            capacity.assertFits(ride, Packages.load(pkg), null);

            return true;
        } catch (ResponseStatusException exception) {
            return false;
        }
    }
}