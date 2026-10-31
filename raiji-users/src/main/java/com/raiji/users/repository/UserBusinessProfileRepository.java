package com.raiji.users.repository;

import com.raiji.users.domain.UserBusinessProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBusinessProfileRepository
extends JpaRepository<UserBusinessProfile, String>{
}
