package com.raiji.users.repository;

import java.util.Optional;
import java.util.UUID;

import com.raiji.users.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
    Optional<UserProfile> findByKeycloakSub(String keycloakSub);
}
