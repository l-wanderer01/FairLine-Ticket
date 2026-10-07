package com.example.SKALA_Mini_Project_1.seatintegration;

import com.example.SKALA_Mini_Project_1.TicketingServiceApplication;
import com.example.SKALA_Mini_Project_1.global.redis.RedisKeyGenerator;
import com.example.SKALA_Mini_Project_1.global.redis.RedisLockRepository;
import com.example.SKALA_Mini_Project_1.modules.seats.service.SeatReservationService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(classes = TicketingServiceApplication.class)
@ActiveProfiles("seat-test")
@Testcontainers
@Tag("seat-integration")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class SeatIntegrationSupport {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("seat_test")
            .withCopyFileToContainer(MountableFile.forClasspathResource("seat-test/sql/01-schema.sql"),
                    "/docker-entrypoint-initdb.d/01-schema.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("seat-test/sql/02-fixture.sql"),
                    "/docker-entrypoint-initdb.d/02-fixture.sql");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.security.user.password", () -> "public-test-only-disabled-login");
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired RedisTemplate<String, String> redis;
    @Autowired RedisLockRepository locks;
    @Autowired SeatReservationService seats;

    @BeforeEach
    void resetFixture() {
        new ResourceDatabasePopulator(new ClassPathResource("seat-test/sql/02-fixture.sql"))
                .execute(dataSource);
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    String key(long seat) { return RedisKeyGenerator.seatLockKey(201L, 301L, seat); }
    String holds(long user) { return RedisKeyGenerator.seatUserHoldsKey(201L, 301L, String.valueOf(user)); }
    String owner(long seat) { return redis.opsForValue().get(key(seat)); }
    long ttl(long seat) { return redis.getExpire(key(seat), TimeUnit.MILLISECONDS); }

    <T> List<T> concurrent(int count, IntFunction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CyclicBarrier start = new CyclicBarrier(count);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await(20, TimeUnit.SECONDS);
                    return action.apply(index);
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            futures.forEach(future -> future.cancel(true));
            pool.shutdownNow();
            if (!pool.awaitTermination(20, TimeUnit.SECONDS))
                throw new IllegalStateException("Workers did not stop; fixture isolation cannot be guaranteed");
        }
    }
}
