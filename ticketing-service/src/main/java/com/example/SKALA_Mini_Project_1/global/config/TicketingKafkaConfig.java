package com.example.SKALA_Mini_Project_1.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

@Configuration
@EnableKafka
@ConditionalOnProperty(name = "ticketing.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TicketingKafkaConfig {
}
