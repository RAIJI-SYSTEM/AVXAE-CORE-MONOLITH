package com.raiji.users.service;

import com.raiji.users.domain.UserBusinessProfile;
import com.raiji.users.repository.UserBusinessProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class UserBusinessProfileService {

    private final UserBusinessProfileRepository repository;

    public UserBusinessProfileService(
            UserBusinessProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserBusinessProfile upsert(
            String sub,
            String phone,
            String photoUrl) {

        UserBusinessProfile profile = repository.findById(sub)
                .orElseGet(() -> {
                    UserBusinessProfile newProfile =
                            new UserBusinessProfile();
                    newProfile.setSub(sub);
                    return newProfile;
                });

        // Atualiza somente os campos enviados.
        if (phone != null) {
            profile.setPhone(phone);
        }

        if (photoUrl != null) {
            profile.setPhotoUrl(photoUrl);
        }

        profile.setUpdatedAt(OffsetDateTime.now());

        return repository.save(profile);
    }
}
