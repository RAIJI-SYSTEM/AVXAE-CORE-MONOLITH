package com.raiji.users.company;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyMemberRepository extends JpaRepository<CompanyMember, UUID> {

    List<CompanyMember> findByUserSubAndStatus(String userSub, MemberStatus status);

    List<CompanyMember> findByCompanyIdAndStatusIn(UUID companyId, Collection<MemberStatus> statuses);

    Optional<CompanyMember> findByCompanyIdAndUserSubAndStatus(UUID companyId, String userSub, MemberStatus status);

    boolean existsByCompanyIdAndUserSubAndStatus(UUID companyId, String userSub, MemberStatus status);

    boolean existsByUserSubAndStatus(String userSub, MemberStatus status);

    @Query("select m from CompanyMember m where lower(m.email) = lower(:email) and m.status = :status")
    List<CompanyMember> findByEmailAndStatus(@Param("email") String email, @Param("status") MemberStatus status);

    @Query("select m from CompanyMember m where m.companyId = :companyId and lower(m.email) = lower(:email) "
            + "and m.status in :statuses")
    Optional<CompanyMember> findOpenByEmail(@Param("companyId") UUID companyId, @Param("email") String email,
                                            @Param("statuses") Collection<MemberStatus> statuses);

    long countByCompanyIdAndRoleAndStatus(UUID companyId, MemberRole role, MemberStatus status);
}
