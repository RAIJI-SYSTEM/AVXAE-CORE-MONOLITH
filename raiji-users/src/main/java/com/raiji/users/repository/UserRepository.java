package com.raiji.users.repository;

import java.util.Optional;
import java.util.UUID;

import com.raiji.users.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByKeycloakSub(String keycloakSub);
    Optional<User> findByEmail(String email);
}
