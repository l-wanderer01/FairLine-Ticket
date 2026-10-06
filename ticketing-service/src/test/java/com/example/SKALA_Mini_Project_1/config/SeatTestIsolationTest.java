package com.example.SKALA_Mini_Project_1.config;

import com.example.SKALA_Mini_Project_1.global.config.TicketingKafkaConfig;
import com.example.SKALA_Mini_Project_1.integration.concert.ConcertServiceClient;
import com.example.SKALA_Mini_Project_1.integration.userauth.UserAuthClient;
import com.example.SKALA_Mini_Project_1.kafka.PaymentEventConsumer;
import com.example.SKALA_Mini_Project_1.modules.bookings.repository.BookingRepository;
import com.example.SKALA_Mini_Project_1.modules.events.service.TicketingInboxEventService;
import com.example.SKALA_Mini_Project_1.modules.finalization.service.TicketingFinalizationService;
import com.example.SKALA_Mini_Project_1.modules.fanscore.*;
import com.example.SKALA_Mini_Project_1.modules.reconciliation.repository.ReconciliationTaskRepository;
import com.example.SKALA_Mini_Project_1.modules.reconciliation.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeatTestIsolationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentEventConsumer.class, FanScoreSyncInitializer.class,
                    FanScoreSyncScheduler.class, ReconciliationMonitorScheduler.class,
                    ReconciliationReplayScheduler.class)
            .withBean(FanScoreService.class, () -> mock(FanScoreService.class))
            .withBean(ReconciliationTaskRepository.class, () -> mock(ReconciliationTaskRepository.class))
            .withBean(ReconciliationTaskService.class, () -> mock(ReconciliationTaskService.class))
            .withBean(TicketingInboxEventService.class, () -> mock(TicketingInboxEventService.class))
            .withBean(TicketingFinalizationService.class, () -> mock(TicketingFinalizationService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void defaultSettingsKeepExistingBackgroundBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaymentEventConsumer.class)
                    .hasSingleBean(FanScoreSyncInitializer.class)
                    .hasSingleBean(FanScoreSyncScheduler.class)
                    .hasSingleBean(ReconciliationMonitorScheduler.class)
                    .hasSingleBean(ReconciliationReplayScheduler.class);
        });
    }

    @Test
    void seatTestPropertiesRemoveAllBackgroundBeansAndKafkaInfrastructure() {
        runner.withUserConfiguration(TicketingKafkaConfig.class, KafkaTopicConfig.class)
                .withInitializer(context -> {
                    try {
                        context.getEnvironment().getPropertySources().addFirst(new ResourcePropertySource(
                                new ClassPathResource("application-seat-test.properties")));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PaymentEventConsumer.class)
                            .doesNotHaveBean(FanScoreSyncInitializer.class)
                            .doesNotHaveBean(FanScoreSyncScheduler.class)
                            .doesNotHaveBean(ReconciliationMonitorScheduler.class)
                            .doesNotHaveBean(ReconciliationReplayScheduler.class)
                            .doesNotHaveBean(TicketingKafkaConfig.class)
                            .doesNotHaveBean(KafkaTopicConfig.class);
                });
    }

    @Test
    void disabledFanScoreDoesNotQueryOrMarkBookingAsApplied() {
        BookingRepository bookings = mock(BookingRepository.class);
        FanScoreQueryRepository queries = mock(FanScoreQueryRepository.class);
        ConcertServiceClient concerts = mock(ConcertServiceClient.class);
        UserAuthClient users = mock(UserAuthClient.class);
        FanScoreMetrics metrics = mock(FanScoreMetrics.class);
        FanScoreService service = new FanScoreService(bookings, queries, concerts, users, metrics);
        ReflectionTestUtils.setField(service, "syncEnabled", false);
        service.applyConfirmedBookingScore(UUID.randomUUID(), 1L);
        assertThat(service.syncArtistFanScoresFromConfirmedBookings()).isZero();
        verifyNoInteractions(bookings, queries, concerts, users, metrics);
    }

    @Test
    void excludedClientOperationsFailBeforeMakingHttpRequests() {
        UserAuthClient users = new UserAuthClient(RestClient.builder(), "http://127.0.0.1:1", "test");
        ConcertServiceClient concerts = new ConcertServiceClient(RestClient.builder(), "http://127.0.0.1:1", "test");
        ReflectionTestUtils.setField(users, "externalCallsEnabled", false);
        ReflectionTestUtils.setField(concerts, "externalCallsEnabled", false);
        assertThatThrownBy(() -> users.getUserProfile(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the seat-test scope");
        assertThatThrownBy(() -> users.applyAttendanceConfirmedFanScore(1L, UUID.randomUUID(), 2L, 3L, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside the seat-test scope");
        assertThatThrownBy(() -> concerts.getArtistIdForConcert(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the seat-test scope");
    }
}
