package com.raiji.rides.push;

import java.util.List;
import java.util.Map;

import com.raiji.rides.messaging.MessagingConfig;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mensagem nova no chat (evento {@code message.created} do raiji-chat) vira push para
 * quem está na conversa, menos quem mandou. Ao tocar, o app abre a conversa.
 */
@Component
@ConditionalOnProperty(name = "raiji.messaging.enabled", havingValue = "true")
public class ChatMessagePush {

    /**
     * @param title    nome de quem mandou (conversa direta) ou do grupo
     * @param preview  texto, ou "📷 Foto", "🎤 Áudio"...; em grupo já vem com o nome de quem mandou
     */
    public record ChatMessageCreated(String conversationId, String senderId, String title, String preview,
                                     List<String> recipientIds) {
    }

    private final PushService push;

    public ChatMessagePush(PushService push) {
        this.push = push;
    }

    @RabbitListener(queues = MessagingConfig.CHAT_MESSAGES_QUEUE)
    public void onMessage(ChatMessageCreated event) {
        if (event.recipientIds() == null || event.recipientIds().isEmpty() || event.conversationId() == null) return;
        List<String> recipients = event.recipientIds().stream()
                .filter(id -> id != null && !id.equals(event.senderId()))
                .distinct().limit(50).toList();
        push.send(recipients, new PushMessage(
                event.title() == null || event.title().isBlank() ? "Nova mensagem" : event.title(),
                event.preview() == null || event.preview().isBlank() ? "Nova mensagem" : event.preview(),
                Map.of("type", "chat", "conversationId", event.conversationId())));
    }
}
