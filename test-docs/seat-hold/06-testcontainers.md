# Testcontainers 자동 회귀 (#6)

JDK 21, Docker Engine에 접근 가능한 계정, Gradle wrapper 다운로드 및 Maven/컨테이너
레지스트리 접근이 필요하다. Testcontainers BOM 1.21.3/JUnit Jupiter/PostgreSQL 모듈을 사용한다.
Redis는 GenericContainer다. Compose와 동일한 `postgres:17`, `redis:7` 이미지를 사용한다.
버전 tag는 최신 patch로 바뀔 수 있으므로 run의 실제 DB/Redis 버전 및 이미지 digest를 기록한다.

저장소 루트에서 실행한다.

```bash
# 기존 Mockito/설정 단위 테스트 (Docker 불필요)
bash gradlew :ticketing-service:test --no-daemon
# 전체 실제 회귀: 컨테이너 생성→SQL→테스트→정리, 실제 5분 TTL 포함
bash gradlew :ticketing-service:seatIntegrationTest --no-daemon
```

두 작업은 분리되어 있다. 기본 `test`는 seat-integration tag를 제외하며
전체 DB/Redis 정합성을 보장하지 않는다. `seatIntegrationTest`는 실패를 무시하지 않는다.
Docker가 없으면 skip/PASS로 바꾸지 않고 컨테이너 준비 실패로 종료한다.
known defect 테스트를 Disabled 또는 예상 실패 통과로 변경하지 않는다.
CI 회귀 gate에도 **전체 seatIntegrationTest 명령**을 포함해야 한다.

개별 진단은 아래와 같지만, 부분 실행 성공을 전체 회귀 통과로 보고하지 않는다.

```bash
bash gradlew :ticketing-service:seatIntegrationTest --tests '*SeatRedisIntegrationTest' --no-daemon
bash gradlew :ticketing-service:seatIntegrationTest --tests '*BookingInvariantIntegrationTest' --no-daemon
```

## 격리와 fixture

- @Testcontainers/@Container가 동적 포트로 DB/Redis를 생성·정리한다. Compose 포트/볼륨을 공유하지 않는다.
- `seat-test` 프로필로 Kafka/스케줄러/팬 점수/외부 HTTP를 격리한다. 실제 Lua/DB/native query/트랜잭션은 유지한다.
- `processTestResources`가 `test-infra/seat-hold/sql/01-schema.sql`, `02-fixture.sql`을
  `seat-test/sql/`에 직접 포함한다. 별도 테스트 SQL 복사본이나 중복 fixture 정의는 없다.
- PostgreSQL entrypoint가 새 컨테이너에서 두 SQL을 순서대로 실행한다.
  각 테스트/반복 전 공통 02-fixture와 전용 Redis FLUSHDB로 원래 상태를 복구한다.
- Hibernate validate로 엔티티 호환성을 확인한다. 테스트 전체를 rollback 트랜잭션으로 감싸지 않는다.
- 한 fork에서 JUnit 병렬 실행을 비활성화하고 class 종료 시 context를 폐기한다.
  경쟁은 명시적 executor/barrier 안에서만 실행한다. workers가 종료하지 않으면 테스트도 실패한다.
- Testcontainers 재사용을 활성화하지 않는다. 로컬 개발 DB/Redis 연결이나 .env를 사용하지 않는다.

## 케이스와 실제 보장 범위

| 케이스 | 실행 및 검사 |
| --- | --- |
| TC-01 | 정상 Lua 선점, owner/양수 PTTL/holds, DB AVAILABLE |
| TC-05 | 타인 해제 거부·소유권 유지, 본인 해제·재해제, 키/참조 제거 |
| TC-03 | fixture 사용자 100명이 시작 barrier, 성공 정확히 1개, 최종 owner와 각 holds 일치, 20회 반복 |
| TC-07 | 같은 사용자 8개 좌석 시작 barrier, 성공 정확히 4개, owner/holds/PTTL 검사, 20회 반복 |
| TC-15 | 실제 300초 TTL을 단축 없이 기다려 소유자 만료 확인, B 재선점·A stale 정리 후 새 선점 |
| 예약 native query | 실제 예약 항목·금액·TTL 기반 만료 검사 |
| TC-19 | 두 실제 트랜잭션/커넥션이 충돌 조회 후 저장 전 barrier, 활성 예약 최대 1개 |
| TC-20 | A Redis·DB 만료 주입→B 재선점/예약→A/B 확정 양 순서. DB 확정 최대 1개 및 응답/커밋 상태 일치 |

TC-15는 약 5분이 걸린다. TC-20의 짧은 Redis TTL/DB expires_at 주입은 경계 재현이며
TC-15의 실제 TTL 검증을 대체하지 않는다. TC-19 test-only Aspect는 실제 native query가
완료된 뒤만 정지한다. 각 스레드의 pg_backend_pid와 빈 충돌 조회 2회가 확인되지 않으면 실패한다.
프로덕션 hook·조회 mock·가짜 성공 클라이언트는 추가하지 않았다.

## 실패 증거와 판정

JUnit XML: `ticketing-service/build/test-results/seatIntegrationTest/`.
HTML: `ticketing-service/build/reports/tests/seatIntegrationTest/index.html`.
TC-19/20은 요청 예약 ID·connection PID·조회 완료 수·DB 상태/건수를 출력한다.
토큰/비밀번호/실 PG 키는 출력하지 않으며 PG 식별자는 test-fake 값이다.
Git commit, SQL SHA256, 실행 명령/시각, 실제 이미지·버전과 XML의 failures/errors/skipped를 함께 기록한다.
다시 실행하면 Gradle 보고서가 덮어써지므로 실패 증거를 먼저 다른 run 디렉토리에 보관한다.

응답이 CONFIRMED인데 DB가 HOLDING이면 확정 건수 0개만 보고 PASS로 처리해서는 안 된다.
TC-20은 응답의 bookingStatus와 실제 커밋 상태를 함께 assert한다.
상세 실제 결과와 결함/검증 한계는 [#6 결과](results/issue-6.md)에 기록한다.
여기서는 정합성 결함 수정이나 PG 환불 정책 변경을 수행하지 않는다.

## CI 및 관리 환경

CI는 JDK 21, 테스트 JVM에서 접근 가능한 Docker socket/daemon, 이미지 pull 권한·CA,
동적 mapped port로 접근 가능한 Docker host, 충분한 thread/메모리와 최소 10분 timeout을 제공해야 한다.
Ryuk cleanup 컨테이너도 허용한다. 전체 suite 종료 후 컨테이너가 남지 않는지 확인한다.
사용자 수동 fixture 볼륨은 삭제하지 않는다.
프록시가 필요한 관리 환경에서는 Gradle JVM의 HTTP/HTTPS 프록시·CA trust를 적용한다.
네트워크/TLS 오류를 TLS 검증 비활성화, Docker 재사용 또는 임의 외부 DB로 우회하지 않는다.
현재 저장소에는 GitHub Actions 테스트 workflow가 없어 runner 규칙을 변경하지 않고 실행 요건을 문서화했다.
