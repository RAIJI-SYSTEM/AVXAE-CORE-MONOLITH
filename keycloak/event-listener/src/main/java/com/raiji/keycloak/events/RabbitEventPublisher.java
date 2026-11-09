package com.raiji.keycloak.events;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.jboss.logging.Logger;

/**
 * Publica mensagens numa exchange topic do RabbitMQ.
 *
 * <p>O envio roda numa única thread de fundo: a requisição do Keycloak não espera
 * o RabbitMQ, e a ordem dos eventos é preservada. A conexão é aberta sob demanda
 * (o Keycloak sobe mesmo com o RabbitMQ fora do ar) e refeita após falhas.
 * Cada mensagem é persistente e confirmada pelo broker (publisher confirms).
 */
final class RabbitEventPublisher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RabbitEventPublisher.class);
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final ConnectionFactory factory;
    private final String exchange;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "raiji-rabbitmq-publisher");
        t.setDaemon(true);
        return t;
    });

    private Connection connection;
    private Channel channel;

    RabbitEventPublisher(String uri, String exchange) {
        this.factory = new ConnectionFactory();
        try {
            factory.setUri(uri);
        } catch (Exception e) {
            throw new IllegalArgumentException("RABBITMQ_URI inválida", e);
        }
        factory.setConnectionTimeout(5_000);
        factory.setAutomaticRecoveryEnabled(false); // reconexão feita aqui, sob demanda
        this.exchange = exchange;
    }

    void publishAsync(String routingKey, byte[] body) {
        executor.execute(() -> publish(routingKey, body));
    }

    private void publish(String routingKey, byte[] body) {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2) // persistente
                .build();
        try {
            Channel ch = channel();
            ch.basicPublish(exchange, routingKey, props, body);
            ch.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            LOG.debugf("evento publicado: %s", routingKey);
        } catch (Exception e) {
            // Sem outbox: se o broker estiver fora do ar o evento é perdido (e logado).
            LOG.errorf(e, "falha ao publicar evento %s no RabbitMQ: %s", routingKey, new String(body));
            closeQuietly();
        }
    }

    private Channel channel() throws Exception {
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        if (connection == null || !connection.isOpen()) {
            connection = factory.newConnection("keycloak-raiji-events");
        }
        channel = connection.createChannel();
        channel.confirmSelect();
        channel.exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true);
        return channel;
    }

    private void closeQuietly() {
        try {
            if (connection != null) {
                connection.abort();
            }
        } catch (Exception ignored) {
            // nada a fazer
        }
        connection = null;
        channel = null;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly();
    }
}
