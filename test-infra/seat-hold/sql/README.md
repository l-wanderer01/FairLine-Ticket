# 공통 SQL / fixture 계약 (#3)

새 전용 DB에 `01-schema.sql` → `02-fixture.sql` 순서로 실행한다.
Compose의 PostgreSQL entrypoint와 #6 Testcontainers가 동일 파일을 사용한다.
`01-schema.sql`은 새 DB 전용으로, 중복 실행하면 실패한다. `02-fixture.sql`은
명시한 테스트 테이블을 한 트랜잭션에서 TRUNCATE/RESTART IDENTITY하고 동일 데이터로 복구한다.
오류를 무시하지 않도록 psql에는 `-v ON_ERROR_STOP=1`을 사용한다.

| 별칭 | 실제 ID / 상태 |
| --- | --- |
| U-A / U-B / U-C | 101 / 102 / 103 |
| U-001~U-100 | 1001~1100, 이메일 u-001~u-100@seat-test.invalid |
| C-A / C-B | 201 / 202, artist 11 / 12 |
| SCH-A1 / SCH-A2 / SCH-B1 | 301 / 302 / 303 |
| S01~S08 | 401~408, SCH-A1, A구역 1열 1~8번, AVAILABLE |
| S09 | 409, SCH-A1, A구역 1열 9번, RESERVED |
| S10 / S11 | 410 / 411, SCH-A2 / SCH-B1, A구역 1열 1번, AVAILABLE |
| 판매 완료 예약 | 00000000-0000-0000-0000-000000000009, U-C, S09, CONFIRMED |
| UNKNOWN | 999999 (user/concert/schedule/seat 어디에도 없음) |

가격은 모든 좌석 10000, grade는 TEST다. 생성 시각·공연 시각은 고정 UTC 값이며
새 예약의 created_at/expires_at은 실제 앱 시각으로 생성된다. 기본 예약/항목은 각각 1개,
나머지 inbox/outbox/reconciliation/fan score 테이블은 비어 있다.
로그인은 범위 밖이므로 password는 로그인 불가능한 placeholder다. JWT 생성은 #4 도구를 사용한다.

초기 Redis는 선점·holds·access·index 키 없음, active 키 없음(논리적으로 0)이다.
SQL은 Redis를 수정하지 않는다. 초기화 시 Redis도 전용 인스턴스를 재생성하거나 #4 reset을 사용한다.
인위적 선점 fixture를 DB에 넣지 않고 실제 API/서비스로 생성한다.

## 스키마 근거와 한계

- Seat/Booking/BookingItem/Inbox/Outbox/ReconciliationTask/UserArtistFanScore 엔티티의
  컬럼·타입·PK·선언된 unique를 반영한다. grade/price 및 concert/schedules는 native query에 필요하다.
- user→booking, artist→concert, concert→schedule→seat, booking/seat→item 및 fan score의
  FK는 fixture의 관계를 보장하는 **테스트 계약**이다. 누락된 운영 init.sql의 FK를 확인한 것은 아니다.
- 좌석 위치 및 booking_items의 조회 인덱스는 테스트 조회용이며 운영 인덱스와 같다고 주장하지 않는다.
- booking_items에는 seat_id 단독 unique나 활성 예약 partial unique가 없다. 코드상 선언되지 않은
  중복 방지 제약을 추가해 TC-19/20의 예약 경합·중복 확정 결함을 숨기지 않는다.
- payment 스키마/PG 데이터는 제외한다. 팬 점수 및 외부 서비스는 seat-test에서 비활성이다.
- 운영용 migration이 아니며 `ddl-auto=update` 없이 사용한다. 호환성 검증은
  `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`로 앱 기동하고 native query를 실제 실행해 확인한다.

최초 볼륨과 기존 볼륨의 차이 및 데이터 삭제 범위는 [Compose 안내](../README.md)를 따른다.
