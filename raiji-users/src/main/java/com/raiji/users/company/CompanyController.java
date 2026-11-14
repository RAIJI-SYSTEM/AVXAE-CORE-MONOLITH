package com.raiji.users.company;

import java.util.List;
import java.util.UUID;

import com.raiji.users.company.CompanyDtos.CnpjPreview;
import com.raiji.users.company.CompanyDtos.CompanyDetailResponse;
import com.raiji.users.company.CompanyDtos.CompanyResponse;
import com.raiji.users.company.CompanyDtos.InviteMemberRequest;
import com.raiji.users.company.CompanyDtos.InviteResponse;
import com.raiji.users.company.CompanyDtos.MemberResponse;
import com.raiji.users.company.CompanyDtos.RegisterCompanyRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET    /api/companies/cnpj/{cnpj}              prévia na Receita (antes de cadastrar)
 * POST   /api/companies                          { cnpj } cadastra (quem cadastra vira dono)
 * GET    /api/companies/mine                     empresas de que participo
 * GET    /api/companies/{id}                     detalhe + membros (só membros)
 * POST   /api/companies/{id}/members             { email, name } convida (qualquer membro)
 * DELETE /api/companies/{id}/members/{memberId}  remove (dono) / sair (eu mesmo)
 * GET    /api/companies/invites                  convites para o meu e-mail
 * POST   /api/companies/invites/{id}/accept|decline
 */
@RestController
@RequestMapping("/api/companies")
@PreAuthorize("isAuthenticated()")
public class CompanyController {

    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    @GetMapping("/cnpj/{cnpj}")
    public CnpjPreview preview(@PathVariable String cnpj) {
        return service.preview(cnpj);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegisterCompanyRequest body) {
        return service.register(jwt, body.cnpj());
    }

    @GetMapping("/mine")
    public List<CompanyResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(jwt.getSubject());
    }

    @GetMapping("/invites")
    public List<InviteResponse> invites(@AuthenticationPrincipal Jwt jwt) {
        return service.myInvites(jwt);
    }

    @PostMapping("/invites/{memberId}/accept")
    public CompanyResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memberId) {
        return service.acceptInvite(memberId, jwt);
    }

    @PostMapping("/invites/{memberId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memberId) {
        service.declineInvite(memberId, jwt);
    }

    @GetMapping("/{id}")
    public CompanyDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(id, jwt.getSubject());
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody InviteMemberRequest body) {
        return service.invite(id, jwt, body.email(), body.name());
    }

    @DeleteMapping("/{id}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID memberId) {
        service.removeMember(id, memberId, jwt.getSubject());
    }
}
