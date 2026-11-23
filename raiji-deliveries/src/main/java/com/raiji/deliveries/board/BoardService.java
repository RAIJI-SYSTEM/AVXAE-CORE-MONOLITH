package com.raiji.deliveries.board;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RideStatus;
import com.raiji.deliveries.board.BoardDtos.BoardOfferResponse;
import com.raiji.deliveries.board.BoardDtos.BoardResponse;
import com.raiji.deliveries.board.BoardDtos.CreateBoardOffer;
import com.raiji.deliveries.board.BoardDtos.CreateBoardRequest;
import com.raiji.deliveries.board.BoardDtos.MatchingRide;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.domain.PackageDetails;
import com.raiji.deliveries.freight.FreightDtos.FreightPlace;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.deliveries.service.DeliveryCodes;
import com.raiji.deliveries.service.DeliveryNotifier;
import com.raiji.deliveries.service.Packages;
import com.raiji.rides.cargo.CargoCapacityService;
import com.raiji.rides.config.RidePricingProperties;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.geo.Place;
import com.raiji.rides.pricing.PriceBreakdown;
import com.raiji.rides.pricing.SegmentPricing;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.repository.RideRouteMatch;
import com.raiji.rides.service.RidePassengerService;
import com.raiji.rides.service.RideService;
import com.raiji.users.company.CompanyMembership;
import org.locationtech.jts.geom.Point;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mural da região: pedidos para DEPOIS (mais de 1h) ficam visíveis aos motoristas perto da saída ou
 * com carona publicada no caminho. O motorista oferece um valor — numa carona que já publicou ou numa
 * viagem nova — e o dono do pedido escolhe. O escolhido vira reserva (ou encomenda) já confirmada,
 * e daí em diante é o fluxo normal: pagamento, grupo do chat, códigos de coleta e entrega.
 */
@Service
public class BoardService {

    /** O app exige 1h; os 5 minutos de folga são o tempo de olhar as caronas achadas antes de publicar. */
    static final int MIN_LEAD_MINUTES = 55;
    static final double REGION_RADIUS_METERS = 30_000;
    static final double MAX_DETOUR_METERS = 5_000;
    /** Carona já publicada: de 1h antes a 4h depois do horário pedido (igual à busca de caronas) */
    static final int RIDE_HOURS_BEFORE = 1;
    static final int RIDE_HOURS_AFTER = 4;

    private final BoardRequestRepository requests;
    private final BoardOfferRepository offers;
    private final DeliveryRepository deliveries;
    private final RideRepository rides;
    private final RideService rideService;
    private final RidePassengerService passengers;
    private final CargoCapacityService capacity;
    private final CompanyMembership membership;
    private final DeliveryNotifier notifier;
    private final RidePricingProperties pricing;
    private final Clock clock;

    public BoardService(BoardRequestRepository requests, BoardOfferRepository offers, DeliveryRepository deliveries,
                        RideRepository rides, RideService rideService, RidePassengerService passengers,
                        CargoCapacityService capacity, CompanyMembership membership, DeliveryNotifier notifier,
                        RidePricingProperties pricing, Clock clock) {
        this.requests = requests;
        this.offers = offers;
        this.deliveries = deliveries;
        this.rides = rides;
        this.rideService = rideService;
        this.passengers = passengers;
        this.capacity = capacity;
        this.membership = membership;
        this.notifier = notifier;
        this.pricing = pricing;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ dono do pedido

    @Transactional
    public BoardResponse publish(String sub, String name, CreateBoardRequest body) {
        LocalDateTime now = now();
        if (body.desiredAt().isBefore(now.plusMinutes(MIN_LEAD_MINUTES))) {
            throw bad("O mural é para daqui a mais de 1 hora. Para sair antes, use \"Agora\".");
        }
        if (body.desiredAt().isAfter(now.plusMonths(3))) {
            throw bad("A data não pode passar de 3 meses.");
        }
        boolean pack = body.kind() == BoardKind.PACKAGE;
        if (pack && body.pkg() == null) {
            throw bad("Informe os dados do pacote.");
        }
        if (!pack && (body.pkg() != null || body.companyId() != null)) {
            throw bad("Pacote e empresa só valem para encomendas.");
        }
        if (body.companyId() != null) {
            membership.requireActiveMember(body.companyId(), sub);
        }
        BoardRequest b = new BoardRequest();
        b.setKind(body.kind());
        b.setRequesterSub(sub);
        b.setRequesterName(name == null || name.isBlank() ? "Usuário" : name.trim());
        b.setCompanyId(body.companyId());
        FreightPlace o = body.origin();
        FreightPlace d = body.destination();
        b.setOriginCity(o.city().trim());
        b.setOriginState(o.state().toUpperCase(Locale.ROOT));
        b.setOriginLocation(GeoSupport.point(o.latitude(), o.longitude()));
        b.setOriginAddress(o.address());
        b.setDestinationCity(d.city().trim());
        b.setDestinationState(d.state().toUpperCase(Locale.ROOT));
        b.setDestinationLocation(GeoSupport.point(d.latitude(), d.longitude()));
        b.setDestinationAddress(d.address());
        b.setDesiredAt(body.desiredAt());
        b.setNotes(body.notes() == null || body.notes().isBlank() ? null : body.notes().trim());
        b.setPkg(pack ? Packages.from(body.pkg()) : null);
        return one(requests.save(b), sub);
    }

    @Transactional(readOnly = true)
    public List<BoardResponse> mine(String sub) {
        List<UUID> companies = membership.companyIdsOf(sub);
        return view(requests.findMine(sub, companies.isEmpty() ? List.of(new UUID(0, 0)) : companies), sub, Map.of());
    }

    /** Dono (ou membro da empresa) vê tudo; motorista vê enquanto está aberto ou se fez oferta. */
    @Transactional(readOnly = true)
    public BoardResponse get(UUID id, String viewerSub, boolean driver) {
        BoardRequest b = requests.findById(id).filter(x -> x.getDeletedAt() == null).orElseThrow(BoardService::notFound);
        if (!owns(b, viewerSub)) {
            boolean offered = offersOf(List.of(id)).stream().anyMatch(o -> o.getDriverSub().equals(viewerSub));
            if (!offered && !(driver && b.statusAt(now()) == BoardStatus.OPEN)) {
                throw notFound();
            }
        }
        return one(b, viewerSub);
    }

    @Transactional(readOnly = true)
    public List<BoardOfferResponse> offersFor(UUID id, String sub) {
        BoardRequest b = requests.findById(id).filter(x -> x.getDeletedAt() == null).orElseThrow(BoardService::notFound);
        requireOwner(b, sub);
        return viewOffers(offersOf(List.of(id)).stream().filter(o -> o.getStatus() != BoardOfferStatus.WITHDRAWN).toList());
    }

    @Transactional
    public BoardResponse cancel(UUID id, String sub) {
        BoardRequest b = requests.findByIdForUpdate(id).orElseThrow(BoardService::notFound);
        requireOwner(b, sub);
        if (b.getStatus() != BoardStatus.OPEN) {
            throw conflict("Este pedido não está mais no mural.");
        }
        b.setStatus(BoardStatus.CANCELLED);
        b.setClosedAt(Instant.now(clock));
        declinePending(b, null, "O pedido para " + b.getDestinationCity() + " foi retirado do mural.");
        return one(requests.save(b), sub);
    }

    /** O dono escolhe uma oferta; as outras são recusadas. */
    @Transactional
    public BoardResponse accept(UUID id, UUID offerId, String sub) {
        BoardRequest b = requests.findByIdForUpdate(id).orElseThrow(BoardService::notFound);
        requireOwner(b, sub);
        requireOpen(b);
        BoardOffer offer = offers.findById(offerId)
                .filter(o -> o.getRequestId().equals(id) && o.getStatus() == BoardOfferStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Esta oferta não está mais disponível."));

        UUID rideId = offer.getRideId();
        if (rideId == null) {
            rideId = newRide(b, offer).getId();
        } else {
            requireRideFits(b, offer.getRideId(), offer.getDriverSub(), true);
        }
        Place pickup = offer.getRideId() == null ? null : place(b.getOriginLocation(), b.getOriginAddress());
        Place dropoff = offer.getRideId() == null ? null : place(b.getDestinationLocation(), b.getDestinationAddress());
        if (b.getKind() == BoardKind.SEAT) {
            b.setBookingId(passengers.confirmFromBoard(rideId, b.getRequesterSub(), b.getRequesterName(),
                    offer.getPrice(), pickup, dropoff).getId());
        } else {
            b.setDeliveryId(newDelivery(b, rideId, offer).getId());
        }
        b.setRideId(rideId);
        b.setStatus(BoardStatus.ACCEPTED);
        b.setAcceptedOfferId(offer.getId());
        b.setClosedAt(Instant.now(clock));
        offer.setStatus(BoardOfferStatus.ACCEPTED);
        offers.save(offer);
        notifier.driver(offer.getDriverSub(), "BOARD_OFFER_ACCEPTED", rideId, b.getId(),
                "Sua oferta de " + money(offer.getPrice()) + " para " + b.getDestinationCity()
                        + " foi escolhida. Já está nas suas viagens.");
        declinePending(b, offer.getId(), "Outra oferta foi escolhida para o pedido até " + b.getDestinationCity() + ".");
        return one(requests.save(b), sub);
    }

    // ------------------------------------------------------------------ motorista

    /** Mural do motorista: perto da posição dele (se informada) ou no caminho das caronas que publicou. */
    @Transactional(readOnly = true)
    public List<BoardResponse> region(String driverSub, Double lat, Double lng) {
        boolean hasPosition = lat != null && lng != null;
        if (hasPosition) {
            try {
                GeoSupport.validate(lat, lng);
            } catch (IllegalArgumentException e) {
                throw bad(e.getMessage());
            }
        }
        List<BoardMatch> found = requests.findForDriver(driverSub, hasPosition, hasPosition ? lat : 0, hasPosition ? lng : 0,
                REGION_RADIUS_METERS, MAX_DETOUR_METERS, now());
        Map<UUID, BoardMatch> matches = found.stream().collect(Collectors.toMap(BoardMatch::getId, Function.identity()));
        Map<UUID, BoardRequest> byId = requests.findAllById(matches.keySet()).stream()
                .collect(Collectors.toMap(BoardRequest::getId, Function.identity()));
        List<UUID> companies = membership.companyIdsOf(driverSub);
        return view(found.stream().map(m -> byId.get(m.getId())).filter(Objects::nonNull)
                .filter(b -> b.getCompanyId() == null || !companies.contains(b.getCompanyId())).toList(), driverSub, matches);
    }

    /** Pedidos em que o motorista se ofereceu (aguardando, escolhido ou recusado). */
    @Transactional(readOnly = true)
    public List<BoardResponse> offered(String driverSub) {
        List<UUID> ids = offers.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(driverSub,
                        List.of(BoardOfferStatus.PENDING, BoardOfferStatus.ACCEPTED, BoardOfferStatus.DECLINED)).stream()
                .map(BoardOffer::getRequestId).distinct().limit(100).toList();
        Map<UUID, BoardRequest> byId = requests.findAllById(ids).stream()
                .collect(Collectors.toMap(BoardRequest::getId, Function.identity()));
        return view(ids.stream().map(byId::get).filter(Objects::nonNull).toList(), driverSub, Map.of());
    }

    @Transactional
    public BoardOfferResponse offer(UUID id, String driverSub, String driverName, CreateBoardOffer body) {
        BoardRequest b = requests.findByIdForUpdate(id).orElseThrow(BoardService::notFound);
        if (owns(b, driverSub)) {
            throw conflict("Você não pode se oferecer para o seu próprio pedido.");
        }
        requireOpen(b);
        if ((body.rideId() == null) == (body.vehicleId() == null)) {
            throw bad("Escolha uma carona sua ou o veículo de uma viagem nova.");
        }
        if (offers.existsByRequestIdAndDriverSubAndStatus(id, driverSub, BoardOfferStatus.PENDING)) {
            throw conflict("Você já tem uma oferta neste pedido.");
        }
        if (body.rideId() != null) {
            requireRideFits(b, body.rideId(), driverSub, false);
        } else {
            rideService.requireVehicle(body.vehicleId(), driverSub);
        }
        BoardOffer o = new BoardOffer();
        o.setRequestId(id);
        o.setDriverSub(driverSub);
        o.setDriverName(driverName);
        o.setPrice(body.price());
        o.setRideId(body.rideId());
        o.setVehicleId(body.vehicleId());
        o.setMessage(body.message() == null || body.message().isBlank() ? null : body.message().trim());
        o = offers.save(o);
        String text = (driverName == null ? "Um motorista" : driverName) + " ofereceu " + money(body.price())
                + (b.getKind() == BoardKind.SEAT ? " pela sua carona até " : " para levar sua encomenda até ")
                + b.getDestinationCity() + ".";
        if (b.getCompanyId() != null) {
            notifier.company(b.getCompanyId(), "BOARD_OFFER", null, id, text);
        } else {
            notifier.passenger(b.getRequesterSub(), "BOARD_OFFER", null, id, text);
        }
        return viewOffers(List.of(o)).get(0);
    }

    @Transactional
    public void withdraw(UUID id, UUID offerId, String driverSub) {
        // trava o pedido: não dá para retirar e ser escolhido ao mesmo tempo
        requests.findByIdForUpdate(id).orElseThrow(BoardService::notFound);
        BoardOffer o = offers.findById(offerId)
                .filter(x -> x.getRequestId().equals(id) && x.getDriverSub().equals(driverSub)
                        && x.getStatus() == BoardOfferStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Oferta não encontrada."));
        o.setStatus(BoardOfferStatus.WITHDRAWN);
        offers.save(o);
    }

    // ------------------------------------------------------------------ fechamento

    private Ride newRide(BoardRequest b, BoardOffer offer) {
        Place origin = place(b.getOriginLocation(), b.getOriginAddress());
        Place destination = place(b.getDestinationLocation(), b.getDestinationAddress());
        if (b.getKind() == BoardKind.SEAT) {
            return rideService.createPrivateSeatRide(offer.getDriverSub(), offer.getDriverName(), offer.getVehicleId(),
                    b.getOriginCity(), b.getOriginState(), origin, b.getDestinationCity(), b.getDestinationState(), destination,
                    b.getDesiredAt(), offer.getPrice());
        }
        PackageDetails p = b.getPkg();
        CargoDetails cargo = new CargoDetails();
        cargo.setMaxWeightKg(p.getWeightKg());
        cargo.setMaxLengthCm(BigDecimal.valueOf(p.getLengthCm()));
        cargo.setMaxWidthCm(BigDecimal.valueOf(p.getWidthCm()));
        cargo.setMaxHeightCm(BigDecimal.valueOf(p.getHeightCm()));
        return rideService.createPrivateCargoRide(offer.getDriverSub(), offer.getDriverName(), offer.getVehicleId(),
                b.getOriginCity(), b.getOriginState(), origin, b.getDestinationCity(), b.getDestinationState(), destination,
                b.getDesiredAt(), cargo);
    }

    private Delivery newDelivery(BoardRequest b, UUID rideId, BoardOffer offer) {
        Ride ride = rides.findById(rideId).orElseThrow(BoardService::notFound);
        Delivery d = new Delivery();
        d.setRideId(rideId);
        d.setSenderSub(b.getRequesterSub());
        d.setSenderName(b.getCompanyId() == null ? b.getRequesterName() : membership.displayName(b.getCompanyId()));
        d.setCompanyId(b.getCompanyId());
        d.setPkg(b.getPkg().copy());
        d.setPickupLocation(b.getOriginLocation());
        d.setPickupAddress(b.getOriginAddress());
        d.setDropoffLocation(b.getDestinationLocation());
        d.setDropoffAddress(b.getDestinationAddress());
        d.setFullRoute(offer.getRideId() == null);
        d.setPrice(offer.getPrice());
        d.setPlatformFeePercent(ride.getPlatformFeePercent());
        d.setPickupCode(DeliveryCodes.pickupCode());
        d.setDeliveryKeyword(DeliveryCodes.keyword());
        d.confirm();
        return deliveries.save(d);
    }

    /** A carona é do motorista, está aberta, passa pelo caminho no sentido certo, no horário e com espaço. */
    private void requireRideFits(BoardRequest b, UUID rideId, String driverSub, boolean lock) {
        Ride ride = (lock ? rides.findByIdForUpdate(rideId) : rides.findById(rideId))
                .filter(r -> r.getDeletedAt() == null && r.getDriverSub().equals(driverSub))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carona não encontrada."));
        boolean seat = b.getKind() == BoardKind.SEAT;
        if (ride.getVisibility() != RideVisibility.PUBLIC || !ride.openForBookings() || ride.getStatus() != RideStatus.PLANNED
                || !ride.getDepartureDate().isAfter(now())) {
            throw conflict("Esta carona não aceita mais pedidos.");
        }
        if (seat ? !ride.carriesPassengers() : !ride.carriesPackages()) {
            throw conflict(seat ? "Esta carona não leva passageiros." : "Esta carona não leva encomendas.");
        }
        if (seat && (ride.getAvailableSeats() == null || ride.getAvailableSeats() <= 0)) {
            throw conflict("Não há mais vagas nesta carona.");
        }
        LocalDateTime at = b.getDesiredAt();
        if (ride.getDepartureDate().isBefore(at.minusHours(RIDE_HOURS_BEFORE))
                || ride.getDepartureDate().isAfter(at.plusHours(RIDE_HOURS_AFTER))) {
            throw conflict("O horário desta carona não combina com o pedido.");
        }
        RideRouteMatch m = rides.locateOnRoute(rideId, b.getOriginLocation().getY(), b.getOriginLocation().getX(),
                        b.getDestinationLocation().getY(), b.getDestinationLocation().getX())
                .orElseThrow(() -> conflict("Esta carona não tem rota no mapa."));
        if (m.getPickupDistance() > MAX_DETOUR_METERS || m.getDropoffDistance() > MAX_DETOUR_METERS
                || m.getDropoffFraction() <= m.getPickupFraction()) {
            throw conflict("Esta carona não passa pelo caminho do pedido.");
        }
        if (!seat) {
            PackageDetails p = b.getPkg();
            capacity.assertItemDimensions(ride, p.getLengthCm(), p.getWidthCm(), p.getHeightCm());
            capacity.assertFits(ride, Packages.load(p), null);
        }
    }

    private void declinePending(BoardRequest b, UUID except, String message) {
        for (BoardOffer o : offers.findByRequestIdAndStatus(b.getId(), BoardOfferStatus.PENDING)) {
            if (o.getId().equals(except)) continue;
            o.setStatus(BoardOfferStatus.DECLINED);
            offers.save(o);
            notifier.driver(o.getDriverSub(), "BOARD_OFFER_DECLINED", null, b.getId(), message);
        }
    }

    // ------------------------------------------------------------------ apresentação

    private BoardResponse one(BoardRequest b, String viewerSub) {
        return view(List.of(b), viewerSub, Map.of()).get(0);
    }

    private List<BoardResponse> view(List<BoardRequest> list, String viewer, Map<UUID, BoardMatch> matches) {
        if (list.isEmpty()) return List.of();
        LocalDateTime now = now();
        Map<UUID, List<BoardOffer>> byRequest = offersOf(list.stream().map(BoardRequest::getId).toList()).stream()
                .collect(Collectors.groupingBy(BoardOffer::getRequestId));
        Map<UUID, Ride> matching = ridesById(matches.values().stream().map(BoardMatch::getRideId).toList());
        return list.stream().map(b -> {
            List<BoardOffer> all = byRequest.getOrDefault(b.getId(), List.of());
            BoardOffer my = all.stream().filter(o -> o.getDriverSub().equals(viewer) && o.getStatus() != BoardOfferStatus.WITHDRAWN)
                    .max(java.util.Comparator.comparing(BoardOffer::getCreatedAt, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                    .orElse(null);
            return response(b, viewer, now, all.stream().filter(o -> o.getStatus() == BoardOfferStatus.PENDING).count(),
                    my == null ? null : viewOffers(List.of(my)).get(0), matches.get(b.getId()), matching);
        }).toList();
    }

    private BoardResponse response(BoardRequest b, String viewer, LocalDateTime now, long pending, BoardOfferResponse my,
                                   BoardMatch match, Map<UUID, Ride> matching) {
        boolean mine = owns(b, viewer);
        PackageDetails p = b.getPkg();
        Point o = b.getOriginLocation();
        Point d = b.getDestinationLocation();
        Ride r = match == null || match.getRideId() == null ? null : matching.get(match.getRideId());
        return new BoardResponse(b.getId(), b.getKind(), b.statusAt(now), mine,
                mine ? b.getRequesterName() : firstName(b.getRequesterName()), b.getCompanyId(),
                b.getCompanyId() == null ? null : membership.displayName(b.getCompanyId()),
                b.getOriginCity(), b.getOriginState(), coord(o.getY(), mine), coord(o.getX(), mine), mine ? b.getOriginAddress() : null,
                b.getDestinationCity(), b.getDestinationState(), coord(d.getY(), mine), coord(d.getX(), mine),
                mine ? b.getDestinationAddress() : null, b.getDesiredAt(),
                (int) Math.round(GeoSupport.haversine(o.getY(), o.getX(), d.getY(), d.getX())),
                match == null || match.getDistance() == null ? null : (int) Math.round(match.getDistance() / 100) * 100,
                r == null ? null : new MatchingRide(r.getId(), r.getOriginCity(), r.getDestinationCity(), r.getDepartureDate()),
                b.getNotes(), p == null ? null : p.getSize(), p == null ? null : p.getDescription(), p == null ? null : p.getWeightKg(),
                p == null ? null : p.getLengthCm(), p == null ? null : p.getWidthCm(), p == null ? null : p.getHeightCm(),
                p == null ? null : p.isFragile(), mine && p != null ? p.getRecipientName() : null,
                mine && p != null ? p.getRecipientPhone() : null,
                mine ? pending : 0, my, mine ? b.getRideId() : null, mine ? b.getBookingId() : null,
                mine ? b.getDeliveryId() : null, b.getCreatedAt(), b.getClosedAt());
    }

    private List<BoardOfferResponse> viewOffers(List<BoardOffer> list) {
        Map<UUID, Ride> ridesOf = ridesById(list.stream().map(BoardOffer::getRideId).toList());
        Map<UUID, String> vehicles = rideService.describeVehicles(list.stream()
                .map(o -> o.getRideId() == null ? o.getVehicleId()
                        : ridesOf.containsKey(o.getRideId()) ? ridesOf.get(o.getRideId()).getVehicleId() : null)
                .filter(Objects::nonNull).distinct().toList());
        return list.stream().map(o -> {
            Ride r = o.getRideId() == null ? null : ridesOf.get(o.getRideId());
            return new BoardOfferResponse(o.getId(), o.getRequestId(), o.getDriverSub(), o.getDriverName(),
                    vehicles.get(r == null ? o.getVehicleId() : r.getVehicleId()), o.getPrice(),
                    PriceBreakdown.of(o.getPrice(), r == null ? pricing.platformFeePercent() : r.getPlatformFeePercent()).driverReceives(),
                    o.getMessage(), o.getRideId() == null, o.getRideId(), r == null ? null : r.getDepartureDate(),
                    o.getStatus(), o.getCreatedAt());
        }).toList();
    }

    private Map<UUID, Ride> ridesById(Collection<UUID> ids) {
        return rides.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Ride::getId, Function.identity()));
    }

    private List<BoardOffer> offersOf(Collection<UUID> ids) {
        return offers.findByRequestIdInAndDeletedAtIsNullOrderByPriceAsc(ids);
    }

    /** ~1 km: o bastante para o motorista saber a região, sem entregar o endereço. */
    private static double coord(double v, boolean exact) {
        return exact ? v : Math.round(v * 100) / 100.0;
    }

    private static String firstName(String name) {
        return name == null ? "Usuário" : name.trim().split("\\s+")[0];
    }

    private static Place place(Point p, String address) {
        return new Place(p.getY(), p.getX(), address);
    }

    private static String money(BigDecimal v) {
        return "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%.2f", v);
    }

    private boolean owns(BoardRequest b, String sub) {
        return b.getRequesterSub().equals(sub) || (b.getCompanyId() != null && membership.isActiveMember(b.getCompanyId(), sub));
    }

    private void requireOwner(BoardRequest b, String sub) {
        if (!owns(b, sub)) throw notFound();
    }

    private void requireOpen(BoardRequest b) {
        BoardStatus s = b.statusAt(now());
        if (s == BoardStatus.EXPIRED) throw conflict("O horário deste pedido já passou.");
        if (s != BoardStatus.OPEN) throw conflict("Este pedido não está mais no mural.");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado.");
    }
}
