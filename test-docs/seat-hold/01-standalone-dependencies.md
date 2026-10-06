# 좌석 테스트용 티켓팅 단독 실행 의존성 (#1)

## 목적과 범위

`seat-test`는 ticketing-service를 PostgreSQL·Redis만으로 기동하기 위한 명시적 opt-in 프로필이다.
실제 좌석 Lua, JWT 필터, 내부 API guard, DB 트랜잭션, outbox 저장 로직은 유지한다.
Kafka/CDC 전송, 결제/PG, 팬 점수 외부 동기화, 자동 정합성 재처리는 검증 범위에서 제외한다.

테스트 설계와 케이스 기준은 [좌석 선점 테스트 문서](README.md)를 참고한다.
이 문서는 현재 코드 조사와 #1 구현을 기준으로 작성했다. Compose·연결 예제는 #2,
공통 스키마/fixture는 #3, JWT/API 도구는 #4, 사용자 수동 실행은 #5,
실제 데이터 경합 자동 회귀는 #6에서 확장한다.

## 부팅 및 기능별 의존성

| 구성 요소 | 일반 프로필 | seat-test | 이유/검증 한계 |
| --- | --- | --- | --- |
| PostgreSQL / JPA | 필수 | 필수 | 실제 DB 메타데이터와 health 확인. 기능 실행에는 스키마/fixture도 필요 |
| Redis | 좌석 기능 및 health에 필요 | 필수 | 소유권·TTL·Lua·입장 키는 실제 Redis 사용 |
| JWT 필터 / JwtUtil | 로컬 서명 검증 | 그대로 유지 | 인증 서버 HTTP 호출 없음. 토큰 발급/로그인 흐름은 제외 |
| 내부 API guard | X-Internal-Api-Key 검증 | 그대로 유지 | 테스트 토큰이 필요하며 인증 우회하지 않음 |
| Kafka 자동 설정 / 리스너 인프라 | 활성 | 자동 설정 제외, 인프라/consumer Bean 없음 | Kafka 연결 및 payment.events.v1 수신 제외 |
| KafkaTopicConfig | 현재 component scan 범위 밖 | 조건으로 비활성 | 기본 scan에 config/가 없음. 향후 import되어도 테스트에서는 NewTopic을 생성하지 않음 |
| FanScoreSyncInitializer | ApplicationRunner에서 DB 조회·외부 동기화 가능 | Bean 없음 | 기존 확정 예약이 있으면 부팅 중 외부 HTTP 가능 |
| FanScoreSyncScheduler | 주기적 팬 점수 동기화 | Bean 없음 | 외부 concert/user-auth 연동 제외 |
| FanScoreService | 확정 시에도 공연 eligibility 조회 후 외부 호출 가능 | 단건/배치 모두 즉시 반환 | DB의 fan_score_applied_at을 성공처럼 기록하지 않음 |
| ReconciliationMonitor/ReplayScheduler | backlog 관찰/재처리 | Bean 없음 | fixture 변경·자동 재처리와 테스트 간 간섭 방지 |
| UserAuthClient / ConcertServiceClient | 생성 시 HTTP 없음, 기능 호출 시 HTTP | Bean 유지, 호출 즉시 IllegalStateException | 예약 상세 개인정보와 팬 점수 호출은 범위 제외. 가짜 성공 응답 없음 |
| OTLP tracing export | 자동 설정에 따라 사용 | tracing 및 OTLP tracing export 비활성 | 관측 서버 불필요. Prometheus 조회는 유지 |

`@EnableScheduling` 자체는 유지한다. 테스트에서는 이 서비스의 scheduled Bean 세 개를 등록하지 않는다.
Kafka 활성화는 scan되는 `global/config/TicketingKafkaConfig`로 이동했다.
모든 신규 활성화 조건은 `matchIfMissing=true`이므로 일반 프로필의 기본 동작을 유지한다.

## 경로별 확인 대상

| 경로 | 실제 의존성 | seat-test에서 확인 가능한 것 |
| --- | --- | --- |
| /api/seats 선점·일괄·해제·이탈 | JWT, Redis, concert.seats 및 schedule 쿼리 | 실제 선점 Lua, 소유권/TTL, 4석 제한, 좌석 상태. 대기열 입장에는 Redis 입장 키 준비 필요 |
| POST /api/bookings | JWT, Redis hold, concert 좌석/회차 데이터, ticketing bookings/items | 예약 생성, 금액·항목·만료 시각 저장. 외부 사용자 클라이언트 호출 없음 |
| GET 예약 상세 | DB + UserAuthClient.getUserProfile | 개인정보 조회는 제외. 클라이언트 도달 시 HTTP 409 범위 제외 오류를 반환하며 외부 HTTP 요청하지 않음 |
| /internal/finalizations 확정·취소·만료 | 내부 guard, DB, Redis, reconciliation 기록, outbox 기록 | 직접 내부 호출에 따른 상태 변경·cleanup·outbox 저장. 팬 점수는 건너뜀 |
| Kafka payment.events.v1 | Kafka + inbox + finalization | 제외. 직접 내부 확정 검증은 결제 승인·이벤트 전달 검증을 의미하지 않음 |

내부 확정의 기존 동작은 hold가 만료되거나 없더라도 확정을 진행할 수 있다.
이번 의존성 정리는 이 예약 정합성 동작을 변경하지 않는다. 늦은 확정/중복 예약은 후속 회귀 대상이다.
Outbox는 DB 저장이므로 Kafka 없이도 유지하지만, Debezium 전달 및 외부 소비는 검증하지 않는다.

## 기동 방법

먼저 독립 PostgreSQL과 Redis를 실행한다. `DB_URL`, `DB_USER`, `DB_PASSWORD`,
`REDIS_HOST`, `REDIS_PORT`는 실제 테스트 인스턴스로 지정한다.
예를 들어 로컬 DB 15432, Redis 16379가 준비되어 있다면:

```bash
bash gradlew :ticketing-service:bootJar :ticketing-service:test
DB_URL=jdbc:postgresql://127.0.0.1:15432/seat_test \
DB_USER=seat_test DB_PASSWORD=seat_test_only \
REDIS_HOST=127.0.0.1 REDIS_PORT=16379 SERVER_PORT=18080 \
java -jar ticketing-service/build/libs/ticketing-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=seat-test
curl --fail http://127.0.0.1:18080/actuator/health
curl --fail http://127.0.0.1:18080/actuator/health/readiness
```

예제의 비밀번호와 프로필 기본 JWT/내부 토큰은 공개된 테스트 전용 값이다.
배포 환경에서 이 프로필을 사용하지 않는다. JWT_SECRET/TICKETING_INTERNAL_API_TOKEN으로 별도 값을 주입할 수 있다.
외부 클라이언트용 토큰 기본값은 비활성 placeholder이며 실제 서버로 전송하지 않는다.
부팅 확인에는 빈 DB를 사용할 수 있으나 health UP은 업무 테이블/fixture 준비를 보장하지 않는다.
`ddl-auto=none`이므로 #3 SQL 준비 이후 실제 좌석 API 검증을 수행한다.

## 검증

`SeatTestIsolationTest`는 기본 프로필의 background Bean 유지, seat-test 설정의
Kafka/초기화/스케줄러 Bean 제외, 확정 시 팬 점수 조회·반영 없음,
범위 밖 클라이언트 호출의 HTTP 이전 실패를 검증한다.
기존 `FanScoreServiceTest`는 기본 설정에서 팬 점수 동작을 계속 검증한다.
실제 컨테이너 부팅 결과는 [실행 결과](results/issue-1.md)에 기록한다.
