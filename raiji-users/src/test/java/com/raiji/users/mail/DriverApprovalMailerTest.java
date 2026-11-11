package com.raiji.users.mail;

import java.time.LocalDate;
import java.util.UUID;

import com.raiji.users.keycloak.KeycloakAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverApprovalMailerTest {

    private final KeycloakAdminService keycloak = mock(KeycloakAdminService.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final DriverApprovalMailer mailer = new DriverApprovalMailer(keycloak, sender);

    @Test
    @DisplayName("monta o e-mail com nome, categoria, validade e chave de idempotência do pedido")
    void conteudo() {
        UUID pedido = UUID.randomUUID();
        when(keycloak.findUserContact("sub-1")).thenReturn(new KeycloakAdminService.UserContact("maria@exemplo.com", "Maria"));

        mailer.sendApproved("sub-1", pedido, "B", LocalDate.of(2030, 5, 20));

        ArgumentCaptor<EmailSender.EmailMessage> msg = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        verify(sender).send(msg.capture());
        assertThat(msg.getValue().to()).isEqualTo("maria@exemplo.com");
        assertThat(msg.getValue().subject()).contains("motorista").contains("aprovado");
        assertThat(msg.getValue().html()).contains("Olá, Maria!").contains("<b>B</b>").contains("20/05/2030");
        assertThat(msg.getValue().text()).contains("Olá, Maria!").contains("válida até 20/05/2030");
        assertThat(msg.getValue().idempotencyKey()).isEqualTo("driver-approved-" + pedido);
    }

    @Test
    @DisplayName("nome com HTML é escapado (o nome vem do cadastro do usuário)")
    void escapaHtml() {
        when(keycloak.findUserContact("sub-1"))
                .thenReturn(new KeycloakAdminService.UserContact("a@b.com", "<script>x</script>"));

        mailer.sendApproved("sub-1", UUID.randomUUID(), "B", LocalDate.of(2030, 1, 1));

        ArgumentCaptor<EmailSender.EmailMessage> msg = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        verify(sender).send(msg.capture());
        assertThat(msg.getValue().html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("conta sem e-mail: não envia")
    void semEmail() {
        when(keycloak.findUserContact("sub-1")).thenReturn(new KeycloakAdminService.UserContact(null, "Maria"));

        mailer.sendApproved("sub-1", UUID.randomUUID(), "B", LocalDate.of(2030, 1, 1));

        verify(sender, never()).send(any());
    }
}
