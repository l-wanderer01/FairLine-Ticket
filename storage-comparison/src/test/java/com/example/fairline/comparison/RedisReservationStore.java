package com.example.fairline.comparison;

import com.example.SKALA_Mini_Project_1.global.redis.RedisKeyGenerator;
import com.example.SKALA_Mini_Project_1.global.redis.RedisLockRepository;
import com.example.SKALA_Mini_Project_1.global.redis.RedisQueueAdmission;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Uses production seat locking and production queue admission Lua, without mocking Redis. */
final class RedisReservationStore implements ReservationStore {
    private final StringRedisTemplate redis;
    private final RedisLockRepository locks;

    RedisReservationStore(StringRedisTemplate redis) {
        this.redis = redis;
        locks = new RedisLockRepository(redis);
    }

    public HoldResult hold(long concert, long schedule, long seat, String user) {
        return switch (locks.lockSeatWithLimit(concert, schedule, seat, user, 4)) {
            case LOCKED -> HoldResult.HELD;
            case LIMIT_EXCEEDED -> HoldResult.LIMIT_EXCEEDED;
            case ALREADY_LOCKED -> HoldResult.ALREADY_HELD;
        };
    }
    public String owner(long concert, long schedule, long seat) { return locks.getSeatOwner(concert, schedule, seat); }
    public boolean release(long concert, long schedule, long seat, String user) {
        return locks.unlockSeatIfOwner(concert, schedule, seat, user);
    }
    public int releaseAll(long concert, long schedule, String user) {
        return locks.releaseUserHeldSeats(concert, schedule, user);
    }
    public void enter(long concert, long schedule, String user, long score) {
        // Test-controlled score replaces clock/priority/jitter, keeping identical ZSET ordering.
        redis.opsForZSet().add(RedisKeyGenerator.queueKey(concert, schedule), user, score);
        redis.opsForValue().set(RedisKeyGenerator.queueHeartbeatKey(concert, schedule, user), "1", Duration.ofMinutes(10));
    }
    public Long rank(long concert, long schedule, String user) {
        return redis.opsForZSet().rank(RedisKeyGenerator.queueKey(concert, schedule), user);
    }
    public boolean leaveQueue(long concert, long schedule, String user) {
        Long removed = redis.opsForZSet().remove(RedisKeyGenerator.queueKey(concert, schedule), user);
        redis.delete(RedisKeyGenerator.queueHeartbeatKey(concert, schedule, user));
        return removed != null && removed == 1;
    }
    public String admit(long concert, long schedule, String user, int capacity) {
        return RedisQueueAdmission.admit(redis, concert, schedule, user, capacity,
                UUID.randomUUID().toString(), Duration.ofSeconds(180));
    }
    public String consume(String token) { return RedisQueueAdmission.consume(redis, token); }
    public long active(long concert, long schedule) {
        String value = redis.opsForValue().get(RedisKeyGenerator.seatActiveKey(concert, schedule));
        return value == null ? 0 : Long.parseLong(value);
    }
    public long decrementActive(long concert, long schedule) {
        return locks.decrementSeatActiveFloorZero(concert, schedule);
    }
}
