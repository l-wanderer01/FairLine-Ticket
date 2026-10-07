package com.example.SKALA_Mini_Project_1.seatintegration;

import com.example.SKALA_Mini_Project_1.modules.seats.service.SeatReservationService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class SeatRedisIntegrationTest extends SeatIntegrationSupport {
    @Test
    void tc01NormalHoldUsesRealLuaAndLeavesDbAvailable() {
        assertThat(seats.reserveSeatTemporary(301L, 401L, 101L))
                .isEqualTo(SeatReservationService.SeatHoldResult.HELD);
        assertThat(owner(401)).isEqualTo("101");
        assertThat(ttl(401)).isBetween(1L, 300000L);
        assertThat(redis.opsForSet().members(holds(101))).containsExactly("401");
        assertThat(jdbc.queryForObject("select status from concert.seats where id=401", String.class))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void tc05OtherUserCannotReleaseAndOwnReleaseIsRepeatable() {
        seats.reserveSeatTemporary(301L, 401L, 101L);
        assertThatThrownBy(() -> seats.releaseSeatHold(301L, 401L, 102L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(owner(401)).isEqualTo("101");
        assertThat(redis.opsForSet().members(holds(102))).isEmpty();
        assertThat(seats.releaseSeatHold(301L, 401L, 101L))
                .isEqualTo(SeatReservationService.SeatReleaseResult.RELEASED);
        assertThat(seats.releaseSeatHold(301L, 401L, 101L))
                .isEqualTo(SeatReservationService.SeatReleaseResult.ALREADY_RELEASED);
        assertThat(owner(401)).isNull();
        assertThat(ttl(401)).isEqualTo(-2);
        assertThat(redis.opsForSet().members(holds(101))).isEmpty();
    }

    @RepeatedTest(20)
    void tc03HundredUsersStartTogetherAndExactlyOneWins() throws Exception {
        List<Long> winners = concurrent(100, index -> {
            long user = 1001L + index;
            try {
                assertThat(seats.reserveSeatTemporary(301L, 401L, user))
                        .isEqualTo(SeatReservationService.SeatHoldResult.HELD);
                return user;
            } catch (IllegalStateException expectedConflict) {
                assertThat(expectedConflict).hasMessageContaining("이미 다른 사용자가");
                return null;
            }
        }).stream().filter(java.util.Objects::nonNull).toList();
        assertThat(winners).hasSize(1);
        assertThat(owner(401)).isEqualTo(winners.getFirst().toString());
        assertThat(ttl(401)).isBetween(1L, 300000L);
        for (long user = 1001; user <= 1100; user++)
            assertThat(redis.opsForSet().members(holds(user)))
                    .hasSize(user == winners.getFirst() ? 1 : 0);
    }

    @RepeatedTest(20)
    void tc07ConcurrentSingleRequestsKeepExactlyFourValidHolds() throws Exception {
        List<Boolean> results = concurrent(8, index -> {
            try {
                assertThat(seats.reserveSeatTemporary(301L, 401L + index, 101L))
                        .isEqualTo(SeatReservationService.SeatHoldResult.HELD);
                return true;
            } catch (IllegalArgumentException expectedLimit) {
                assertThat(expectedLimit).hasMessageContaining("최대 4매");
                return false;
            }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(4);
        Set<String> members = redis.opsForSet().members(holds(101));
        assertThat(members).hasSize(4);
        assertThat(LongStream.rangeClosed(401, 408).filter(id -> "101".equals(owner(id))).count()).isEqualTo(4);
        for (String member : members) assertThat(ttl(Long.parseLong(member))).isBetween(1L, 300000L);
    }

    @Test
    @Tag("slow-ttl")
    @Timeout(330)
    void tc15ActualFiveMinuteExpirationAllowsReholdAndPrunesStaleReferences() {
        for (long seat = 401; seat <= 404; seat++) seats.reserveSeatTemporary(301L, seat, 101L);
        assertThat(ttl(401)).isBetween(295000L, 300000L);
        System.out.println("TC-15: waiting for actual 300-second production TTL (no shortened TTL)");
        await().atMost(Duration.ofSeconds(315)).pollInterval(Duration.ofSeconds(1))
                .until(() -> LongStream.rangeClosed(401, 404).allMatch(id -> owner(id) == null));
        assertThat(seats.reserveSeatTemporary(301L, 401L, 102L))
                .isEqualTo(SeatReservationService.SeatHoldResult.HELD);
        assertThat(seats.reserveSeatTemporary(301L, 405L, 101L))
                .isEqualTo(SeatReservationService.SeatHoldResult.HELD);
        assertThat(owner(401)).isEqualTo("102");
        assertThat(owner(405)).isEqualTo("101");
        assertThat(redis.opsForSet().members(holds(101))).containsExactly("405");
        assertThat(redis.opsForSet().members(holds(102))).containsExactly("401");
    }
}
