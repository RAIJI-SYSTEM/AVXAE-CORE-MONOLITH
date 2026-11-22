package com.raiji.rides.push;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;

import static org.assertj.core.api.Assertions.assertThat;

class FcmSenderTest {

    @Test
    @DisplayName("Sem conta de serviço, o push fica desligado")
    void desligado() {
        assertThat(new FcmSender("", new ObjectMapper()).enabled()).isFalse();
        assertThat(new FcmSender("/nao/existe.json", new ObjectMapper()).enabled()).isFalse();
    }

    @Test
    @DisplayName("Token que o Firebase não reconhece é removido; outros erros não")
    void classifica() {
        assertThat(FcmSender.classify(HttpStatusCode.valueOf(200), "{}")).isEqualTo(FcmSender.Result.SENT);
        assertThat(FcmSender.classify(HttpStatusCode.valueOf(404), "{\"error\":{\"status\":\"NOT_FOUND\"}}"))
                .isEqualTo(FcmSender.Result.INVALID_TOKEN);
        assertThat(FcmSender.classify(HttpStatusCode.valueOf(400),
                "{\"error\":{\"message\":\"The registration token is not a valid FCM registration token\"}}"))
                .isEqualTo(FcmSender.Result.INVALID_TOKEN);
        assertThat(FcmSender.classify(HttpStatusCode.valueOf(500), "boom")).isEqualTo(FcmSender.Result.FAILED);
    }

    @Test
    @DisplayName("Título do push pelo assunto da notificação")
    void titulos() {
        assertThat(NotificationPush.titleOf("RIDE_REQUEST_ACCEPTED")).isEqualTo("Reserva aceita");
        assertThat(NotificationPush.titleOf("DELIVERY_ACCEPTED")).isEqualTo("Encomenda");
        assertThat(NotificationPush.titleOf(null)).isEqualTo("Raiji");
    }
}
