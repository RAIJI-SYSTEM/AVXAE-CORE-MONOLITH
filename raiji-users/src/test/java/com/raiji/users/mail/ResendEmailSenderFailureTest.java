package com.raiji.users.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * [Análise de Falhas] Resend indisponível.
 *
 * <p>O envio é síncrono: erro 5xx sobe como exceção HTTP e servidor fora do ar
 * sobe como falha de rede. O teste fixa esse comportamento para que o chamador
 * consiga registrar/tratar a falha em vez de achar que o e-mail foi enviado.
 */
@DisplayName("[Análise de Falhas] ResendEmailSender — falhas do provedor")
class ResendEmailSenderFailureTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ResendEmailSender sender() {
        return new ResendEmailSender(new MailProperties(new MailProperties.Resend(
                "re_falsa",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "Raiji <nao-responda@raiji.test>")));
    }

    private static EmailSender.EmailMessage mensagem() {
        return new EmailSender.EmailMessage(
                "maria@exemplo.com", "Assunto", "<p>oi</p>", "oi", "driver-approved-1");
    }

    @Test
    @DisplayName("5xx do Resend propaga como HttpServerErrorException")
    void erroDoProvedorNaoEhSilenciado() {
        assertThatThrownBy(() -> sender().send(mensagem()))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> assertThat(((HttpServerErrorException) ex).getStatusCode().value()).isEqualTo(500));
    }

    @Test
    @DisplayName("servidor fora do ar propaga como falha de rede")
    void indisponibilidadeDeRede() {
        server.stop(0);

        assertThatThrownBy(() -> sender().send(mensagem()))
                .isInstanceOf(ResourceAccessException.class);
    }
}
