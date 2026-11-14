package com.raiji.users.company;

import java.time.Instant;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_members")
public class CompanyMember extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "user_sub", length = 64)
    private String userSub;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    @Column(name = "invited_by", length = 64)
    private String invitedBy;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "joined_at")
    private Instant joinedAt;

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID v) { this.companyId = v; }
    public String getUserSub() { return userSub; }
    public void setUserSub(String v) { this.userSub = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public MemberRole getRole() { return role; }
    public void setRole(MemberRole v) { this.role = v; }
    public MemberStatus getStatus() { return status; }
    public void setStatus(MemberStatus v) { this.status = v; }
    public String getInvitedBy() { return invitedBy; }
    public void setInvitedBy(String v) { this.invitedBy = v; }
    public Instant getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Instant v) { this.invitedAt = v; }
    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant v) { this.joinedAt = v; }
}
