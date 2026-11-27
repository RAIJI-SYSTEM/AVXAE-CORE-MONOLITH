package com.raiji.web.audit;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionMapperTest {

    private final AuditActionMapper mapper = new AuditActionMapper();
    private final UUID ride = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();

    @Test
    void createsRide() {
        AuditActionMapper.Target target = mapper.resolve("POST", "/api/rides", null);
        assertThat(target.action()).isEqualTo("RIDE_CREATED");
        assertThat(target.entityType()).isEqualTo("HTTP");
        assertThat(target.entityId()).isNull();
    }

    @Test
    void rideLifecycleActions() {
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/cancel", null).action())
                .isEqualTo("RIDE_CANCELLED");
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/start", null).action())
                .isEqualTo("RIDE_STARTED");
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/finish", null).action())
                .isEqualTo("RIDE_FINISHED");
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/close-bookings", null).action())
                .isEqualTo("RIDE_BOOKINGS_CLOSED");
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/cancel", null).entityType())
                .isEqualTo("RIDE");
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/cancel", null).entityId())
                .isEqualTo(ride);
    }

    @Test
    void bookingActions() {
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/requests", null).action())
                .isEqualTo("BOOKING_REQUESTED");
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/requests/accept-all", null).action())
                .isEqualTo("BOOKINGS_ACCEPTED_ALL");

        AuditActionMapper.Target accepted = mapper.resolve(
                "PATCH", "/api/rides/" + ride + "/requests/" + request, "ACCEPT");
        assertThat(accepted.action()).isEqualTo("BOOKING_ACCEPTED");
        assertThat(accepted.entityType()).isEqualTo("RIDE_REQUEST");
        assertThat(accepted.entityId()).isEqualTo(request);

        assertThat(mapper.resolve("PATCH", "/api/rides/" + ride + "/requests/" + request, "REJECT").action())
                .isEqualTo("BOOKING_REJECTED");
        assertThat(mapper.resolve("PATCH", "/api/rides/" + ride + "/requests/" + request, null).action())
                .isEqualTo("BOOKING_ACCEPTED");
        assertThat(mapper.resolve("DELETE", "/api/rides/" + ride + "/requests/" + request, null).action())
                .isEqualTo("BOOKING_CANCELLED");
        assertThat(mapper.resolve("PUT", "/api/rides/" + ride + "/requests/" + request + "/baggage", null).action())
                .isEqualTo("BAGGAGE_UPDATED");
        assertThat(mapper.resolve("PATCH", "/api/rides/" + ride + "/requests/" + request + "/baggage", null).action())
                .isEqualTo("BAGGAGE_DECIDED");
        assertThat(mapper.resolve("POST", "/api/rides/" + ride + "/requests/" + request + "/payment", null).action())
                .isEqualTo("PAYMENT_STARTED");
    }

    @Test
    void payoutDeliveryFreightAndWebhook() {
        assertThat(mapper.resolve("POST", "/api/admin/payouts", null).action())
                .isEqualTo("PAYOUT_REGISTERED");
        assertThat(mapper.resolve("POST", "/api/admin/payouts", null).entityType())
                .isEqualTo("PAYOUT");
        assertThat(mapper.resolve("PUT", "/api/drivers/me/pix-key", null).action())
                .isEqualTo("PIX_KEY_UPDATED");
        assertThat(mapper.resolve("POST", "/api/deliveries", null).action())
                .isEqualTo("DELIVERY_CREATED");
        assertThat(mapper.resolve("PATCH", "/api/deliveries/" + ride, null).action())
                .isEqualTo("DELIVERY_UPDATED");
        assertThat(mapper.resolve("DELETE", "/api/deliveries/" + ride, null).action())
                .isEqualTo("DELIVERY_CANCELLED");
        assertThat(mapper.resolve("POST", "/api/freights", null).action())
                .isEqualTo("FREIGHT_CREATED");
        assertThat(mapper.resolve("PUT", "/api/freights/" + ride, null).action())
                .isEqualTo("FREIGHT_UPDATED");
        assertThat(mapper.resolve("POST", "/api/public/stripe/webhook", null).action())
                .isEqualTo("PAYMENT_WEBHOOK");
    }

    @Test
    void fallbackAndInvalidInput() {
        assertThat(mapper.resolve("GET", "/api/unknown/path", null).action())
                .isEqualTo("HTTP_GET");
        assertThat(mapper.resolve(null, null, null).action())
                .isEqualTo("HTTP_");
        assertThat(mapper.resolve("POST", "/api/rides/not-a-uuid/requests", null).entityId())
                .isNull();
    }
}
