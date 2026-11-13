package com.raiji.deliveries.service;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.domain.PackageDetails;
import com.raiji.deliveries.dto.DeliveryCreateRequest;
import com.raiji.deliveries.dto.DeliveryResponse;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.rides.cargo.CargoCapacityService;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.geo.Place;
import com.raiji.rides.pricing.SegmentPricing;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.repository.RideRouteMatch;
import com.raiji.users.company.CompanyMembership;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Encomendas como "espaço de carga" numa carona publicada (CARGO ou MIXED):
 * <pre>
 * remetente pede (PENDING) -> motorista aceita, conferindo peso/medidas (CONFIRMED)
 * -> coleta com o código de 4 dígitos do remetente (IN_TRANSIT)
 * -> entrega com a palavra-chave do destinatário (DELIVERED)
 * </pre>
 * Recusa/cancelamento => CANCELLED. Preço = preço por encomenda da carona
 * (proporcional ao trecho, se a coleta/entrega for no meio do caminho).
 */
@Service
public class DeliveryService {

    static final double MAX_DETOUR_METERS = 5_000;

    private final DeliveryRepository deliveries;
    private final RideRepository rides;
    private final CargoCapacityService capacity;
    private final CompanyMembership membership;
    private final DeliveryNotifier notifier;
    private final Clock clock;

    public DeliveryService(DeliveryRepository deliveries, RideRepository rides, CargoCapacityService capacity,
                           CompanyMembership membership, DeliveryNotifier notifier, Clock clock) {
        this.deliveries = deliveries;
        this.rides = rides;
        this.capacity = capacity;
        this.membership = membership;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Transactional
    public DeliveryResponse request(UUID rideId, String senderSub, String tokenName, DeliveryCreateRequest body) {
        Ride ride = rides.findById(rideId)
                .filter(r -> r.getDeletedAt() == null && r.getVisibility() == RideVisibility.PUBLIC)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carona não encontrada."));
        if (ride.getDriverSub().equals(senderSub)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você não pode enviar encomenda na sua própria carona.");
        }
        if (!ride.carriesPackages()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta carona não leva encomendas.");
        }
        if (!ride.openForBookings() || !ride.getDepartureDate().isAfter(LocalDateTime.now(clock))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta carona não está aceitando encomendas.");
        }
        if (body.companyId() != null) {
            membership.requireActiveMember(body.companyId(), senderSub);
        }

        PackageDetails pkg = Packages.from(body.pkg());
        capacity.assertItemDimensions(ride, pkg.getLengthCm(), pkg.getWidthCm(), pkg.getHeightCm());
        // Confere já no pedido se ainda cabe (o aceite confere de novo, com trava)
        capacity.assertFits(ride, Packages.load(pkg), null);

        Delivery d = new Delivery();
        d.setRideId(rideId);
        d.setSenderSub(senderSub);
        d.setSenderName(nome(body.senderName(), tokenName));
        d.setCompanyId(body.companyId());
        d.setPkg(pkg);
        d.setPlatformFeePercent(ride.getPlatformFeePercent());
        aplicarTrecho(ride, d, body.pickup(), body.dropoff());
        d.setPickupCode(DeliveryCodes.pickupCode());
        d.setDeliveryKeyword(DeliveryCodes.keyword());
        d = deliveries.save(d);

        notifier.driver(ride.getDriverSub(), "DELIVERY_REQUEST", ride.getId(), d.getId(),
                "Novo pedido de encomenda (" + pkg.getSize() + ", " + pkg.getWeightKg().stripTrailingZeros().toPlainString()
                        + " kg) na sua carona para " + ride.getDestinationCity() + ".");
        return DeliveryResponse.of(d, ride, true);
    }

    /** Visão do motorista: as encomendas da carona (sem os códigos). */
    @Transactional(readOnly = true)
    public List<DeliveryResponse> listForDriver(UUID rideId, String driverSub) {
        Ride ride = rideDoMotorista(rideId, driverSub, false);
        return deliveries.findByRideIdAndDeletedAtIsNull(rideId).stream()
                .map(d -> DeliveryResponse.of(d, ride, false)).toList();
    }

    @Transactional
    public DeliveryResponse decide(UUID rideId, UUID deliveryId, String driverSub, boolean accept, String reason) {
        Ride ride = rideDoMotorista(rideId, driverSub, true);
        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A viagem já começou.");
        }
        Delivery d = daCarona(rideId, deliveryId);
        if (d.getStatus() != DeliveryStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este pedido já foi decidido.");
        }
        if (accept) {
            capacity.assertFits(ride, Packages.load(d.getPkg()), null);
            d.confirm();
            notifier.sender(d, "DELIVERY_ACCEPTED",
                    ride.getDriverName() + " aceitou levar sua encomenda para " + d.getPkg().getRecipientName()
                            + ". Na coleta, mostre o código " + d.getPickupCode() + ".");
        } else {
            if (reason == null || reason.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo da recusa.");
            }
            d.cancel(reason.trim());
            notifier.sender(d, "DELIVERY_REJECTED",
                    ride.getDriverName() + " não pode levar sua encomenda. Motivo: " + reason.trim());
        }
        return DeliveryResponse.of(deliveries.save(d), ride, false);
    }

    /**
     * Coleta: o motorista digita o código de 4 dígitos que o remetente mostrou.
     * (noRollbackFor: a tentativa errada fica registrada mesmo com o erro 400.)
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public DeliveryResponse pickUp(UUID deliveryId, String driverSub, String code) {
        Delivery d = deliveries.findById(deliveryId).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);
        Ride ride = rideDoMotorista(d.getRideId(), driverSub, false);
        if (d.getStatus() != DeliveryStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta encomenda não está aguardando coleta.");
        }
        if (ride.getStatus() != RideStatus.PLANNED && ride.getStatus() != RideStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta viagem não está ativa.");
        }
        conferirCodigo(d, d.getPickupCode(), code, "Código de coleta incorreto.");
        d.pickUp();
        notifier.sender(d, "DELIVERY_PICKED_UP",
                "Sua encomenda foi coletada por " + ride.getDriverName() + " e está a caminho.");
        return DeliveryResponse.of(deliveries.save(d), ride, false);
    }

    /** Entrega: o motorista digita a palavra-chave que o destinatário disse. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public DeliveryResponse deliver(UUID deliveryId, String driverSub, String keyword) {
        Delivery d = deliveries.findById(deliveryId).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);
        Ride ride = rideDoMotorista(d.getRideId(), driverSub, false);
        if (d.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta encomenda não está em trânsito.");
        }
        conferirCodigo(d, d.getDeliveryKeyword(), keyword, "Palavra-chave incorreta.");
        d.deliver();
        notifier.sender(d, "DELIVERY_DELIVERED",
                "Sua encomenda foi entregue para " + d.getPkg().getRecipientName() + ".");
        return DeliveryResponse.of(deliveries.save(d), ride, false);
    }

    /**
     * Cancelar antes da coleta: quem enviou (ou um membro da empresa) ou o motorista.
     * Depois de coletada, não dá para cancelar pelo app.
     */
    @Transactional
    public DeliveryResponse cancel(UUID deliveryId, String actorSub, String reason) {
        Delivery d = deliveries.findById(deliveryId).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);
        Ride ride = rides.findById(d.getRideId()).orElseThrow(this::naoEncontrada);
        boolean remetente = podeVerComoRemetente(d, actorSub);
        boolean motorista = ride.getDriverSub().equals(actorSub);
        if (!remetente && !motorista) {
            throw naoEncontrada();
        }
        if (d.getStatus() != DeliveryStatus.PENDING && d.getStatus() != DeliveryStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Só dá para cancelar antes da coleta (status atual: " + d.getStatus() + ").");
        }
        String motivo = reason == null || reason.isBlank() ? null : reason.trim();
        d.cancel(motivo);
        if (motorista && !remetente) {
            notifier.sender(d, "DELIVERY_CANCELLED",
                    "O motorista cancelou o transporte da sua encomenda." + (motivo == null ? "" : " Motivo: " + motivo));
        } else {
            notifier.driver(ride.getDriverSub(), "DELIVERY_CANCELLED", ride.getId(), d.getId(),
                    "Uma encomenda da sua carona foi cancelada pelo remetente." + (motivo == null ? "" : " Motivo: " + motivo));
        }
        return DeliveryResponse.of(deliveries.save(d), ride, remetente);
    }

    @Transactional(readOnly = true)
    public DeliveryResponse get(UUID deliveryId, String viewerSub) {
        Delivery d = deliveries.findById(deliveryId).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(this::naoEncontrada);
        Ride ride = rides.findById(d.getRideId()).orElse(null);
        boolean remetente = podeVerComoRemetente(d, viewerSub);
        boolean motorista = ride != null && ride.getDriverSub().equals(viewerSub);
        if (!remetente && !motorista) {
            throw naoEncontrada();
        }
        return DeliveryResponse.of(d, ride, remetente);
    }

    /** Minhas encomendas como remetente (inclui as das minhas empresas). */
    @Transactional(readOnly = true)
    public List<DeliveryResponse> mine(String senderSub) {
        List<UUID> empresas = membership.companyIdsOf(senderSub);
        List<Delivery> lista = deliveries.findMine(senderSub, empresas.isEmpty() ? List.of(new UUID(0, 0)) : empresas);
        Map<UUID, Ride> porId = ridesDe(lista);
        return lista.stream().map(d -> DeliveryResponse.of(d, porId.get(d.getRideId()), true)).toList();
    }

    /** Encomendas que eu levo como motorista (das minhas caronas). */
    @Transactional(readOnly = true)
    public List<DeliveryResponse> driving(String driverSub) {
        List<UUID> minhas = rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                driverSub, List.of(RideStatus.PLANNED, RideStatus.IN_PROGRESS, RideStatus.COMPLETED))
                .stream().map(Ride::getId).toList();
        if (minhas.isEmpty()) return List.of();
        List<Delivery> lista = deliveries.findByRideIds(minhas);
        Map<UUID, Ride> porId = ridesDe(lista);
        return lista.stream().map(d -> DeliveryResponse.of(d, porId.get(d.getRideId()), false)).toList();
    }

    // ------------------------------------------------------------------ apoio

    /** Remetente: quem enviou ou membro ativo da empresa em nome de quem foi enviada. */
    boolean podeVerComoRemetente(Delivery d, String sub) {
        return d.getSenderSub().equals(sub)
                || (d.getCompanyId() != null && membership.isActiveMember(d.getCompanyId(), sub));
    }

    private void conferirCodigo(Delivery d, String esperado, String digitado, String mensagemErro) {
        if (d.getFailedCodeAttempts() >= DeliveryCodes.MAX_FAILED_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas erradas. Fale com o remetente pelo chat ou com o suporte.");
        }
        if (!DeliveryCodes.matches(esperado, digitado)) {
            d.setFailedCodeAttempts(d.getFailedCodeAttempts() + 1);
            deliveries.saveAndFlush(d);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagemErro);
        }
    }

    private void aplicarTrecho(Ride ride, Delivery d, Place pickup, Place dropoff) {
        d.setFullRoute(true);
        d.setPrice(ride.getPricePerPackage());
        if (pickup == null && dropoff == null) {
            return;
        }
        if (ride.getRoute() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta carona não tem rota no mapa: a coleta e a entrega são na origem e no destino.");
        }
        double pLat = pickup != null ? pickup.latitude() : ride.getOriginLocation().getY();
        double pLng = pickup != null ? pickup.longitude() : ride.getOriginLocation().getX();
        double dLat = dropoff != null ? dropoff.latitude() : ride.getDestinationLocation().getY();
        double dLng = dropoff != null ? dropoff.longitude() : ride.getDestinationLocation().getX();
        RideRouteMatch m = rides.locateOnRoute(ride.getId(), pLat, pLng, dLat, dLng)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rota da carona indisponível."));
        if (m.getPickupDistance() > MAX_DETOUR_METERS || m.getDropoffDistance() > MAX_DETOUR_METERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A coleta ou a entrega fica longe demais do caminho desta carona (máximo 5 km).");
        }
        if (m.getDropoffFraction() <= m.getPickupFraction()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A entrega precisa vir depois da coleta, no sentido da viagem.");
        }
        double fraction = SegmentPricing.fraction(m.getPickupFraction(), m.getDropoffFraction());
        d.setFullRoute(SegmentPricing.isFullRoute(fraction));
        d.setPrice(SegmentPricing.price(ride.getPricePerPackage(), fraction));
        if (pickup != null) {
            d.setPickupLocation(GeoSupport.point(pickup.latitude(), pickup.longitude()));
            d.setPickupAddress(pickup.address());
        }
        if (dropoff != null) {
            d.setDropoffLocation(GeoSupport.point(dropoff.latitude(), dropoff.longitude()));
            d.setDropoffAddress(dropoff.address());
        }
    }

    private Ride rideDoMotorista(UUID rideId, String driverSub, boolean lock) {
        Ride ride = (lock ? rides.findByIdForUpdate(rideId) : rides.findById(rideId))
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carona não encontrada."));
        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta carona é de outro motorista.");
        }
        return ride;
    }

    private Delivery daCarona(UUID rideId, UUID deliveryId) {
        return deliveries.findById(deliveryId)
                .filter(d -> d.getDeletedAt() == null && d.getRideId().equals(rideId))
                .orElseThrow(this::naoEncontrada);
    }

    private Map<UUID, Ride> ridesDe(List<Delivery> lista) {
        return rides.findAllById(lista.stream().map(Delivery::getRideId).distinct().toList()).stream()
                .collect(Collectors.toMap(Ride::getId, Function.identity()));
    }

    private static String nome(String informado, String doToken) {
        String n = informado != null && !informado.isBlank() ? informado : doToken;
        return n == null ? null : n.trim();
    }

    private ResponseStatusException naoEncontrada() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Encomenda não encontrada.");
    }
}
