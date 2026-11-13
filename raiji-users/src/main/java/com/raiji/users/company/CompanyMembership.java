package com.raiji.users.company;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Consultas de participação em empresas, para os outros módulos (encomendas, frete, caronas). */
@Service
public class CompanyMembership {

    private final CompanyRepository companies;
    private final CompanyMemberRepository members;

    public CompanyMembership(CompanyRepository companies, CompanyMemberRepository members) {
        this.companies = companies;
        this.members = members;
    }

    /** A empresa, se a pessoa for membro ativo; senão 404 (não revela empresas alheias). */
    @Transactional(readOnly = true)
    public Company requireActiveMember(UUID companyId, String userSub) {
        Company company = companies.findById(companyId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
        if (!members.existsByCompanyIdAndUserSubAndStatus(companyId, userSub, MemberStatus.ACTIVE)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
        }
        return company;
    }

    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID companyId, String userSub) {
        return members.existsByCompanyIdAndUserSubAndStatus(companyId, userSub, MemberStatus.ACTIVE);
    }

    /** Participa de alguma empresa (conta PJ na prática)? */
    @Transactional(readOnly = true)
    public boolean isMemberOfAnyCompany(String userSub) {
        return members.existsByUserSubAndStatus(userSub, MemberStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<UUID> companyIdsOf(String userSub) {
        return members.findByUserSubAndStatus(userSub, MemberStatus.ACTIVE).stream()
                .map(CompanyMember::getCompanyId).toList();
    }

    /** Subs dos membros ativos (para avisar todos sobre uma oferta, um aceite etc.). */
    @Transactional(readOnly = true)
    public List<String> activeMemberSubs(UUID companyId) {
        return members.findByCompanyIdAndStatusIn(companyId, List.of(MemberStatus.ACTIVE)).stream()
                .map(CompanyMember::getUserSub).filter(s -> s != null).toList();
    }

    /** A empresa (sem checar participação: uso interno, ex.: o motorista que fecha um frete). */
    @Transactional(readOnly = true)
    public Company company(UUID companyId) {
        return companies.findById(companyId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
    }

    @Transactional(readOnly = true)
    public String displayName(UUID companyId) {
        return companies.findById(companyId).map(Company::displayName).orElse("Empresa");
    }
}
