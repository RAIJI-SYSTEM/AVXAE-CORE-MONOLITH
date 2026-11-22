package com.raiji.rides.push;

import java.util.Map;

/**
 * Uma notificação push.
 *
 * @param data o app usa para abrir a tela certa ao tocar (ex.: {@code type=chat, conversationId=...})
 */
public record PushMessage(String title, String body, Map<String, String> data) {

    public PushMessage {
        title = cut(title, 100);
        body = cut(body, 240);
        data = data == null ? Map.of() : data;
    }

    private static String cut(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }
}
