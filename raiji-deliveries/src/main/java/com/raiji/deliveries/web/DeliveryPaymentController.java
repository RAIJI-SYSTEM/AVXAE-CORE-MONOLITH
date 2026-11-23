package com.raiji.deliveries.web;

import java.util.UUID;

import com.raiji.deliveries.service.DeliveryPaymentService;
import com.raiji.rides.payment.PaymentResponse;
import com.raiji.rides.rewards.CouponChoice;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de pagamento da encomenda (portados da feature/integracao-parte-2).
 *
 * POST /api/deliveries/{id}/payment   remetente inicia o pagamento (após o aceite)
 * GET  /api/deliveries/{id}/payment   situação do pagamento (remetente ou motorista)
 */
@RestController
@PreAuthorize("isAuthenticated()")
public class DeliveryPaymentController {

    private final DeliveryPaymentService payments;

    public DeliveryPaymentController(DeliveryPaymentService payments) {
        this.payments = payments;
    }

    /** Corpo opcional: {"coupon": "<id do cupom>"} ou {"coupon": "none"}; sem corpo mantém o cupom atual */
    @PostMapping("/api/deliveries/{id}/payment")
    public PaymentResponse startPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                        @RequestBody(required = false) CouponChoice.Body body) {
        return payments.start(id, jwt.getSubject(), CouponChoice.from(body));
    }

    @GetMapping("/api/deliveries/{id}/payment")
    public PaymentResponse paymentStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.status(id, jwt.getSubject());
    }
}
