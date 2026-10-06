package com.example.SKALA_Mini_Project_1.seatintegration;

import com.example.SKALA_Mini_Project_1.modules.bookings.dto.CreateBookingRequest;
import com.example.SKALA_Mini_Project_1.modules.bookings.dto.CreateBookingResponse;
import com.example.SKALA_Mini_Project_1.modules.bookings.service.BookingService;
import com.example.SKALA_Mini_Project_1.modules.finalization.dto.InternalBookingConfirmRequest;
import com.example.SKALA_Mini_Project_1.modules.finalization.service.TicketingFinalizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

@Import(BookingInvariantIntegrationTest.RaceConfiguration.class)
class BookingInvariantIntegrationTest extends SeatIntegrationSupport {
    @Autowired BookingService bookings;
    @Autowired TicketingFinalizationService finalizations;
    @Autowired ObjectMapper mapper;
    @Autowired ConflictReadBarrier barrier;

    CreateBookingRequest request() throws Exception {
        return mapper.readValue("{\"concertId\":201,\"seatIds\":[401]}", CreateBookingRequest.class);
    }

    @Test
    void commonFixtureAndBookingAmountExpiryUseActualNativeQueries() throws Exception {
        seats.reserveSeatTemporary(301L, 401L, 101L);
        long before = locks.getSeatLockTtlSeconds(201L, 301L, 401L);
        CreateBookingResponse response = bookings.createBooking(101L, request());
        assertThat(response.getStatus()).isEqualTo("HOLDING");
        assertThat(response.getTotalPrice()).isEqualTo(10000);
        assertThat(jdbc.queryForObject("select count(*) from ticketing.booking_items where booking_id=?", Integer.class,
                response.getBookingId())).isEqualTo(1);
        Long duration = jdbc.queryForObject("select extract(epoch from expires_at-created_at)::bigint "
                + "from ticketing.bookings where id=?", Long.class, response.getBookingId());
        assertThat(duration).isBetween(before - 2, before);
    }

    @Test
    void tc19ConcurrentBookingsMustNotCreateTwoActiveReservations() throws Exception {
        seats.reserveSeatTemporary(301L, 401L, 101L);
        CreateBookingRequest request = request();
        barrier.arm();
        List<UUID> created;
        try {
            created = concurrent(2, index -> {
                try {
                    return bookings.createBooking(101L, request).getBookingId();
                } catch (IllegalStateException legitimateConflict) {
                    assertThat(legitimateConflict).hasMessageContaining("이미 진행 중이거나 확정된 예약");
                    return null;
                }
            }).stream().filter(java.util.Objects::nonNull).toList();
        } finally {
            barrier.disarm();
        }
        assertThat(barrier.pids).as("TC-19 must run in two separate PostgreSQL connections").hasSize(2);
        assertThat(barrier.emptyCount.get()).as("both real conflict queries completed before either save").isEqualTo(2);
        long active = activeReservations();
        System.out.printf("TC-19 evidence: pids=%s, emptyReads=%d, bookingIds=%s, active=%d%n",
                barrier.pids, barrier.emptyCount.get(), created, active);
        assertThat(active).as("TC-19 / INV-06: simultaneous active reservations for seat 401").isLessThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "TC-20 late A confirmation first={0}")
    @ValueSource(booleans = {true, false})
    void tc20LateConfirmationMustNotDoubleConfirm(boolean lateFirst) throws Exception {
        seats.reserveSeatTemporary(301L, 401L, 101L);
        UUID a = bookings.createBooking(101L, request()).getBookingId();
        // Boundary injection, distinct from TC-15's real 5-minute wait.
        redis.expire(key(401), java.time.Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() -> owner(401) == null);
        jdbc.update("update ticketing.bookings set expires_at=now()-interval '1 second' where id=?", a);
        assertThat(jdbc.queryForObject("select expires_at < now() from ticketing.bookings where id=?", Boolean.class,a)).isTrue();
        seats.reserveSeatTemporary(301L, 401L, 102L);
        UUID b = bookings.createBooking(102L, request()).getBookingId();
        UUID first = lateFirst ? a : b;
        UUID second = lateFirst ? b : a;
        var firstResponse = finalizations.confirmBooking(confirm(first));
        var secondResponse = finalizations.confirmBooking(confirm(second));
        long confirmed = jdbc.queryForObject("select count(distinct b.id) from ticketing.bookings b "
                + "join ticketing.booking_items bi on bi.booking_id=b.id where bi.seat_id=401 and b.status='CONFIRMED'", Long.class);
        String firstStored = jdbc.queryForObject("select status from ticketing.bookings where id=?", String.class, first);
        String secondStored = jdbc.queryForObject("select status from ticketing.bookings where id=?", String.class, second);
        System.out.printf("TC-20 evidence: lateFirst=%s, a=%s, b=%s, outcomes=%s/%s, holdValid=%s/%s, stored=%s/%s, confirmed=%d%n",
                lateFirst,a,b,firstResponse.outcome(),secondResponse.outcome(),
                firstResponse.holdValid(),secondResponse.holdValid(),firstStored,secondStored,confirmed);
        // A zero count must not give a vacuous pass when both responses claim CONFIRMED.
        org.junit.jupiter.api.Assertions.assertAll("TC-20 persisted outcome and uniqueness",
                () -> assertThat(confirmed).as("INV-04: confirmed reservations for seat 401").isLessThanOrEqualTo(1),
                () -> assertThat(firstStored).as("first response bookingStatus must match committed DB")
                        .isEqualTo(firstResponse.bookingStatus()),
                () -> assertThat(secondStored).as("second response bookingStatus must match committed DB")
                        .isEqualTo(secondResponse.bookingStatus()),
                () -> assertThat(jdbc.queryForObject("select status from concert.seats where id=401", String.class))
                        .as("successful confirmation must reserve the seat").isEqualTo("RESERVED"));
    }

    long activeReservations() {
        return jdbc.queryForObject("select count(distinct b.id) from ticketing.bookings b "
                + "join ticketing.booking_items bi on bi.booking_id=b.id where bi.seat_id=401 and "
                + "(b.status='CONFIRMED' or (b.status='HOLDING' and b.expires_at>now()))", Long.class);
    }

    InternalBookingConfirmRequest confirm(UUID id) {
        return new InternalBookingConfirmRequest(UUID.randomUUID(), "test-fake-order", "test-fake-key", 10000L,
                OffsetDateTime.now(ZoneOffset.UTC), id);
    }

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class RaceConfiguration {
        @Bean ConflictReadBarrier conflictReadBarrier(JdbcTemplate jdbc) { return new ConflictReadBarrier(jdbc); }
    }

    // Test-only advice. Actual Spring Data native query and transaction proceed first.
    // No mock query, transaction rollback wrapper or production hooks.
    @Aspect
    static class ConflictReadBarrier {
        final JdbcTemplate jdbc;
        volatile CyclicBarrier gate;
        final Set<Integer> pids = ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger emptyCount = new java.util.concurrent.atomic.AtomicInteger();
        ConflictReadBarrier(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        void arm() { pids.clear(); emptyCount.set(0); gate = new CyclicBarrier(2); }
        void disarm() { gate = null; }
        @Around("execution(* com.example.SKALA_Mini_Project_1.modules.bookings.repository.BookingItemRepository.findActiveConflictSeatIds(..))")
        Object afterConflictRead(ProceedingJoinPoint join) throws Throwable {
            Object result = join.proceed();
            CyclicBarrier current = gate;
            if (current != null) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat((List<?>) result).isEmpty();
                pids.add(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                emptyCount.incrementAndGet();
                current.await(15, TimeUnit.SECONDS);
            }
            return result;
        }
    }
}
