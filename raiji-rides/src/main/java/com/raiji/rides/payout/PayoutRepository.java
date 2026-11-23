package com.raiji.rides.payout;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Consultas do repasse (SQL direto: junta reservas e encomendas, que vivem em módulos diferentes). */
@Repository
public class PayoutRepository {

    /** Vagas pagas de viagens concluídas, ainda sem repasse */
    private static final String RIDE_ITEMS = """
            SELECT 'RIDE' AS kind, rp.id, r.id AS ride_id, r.driver_sub, r.driver_name,
                   COALESCE(rp.passenger_name, 'Passageiro') || ' · ' || r.origin_city || ' → ' || r.destination_city AS description,
                   r.departure_date, rp.paid_at,
                   COALESCE(rp.paid_amount_cents + COALESCE(rp.discount_cents, 0), ROUND(rp.price * 100)::BIGINT) AS paid_cents,
                   r.platform_fee_percent AS fee_percent
              FROM ride_passengers rp
              JOIN rides r ON r.id = rp.ride_id
             WHERE rp.payment_status = 'PAID'
               AND rp.payout_id IS NULL
               AND rp.deleted_at IS NULL
               AND rp.status <> 'CANCELLED'
               AND r.status = 'COMPLETED'
               AND (CAST(:driverSub AS VARCHAR) IS NULL OR r.driver_sub = :driverSub)
            """;

    /** Encomendas pagas e entregues, ainda sem repasse */
    private static final String DELIVERY_ITEMS = """
            SELECT 'DELIVERY' AS kind, d.id, r.id AS ride_id, r.driver_sub, r.driver_name,
                   'Encomenda: ' || d.description AS description,
                   r.departure_date, d.paid_at,
                   COALESCE(d.paid_amount_cents + COALESCE(d.discount_cents, 0), ROUND(d.price * 100)::BIGINT) AS paid_cents,
                   d.platform_fee_percent AS fee_percent
              FROM deliveries d
              JOIN rides r ON r.id = d.ride_id
             WHERE d.payment_status = 'PAID'
               AND d.payout_id IS NULL
               AND d.deleted_at IS NULL
               AND d.status = 'DELIVERED'
               AND (CAST(:driverSub AS VARCHAR) IS NULL OR r.driver_sub = :driverSub)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PayoutRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param driverSub null = de todos os motoristas */
    public List<PayoutItem> pendingItems(String driverSub, boolean lock) {
        String suffix = lock ? " FOR UPDATE OF rp" : "";
        String suffixDelivery = lock ? " FOR UPDATE OF d" : "";
        MapSqlParameterSource params = new MapSqlParameterSource("driverSub", driverSub);
        List<PayoutItem> items = new ArrayList<>(jdbc.query(RIDE_ITEMS + suffix, params, PayoutRepository::item));
        items.addAll(jdbc.query(DELIVERY_ITEMS + suffixDelivery, params, PayoutRepository::item));
        items.sort(Comparator.comparing(PayoutItem::departureDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return items;
    }

    public void insertPayout(DriverPayout payout, String paidBy) {
        jdbc.update("""
                INSERT INTO driver_payouts (id, driver_sub, amount_cents, item_count, pix_key, note, paid_by, paid_at)
                VALUES (:id, :driverSub, :amount, :count, :pixKey, :note, :paidBy, :paidAt)
                """, new MapSqlParameterSource()
                .addValue("id", payout.id())
                .addValue("driverSub", payout.driverSub())
                .addValue("amount", payout.amountCents())
                .addValue("count", payout.itemCount())
                .addValue("pixKey", payout.pixKey())
                .addValue("note", payout.note())
                .addValue("paidBy", paidBy)
                .addValue("paidAt", Timestamp.from(payout.paidAt())));
    }

    public void markPaidOut(UUID payoutId, List<PayoutItem> items) {
        List<UUID> rides = items.stream().filter(i -> "RIDE".equals(i.kind())).map(PayoutItem::id).toList();
        List<UUID> deliveries = items.stream().filter(i -> "DELIVERY".equals(i.kind())).map(PayoutItem::id).toList();
        if (!rides.isEmpty()) {
            jdbc.update("UPDATE ride_passengers SET payout_id = :payout WHERE id IN (:ids)",
                    Map.of("payout", payoutId, "ids", rides));
        }
        if (!deliveries.isEmpty()) {
            jdbc.update("UPDATE deliveries SET payout_id = :payout WHERE id IN (:ids)",
                    Map.of("payout", payoutId, "ids", deliveries));
        }
    }

    /** @param driverSub null = de todos */
    public List<DriverPayout> payouts(String driverSub, int limit) {
        return jdbc.query("""
                SELECT id, driver_sub, amount_cents, item_count, pix_key, note, paid_at
                  FROM driver_payouts
                 WHERE CAST(:driverSub AS VARCHAR) IS NULL OR driver_sub = :driverSub
                 ORDER BY paid_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource("driverSub", driverSub).addValue("limit", limit),
                (rs, n) -> new DriverPayout(
                        rs.getObject("id", UUID.class),
                        rs.getString("driver_sub"),
                        rs.getLong("amount_cents"),
                        rs.getInt("item_count"),
                        rs.getString("pix_key"),
                        rs.getString("note"),
                        rs.getTimestamp("paid_at").toInstant()));
    }

    public Optional<String> pixKey(String driverSub) {
        return jdbc.query("SELECT pix_key FROM driver_pix_keys WHERE driver_sub = :sub",
                Map.of("sub", driverSub), (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    public Map<String, String> pixKeys() {
        Map<String, String> keys = new java.util.HashMap<>();
        jdbc.query("SELECT driver_sub, pix_key FROM driver_pix_keys",
                rs -> { keys.put(rs.getString(1), rs.getString(2)); });
        return keys;
    }

    public void savePixKey(String driverSub, String pixKey) {
        jdbc.update("""
                INSERT INTO driver_pix_keys (driver_sub, pix_key, updated_at) VALUES (:sub, :key, NOW())
                ON CONFLICT (driver_sub) DO UPDATE SET pix_key = EXCLUDED.pix_key, updated_at = NOW()
                """, Map.of("sub", driverSub, "key", pixKey));
    }

    private static PayoutItem item(ResultSet rs, int n) throws SQLException {
        long paid = rs.getLong("paid_cents");
        BigDecimal pct = rs.getBigDecimal("fee_percent");
        long fee = PayoutMath.feeCents(paid, pct);
        Timestamp paidAt = rs.getTimestamp("paid_at");
        Timestamp departure = rs.getTimestamp("departure_date");
        return new PayoutItem(
                rs.getString("kind"),
                rs.getObject("id", UUID.class),
                rs.getObject("ride_id", UUID.class),
                rs.getString("driver_sub"),
                rs.getString("driver_name"),
                rs.getString("description"),
                departure == null ? null : departure.toLocalDateTime(),
                paidAt == null ? null : paidAt.toInstant(),
                paid,
                fee,
                paid - fee);
    }
}
