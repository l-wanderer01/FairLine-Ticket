package com.example.fairline.comparison;

import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL baseline. A schedule row serializes writes across processes, not just JVM threads. */
public final class JdbcReservationStore implements ReservationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcReservationStore(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }

    private <T> T locked(long concert, long schedule, Supplier<T> action) {
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO comparison_scope(concert_id,schedule_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                    concert, schedule);
            jdbc.queryForObject("SELECT active_count FROM comparison_scope WHERE concert_id=? AND schedule_id=? FOR UPDATE",
                    Long.class, concert, schedule);
            return action.get();
        });
    }

    @Override
    public HoldResult hold(long concert, long schedule, long seat, String user) {
        return locked(concert, schedule, () -> {
            long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM comparison_hold WHERE concert_id=? AND schedule_id=?
                    AND user_id=? AND expires_at>clock_timestamp()
                    """, Long.class, concert, schedule, user);
            // Same result precedence as RedisLockRepository.lockSeatWithLimit.
            if (count >= 4) return HoldResult.LIMIT_EXCEEDED;
            int changed = jdbc.update("""
                    INSERT INTO comparison_hold(concert_id,schedule_id,seat_id,user_id,expires_at)
                    VALUES (?,?,?,?,clock_timestamp()+INTERVAL '5 minutes')
                    ON CONFLICT(concert_id,schedule_id,seat_id) DO UPDATE
                    SET user_id=EXCLUDED.user_id, expires_at=EXCLUDED.expires_at
                    WHERE comparison_hold.expires_at<=clock_timestamp()
                    """, concert, schedule, seat, user);
            return changed == 1 ? HoldResult.HELD : HoldResult.ALREADY_HELD;
        });
    }

    @Override
    public String owner(long concert, long schedule, long seat) {
        var owners = jdbc.queryForList("""
                SELECT user_id FROM comparison_hold WHERE concert_id=? AND schedule_id=?
                AND seat_id=? AND expires_at>clock_timestamp()
                """, String.class, concert, schedule, seat);
        return owners.isEmpty() ? null : owners.getFirst();
    }

    @Override
    public boolean release(long concert, long schedule, long seat, String user) {
        return locked(concert, schedule, () -> jdbc.update("""
                DELETE FROM comparison_hold WHERE concert_id=? AND schedule_id=? AND seat_id=?
                AND user_id=? AND expires_at>clock_timestamp()
                """, concert, schedule, seat, user) == 1);
    }

    @Override
    public int releaseAll(long concert, long schedule, String user) {
        return locked(concert, schedule, () -> jdbc.update("""
                DELETE FROM comparison_hold WHERE concert_id=? AND schedule_id=?
                AND user_id=? AND expires_at>clock_timestamp()
                """, concert, schedule, user));
    }

    @Override
    public void enter(long concert, long schedule, String user, long score) {
        locked(concert, schedule, () -> jdbc.update("""
                INSERT INTO comparison_queue(concert_id,schedule_id,user_id,score,heartbeat_expires_at)
                VALUES (?,?,?,?,clock_timestamp()+INTERVAL '10 minutes')
                ON CONFLICT(concert_id,schedule_id,user_id) DO UPDATE
                SET score=EXCLUDED.score, heartbeat_expires_at=EXCLUDED.heartbeat_expires_at
                """, concert, schedule, user, score));
    }

    @Override
    public Long rank(long concert, long schedule, String user) {
        // One statement avoids an inconsistent read between looking up the score and counting predecessors.
        var ranks = jdbc.queryForList("""
                SELECT (SELECT COUNT(*) FROM comparison_queue q
                  WHERE q.concert_id=me.concert_id AND q.schedule_id=me.schedule_id
                  AND (q.score<me.score OR (q.score=me.score AND q.user_id<me.user_id)))
                FROM comparison_queue me WHERE me.concert_id=? AND me.schedule_id=? AND me.user_id=?
                """, Long.class, concert, schedule, user);
        return ranks.isEmpty() ? null : ranks.getFirst();
    }

    @Override
    public boolean leaveQueue(long concert, long schedule, String user) {
        return locked(concert, schedule, () -> jdbc.update(
                "DELETE FROM comparison_queue WHERE concert_id=? AND schedule_id=? AND user_id=?",
                concert, schedule, user) == 1);
    }

    @Override
    public String admit(long concert, long schedule, String user, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        return locked(concert, schedule, () -> {
            Long position = rank(concert, schedule, user);
            long available = capacity - active(concert, schedule);
            if (position == null || position >= available) return null;
            String token = UUID.randomUUID().toString();
            jdbc.update("DELETE FROM comparison_queue WHERE concert_id=? AND schedule_id=? AND user_id=?",
                    concert, schedule, user);
            jdbc.update("UPDATE comparison_scope SET active_count=active_count+1 WHERE concert_id=? AND schedule_id=?",
                    concert, schedule);
            jdbc.update("INSERT INTO comparison_token(token,payload,expires_at) VALUES (?,?,clock_timestamp()+INTERVAL '180 seconds')",
                    token, user + ":" + concert + ":" + schedule);
            return token;
        });
    }

    @Override
    public String consume(String token) {
        if (token == null || token.isBlank()) return null;
        var values = jdbc.queryForList("""
                DELETE FROM comparison_token WHERE token=? AND expires_at>clock_timestamp() RETURNING payload
                """, String.class, token);
        return values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public long active(long concert, long schedule) {
        var values = jdbc.queryForList("SELECT active_count FROM comparison_scope WHERE concert_id=? AND schedule_id=?",
                Long.class, concert, schedule);
        return values.isEmpty() ? 0 : values.getFirst();
    }

    @Override
    public long decrementActive(long concert, long schedule) {
        return locked(concert, schedule, () -> {
            jdbc.update("UPDATE comparison_scope SET active_count=GREATEST(active_count-1,0) WHERE concert_id=? AND schedule_id=?",
                    concert, schedule);
            return active(concert, schedule);
        });
    }
}
