package com.raiji.rides.rewards;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Cupons, progresso da missão de anúncios e anúncios já creditados. */
@Repository
public class CouponRepository {

    public record Coupon(UUID id, String userSub, String code, String title, String description,
                         long discountCents, String source, Instant expiresAt, Instant usedAt) {
    }

    public record Progress(int points, boolean completed) {
    }

    private final JdbcClient jdbc;

    public CouponRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Coupon> listOf(String userSub) {
        return jdbc.sql("SELECT * FROM coupons WHERE user_sub = :sub ORDER BY created_at DESC")
                .param("sub", userSub).query(CouponRepository::map).list();
    }

    public Optional<Coupon> find(UUID id) {
        return jdbc.sql("SELECT * FROM coupons WHERE id = :id").param("id", id).query(CouponRepository::map).optional();
    }

    public boolean codeExists(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM coupons WHERE code = :code)").param("code", code)
                .query(Boolean.class).single();
    }

    /** Grava o cupom; o de boas-vindas só uma vez por pessoa. @return false se já existia */
    public boolean insert(Coupon c) {
        return jdbc.sql("""
                INSERT INTO coupons (id, user_sub, code, title, description, discount_cents, source, expires_at)
                VALUES (:id, :sub, :code, :title, :description, :discount, :source, :expires)
                ON CONFLICT DO NOTHING
                """)
                .param("id", c.id()).param("sub", c.userSub()).param("code", c.code()).param("title", c.title())
                .param("description", c.description()).param("discount", c.discountCents())
                .param("source", c.source()).param("expires", Timestamp.from(c.expiresAt()))
                .update() > 0;
    }

    public void markUsed(UUID id, Instant at) {
        jdbc.sql("UPDATE coupons SET used_at = :at WHERE id = :id AND used_at IS NULL")
                .param("id", id).param("at", Timestamp.from(at)).update();
    }

    /** Pagamento estornado: o cupom volta a valer (até a validade dele) */
    public void release(UUID id) {
        jdbc.sql("UPDATE coupons SET used_at = NULL WHERE id = :id").param("id", id).update();
    }

    /** O cupom está num outro pagamento ainda em aberto (reserva ou encomenda)? */
    public boolean inOpenPaymentOtherThan(UUID couponId, UUID payableId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM ride_passengers
                     WHERE coupon_id = :coupon AND id <> :self AND deleted_at IS NULL
                       AND status IN ('PENDING', 'CONFIRMED') AND payment_status IN ('UNPAID', 'PROCESSING')
                    UNION ALL
                    SELECT 1 FROM deliveries
                     WHERE coupon_id = :coupon AND id <> :self AND deleted_at IS NULL
                       AND status IN ('PENDING', 'CONFIRMED') AND payment_status IN ('UNPAID', 'PROCESSING'))
                """)
                .param("coupon", couponId).param("self", payableId).query(Boolean.class).single();
    }

    // ------------------------------------------------------------- missão de anúncios

    public Progress progressOf(String userSub) {
        return jdbc.sql("SELECT points, completed FROM reward_progress WHERE user_sub = :sub")
                .param("sub", userSub)
                .query((rs, n) -> new Progress(rs.getInt("points"), rs.getBoolean("completed")))
                .optional().orElse(new Progress(0, false));
    }

    public void saveProgress(String userSub, Progress progress) {
        jdbc.sql("""
                INSERT INTO reward_progress (user_sub, points, completed) VALUES (:sub, :points, :completed)
                ON CONFLICT (user_sub) DO UPDATE SET points = :points, completed = :completed, updated_at = now()
                """)
                .param("sub", userSub).param("points", progress.points()).param("completed", progress.completed())
                .update();
    }

    /** @return false se este anúncio já foi creditado (callback repetido) */
    public boolean recordAdCredit(String transactionId, String userSub, String kind, int points) {
        return jdbc.sql("""
                INSERT INTO reward_ad_credits (transaction_id, user_sub, ad_kind, points)
                VALUES (:tx, :sub, :kind, :points) ON CONFLICT DO NOTHING
                """)
                .param("tx", transactionId).param("sub", userSub).param("kind", kind).param("points", points)
                .update() > 0;
    }

    private static Coupon map(ResultSet rs, int row) throws SQLException {
        Timestamp used = rs.getTimestamp("used_at");
        return new Coupon(rs.getObject("id", UUID.class), rs.getString("user_sub"), rs.getString("code"),
                rs.getString("title"), rs.getString("description"), rs.getLong("discount_cents"),
                rs.getString("source"), rs.getTimestamp("expires_at").toInstant(), used == null ? null : used.toInstant());
    }
}
