package com.raiji.rides.push;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PushDeviceRepository {

    private final JdbcClient jdbc;

    public PushDeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Registra (ou transfere para esta pessoa) o token do aparelho */
    public void save(String token, String userSub, String platform) {
        jdbc.sql("""
                INSERT INTO push_devices (token, user_sub, platform) VALUES (:token, :userSub, :platform)
                ON CONFLICT (token) DO UPDATE SET user_sub = :userSub, platform = :platform, updated_at = now()
                """)
                .param("token", token).param("userSub", userSub).param("platform", platform)
                .update();
    }

    public void delete(String token, String userSub) {
        jdbc.sql("DELETE FROM push_devices WHERE token = :token AND user_sub = :userSub")
                .param("token", token).param("userSub", userSub).update();
    }

    /** O Firebase disse que o token não vale mais (app desinstalado, por exemplo) */
    public void deleteInvalid(String token) {
        jdbc.sql("DELETE FROM push_devices WHERE token = :token").param("token", token).update();
    }

    public List<String> tokensOf(String userSub) {
        return jdbc.sql("SELECT token FROM push_devices WHERE user_sub = :userSub ORDER BY updated_at DESC LIMIT 10")
                .param("userSub", userSub).query(String.class).list();
    }
}
