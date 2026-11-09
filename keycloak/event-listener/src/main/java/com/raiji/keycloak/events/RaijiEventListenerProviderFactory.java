package com.raiji.keycloak.events;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registra o listener "raiji-rabbitmq". Para ativar num realm:
 * Realm settings → Events → Event listeners (ou "eventsListeners" no realm JSON).
 *
 * <p>Variáveis de ambiente:
 * <ul>
 *   <li>{@code RABBITMQ_URI} (default amqp://raiji:raiji123@rabbitmq:5672)</li>
 *   <li>{@code RABBITMQ_USERS_EXCHANGE} (default raiji.users)</li>
 * </ul>
 */
public final class RaijiEventListenerProviderFactory implements EventListenerProviderFactory {

    public static final String ID = "raiji-rabbitmq";
    private static final Logger LOG = Logger.getLogger(RaijiEventListenerProviderFactory.class);

    private RabbitEventPublisher publisher;

    @Override
    public void init(Config.Scope config) {
        String uri = env("RABBITMQ_URI", "amqp://raiji:raiji123@rabbitmq:5672");
        String exchange = env("RABBITMQ_USERS_EXCHANGE", "raiji.users");
        publisher = new RabbitEventPublisher(uri, exchange);
        LOG.infof("raiji-rabbitmq: publicando eventos de usuário na exchange '%s'", exchange);
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new RaijiEventListenerProvider(session, publisher);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // nada
    }

    @Override
    public void close() {
        if (publisher != null) {
            publisher.close();
        }
    }

    @Override
    public String getId() {
        return ID;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
