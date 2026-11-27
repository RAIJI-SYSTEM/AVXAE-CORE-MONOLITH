package com.raiji.rides.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mensageria com o chat (RabbitMQ), ligada por {@code raiji.messaging.enabled}:
 * <ul>
 *   <li>{@code raiji.rides} (saída): situação de cada carona, para o chat montar o grupo;</li>
 *   <li>{@code raiji.chat} (entrada): mensagem nova no chat, para avisar por push.</li>
 * </ul>
 * Desligada (testes, rodar sem Docker), nada disso existe e o resto do sistema segue igual.
 */
@Configuration
@ConditionalOnProperty(name = "raiji.messaging.enabled", havingValue = "true")
public class MessagingConfig {

    public static final String RIDES_EXCHANGE = "raiji.rides";
    public static final String RIDE_SYNCED = "ride.synced";
    public static final String CHAT_EXCHANGE = "raiji.chat";
    public static final String CHAT_MESSAGES_QUEUE = "monolith.chat-messages";
    public static final String CHAT_DLX = "raiji.chat.dlx";
    public static final String CHAT_MESSAGES_DLQ = "monolith.chat-messages.dlq";

    @Bean
    TopicExchange ridesExchange() {
        return new TopicExchange(RIDES_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange chatExchange() {
        return new TopicExchange(CHAT_EXCHANGE, true, false);
    }

    @Bean
    FanoutExchange chatDeadLetterExchange() {
        return new FanoutExchange(CHAT_DLX, true, false);
    }

    @Bean
    Queue chatMessagesDlq() {
        return QueueBuilder.durable(CHAT_MESSAGES_DLQ).build();
    }

    @Bean
    Binding chatMessagesDlqBinding(Queue chatMessagesDlq, FanoutExchange chatDeadLetterExchange) {
        return BindingBuilder.bind(chatMessagesDlq).to(chatDeadLetterExchange);
    }

    @Bean
    Queue chatMessagesQueue() {
        return QueueBuilder.durable(CHAT_MESSAGES_QUEUE)
                .deadLetterExchange(CHAT_DLX)
                .build();
    }

    @Bean
    Binding chatMessagesBinding(Queue chatMessagesQueue, TopicExchange chatExchange) {
        return BindingBuilder.bind(chatMessagesQueue).to(chatExchange).with("message.created");
    }

    /** JSON nos dois sentidos (o chat é TypeScript) */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
