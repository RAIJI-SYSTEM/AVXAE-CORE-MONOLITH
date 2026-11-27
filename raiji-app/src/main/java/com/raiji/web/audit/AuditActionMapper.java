package com.raiji.web.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Traduz método + rota em uma ação de negócio auditável e no recurso afetado.
 *
 * <p>A regra fica isolada aqui para ser testada sem subir o Spring.
 */
@Component
public class AuditActionMapper {

    public record Target(String action, String entityType, UUID entityId) {
    }

    public Target resolve(String method, String path, String decision) {
        String httpMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        List<String> segments = segments(path);

        String action = resolveAction(httpMethod, segments, decision);
        UUID entityId = resolveEntityId(segments);
        String entityType = resolveEntityType(segments, entityId);
        return new Target(action, entityType, entityId);
    }

    private String resolveAction(String method, List<String> segments, String decision) {
        String path = "/" + String.join("/", segments);

        if ("POST".equals(method) && "/api/rides".equals(path)) {
            return "RIDE_CREATED";
        }

        if (segments.size() >= 3
                && "api".equals(segments.get(0))
                && "rides".equals(segments.get(1))) {

            if (segments.size() == 4) {
                switch (segments.get(3)) {
                    case "cancel":
                        if ("POST".equals(method)) return "RIDE_CANCELLED";
                        break;
                    case "start":
                        if ("POST".equals(method)) return "RIDE_STARTED";
                        break;
                    case "finish":
                        if ("POST".equals(method)) return "RIDE_FINISHED";
                        break;
                    case "close-bookings":
                        if ("POST".equals(method)) return "RIDE_BOOKINGS_CLOSED";
                        break;
                    default:
                        break;
                }
            }

            if (segments.size() >= 4 && "requests".equals(segments.get(3))) {
                if (segments.size() == 4 && "POST".equals(method)) {
                    return "BOOKING_REQUESTED";
                }
                if (segments.size() >= 5 && "accept-all".equals(segments.get(4))
                        && "POST".equals(method)) {
                    return "BOOKINGS_ACCEPTED_ALL";
                }
                if (segments.size() == 5) {
                    if ("PATCH".equals(method)) {
                        return "REJECT".equalsIgnoreCase(decision)
                                ? "BOOKING_REJECTED" : "BOOKING_ACCEPTED";
                    }
                    if ("DELETE".equals(method)) return "BOOKING_CANCELLED";
                }
                if (segments.size() >= 6) {
                    String tail = segments.get(5);
                    if ("baggage".equals(tail)) {
                        if ("PATCH".equals(method)) return "BAGGAGE_DECIDED";
                        if ("PUT".equals(method)) return "BAGGAGE_UPDATED";
                    }
                    if ("payment".equals(tail) && "POST".equals(method)) {
                        return "PAYMENT_STARTED";
                    }
                }
            }
        }

        if ("POST".equals(method) && "/api/admin/payouts".equals(path)) {
            return "PAYOUT_REGISTERED";
        }
        if ("PUT".equals(method) && "/api/drivers/me/pix-key".equals(path)) {
            return "PIX_KEY_UPDATED";
        }
        if (segments.size() >= 2 && "api".equals(segments.get(0))
                && "deliveries".equals(segments.get(1))) {
            if ("POST".equals(method) && segments.size() == 2) return "DELIVERY_CREATED";
            if ("PATCH".equals(method)) return "DELIVERY_UPDATED";
            if ("DELETE".equals(method)) return "DELIVERY_CANCELLED";
        }
        if (segments.size() >= 2 && "api".equals(segments.get(0))
                && ("freights".equals(segments.get(1)) || "freight".equals(segments.get(1)))) {
            if ("POST".equals(method)) return "FREIGHT_CREATED";
            if ("PATCH".equals(method) || "PUT".equals(method)) return "FREIGHT_UPDATED";
        }
        if ("POST".equals(method) && "/api/public/stripe/webhook".equals(path)) {
            return "PAYMENT_WEBHOOK";
        }

        String generic = "HTTP_" + method;
        return generic.length() > 50 ? generic.substring(0, 50) : generic;
    }

    private UUID resolveEntityId(List<String> segments) {
        int requests = segments.indexOf("requests");
        if (requests >= 0 && requests + 1 < segments.size()) {
            UUID requestId = uuid(segments.get(requests + 1));
            if (requestId != null) return requestId;
        }
        for (String candidate : List.of("rides", "deliveries", "freights", "freight")) {
            int index = segments.indexOf(candidate);
            if (index >= 0 && index + 1 < segments.size()) {
                UUID id = uuid(segments.get(index + 1));
                if (id != null) return id;
            }
        }
        for (String segment : segments) {
            UUID id = uuid(segment);
            if (id != null) return id;
        }
        return null;
    }

    private String resolveEntityType(List<String> segments, UUID entityId) {
        if (entityId == null) {
            if (segments.contains("payouts")) return "PAYOUT";
            if (segments.contains("pix-key")) return "DRIVER";
            if (segments.contains("stripe") || segments.contains("payment")) return "PAYMENT";
            return "HTTP";
        }
        int requests = segments.indexOf("requests");
        if (requests >= 0 && requests + 1 < segments.size()
                && entityId.equals(uuid(segments.get(requests + 1)))) {
            return "RIDE_REQUEST";
        }
        if (segments.contains("rides")) return "RIDE";
        if (segments.contains("deliveries")) return "DELIVERY";
        if (segments.contains("freights") || segments.contains("freight")) return "FREIGHT";
        return "HTTP";
    }

    private List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        for (String part : path.split("/")) {
            if (!part.isBlank()) out.add(part);
        }
        return out;
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
