package com.raiji.rides.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.rides.config.RidePricingProperties;
import com.raiji.rides.config.RideRulesProperties;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.dto.RideDetailResponse;
import com.raiji.rides.dto.RideSearchResult;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.geo.LatLng;
import com.raiji.rides.geo.Place;
import com.raiji.rides.pricing.SegmentPricing;
import com.raiji.rides.repository.RideRouteMatch;
import org.locationtech.jts.geom.LineString;
import com.raiji.rides.dto.RideRequest;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.dto.RideSearchFilter;
import com.raiji.rides.dto.RideSearchResponse;
import com.raiji.rides.pricing.PriceBreakdown;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.repository.RideSpecification;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RideService {

    private final RideRepository rideRepository;
    private final VehicleRepository vehicleRepository;
    private final RidePricingProperties pricing;
    private final RideRulesProperties rules;
    private final Clock clock;

    /** Distância máxima (m) entre a rota e o embarque/desembarque na busca. */
    public static final double MAX_SEARCH_RADIUS_METERS = 50_000;

    public RideService(RideRepository rideRepository, VehicleRepository vehicleRepository,
                       RidePricingProperties pricing, RideRulesProperties rules, Clock clock) {
        this.rideRepository = rideRepository;
        this.vehicleRepository = vehicleRepository;
        this.pricing = pricing;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * Publica a carona.
     *
     * @param driverIsBusiness o motorista tem conta PJ (CNPJ/MEI). Só importa com a
     *        regra {@code raiji.rules.intermunicipal-requires-business} ligada.
     */
    @Transactional
    public RideResponse create(RideRequest request, String driverSub, String driverName,
                               boolean driverIsBusiness) {
        validarDepartureDate(request.departureDate());
        validarCargoDetails(request);
        validarPreco(request.pricePerPassenger());
        validarVagas(request.type(), request.totalSeats());
        validarRegraIntermunicipal(request, driverIsBusiness);

        Vehicle vehicle = vehicleDoMotorista(request.vehicleId(), driverSub);
        validarTotalSeats(request.totalSeats(), vehicle);

        Ride ride = new Ride();
        ride.setType(request.type());
        ride.setDriverSub(driverSub);
        ride.setDriverName(driverName);
        ride.setVehicleId(vehicle.getId());
        ride.setOriginCity(request.originCity().trim());
        ride.setOriginState(request.originState().toUpperCase());
        ride.setDestinationCity(request.destinationCity().trim());
        ride.setDestinationState(request.destinationState().toUpperCase());
        ride.setDepartureDate(request.departureDate());
        ride.setPricePerPassenger(request.pricePerPassenger());
        ride.setPlatformFeePercent(pricing.platformFeePercent());
        ride.setTotalSeats(request.totalSeats());
        ride.setAvailableSeats(request.totalSeats());
        ride.setStatus(RideStatus.PLANNED);
        ride.setCargoDetails(request.cargoDetails());
        ride.setMeetingPoint(trimToNull(request.meetingPoint()));
        validarPreco(request.pricePerPackage());
        if (request.pricePerPackage() != null && request.type() == RideType.PASSENGER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pricePerPackage só vale para viagens que levam encomendas (CARGO ou MIXED).");
        }
        ride.setPricePerPackage(request.pricePerPackage());
        aplicarLocalizacao(ride, request.origin(), request.destination(), request.route());

        return RideResponse.from(rideRepository.save(ride));
    }

    /**
     * Viagem EXCLUSIVA de carga, criada quando um motorista fecha um frete com uma
     * empresa (não aparece na busca, 0 vagas, sem preço por vaga: o valor do frete
     * fica na encomenda).
     */
    @Transactional
    public Ride createPrivateCargoRide(String driverSub, String driverName, UUID vehicleId,
                                       String originCity, String originState, Place origin,
                                       String destinationCity, String destinationState, Place destination,
                                       LocalDateTime departureDate, CargoDetails cargoDetails) {
        Vehicle vehicle = vehicleDoMotorista(vehicleId, driverSub);
        Ride ride = new Ride();
        ride.setType(RideType.CARGO);
        ride.setVisibility(RideVisibility.PRIVATE);
        ride.setDriverSub(driverSub);
        ride.setDriverName(driverName);
        ride.setVehicleId(vehicle.getId());
        ride.setOriginCity(originCity.trim());
        ride.setOriginState(originState.toUpperCase(Locale.ROOT));
        ride.setDestinationCity(destinationCity.trim());
        ride.setDestinationState(destinationState.toUpperCase(Locale.ROOT));
        ride.setDepartureDate(departureDate);
        ride.setTotalSeats(0);
        ride.setAvailableSeats(0);
        ride.setStatus(RideStatus.PLANNED);
        ride.setPlatformFeePercent(pricing.platformFeePercent());
        ride.setCargoDetails(cargoDetails);
        aplicarLocalizacao(ride, origin, destination, null);
        return rideRepository.save(ride);
    }

    /** Detalhe da carona; viagem exclusiva (PRIVATE) só para o próprio motorista. */
    @Transactional(readOnly = true)
    public RideDetailResponse get(UUID rideId, String requesterSub) {
        Ride ride = rideRepository.findById(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .filter(r -> r.getVisibility() == RideVisibility.PUBLIC || r.getDriverSub().equals(requesterSub))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carona não encontrada."));
        List<LatLng> route = ride.getRoute() == null ? List.of()
                : java.util.Arrays.stream(ride.getRoute().getCoordinates())
                        .map(c -> new LatLng(c.y, c.x)).toList();
        return new RideDetailResponse(RideResponse.from(ride), route);
    }

    /** "Minhas viagens" do motorista. Sem status: as que ainda vão acontecer ou estão em andamento. */
    @Transactional(readOnly = true)
    public List<RideResponse> mine(String driverSub, List<RideStatus> statuses) {
        List<RideStatus> filtro = statuses == null || statuses.isEmpty()
                ? List.of(RideStatus.PLANNED, RideStatus.IN_PROGRESS) : statuses;
        return rideRepository.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(driverSub, filtro)
                .stream().map(RideResponse::from).toList();
    }

    /**
     * Busca pelo caminho: perto de mim (só origem), de A para B, ou embarcando e
     * descendo no meio da rota. {@code kind} = SEAT (vaga) ou PACKAGE (encomenda).
     */
    @Transactional(readOnly = true)
    public List<RideSearchResult> searchAlongRoute(String requesterSub, LatLng origin, LatLng destination,
                                                   double radiusMeters, LocalDateTime from, LocalDateTime to,
                                                   SearchKind kind) {
        if (origin == null || origin.latitude() == null || origin.longitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o ponto de embarque (latitude e longitude).");
        }
        GeoSupport.validate(origin.latitude(), origin.longitude());
        boolean hasDest = destination != null && destination.latitude() != null && destination.longitude() != null;
        if (hasDest) {
            GeoSupport.validate(destination.latitude(), destination.longitude());
        }
        if (radiusMeters <= 0 || radiusMeters > MAX_SEARCH_RADIUS_METERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O raio deve ser maior que zero e no máximo 50 km.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime inicio = from == null || from.isBefore(now) ? now : from;
        LocalDateTime fim = to == null ? now.plusMonths(3) : to;
        if (!fim.isAfter(inicio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O fim do período deve ser depois do início.");
        }
        List<String> tipos = kind == SearchKind.PACKAGE
                ? List.of(RideType.CARGO.name(), RideType.MIXED.name())
                : List.of(RideType.PASSENGER.name(), RideType.MIXED.name());

        List<RideRouteMatch> matches = rideRepository.searchAlongRoute(
                origin.latitude(), origin.longitude(), hasDest,
                hasDest ? destination.latitude() : 0, hasDest ? destination.longitude() : 0,
                radiusMeters, inicio, fim, tipos, kind == SearchKind.SEAT, requesterSub == null ? "" : requesterSub);
        if (matches.isEmpty()) {
            return List.of();
        }
        Map<UUID, Ride> rides = rideRepository.findAllById(matches.stream().map(RideRouteMatch::getId).toList())
                .stream().collect(Collectors.toMap(Ride::getId, r -> r));
        Map<UUID, Vehicle> veiculos = vehicleRepository.findAllById(
                        rides.values().stream().map(Ride::getVehicleId).distinct().toList())
                .stream().collect(Collectors.toMap(Vehicle::getId, v -> v));
        List<RideSearchResult> results = new ArrayList<>();
        for (RideRouteMatch m : matches) {
            Ride ride = rides.get(m.getId());
            if (ride == null) continue;
            double fraction = SegmentPricing.fraction(m.getPickupFraction(), hasDest ? m.getDropoffFraction() : null);
            results.add(new RideSearchResult(
                    RideResponse.from(ride),
                    (int) Math.round(m.getPickupDistance()),
                    m.getDropoffDistance() == null ? null : (int) Math.round(m.getDropoffDistance()),
                    Math.round(fraction * 1000) / 1000.0,
                    SegmentPricing.isFullRoute(fraction),
                    SegmentPricing.price(ride.getPricePerPassenger(), fraction),
                    descreverVeiculo(veiculos.get(ride.getVehicleId())),
                    SegmentPricing.price(ride.getPricePerPackage(), fraction)));
        }
        return results;
    }

    public enum SearchKind { SEAT, PACKAGE }

    private static String descreverVeiculo(Vehicle v) {
        if (v == null) return null;
        return v.getColor() == null || v.getColor().isBlank() ? v.getModel() : v.getModel() + " · " + v.getColor();
    }

    private void aplicarLocalizacao(Ride ride, Place origin, Place destination, List<LatLng> route) {
        if (origin == null && destination == null) {
            if (route != null && !route.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Para enviar a rota, informe também origin e destination.");
            }
            return; // contrato antigo: só cidades (não aparece na busca pelo caminho)
        }
        if (origin == null || destination == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe origin e destination juntos (coordenadas do mapa).");
        }
        try {
            ride.setOriginLocation(GeoSupport.point(origin.latitude(), origin.longitude()));
            ride.setDestinationLocation(GeoSupport.point(destination.latitude(), destination.longitude()));
            List<LatLng> caminho = route == null || route.size() < 2
                    ? List.of(new LatLng(origin.latitude(), origin.longitude()),
                              new LatLng(destination.latitude(), destination.longitude()))
                    : route;
            LineString linha = GeoSupport.route(caminho);
            ride.setRoute(linha);
            ride.setRouteDistanceMeters(GeoSupport.lengthMeters(linha));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        ride.setOriginAddress(trimToNull(origin.address()));
        ride.setDestinationAddress(trimToNull(destination.address()));
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Só encomendas (CARGO) = 0 vagas; com passageiros (PASSENGER/MIXED) = pelo menos 1. */
    private static void validarVagas(RideType type, Integer totalSeats) {
        int vagas = totalSeats == null ? 0 : totalSeats;
        if (type == RideType.CARGO && vagas != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Viagem só de encomendas (CARGO) não tem vagas: use MIXED para levar passageiros também.");
        }
        if (type != RideType.CARGO && vagas < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Viagem com passageiros precisa de pelo menos 1 vaga (totalSeats).");
        }
    }

    @Transactional
    public RideResponse updatePrice(UUID rideId, BigDecimal novoPreco, String driverSub) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Carona não encontrada."));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Carona pertence a outro motorista.");
        }

        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Só é possível alterar o preço em caronas PLANNED (RN08).");
        }

        validarPreco(novoPreco);

        // A comissão continua a gravada na publicação: mudar o preço não muda a regra
        ride.setPricePerPassenger(novoPreco);
        return RideResponse.from(rideRepository.save(ride));
    }

    /** Prévia para a tela de publicar: quanto fica com a plataforma e quanto o motorista recebe. */
    public PriceBreakdown previewPrice(BigDecimal price) {
        validarPreco(price);
        return PriceBreakdown.of(price, pricing.platformFeePercent());
    }

    @Transactional(readOnly = true)
    public RideSearchResponse search(RideSearchFilter filter, Pageable pageable) {
        Pageable sorted = withDefaultSort(pageable);
        Page<Ride> page = rideRepository.findAll(RideSpecification.search(filter), sorted);
        return RideSearchResponse.from(page);
    }

    private Pageable withDefaultSort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    Sort.by("departureDate").ascending());
        }
        return pageable;
    }

    private void validarDepartureDate(LocalDateTime departure) {
        LocalDateTime agora = LocalDateTime.now(clock);

        if (departure.isBefore(agora.plusMinutes(5))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "departureDate deve estar pelo menos 5 minutos no futuro.");
        }

        if (departure.isAfter(agora.plusMonths(3))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "departureDate não pode exceder 3 meses (RN10).");
        }
    }

    private static void validarPreco(BigDecimal preco) {
        if (preco != null && preco.signum() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "pricePerPassenger não pode ser negativo.");
        }
    }

    /**
     * Regra liga/desliga: carona entre cidades diferentes só para conta PJ (CNPJ/MEI).
     * Desligada por padrão ({@code raiji.rules.intermunicipal-requires-business=false}).
     */
    private void validarRegraIntermunicipal(RideRequest request, boolean driverIsBusiness) {
        if (!rules.intermunicipalRequiresBusiness() || driverIsBusiness) {
            return;
        }
        if (isIntermunicipal(request.originCity(), request.originState(),
                request.destinationCity(), request.destinationState())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Caronas entre cidades diferentes exigem conta PJ (CNPJ ou MEI).");
        }
    }

    static boolean isIntermunicipal(String originCity, String originState,
                                    String destinationCity, String destinationState) {
        return !normalizar(originState).equals(normalizar(destinationState))
                || !normalizar(originCity).equals(normalizar(destinationCity));
    }

    /** "São Paulo " e "sao paulo" são a mesma cidade. */
    private static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private void validarCargoDetails(RideRequest request) {
        boolean ehCarga = request.type() == RideType.CARGO
                || request.type() == RideType.MIXED;

        if (ehCarga && (request.cargoDetails() == null
                || !request.cargoDetails().hasCargoCapacity())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "cargoDetails com capacidade > 0 é obrigatório para type CARGO/MIXED.");
        }
    }

    private Vehicle vehicleDoMotorista(UUID vehicleId, String driverSub) {
        return vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, driverSub)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Veículo não encontrado ou não pertence ao motorista."));
    }

    private void validarTotalSeats(Integer totalSeats, Vehicle vehicle) {
        int maxSeats = vehicle.getPassengerSeats() != null
                ? vehicle.getPassengerSeats()
                : 4;

        if (totalSeats > maxSeats) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "totalSeats não pode exceder os assentos do veículo (RN03).");
        }
    }
}
