# 테스트 기준

## 범위

1차 범위는 ticketing-service의 단일·일괄 선점, 해제, 사용자별 제한, TTL, 예약 생성과 선점의 연결, 확정·취소·만료, 화면 이탈 정리다. shared-kernel의 Redis Lua도 포함한다.

대기열 토큰 발급·인증·결제 PG·Kafka·CDC·프론트 화면은 2차 연동 범위다. 1차에서 내부 확정 API를 호출하는 것은 결제 후 티켓팅 동작을 검증하는 것이며 실제 PG 승인이나 이벤트 전달을 검증한 것으로 간주하지 않는다.

## 핵심 불변 조건

| ID | 합격 기준 |
| --- | --- |
| INV-01 | 같은 좌석의 유효한 Redis 소유자는 최대 1명이다. |
| INV-02 | 사용자별 같은 공연·회차의 유효한 선점은 최대 4석이다. |
| INV-03 | 타인의 해제·늦은 요청·정리 작업은 현재 소유자의 선점을 삭제하지 않는다. |
| INV-04 | 같은 좌석을 포함하는 CONFIRMED 예약은 최대 1개다. |
| INV-05 | 한 예약의 좌석 전체가 확정되지 않았다면 예약 전체를 성공 확정으로 처리하지 않는다. |
| INV-06 | 동일 좌석에 동시에 유효한 HOLDING/CONFIRMED 예약이 중복 생성되지 않는다. |
| INV-07 | 같은 입장 세션 정리로 활성 인원을 중복 차감하지 않으며 0 미만이 되지 않는다. |

INV-06의 HOLDING 유효 기준은 DB 기준 시각에서 `expires_at > now`다. Redis 소유권과 예약 상태는 별도 상태이므로 각각 검사한다.

## 코드에서 확인한 현재 동작

| 항목 | 현재 구현 | 근거 |
| --- | --- | --- |
| 단일 선점 | 같은 소유자가 다시 요청하면 해제하는 토글 | [SeatReservationService](../../ticketing-service/src/main/java/com/example/SKALA_Mini_Project_1/modules/seats/service/SeatReservationService.java) |
| TTL·수량 | 5분, 사용자/공연/회차별 4석, Lua에서 stale 참조 정리 후 제한 검사 | [RedisLockRepository](../../shared-kernel/src/main/java/com/example/SKALA_Mini_Project_1/global/redis/RedisLockRepository.java) |
| 일괄 선점 | 좌석별 Lua 호출, 실패 시 이번 요청의 신규 선점 보상 해제 | SeatReservationService |
| 중복 ID | 서비스는 중복 제거, HTTP DTO는 원본 목록 길이 최대 4개 검사 | [BatchSeatHoldRequest](../../ticketing-service/src/main/java/com/example/SKALA_Mini_Project_1/modules/seats/dto/BatchSeatHoldRequest.java) |
| 예약 생성 | 충돌 조회 → Redis 소유자·TTL 확인 → 예약과 항목 저장. 최소 TTL로 만료 설정 | [BookingService](../../ticketing-service/src/main/java/com/example/SKALA_Mini_Project_1/modules/bookings/service/BookingService.java) |
| 확정 | 예약 행 잠금, 선점 검증 실패 시 경고 후 계속 확정, 좌석 UPDATE 건수 미확인 | [TicketingFinalizationService](../../ticketing-service/src/main/java/com/example/SKALA_Mini_Project_1/modules/finalization/service/TicketingFinalizationService.java) |
| 정리 | DB 커밋 후 사용자/회차의 선점 전체와 접근 키 정리 | TicketingFinalizationService |
| 좌석 접근 | 보안 설정은 JWT 인증을 요구. hold에서 입장 접근 키 검사는 확인되지 않음 | [SecurityConfig](../../ticketing-service/src/main/java/com/example/SKALA_Mini_Project_1/global/config/SecurityConfig.java), SeatController |

위 동작은 코드 분석 결과다. 위험은 재현 전까지 확정 결함으로 기록하지 않는다. 특히 Redis 단일 Lua의 원자성과 일괄 요청 전체의 원자성은 다르다.

## 결정할 정책

| ID | 결정 사항 | 테스트에 사용할 제안 / 영향 |
| --- | --- | --- |
| POL-01 | 단일 선점의 재시도 의미 | 현재 토글 동작을 특성 테스트로 기록. 유지형 API 또는 요청 ID 멱등성 여부는 미결정. TC-04 |
| POL-02 | 일괄 실패의 범위 | 기존 선점 유지, 이번 신규 선점 전부 정리 제안. 장애 시 정리 완료 기한도 미결정. TC-11~13, 25 |
| POL-03 | 예약 생성 후 해제·재선점 | 진행 중 예약 좌석 변경 허용 여부와 Redis/DB 충돌 시 우선권 미결정. TC-18~20 |
| POL-04 | 늦은 결제 확정 | 선점 만료 이후 결제 수용·환불·보정 정책 미결정. 중복 확정 금지는 항상 적용. TC-20~22 |
| POL-05 | 대기열 입장 강제 | JWT만 있는 사용자의 hold 허용 여부 미결정. TC-08 |
| POL-06 | 정리 대상 | 예약 좌석만 정리할지 같은 사용자의 해당 회차 전체를 정리할지 미결정. TC-24 |
| POL-07 | 성능 목표 | 예상 동시 사용자·요청률·p95/p99·오류율·복구 시간 목표 미결정. TC-27 |

정책 결정 시 결정일·근거·선택한 동작을 이 표에 추가하고 해당 기대 결과를 갱신한다.

## 기존 테스트와 준비 공백

RedisLockRepositoryTest의 Mockito 테스트 2건은 그대로 유지한다. 실제 Lua·TTL·경쟁·예약 native query와 늦은 확정은 [Testcontainers 회귀](06-testcontainers.md)에 추가했다. 공통 초기화 SQL은 `test-infra/seat-hold/sql/`에서 Compose와 자동 테스트가 함께 사용한다. 실제 판정은 [#6 결과](results/issue-6.md)를 따른다. 사용자 수동 smoke와 자동화하지 않은 장애·다중 인스턴스·성능 케이스는 별도 실행이 필요하다.
