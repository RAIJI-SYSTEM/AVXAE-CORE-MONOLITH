package com.raiji.users.mail;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.raiji.users.keycloak.KeycloakAdminService;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * E-mail "seu cadastro de motorista foi aprovado". O endereço vem do Keycloak
 * (sempre o atual), não fica guardado no monolito.
 */
@Component
public class DriverApprovalMailer {

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final KeycloakAdminService keycloak;
    private final EmailSender sender;

    public DriverApprovalMailer(KeycloakAdminService keycloak, EmailSender sender) {
        this.keycloak = keycloak;
        this.sender = sender;
    }

    public void sendApproved(String sub, UUID applicationId, String categoria, LocalDate validade) {
        KeycloakAdminService.UserContact contact = keycloak.findUserContact(sub);
        if (contact == null || contact.email() == null || contact.email().isBlank()) {
            return;
        }
        String nome = contact.firstName() == null || contact.firstName().isBlank() ? "" : contact.firstName().trim();
        String saudacao = nome.isEmpty() ? "Olá!" : "Olá, " + nome + "!";
        String validadeTxt = validade == null ? "" : validade.format(BR_DATE);
        String categoriaTxt = categoria == null ? "" : categoria;

        String text = saudacao + "\n\n"
                + "Sua CNH foi verificada e seu cadastro como motorista no Raiji foi aprovado.\n"
                + "Agora você já pode cadastrar seu veículo e publicar caronas pelo app.\n\n"
                + "CNH categoria " + categoriaTxt + ", válida até " + validadeTxt + ".\n\n"
                + "Você continua podendo viajar como passageiro normalmente.\n\n"
                + "Equipe Raiji";

        String html = """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:520px;margin:0 auto;color:#1E293B">
                  <div style="background:#EF6C00;color:#fff;padding:20px 24px;border-radius:12px 12px 0 0">
                    <h1 style="margin:0;font-size:22px">Cadastro de motorista aprovado 🚗</h1>
                  </div>
                  <div style="padding:24px;border:1px solid #E2E8F0;border-top:0;border-radius:0 0 12px 12px">
                    <p style="font-size:16px">%s</p>
                    <p>Sua CNH foi verificada e seu cadastro como <b>motorista</b> no Raiji foi aprovado.
                       Agora você já pode cadastrar seu veículo e publicar caronas pelo app.</p>
                    <p style="background:#FFF3E0;padding:12px 16px;border-radius:8px">
                      CNH categoria <b>%s</b>, válida até <b>%s</b>.</p>
                    <p>Você continua podendo viajar como passageiro normalmente.</p>
                    <p style="color:#64748B;font-size:13px;margin-top:24px">Equipe Raiji</p>
                  </div>
                </div>
                """.formatted(HtmlUtils.htmlEscape(saudacao, "UTF-8"), HtmlUtils.htmlEscape(categoriaTxt, "UTF-8"), validadeTxt);

        sender.send(new EmailSender.EmailMessage(
                contact.email(),
                "Seu cadastro de motorista no Raiji foi aprovado",
                html,
                text,
                "driver-approved-" + applicationId));
    }
}
