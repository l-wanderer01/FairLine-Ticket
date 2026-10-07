package com.example.SKALA_Mini_Project_1.global.redis;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Atomic operations shared by the queue service and storage comparison tests. */
public final class RedisQueueAdmission {
    private RedisQueueAdmission() {}

    private static final String ADMIT_AND_ISSUE_TOKEN_SCRIPT = """
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if not rank then
                return nil
            end

            local active = tonumber(redis.call('GET', KEYS[2]) or '0')
            local capacity = tonumber(ARGV[2])
            if not capacity then
                return nil
            end

            local available = capacity - active
            if available <= 0 then
                return nil
            end

            if rank < available then
                local removed = redis.call('ZREM', KEYS[1], ARGV[1])
                if removed == 1 then
                    redis.call('INCR', KEYS[2])
                    redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[4])
                    return ARGV[5]
                end
            end

            return nil
            """;

    private static final String CONSUME_ENTRY_TOKEN_SCRIPT = """
            local value = redis.call('GET', KEYS[1])
            if not value then
                return nil
            end
            redis.call('DEL', KEYS[1])
            return value
            """;

    public static String admit(RedisTemplate<String, String> redis, Long concertId, Long scheduleId,
                               String userId, long capacity, String token, Duration ttl) {
        var script = new DefaultRedisScript<>(ADMIT_AND_ISSUE_TOKEN_SCRIPT, String.class);
        return redis.execute(script, List.of(
                RedisKeyGenerator.queueKey(concertId, scheduleId),
                RedisKeyGenerator.seatActiveKey(concertId, scheduleId),
                RedisKeyGenerator.seatEntryKey(token)),
                userId, String.valueOf(capacity), userId + ":" + concertId + ":" + scheduleId,
                String.valueOf(ttl.toMillis()), token);
    }

    public static String consume(RedisTemplate<String, String> redis, String token) {
        return redis.execute(new DefaultRedisScript<>(CONSUME_ENTRY_TOKEN_SCRIPT, String.class),
                List.of(RedisKeyGenerator.seatEntryKey(token)));
    }
}
