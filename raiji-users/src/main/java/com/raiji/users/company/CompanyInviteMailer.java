package com.raiji.users.company;

import com.raiji.users.mail.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** E-mail "você foi convidado para gerenciar a empresa X no Raiji". */
@Component
public class CompanyInviteMailer {

    private static final Logger log = LoggerFactory.getLogger(CompanyInviteMailer.class);

    private final EmailSender sender;

    public CompanyInviteMailer(EmailSender sender) {
        this.sender = sender;
    }

    public void sendInvite(CompanyMember invite, Company company, String inviterName) {
        String empresa = company.displayName();
        String quem = inviterName == null || inviterName.isBlank() ? "Um membro da empresa" : inviterName.trim();
        String text = "Olá!\n\n" + quem + " convidou você para gerenciar a empresa " + empresa
                + " (CNPJ " + Cnpj.format(company.getCnpj()) + ") no Raiji.\n\n"
                + "Entre no app com este e-mail (" + invite.getEmail() + ") e aceite o convite em Empresa > Convites.\n"
                + "Se você não conhece esta empresa, ignore este e-mail.\n\nEquipe Raiji";
        String html = """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:520px;margin:0 auto;color:#1E293B">
                  <div style="background:#EF6C00;color:#fff;padding:20px 24px;border-radius:12px 12px 0 0">
                    <h1 style="margin:0;font-size:22px">Convite para gerenciar uma empresa</h1>
                  </div>
                  <div style="padding:24px;border:1px solid #E2E8F0;border-top:0;border-radius:0 0 12px 12px">
                    <p><b>%s</b> convidou você para gerenciar a empresa <b>%s</b> (CNPJ %s) no Raiji.</p>
                    <p>Entre no app com este e-mail e aceite o convite em <b>Empresa &gt; Convites</b>.</p>
                    <p style="color:#64748B;font-size:13px">Se você não conhece esta empresa, ignore este e-mail.</p>
                    <p style="color:#64748B;font-size:13px;margin-top:24px">Equipe Raiji</p>
                  </div>
                </div>
                """.formatted(HtmlUtils.htmlEscape(quem, "UTF-8"), HtmlUtils.htmlEscape(empresa, "UTF-8"),
                Cnpj.format(company.getCnpj()));
        try {
            sender.send(new EmailSender.EmailMessage(invite.getEmail(),
                    "Convite: gerenciar " + empresa + " no Raiji", html, text, "company-invite-" + invite.getId()));
        } catch (RuntimeException e) {
            // O convite continua válido no app mesmo se o e-mail falhar
            log.warn("Falha ao enviar o e-mail de convite {}: {}", invite.getId(), e.getMessage());
        }
    }
}
