package com.raiji.rides.payment;

import java.util.UUID;

import com.raiji.rides.rewards.CouponChoice;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pagamento da reserva de vaga.
 * <ul>
 *   <li>POST: o passageiro inicia/retoma o pagamento (devolve o clientSecret do Stripe);</li>
 *   <li>GET: passageiro ou motorista consulta a situação (conferida no Stripe).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rides/{rideId}/requests/{requestId}/payment")
public class RidePaymentController {

    private final RidePaymentService service;

    public RidePaymentController(RidePaymentService service) {
        this.service = service;
    }

    /** Corpo opcional: {"coupon": "<id do cupom>"} ou {"coupon": "none"}; sem corpo mantém o cupom atual */
    @PostMapping
    public PaymentResponse start(@PathVariable UUID rideId, @PathVariable UUID requestId,
                                 @RequestBody(required = false) CouponChoice.Body body,
                                 @AuthenticationPrincipal Jwt jwt) {
        return service.start(rideId, requestId, jwt.getSubject(), CouponChoice.from(body));
    }

    @GetMapping
    public PaymentResponse status(@PathVariable UUID rideId, @PathVariable UUID requestId,
                                  @AuthenticationPrincipal Jwt jwt) {
        return service.status(rideId, requestId, jwt.getSubject());
    }
}
