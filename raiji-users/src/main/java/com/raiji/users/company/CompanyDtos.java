package com.raiji.users.company;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contratos HTTP das empresas. */
public final class CompanyDtos {

    private CompanyDtos() {
    }

    public record RegisterCompanyRequest(@NotBlank(message = "Informe o CNPJ.") String cnpj) {}

    public record InviteMemberRequest(
            @NotBlank(message = "Informe o e-mail.") @Email(message = "E-mail inválido.") @Size(max = 254) String email,
            @Size(max = 150) String name) {}

    public record CnpjPreview(String cnpj, String cnpjFormatted, String legalName, String tradeName,
                              CompanyKind kind, String registrationStatus, boolean active,
                              String mainActivity, String city, String state, boolean alreadyRegistered) {}

    public record CompanyResponse(UUID id, String cnpj, String cnpjFormatted, String legalName, String tradeName,
                                  String displayName, CompanyKind kind, String registrationStatus,
                                  String mainActivity, String city, String state, MemberRole myRole) {
        static CompanyResponse of(Company c, MemberRole myRole) {
            return new CompanyResponse(c.getId(), c.getCnpj(), Cnpj.format(c.getCnpj()), c.getLegalName(),
                    c.getTradeName(), c.displayName(), c.getKind(), c.getRegistrationStatus(), c.getMainActivity(),
                    c.getCity(), c.getState(), myRole);
        }
    }

    public record MemberResponse(UUID id, String email, String displayName, MemberRole role, MemberStatus status,
                                 Instant invitedAt, Instant joinedAt, boolean you) {
        static MemberResponse of(CompanyMember m, String viewerSub) {
            return new MemberResponse(m.getId(), m.getEmail(), m.getDisplayName(), m.getRole(), m.getStatus(),
                    m.getInvitedAt(), m.getJoinedAt(), viewerSub != null && viewerSub.equals(m.getUserSub()));
        }
    }

    public record CompanyDetailResponse(CompanyResponse company, List<MemberResponse> members) {}

    public record InviteResponse(UUID memberId, UUID companyId, String companyName, String cnpjFormatted,
                                 CompanyKind kind, Instant invitedAt) {}
}
