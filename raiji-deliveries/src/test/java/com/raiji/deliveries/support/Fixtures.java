package com.raiji.deliveries.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.PaymentStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.domain.PackageDetails;
import com.raiji.deliveries.domain.PackageSize;
import com.raiji.deliveries.dto.PackageRequest;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.geo.GeoSupport;

/** Objetos de apoio para os testes do módulo de encomendas. */
public final class Fixtures {

    private Fixtures() {
    }

    public static UUID id() {
        return UUID.randomUUID();
    }

    public static PackageDetails pkg() {
        PackageDetails p = new PackageDetails();
        p.setSize(PackageSize.MEDIUM);
        p.setDescription("Caixa");
        p.setWeightKg(new BigDecimal("2.00"));
        p.setLengthCm(50);
        p.setWidthCm(40);
        p.setHeightCm(30);
        p.setFragile(false);
        p.setRecipientName("Bia");
        p.setRecipientPhone("+5511999999999");
        return p;
    }

    public static PackageRequest pkgRequest() {
        return new PackageRequest(PackageSize.MEDIUM, "Caixa", new BigDecimal("2.00"), null, null, null,
                false, null, "Bia", "+5511999999999");
    }

    public static Ride ride() {
        Ride r = new Ride();
        r.setType(RideType.MIXED);
        r.setDriverSub("driver-1");
        r.setDriverName("Carlos");
        r.setOriginCity("São Paulo");
        r.setOriginState("SP");
        r.setDestinationCity("Campinas");
        r.setDestinationState("SP");
        r.setOriginLocation(GeoSupport.point(-23.55, -46.63));
        r.setDestinationLocation(GeoSupport.point(-22.90, -47.06));
        r.setDepartureDate(LocalDateTime.now().plusDays(1));
        r.setPricePerPackage(new BigDecimal("20.00"));
        r.setPricePerPassenger(new BigDecimal("50.00"));
        r.setPlatformFeePercent(new BigDecimal("15"));
        r.setVisibility(RideVisibility.PUBLIC);
        r.setStatus(RideStatus.PLANNED);
        r.setTotalSeats(3);
        r.setAvailableSeats(3);
        r.setMeetingPoint("Posto");
        return r;
    }

    public static Delivery delivery() {
        return delivery(UUID.randomUUID(), DeliveryStatus.PENDING);
    }

    public static Delivery delivery(UUID rideId, DeliveryStatus status) {
        Delivery d = new Delivery();
        d.setRideId(rideId);
        d.setSenderSub("sender-1");
        d.setSenderName("Ana");
        d.setPkg(pkg());
        d.setPrice(new BigDecimal("20.00"));
        d.setPlatformFeePercent(new BigDecimal("15"));
        d.setPickupCode("1234");
        d.setDeliveryKeyword("SOL-42");
        d.setPaymentStatus(PaymentStatus.UNPAID);
        if (status == DeliveryStatus.CONFIRMED) d.confirm();
        if (status == DeliveryStatus.IN_TRANSIT) {
            d.confirm();
            d.pickUp();
        }
        if (status == DeliveryStatus.DELIVERED) {
            d.confirm();
            d.pickUp();
            d.deliver();
        }
        if (status == DeliveryStatus.CANCELLED) d.cancel("teste");
        return d;
    }
}
