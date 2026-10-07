package com.example.fairline.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.SKALA_Mini_Project_1.global.redis.RedisKeyGenerator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class StorageComparisonTest {
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static final AtomicLong ids = new AtomicLong(System.currentTimeMillis());
    private static final List<String> timings = Collections.synchronizedList(new ArrayList<>());
    record Backend(String name, ReservationStore store) {
        public String toString() { return name; }
    }

    @BeforeAll
    static void setup() {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("COMPARISON_DB_URL"));
        config.setUsername(System.getenv("COMPARISON_DB_USER"));
        config.setPassword(System.getenv("COMPARISON_DB_PASSWORD"));
        config.setMaximumPoolSize(16);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        connection = new LettuceConnectionFactory("localhost", Integer.parseInt(System.getenv("COMPARISON_REDIS_PORT")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        assertThat(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping())).isEqualTo("PONG");
        timings.add("backend,scenario,requests,successes,elapsed_ms,p50_ms,p95_ms,p99_ms,requests_per_second");
    }

    static Stream<Backend> backends() {
        return Stream.of(new Backend("redis", new RedisReservationStore(redis)),
                new Backend("postgresql", new JdbcReservationStore(dataSource)));
    }

    @AfterAll
    static void close() throws Exception {
        try {
            Path report = Path.of("build/reports/comparison/results.csv");
            Files.createDirectories(report.getParent());
            Files.write(report, timings);
        } finally {
            if (connection != null) connection.destroy();
            if (dataSource != null) dataSource.close();
        }
    }

    @ParameterizedTest @MethodSource("backends")
    void onlyOneUserCanHoldTheSameSeat(Backend backend) throws Exception {
        long c = ids.incrementAndGet();
        var result = concurrently(100, i -> backend.store.hold(c, 1, 1, "user-" + i));
        long successes = result.values.stream().filter(v -> v == ReservationStore.HoldResult.HELD).count();
        assertThat(successes).isEqualTo(1);
        assertThat(backend.store.owner(c, 1, 1)).isNotNull();
        record(backend, "same_seat_contention", result, successes);
    }

    @ParameterizedTest @MethodSource("backends")
    void concurrentRequestsCannotExceedFourSeatsPerUser(Backend backend) throws Exception {
        long c = ids.incrementAndGet();
        var result = concurrently(100, i -> backend.store.hold(c, 1, i, "same-user"));
        long successes = result.values.stream().filter(v -> v == ReservationStore.HoldResult.HELD).count();
        assertThat(successes).isEqualTo(4);
        assertThat(result.values.stream().filter(v -> v == ReservationStore.HoldResult.LIMIT_EXCEEDED).count()).isEqualTo(96);
        assertThat(backend.store.releaseAll(c, 1, "same-user")).isEqualTo(4);
        assertThat(backend.store.releaseAll(c, 1, "same-user")).isZero();
        record(backend, "four_seat_limit", result, successes);
    }

    @ParameterizedTest @MethodSource("backends")
    void onlyOwnerCanReleaseAndScopesAreIndependent(Backend backend) {
        long c = ids.incrementAndGet();
        assertThat(backend.store.hold(c, 1, 1, "alice")).isEqualTo(ReservationStore.HoldResult.HELD);
        assertThat(backend.store.release(c, 1, 1, "bob")).isFalse();
        assertThat(backend.store.owner(c, 1, 1)).isEqualTo("alice");
        assertThat(backend.store.hold(c, 2, 1, "bob")).isEqualTo(ReservationStore.HoldResult.HELD);
        assertThat(backend.store.release(c, 1, 1, "alice")).isTrue();
        assertThat(backend.store.release(c, 1, 1, "alice")).isFalse();
        assertThat(backend.store.hold(c, 1, 1, "bob")).isEqualTo(ReservationStore.HoldResult.HELD);
    }

    @ParameterizedTest @MethodSource("backends")
    void expiredHoldsCanBeReacquiredAndDoNotUseTheLimit(Backend backend) throws Exception {
        long c = ids.incrementAndGet();
        for (int i = 0; i < 4; i++) backend.store.hold(c, 1, i, "alice");
        expireHolds(backend, c, 1);
        assertThat(backend.store.owner(c, 1, 0)).isNull();
        assertThat(backend.store.hold(c, 1, 4, "alice")).isEqualTo(ReservationStore.HoldResult.HELD);
        assertThat(backend.store.hold(c, 1, 0, "bob")).isEqualTo(ReservationStore.HoldResult.HELD);
        assertThat(backend.store.release(c, 1, 0, "alice")).isFalse();
    }

    @ParameterizedTest @MethodSource("backends")
    void queueOrdersByScoreThenUserAndReentryUpdatesInsteadOfDuplicating(Backend backend) {
        long c = ids.incrementAndGet();
        backend.store.enter(c, 1, "bob", 100);
        backend.store.enter(c, 1, "alice", 100);
        backend.store.enter(c, 1, "fan", 50);
        assertThat(backend.store.rank(c, 1, "fan")).isZero();
        assertThat(backend.store.rank(c, 1, "alice")).isEqualTo(1L);
        assertThat(backend.store.rank(c, 1, "bob")).isEqualTo(2L);
        backend.store.enter(c, 1, "bob", 0);
        assertThat(backend.store.rank(c, 1, "bob")).isZero();
        assertThat(backend.store.rank(c, 1, "alice")).isEqualTo(2L);
        assertThat(backend.store.leaveQueue(c, 1, "bob")).isTrue();
        assertThat(backend.store.leaveQueue(c, 1, "bob")).isFalse();
        assertThat(backend.store.rank(c, 1, "bob")).isNull();
        assertThat(backend.store.rank(c, 1, "fan")).isZero();
        assertThat(backend.store.admit(c, 1, "alice", 1)).isNull();
        assertThat(backend.store.admit(c, 1, "fan", 1)).isNotNull();
    }

    @ParameterizedTest @MethodSource("backends")
    void concurrentAdmissionCannotExceedCapacity(Backend backend) throws Exception {
        long c = ids.incrementAndGet();
        for (int i = 0; i < 100; i++) backend.store.enter(c, 1, "user-" + i, i);
        var result = concurrently(100, i -> backend.store.admit(c, 1, "user-" + i, 10));
        long issued = result.values.stream().filter(v -> v != null).count();
        assertThat(issued).isEqualTo(10);
        assertThat(backend.store.active(c, 1)).isEqualTo(10);
        for (int i = 0; i < 10; i++) {
            assertThat(backend.store.rank(c, 1, "user-" + i)).isNull();
            assertThat(backend.store.admit(c, 1, "user-" + i, 10)).isNull();
        }
        var decrements = concurrently(100, i -> backend.store.decrementActive(c, 1));
        assertThat(decrements.values).allMatch(n -> n >= 0);
        assertThat(backend.store.active(c, 1)).isZero();
        assertThat(backend.store.admit(c, 1, "user-10", 10)).isNotNull();
        record(backend, "queue_admission", result, issued);
    }

    @ParameterizedTest @MethodSource("backends")
    void tokenCanOnlyBeConsumedOnceAcrossConcurrentClients(Backend backend) throws Exception {
        long c = ids.incrementAndGet();
        backend.store.enter(c, 1, "alice", 1);
        String token = backend.store.admit(c, 1, "alice", 1);
        assertThat(token).isNotNull();
        var result = concurrently(100, i -> backend.store.consume(token));
        assertThat(result.values.stream().filter(v -> v != null).toList()).containsExactly("alice:" + c + ":1");
        record(backend, "token_consumption", result, 1);
    }

    @ParameterizedTest @MethodSource("backends")
    void expiredTokenIsRejected(Backend backend) throws Exception {
        long c = ids.incrementAndGet();
        backend.store.enter(c, 1, "alice", 1);
        String token = backend.store.admit(c, 1, "alice", 1);
        if (backend.name.equals("redis")) {
            redis.expire(RedisKeyGenerator.seatEntryKey(token), Duration.ofMillis(20));
            Thread.sleep(50);
        } else {
            jdbc.update("UPDATE comparison_token SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE token=?", token);
        }
        assertThat(backend.store.consume(token)).isNull();
    }

    @ParameterizedTest @MethodSource("backends")
    void measureIndependentHoldsAndQueueRankReads(Backend backend) throws Exception {
        long warmup = ids.incrementAndGet();
        // Warm up both stores before the throughput sample. Correctness scenarios are not benchmarks.
        for (int i = 0; i < 100; i++) backend.store.hold(warmup, 1, i, "warm-" + i);
        long c = ids.incrementAndGet();
        var holds = concurrently(1000, i -> backend.store.hold(c, 1, i, "user-" + i));
        assertThat(holds.values).allMatch(v -> v == ReservationStore.HoldResult.HELD);
        record(backend, "independent_holds", holds, 1000);
        for (int i = 0; i < 1000; i++) backend.store.enter(c, 1, "user-" + i, i);
        var ranks = concurrently(1000, i -> backend.store.rank(c, 1, "user-" + i));
        assertThat(ranks.values).allMatch(v -> v != null && v >= 0 && v < 1000);
        record(backend, "queue_rank_reads", ranks, 1000);
    }

    private static void expireHolds(Backend backend, long c, long s) throws Exception {
        if (backend.name.equals("redis")) {
            for (long i = 0; i < 4; i++) redis.expire(RedisKeyGenerator.seatLockKey(c, s, i), Duration.ofMillis(20));
            Thread.sleep(50);
        } else {
            jdbc.update("UPDATE comparison_hold SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE concert_id=? AND schedule_id=?", c, s);
        }
    }

    record Sample<T>(List<T> values, List<Long> nanos, long elapsed) {}
    private static <T> Sample<T> concurrently(int requests, IntFunction<T> operation) throws Exception {
        int clients = 16;
        var ready = new CountDownLatch(clients);
        var start = new CountDownLatch(1);
        var nanos = Collections.synchronizedList(new ArrayList<Long>());
        try (var executor = Executors.newFixedThreadPool(clients)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < requests; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    long before = System.nanoTime();
                    T value = operation.apply(index);
                    nanos.add(System.nanoTime() - before);
                    return value;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            long before = System.nanoTime();
            start.countDown();
            var values = new ArrayList<T>();
            for (var future : futures) values.add(future.get(60, TimeUnit.SECONDS));
            return new Sample<>(values, nanos, System.nanoTime() - before);
        }
    }

    private static void record(Backend backend, String scenario, Sample<?> sample, long successes) {
        var sorted = sample.nanos.stream().sorted().toList();
        String row = String.format(java.util.Locale.ROOT, "%s,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.1f",
                backend.name, scenario, sample.values.size(), successes, sample.elapsed / 1e6,
                percentile(sorted, .50), percentile(sorted, .95), percentile(sorted, .99),
                sample.values.size() * 1e9 / sample.elapsed);
        timings.add(row);
        System.out.println(row);
    }
    private static double percentile(List<Long> sorted, double fraction) {
        return sorted.get((int) Math.ceil(sorted.size() * fraction) - 1) / 1e6;
    }
}
