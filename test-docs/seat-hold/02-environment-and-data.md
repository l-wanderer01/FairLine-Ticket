# 환경 및 데이터 준비

## 실행 전 체크리스트

- [ ] 코드 커밋, JDK 21, Gradle, Redis/PostgreSQL 버전 기록.
- [ ] 테스트 전용 Redis와 PostgreSQL 사용. 운영 데이터·서비스와 연결하지 않음.
- [ ] 스키마 및 데이터 초기화 스크립트 준비. 현재 Compose의 init.sql은 누락되어 있음.
- [x] `seat-test` 프로필 및 [독립 Compose](../../test-infra/seat-hold/README.md): DB/Redis 주소, 테스트 JWT·내부 API 토큰, 외부 의존성 설정.
- [x] 스케줄러·Kafka 리스너 격리: [단독 실행 의존성](01-standalone-dependencies.md)의 비활성화 설정 적용.
- [ ] 실제 Redis Lua와 PostgreSQL 네이티브 쿼리를 실행하는 통합 테스트 구성. Testcontainers 권장, 현재 추가되어 있지 않음.
- [ ] 동시 시작 barrier와 서비스 내부 중단 지점/latch 또는 장애 프록시 준비.
- [ ] DB 실제 제약조건·인덱스 확인. 엔티티 선언만으로 중복 방지 여부 단정하지 않음.
- [ ] 요청·로그·Redis·DB 스냅샷을 run ID로 연결. 비밀값과 JWT는 결과에 저장하지 않음.

## 단계별 구성

| 단계 | 구성 | 검증 한계 |
| --- | --- | --- |
| 단위 | JUnit/Mockito | 입력·분기·보상 호출만 검증; Lua/TTL/경쟁 보장 불가 |
| 통합 | 실제 Redis 7, PostgreSQL 17, 티켓팅 코드 | 선점·예약·DB 정합성. 필요한 외부 클라이언트는 stub |
| HTTP | ticketing-service + 위 저장소 + 유효 JWT/내부 토큰 | 필터·DTO·응답까지 포함 |
| 다중 인스턴스 | 티켓팅 2개 이상, 같은 Redis/DB | 프로세스 사이 경쟁; 대상 인스턴스별 요청 수 기록 |
| MSA 연동 | 인증·대기열·결제·Kafka·Debezium 추가 | 토큰 발급과 결제 이벤트 전달까지 별도 검증 |

## 데이터 fixture 계약

아래 ID는 논리 별칭이다. 생성 후 실제 ID 매핑을 결과 문서에 기록한다. 사용자·공연·회차·좌석·예약의 관계와 필요한 schema/FK를 충족해야 한다.

| 별칭 | 데이터 |
| --- | --- |
| U-A, U-B, U-C | 서로 다른 테스트 사용자와 유효 JWT |
| U-001~U-100 | 단일 좌석 경쟁용 서로 다른 사용자 |
| C-A / SCH-A1, SCH-A2 | 한 공연의 서로 다른 회차 |
| C-B / SCH-B1 | 다른 공연과 회차 |
| S01~S08 | SCH-A1의 AVAILABLE 좌석. 위치 A구역 1열 1~8번, 가격 각각 10000 |
| S09 | SCH-A1의 RESERVED 좌석. 이를 소유하는 CONFIRMED 예약 포함 |
| S10 | SCH-A2의 AVAILABLE 좌석 |
| S11 | SCH-B1의 AVAILABLE 좌석 |
| UNKNOWN | 실제로 존재하지 않는 좌석·회차·공연 ID |

기본 상태는 Redis 선점·사용자 목록·접근 키 없음, 활성 인원 0, S09 이외 진행/확정 예약 없음이다. 케이스에서 요구하는 선점은 실제 API 또는 repository로 생성한다. 이탈 케이스는 접근 키·접근 인덱스·활성 인원을 일관되게 초기화한다. 일반 hold 테스트는 접근 키가 없어도 현재 구현에서 실행되므로 대기열 입장 검증과 분리한다.

## 요청 계약

JWT 요청은 `Authorization: Bearer <테스트 JWT>`를 사용한다.

| 동작 | HTTP | 입력 |
| --- | --- | --- |
| 단일 선점/토글 | POST /api/seats/hold | scheduleId, section, rowNumber, seatNumber |
| 단일 해제 | DELETE /api/seats/hold | 위와 동일 |
| 일괄 선점 | POST /api/seats/holds | concertId, seatIds |
| 화면 이탈 | POST /api/seats/leave?concertId=...&scheduleId=... | JWT |
| 예약 생성 | POST /api/bookings | concertId, seatIds |
| 확정/취소/만료 | POST /internal/finalizations/confirm 또는 cancel 또는 expire | 실제 DTO에 맞는 bookingId와 이벤트 정보, 내부 토큰 |

확정 요청의 필드는 bookingId, paymentId, pgOrderId, pgPaymentKey, amount, confirmedAt이다. 내부 토큰 헤더명은 InternalApiGuard.HEADER_NAME을 확인한다. 내부 API 테스트는 가짜 PG 식별자만 사용한다.

## 관찰 및 초기화

Redis 키는 아래 형식을 사용한다. 각 run의 실제 ID로 치환한다.

```text
seat:concert:<C>:schedule:<SCH>:seatId:<S>          # GET, PTTL
seat:user:holds:concert:<C>:schedule:<SCH>:user:<U> # SMEMBERS
seat:access:user:<U>:concert:<C>:schedule:<SCH>
seat:access:user:<U>:schedule:<SCH>
seat:access:index:concert:<C>:schedule:<SCH>
seat:active:concert:<C>:schedule:<SCH>
```

경쟁 중 관측은 시각 차이가 있으므로 최종 검증은 모든 요청·보상·커밋 완료 후 수행한다. Lua 내부 원자성은 결과와 별도 스크립트 통합 테스트로 확인한다. TTL은 소유자와 함께 관측하고, 정확한 300초 일치를 요구하지 않는다. 성공 직후 PTTL은 `0 < PTTL <= 300000`이어야 한다.

각 케이스 전 fixture 재생성 또는 해당 데이터만 정리하고 기본 상태를 확인한다. 활성 인원·접근 인덱스·booking_items·bookings·좌석 상태까지 초기화한다. 전용 컨테이너 재생성이 가장 단순하다. 실패 증거를 수집하기 전에 정리하지 않는다.

TTL 경계 테스트는 실제 5분 만료 검증을 최소 1회 수행한다. 짧은 TTL 주입은 경계 재현용 별도 실행으로 표시하며 운영 TTL 검증을 대체하지 않는다. 선점 TTL과 DB expires_at을 각각 제어하고 기록한다.
