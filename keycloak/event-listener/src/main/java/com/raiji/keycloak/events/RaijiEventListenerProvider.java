package com.raiji.keycloak.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.util.JsonSerialization;

/**
 * Traduz eventos do Keycloak em mensagens de ciclo de vida do usuário:
 *
 * <ul>
 *   <li>{@code user.created}: auto-cadastro (REGISTER) ou criação pelo admin</li>
 *   <li>{@code user.deleted}: exclusão pelo admin ou pela própria conta (DELETE_ACCOUNT)</li>
 * </ul>
 *
 * <p>A mensagem só é enviada depois que a transação do Keycloak é confirmada:
 * se a operação falhar (rollback), nada é publicado.
 */
final class RaijiEventListenerProvider implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(RaijiEventListenerProvider.class);
    private static final Pattern USER_PATH = Pattern.compile("^users/([^/]+)$");

    static final String USER_CREATED = "user.created";
    static final String USER_DELETED = "user.deleted";

    private final KeycloakSession session;
    private final RabbitEventPublisher publisher;

    RaijiEventListenerProvider(KeycloakSession session, RabbitEventPublisher publisher) {
        this.session = session;
        this.publisher = publisher;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.REGISTER) {
            enqueue(USER_CREATED, event.getUserId(), event.getRealmId());
        } else if (event.getType() == EventType.DELETE_ACCOUNT) {
            enqueue(USER_DELETED, event.getUserId(), event.getRealmId());
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() != ResourceType.USER || event.getResourcePath() == null) {
            return;
        }
        Matcher m = USER_PATH.matcher(event.getResourcePath());
        if (!m.matches()) {
            return; // ex.: users/{id}/role-mappings — não é criação/exclusão do usuário
        }
        String userId = m.group(1);
        if (event.getOperationType() == OperationType.CREATE) {
            enqueue(USER_CREATED, userId, event.getRealmId());
        } else if (event.getOperationType() == OperationType.DELETE) {
            enqueue(USER_DELETED, userId, event.getRealmId());
        }
    }

    private void enqueue(String type, String userId, String realmId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("userId", userId);
        payload.put("realm", realmName(realmId));
        payload.put("occurredAt", Instant.now().toString());

        final byte[] body;
        try {
            body = JsonSerialization.writeValueAsBytes(payload);
        } catch (Exception e) {
            LOG.errorf(e, "falha ao serializar evento %s", type);
            return;
        }

        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override
            protected void commitImpl() {
                publisher.publishAsync(type, body);
            }

            @Override
            protected void rollbackImpl() {
                // operação desfeita: não publica
            }
        });
    }

    private String realmName(String realmId) {
        RealmModel realm = realmId == null ? null : session.realms().getRealm(realmId);
        return realm != null ? realm.getName() : realmId;
    }

    @Override
    public void close() {
        // nada: o publisher é compartilhado e fechado pela factory
    }
}
