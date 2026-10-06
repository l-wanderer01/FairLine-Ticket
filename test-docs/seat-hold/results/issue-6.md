# 이슈 #6 실제 자동 회귀 결과

## 실행 조건

- 실행일: 2026-10-06 UTC (Asia/Seoul 같은 날짜).
- 분기 기준: develop `d246ba527f5c56b50c72acab01dc5db8bc8440e2`, feature/issue6. 테스트 소스는 이 결과와 같은 커밋의 버전이다.
- JDK: Temurin 21.0.12.1, Gradle wrapper 9.3.0, Testcontainers 1.21.3.
- PostgreSQL 17.11 (`postgres:17`), Redis 7.4.11 (`redis:7`), Ryuk 0.11.0.
- PostgreSQL digest: `sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f`.
- Redis digest: `sha256:c6eabf748fc7a61dbb5a705c78bcf3d6377b1127a97d0ce965c11c44ba46896f`.
- SQL SHA256: 01-schema `3d93d627b131791dd642453cc31d3fe16fe936037a379b8f57acdf0a91ee12c0`, 02-fixture `01800712607fd190cccea0ced904f3bad4b11f291e9370ae6e7abeae635e85f7`.
- Compose를 종료한 뒤 최종 전체 실행. Testcontainers의 전용 동적 포트/새 컨테이너만 사용했다.
- 관리 환경의 JDK/proxy/CA trust를 설정해 아래 명령을 실행했다. 연결 비밀값은 기록하지 않는다.

```bash
bash gradlew :ticketing-service:test :ticketing-service:seatIntegrationTest --no-daemon --console=plain
```

## 최종 결과

최종 전체 Gradle 실행 시간: 6분 1초. 전체 명령 종료 코드 **1**.

| 작업 / 케이스 | 실행 수 | PASS | FAIL | SKIP |
| --- | --- | --- | --- | --- |
| 기존 단위·설정 테스트 | 7 | 7 | 0 | 0 |
| Redis 통합 (TC-01/05 각 1, TC-03/07 각 20, TC-15 1) | 43 | 43 | 0 | 0 |
| 실제 예약 금액·항목·만료 native query | 1 | 1 | 0 | 0 |
| TC-19 중복 예약 | 1 | 0 | 1 | 0 |
| TC-20 늦은 확정 양 순서 | 2 | 0 | 2 | 0 |
| 통합 전체 | 47 | 44 | 3 | 0 |

JUnit XML errors는 0이다. TC-15 실제 5분 만료와 stale 정리·재선점은 PASS다.
결과는 `ticketing-service/build/test-results/{test,seatIntegrationTest}/TEST-*.xml`에서 집계했다.
기동/SQL/연결 오류가 아니라 아래 제품 정합성 위반으로 통합 작업이 실패했다.
문서 5개 로컬 링크, 공통 SQL/복사된 테스트 리소스 바이트 동일성, `git diff --check`도 확인했다.
전체 JVM 종료 후 Testcontainers PostgreSQL/Redis/Ryuk이 제거됨을 확인했다.

## 재현된 결함

### TC-19 / INV-06: 동일 좌석의 활성 예약 중복

실제 두 Spring 트랜잭션의 PostgreSQL connection PID는 71/72였다.
각 실제 native 충돌 조회가 빈 결과를 반환한 뒤 barrier로 두 요청을 재개했다.
좌석 401의 활성 예약이 2개 생성됐다.

- 예약 ID: `94b52822-4d9e-4f1c-8da7-b39020acd858`, `421537b9-6d3a-4913-be99-eb5c11e9909d`.
- 빈 충돌 조회 완료: 2회. DB 활성 예약: 2개. 기대: 최대 1개.
- 실제 조회/저장을 mock하지 않았고 테스트 전체 rollback 트랜잭션도 사용하지 않았다.
- 재현: `bash gradlew :ticketing-service:seatIntegrationTest --tests '*BookingInvariantIntegrationTest.tc19*' --no-daemon`.

### TC-20: 확정 응답과 커밋된 예약 상태 불일치

A 만료→B 재선점/예약 후 A→B, B→A 확정 순서를 각각 새 fixture로 실행했다.
두 순서 모두 응답은 `CONFIRMED/CONFIRMED`인데 DB는 `HOLDING/HOLDING`이었다.
실제 DB CONFIRMED 수는 0개다. **DB 중복 확정 2개를 재현했다고 주장하지 않는다.**
0개라는 이유만으로 통과하지 않도록 응답 bookingStatus와 커밋 상태도 검사하며 두 순서 모두 FAIL이다.

| 확정 순서 | A 예약 | B 예약 | holdValid(호출 순서) | 응답 / DB |
| --- | --- | --- | --- | --- |
| A→B | f31bd79b-ba9f-40e9-834a-5c8bda670e8c | 2e7497c1-5654-48cd-8020-24a5984e782b | false / true | CONFIRMED 둘 / HOLDING 둘 |
| B→A | fa9b533d-5fcc-4273-b425-d8e5b4114402 | f2cacb7a-7917-4dc6-973f-2f9ac7dc0df1 | true / false | CONFIRMED 둘 / HOLDING 둘 |

원인 후보는 `reserveSeatsByBookingId`의 `@Modifying(clearAutomatically=true)` 실행 이후
이미 읽은 Booking에 상태를 설정하는 흐름이다. 이 테스트 작업은 제품 코드를 수정하지 않으며
정확한 원인·수정·재검증은 후속 결함 작업에서 확인해야 한다.
재현: `bash gradlew :ticketing-service:seatIntegrationTest --tests '*BookingInvariantIntegrationTest.tc20*' --no-daemon`.

## 판정과 범위

실패를 ignoreFailures/Disabled/예상 실패 통과로 숨기지 않는다. 전체 회귀 gate는 정합성 결함이 수정될 때까지 실패한다.
초기 실행에서 약한 TC-20 검사(확정 건수만 검사)가 0개를 PASS로 처리하는 것을 발견해,
응답과 커밋 상태 검사로 보강한 뒤 최종 전체 실행을 다시 수행했다. 초기 결과를 최종 통과 증거로 사용하지 않는다.
TC-15는 운영 코드의 실제 300초 TTL을 기다린다. TC-20 경계 주입은 이를 대체하지 않는다.
사용자 수동 smoke(#5)는 NOT_RUN이며 자동 테스트 결과로 대체하지 않는다.
HTTP/JWT·실제 PG·Kafka·다중 인스턴스·장애 프록시·성능 전체 검증은 이번 자동 회귀 범위에 포함되지 않는다.
