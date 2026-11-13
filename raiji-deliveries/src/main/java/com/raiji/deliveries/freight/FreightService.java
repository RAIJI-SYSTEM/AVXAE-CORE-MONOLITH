package com.raiji.deliveries.freight;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.domain.PackageDetails;
import com.raiji.deliveries.freight.FreightDtos.CreateFreightRequest;
import com.raiji.deliveries.freight.FreightDtos.CreateOfferRequest;
import com.raiji.deliveries.freight.FreightDtos.FreightPlace;
import com.raiji.deliveries.freight.FreightDtos.FreightResponse;
import com.raiji.deliveries.freight.FreightDtos.OfferResponse;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.deliveries.service.DeliveryCodes;
import com.raiji.deliveries.service.DeliveryNotifier;
import com.raiji.deliveries.service.Packages;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.geo.Place;
import com.raiji.rides.service.RideService;
import com.raiji.users.company.Company;
import com.raiji.users.company.CompanyMembership;
import com.raiji.users.keycloak.KeycloakAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Propostas de frete de empresas. Qualquer membro ativo da empresa cria, cancela
 * e escolhe ofertas (e aprova o pagamento). Ao fechar, o motorista ganha uma
 * viagem EXCLUSIVA (fora da busca) já com a encomenda aceita — daí em diante é o
 * fluxo normal da encomenda (código de coleta, palavra-chave de entrega).
 */
@Service
public class FreightService {

    private static final Logger log = LoggerFactory.getLogger(FreightService.class);

    static final int MIN_LEAD_MINUTES = 30;

    private final FreightRequestRepository requests;
    private final FreightOfferRepository offers;
    private final DeliveryRepository deliveries;
    private final RideService rideService;
    private final CompanyMembership membership;
    private final KeycloakAdminService keycloak;
    private final DeliveryNotifier notifier;
    private final Clock clock;

    public FreightService(FreightRequestRepository requests, FreightOfferRepository offers,
                          DeliveryRepository deliveries, RideService rideService, CompanyMembership membership,
                          KeycloakAdminService keycloak, DeliveryNotifier notifier, Clock clock) {
        this.requests = requests;
        this.offers = offers;
        this.deliveries = deliveries;
        this.rideService = rideService;
        this.membership = membership;
        this.keycloak = keycloak;
        this.notifier = notifier;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ empresa

    @Transactional
    public FreightResponse create(UUID companyId, String memberSub, CreateFreightRequest body) {
        Company company = membership.requireActiveMember(companyId, memberSub);
        LocalDateTime agora = LocalDateTime.now(clock);
        if (body.desiredDate().isBefore(agora.plusMinutes(MIN_LEAD_MINUTES))) {
            throw bad("A data desejada precisa ser daqui a pelo menos " + MIN_LEAD_MINUTES + " minutos.");
        }
        if (body.desiredDate().isAfter(agora.plusMonths(3))) {
            throw bad("A data desejada não pode passar de 3 meses.");
        }
        String alvo = body.targetDriverSub() == null || body.targetDriverSub().isBlank() ? null : body.targetDriverSub().trim();
        if (alvo != null) {
            if (body.offeredPrice() == null) {
                throw bad("Na proposta direta, informe o valor oferecido ao motorista.");
            }
            if (membership.isActiveMember(companyId, alvo)) {
                throw bad("O motorista não pode ser membro da própria empresa.");
            }
            exigirMotorista(alvo);
        }

        FreightRequest f = new FreightRequest();
        f.setCompanyId(companyId);
        f.setCreatedBySub(memberSub);
        f.setTargetDriverSub(alvo);
        aplicarLugar(f, body.origin(), true);
        aplicarLugar(f, body.destination(), false);
        f.setDesiredDate(body.desiredDate());
        f.setPkg(Packages.from(body.pkg()));
        f.setOfferedPrice(body.offeredPrice());
        f.setNotes(body.notes() == null || body.notes().isBlank() ? null : body.notes().trim());
        f = requests.save(f);

        if (alvo != null) {
            notifier.driver(alvo, "FREIGHT_PROPOSAL", null, f.getId(),
                    company.displayName() + " propôs um frete de " + f.getOriginCity() + " para "
                            + f.getDestinationCity() + " por R$ " + f.getOfferedPrice().toPlainString() + ".");
        }
        return view(f, company.displayName(), true, null);
    }

    @Transactional(readOnly = true)
    public List<FreightResponse> listForCompany(UUID companyId, String memberSub) {
        Company company = membership.requireActiveMember(companyId, memberSub);
        return requests.findByCompanyIdAndDeletedAtIsNullOrderByCreatedAtDesc(companyId).stream()
                .map(f -> view(f, company.displayName(), true, null)).toList();
    }

    /** Detalhe: membro da empresa vê tudo; motorista (alvo ou de pedido público aberto) vê o essencial. */
    @Transactional(readOnly = true)
    public FreightResponse get(UUID id, String viewerSub) {
        FreightRequest f = requests.findById(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrado);
        String nome = membership.displayName(f.getCompanyId());
        if (membership.isActiveMember(f.getCompanyId(), viewerSub)) {
            return view(f, nome, true, null);
        }
        boolean alvo = viewerSub.equals(f.getTargetDriverSub());
        boolean publicoAberto = !f.direct() && f.getStatus() == FreightStatus.OPEN;
        boolean ofertou = offers.findByRequestIdAndDeletedAtIsNullOrderByPriceAsc(id).stream()
                .anyMatch(o -> o.getDriverSub().equals(viewerSub));
        if (!alvo && !publicoAberto && !ofertou) {
            throw naoEncontrado();
        }
        return view(f, nome, false, viewerSub);
    }

    @Transactional(readOnly = true)
    public List<OfferResponse> offersFor(UUID id, String memberSub) {
        FreightRequest f = requests.findById(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrado);
        membership.requireActiveMember(f.getCompanyId(), memberSub);
        return offers.findByRequestIdAndDeletedAtIsNullOrderByPriceAsc(id).stream().map(OfferResponse::of).toList();
    }

    @Transactional
    public FreightResponse cancel(UUID id, String memberSub) {
        FreightRequest f = requests.findByIdForUpdate(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrado);
        Company company = membership.requireActiveMember(f.getCompanyId(), memberSub);
        exigirAberto(f);
        f.setStatus(FreightStatus.CANCELLED);
        f.setClosedAt(Instant.now(clock));
        for (FreightOffer o : offers.findByRequestIdAndStatus(id, OfferStatus.PENDING)) {
            o.setStatus(OfferStatus.DECLINED);
            offers.save(o);
            notifier.driver(o.getDriverSub(), "FREIGHT_OFFER_DECLINED", null, f.getId(),
                    company.displayName() + " cancelou o pedido de frete para " + f.getDestinationCity() + ".");
        }
        if (f.direct()) {
            notifier.driver(f.getTargetDriverSub(), "FREIGHT_CANCELLED", null, f.getId(),
                    company.displayName() + " cancelou a proposta de frete.");
        }
        return view(requests.save(f), company.displayName(), true, null);
    }

    /** A empresa escolhe uma oferta: fecha o frete; as outras ofertas são recusadas. */
    @Transactional
    public FreightResponse acceptOffer(UUID id, UUID offerId, String memberSub) {
        FreightRequest f = requests.findByIdForUpdate(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrado);
        Company company = membership.requireActiveMember(f.getCompanyId(), memberSub);
        exigirAberto(f);
        FreightOffer offer = offers.findById(offerId)
                .filter(o -> o.getRequestId().equals(id) && o.getStatus() == OfferStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Oferta não encontrada."));

        fechar(f, company, offer.getDriverSub(), offer.getDriverName(), offer.getVehicleId(), offer.getPrice(), memberSub);
        offer.setStatus(OfferStatus.ACCEPTED);
        offers.save(offer);
        f.setAcceptedOfferId(offer.getId());
        notifier.driver(offer.getDriverSub(), "FREIGHT_OFFER_ACCEPTED", f.getRideId(), f.getId(),
                company.displayName() + " aceitou sua oferta de R$ " + offer.getPrice().toPlainString()
                        + ". A viagem já está nas suas viagens.");
        for (FreightOffer o : offers.findByRequestIdAndStatus(id, OfferStatus.PENDING)) {
            o.setStatus(OfferStatus.DECLINED);
            offers.save(o);
            notifier.driver(o.getDriverSub(), "FREIGHT_OFFER_DECLINED", null, f.getId(),
                    company.displayName() + " escolheu outra oferta para o frete até " + f.getDestinationCity() + ".");
        }
        return view(requests.save(f), company.displayName(), true, null);
    }

    // ------------------------------------------------------------------ motorista

    /** Propostas diretas para mim, em aberto. */
    @Transactional(readOnly = true)
    public List<FreightResponse> forMe(String driverSub) {
        return requests.findByTargetDriverSubAndStatusAndDeletedAtIsNullOrderByDesiredDateAsc(driverSub, FreightStatus.OPEN)
                .stream().map(f -> view(f, membership.displayName(f.getCompanyId()), false, driverSub)).toList();
    }

    /** Pedidos públicos com a origem perto de mim. */
    @Transactional(readOnly = true)
    public List<FreightResponse> nearby(String driverSub, double lat, double lng, double radiusMeters) {
        GeoSupport.validate(lat, lng);
        if (radiusMeters <= 0 || radiusMeters > 100_000) {
            throw bad("O raio deve ser maior que zero e no máximo 100 km.");
        }
        List<UUID> minhasEmpresas = membership.companyIdsOf(driverSub);
        return requests.findOpenNearby(lat, lng, radiusMeters, LocalDateTime.now(clock)).stream()
                .filter(f -> !minhasEmpresas.contains(f.getCompanyId()))
                .map(f -> view(f, membership.displayName(f.getCompanyId()), false, driverSub)).toList();
    }

    /** Aceitar a proposta direta (escolhendo o veículo): vira viagem exclusiva + encomenda aceita. */
    @Transactional
    public FreightResponse acceptDirect(UUID id, String driverSub, String driverName, UUID vehicleId) {
        FreightRequest f = requests.findByIdForUpdate(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrado);
        if (!driverSub.equals(f.getTargetDriverSub())) {
            throw naoEncontrado();
        }
        exigirAberto(f);
        Company company = companyOf(f);
        fechar(f, company, driverSub, driverName, vehicleId, f.getOfferedPrice(), f.getCreatedBySub());
        notifier.company(f.getCompanyId(), "FREIGHT_ACCEPTED", f.getRideId(), f.getId(),
                (driverName == null ? "O motorista" : driverName) + " aceitou o frete para " + f.getDestinationCity() + ".");
        return view(requests.save(f), company.displayName(), false, driverSub);
    }

    @Transactional
    public FreightResponse declineDirect(UUID id, String driverSub, String reason) {
        FreightRequest f = requests.findByIdForUpdate(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrado);
        if (!driverSub.equals(f.getTargetDriverSub())) {
            throw naoEncontrado();
        }
        exigirAberto(f);
        f.setStatus(FreightStatus.DECLINED);
        f.setDeclineReason(reason == null || reason.isBlank() ? null : reason.trim());
        f.setClosedAt(Instant.now(clock));
        notifier.company(f.getCompanyId(), "FREIGHT_DECLINED", null, f.getId(),
                "O motorista recusou o frete para " + f.getDestinationCity() + "."
                        + (f.getDeclineReason() == null ? "" : " Motivo: " + f.getDeclineReason()));
        return view(requests.save(f), membership.displayName(f.getCompanyId()), false, driverSub);
    }

    /** Oferecer-se para um pedido público. */
    @Transactional
    public OfferResponse offer(UUID id, String driverSub, String driverName, CreateOfferRequest body) {
        FreightRequest f = requests.findByIdForUpdate(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrado);
        if (f.direct()) {
            throw naoEncontrado();
        }
        exigirAberto(f);
        if (membership.isActiveMember(f.getCompanyId(), driverSub)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você não pode se oferecer para um frete da sua empresa.");
        }
        if (offers.existsByRequestIdAndDriverSubAndStatus(id, driverSub, OfferStatus.PENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já tem uma oferta para este frete.");
        }
        FreightOffer o = new FreightOffer();
        o.setRequestId(id);
        o.setDriverSub(driverSub);
        o.setDriverName(driverName);
        o.setVehicleId(vehicleId(body.vehicleId()));
        o.setPrice(body.price());
        o.setMessage(body.message() == null || body.message().isBlank() ? null : body.message().trim());
        o = offers.save(o);
        notifier.company(f.getCompanyId(), "FREIGHT_OFFER", null, f.getId(),
                (driverName == null ? "Um motorista" : driverName) + " ofereceu R$ " + body.price().toPlainString()
                        + " para o frete até " + f.getDestinationCity() + ".");
        return OfferResponse.of(o);
    }

    @Transactional
    public void withdrawOffer(UUID id, UUID offerId, String driverSub) {
        FreightOffer o = offers.findById(offerId)
                .filter(x -> x.getRequestId().equals(id) && x.getDriverSub().equals(driverSub)
                        && x.getStatus() == OfferStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Oferta não encontrada."));
        o.setStatus(OfferStatus.WITHDRAWN);
        offers.save(o);
    }

    @Transactional(readOnly = true)
    public List<OfferResponse> myOffers(String driverSub) {
        return offers.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(driverSub,
                List.of(OfferStatus.PENDING, OfferStatus.ACCEPTED, OfferStatus.DECLINED)).stream()
                .map(OfferResponse::of).toList();
    }

    // ------------------------------------------------------------------ apoio

    /** Fecha o frete: viagem exclusiva do motorista + encomenda já aceita (em nome da empresa). */
    private void fechar(FreightRequest f, Company company, String driverSub, String driverName, UUID vehicleId,
                        BigDecimal price, String senderSub) {
        if (!f.getDesiredDate().isAfter(LocalDateTime.now(clock))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A data do frete já passou.");
        }
        PackageDetails pkg = f.getPkg();
        CargoDetails cargo = new CargoDetails();
        cargo.setMaxWeightKg(pkg.getWeightKg());
        cargo.setMaxLengthCm(BigDecimal.valueOf(pkg.getLengthCm()));
        cargo.setMaxWidthCm(BigDecimal.valueOf(pkg.getWidthCm()));
        cargo.setMaxHeightCm(BigDecimal.valueOf(pkg.getHeightCm()));
        cargo.setObservation("Frete para " + company.displayName());

        Place origem = new Place(f.getOriginLocation().getY(), f.getOriginLocation().getX(), f.getOriginAddress());
        Place destino = new Place(f.getDestinationLocation().getY(), f.getDestinationLocation().getX(), f.getDestinationAddress());
        Ride ride = rideService.createPrivateCargoRide(driverSub, driverName, vehicleId,
                f.getOriginCity(), f.getOriginState(), origem, f.getDestinationCity(), f.getDestinationState(), destino,
                f.getDesiredDate(), cargo);

        Delivery d = new Delivery();
        d.setRideId(ride.getId());
        d.setSenderSub(senderSub);
        d.setSenderName(company.displayName());
        d.setCompanyId(company.getId());
        d.setPkg(pkg.copy());
        d.setPickupLocation(ride.getOriginLocation());
        d.setPickupAddress(f.getOriginAddress());
        d.setDropoffLocation(ride.getDestinationLocation());
        d.setDropoffAddress(f.getDestinationAddress());
        d.setFullRoute(true);
        d.setPrice(price);
        d.setPlatformFeePercent(ride.getPlatformFeePercent());
        d.setPickupCode(DeliveryCodes.pickupCode());
        d.setDeliveryKeyword(DeliveryCodes.keyword());
        d.setFreightRequestId(f.getId());
        d.confirm();
        d = deliveries.save(d);

        f.setStatus(FreightStatus.ACCEPTED);
        f.setRideId(ride.getId());
        f.setDeliveryId(d.getId());
        f.setClosedAt(Instant.now(clock));
        log.info("Frete {} fechado: viagem {} encomenda {}", f.getId(), ride.getId(), d.getId());
    }

    private FreightResponse view(FreightRequest f, String companyName, boolean companyView, String driverSub) {
        long pendentes = offers.countByRequestIdAndStatus(f.getId(), OfferStatus.PENDING);
        OfferResponse minha = driverSub == null ? null
                : offers.findByRequestIdAndDeletedAtIsNullOrderByPriceAsc(f.getId()).stream()
                        .filter(o -> o.getDriverSub().equals(driverSub) && o.getStatus() != OfferStatus.WITHDRAWN)
                        .findFirst().map(OfferResponse::of).orElse(null);
        return FreightResponse.of(f, companyName, companyView, pendentes, minha);
    }

    private void aplicarLugar(FreightRequest f, FreightPlace p, boolean origem) {
        if (origem) {
            f.setOriginCity(p.city().trim());
            f.setOriginState(p.state().toUpperCase(Locale.ROOT));
            f.setOriginLocation(GeoSupport.point(p.latitude(), p.longitude()));
            f.setOriginAddress(p.address());
        } else {
            f.setDestinationCity(p.city().trim());
            f.setDestinationState(p.state().toUpperCase(Locale.ROOT));
            f.setDestinationLocation(GeoSupport.point(p.latitude(), p.longitude()));
            f.setDestinationAddress(p.address());
        }
    }

    private void exigirMotorista(String sub) {
        List<String> roles;
        try {
            roles = keycloak.effectiveRealmRoles(sub);
        } catch (RuntimeException e) {
            throw bad("Motorista não encontrado.");
        }
        if (roles == null || !roles.contains("DRIVER")) {
            throw bad("Esta pessoa não é motorista verificado no Raiji.");
        }
    }

    private static UUID vehicleId(UUID id) {
        if (id == null) throw bad("Escolha o veículo.");
        return id;
    }

    private Company companyOf(FreightRequest f) {
        // o motorista não é membro: carrega a empresa sem checar participação
        return membership.company(f.getCompanyId());
    }

    private static void exigirAberto(FreightRequest f) {
        if (f.getStatus() != FreightStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este frete não está mais em aberto.");
        }
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseStatusException naoEncontrado() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Frete não encontrado.");
    }
}
