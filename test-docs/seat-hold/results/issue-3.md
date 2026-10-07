# 이슈 #3 검증 결과

- 일자: 2026-10-06 (Asia/Seoul)
- 기준: develop d387db8 + feature/issue3
- PostgreSQL 17.11 / Redis 7.4.11, 테스트 프로젝트 fairline-seat-test

`01-schema.sql`과 `02-fixture.sql`을 새 PostgreSQL 볼륨의 init 디렉토리에서 실행했다.
기존 #2 이미지(앱 코드는 동일)에 `ddl-auto=validate`를 주입한 Compose 기동이 성공했다.
모든 티켓팅 엔티티 스키마 validation과 readiness HTTP 200/UP을 확인했다.

| 관찰 | 결과 |
| --- | --- |
| 사용자 / 공연 / 회차 / 좌석 / 예약 | 103 / 2 / 3 / 11 / 1 |
| S01~S08 | 401~408, schedule 301, AVAILABLE, 10000 |
| S09 / 판매 예약 | 409 RESERVED, U-C의 CONFIRMED 예약 항목 1개, 가격 10000 |
| S10 / S11 | 410 / 411, schedule 302 / 303, AVAILABLE |
| 실제 join 및 sum 쿼리 | FK 관계·예약 금액 일치 |
| 예약 인덱스 | PK, booking/seat 조회 인덱스 존재. seat_id 단독 unique 없음 |
| 02-fixture.sql 반복 실행 | 성공, 기본 건수 동일 |
| S01을 RESERVED로 바꾼 뒤 재실행 | AVAILABLE로 복구, 기본 항목 1개 |
| Compose config 및 diff 검사 | PASS |

SQL은 원자적 트랜잭션이며 psql `ON_ERROR_STOP=1`로 실행했다.
새 테스트 스키마의 FK/조회 인덱스는 문서화된 테스트 계약이다.
운영 init.sql이 없으므로 운영 DB 제약과 동일하다는 주장은 하지 않는다.
전체 MSA/PG/팬 점수 연동 및 fixture 기반 HTTP smoke는 이 검증 범위 밖이다.
#6 자동 테스트는 이 두 파일을 test resources에 직접 포함해 재사용한다.
