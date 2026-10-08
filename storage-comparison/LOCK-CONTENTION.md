# PostgreSQL 비관적 잠금 경합·CPU 계측

기존 `JdbcReservationStore`를 수정 없이 실행하는 테스트다. 현재 구현은 **회차 행**을 `SELECT ... FOR UPDATE`로 잠근다. 과거 운영 RDB 구현이나 좌석별 잠금 성능을 측정한 것으로 설명하지 않는다.

## 실행

```bash
docker compose -f storage-comparison/compose-lock-metrics.yaml up -d --wait
bash gradlew :storage-comparison:rdbLockContention --no-daemon --max-workers=2
```

JDK 21, 테스트 JVM의 로컬 Docker socket/동적 프로세스 접근이 필요하다. 테스트는 Compose의 독립 DB `lock_metrics`만 사용하고 각 단계에서 `comparison_*` 테이블을 TRUNCATE한다. 기존 comparison/app DB로 연결하지 않는다. 기본 주소는 localhost:25432이고 공개 테스트 사용자/암호는 comparison이다. 사용자 지정 연결은 `RDB_METRICS_DB_URL/USER/PASSWORD`, CPU 계측 컨테이너는 `RDB_METRICS_CONTAINER`로 지정한다. 다른 DB를 지정하면 그곳의 comparison_* 테스트 데이터를 초기화하므로 전용 DB를 사용한다.

짧은 계측 smoke는 `RDB_METRICS_SECONDS=2 RDB_METRICS_REPEATS=1`로 실행한다. 기본 측정은 단계당 20초, 3회 반복이며 전체 약 5분이다. 일반 test 작업에는 포함되지 않는다.

## 부하 조건

- 호출 대상: 실제 `JdbcReservationStore.hold`, READ COMMITTED 트랜잭션, scope INSERT → 회차 행 FOR UPDATE → 유효 사용자 선점 수 확인 → 좌석 UPSERT → COMMIT.
- 같은 회차/독립 좌석·사용자: 동시 worker 1/8/32개.
- 대조군: 동시 worker 32개가 각각 다른 회차에 독립 좌석/사용자를 선점한다.
- 같은 좌석 경쟁: 동시 worker 32개, 첫 선점 1건 성공 후 나머지는 정상 충돌.
- closed-loop worker는 이전 요청이 끝나야 다음 요청을 보낸다. 고정 유입 TPS나 HTTP 부하 측정이 아니다.
- Hikari load pool 최대/최소 32개로 고정, 별도 observer pool 1개. pool 부족과 DB 잠금 대기를 구분한다.
- 500건으로 JVM/JDBC 워밍업 후 각 단계 전에 DB fixture를 초기화하고 회차 행을 미리 생성한다. 2회차는 단계 순서를 반대로 실행한다.
- 인위적인 pg_sleep이나 잠금 유지 지연을 삽입하지 않는다. 실제 SQL 경로를 사용한다.
- PostgreSQL 전용 컨테이너 상한 2 CPU/2 GiB. load generator와 DB가 공유하는 상위 실행 환경도 2 CPU/8 GiB 제한이므로 전용 서버 결과가 아니다.

## 지표 정의

`build/reports/lock-contention/results.csv`:

- mean/p95/p99: store.hold 호출 시작부터 종료까지. 트랜잭션·pool 확보 포함, HTTP·인증 제외.
- lock_sql_mean/p95: **FOR UPDATE SQL의 JDBC execute 구간**. DB 실행·네트워크·락 대기 포함. 순수 락 대기 시간으로 해석하지 않는다.
- scope_insert_mean: 트랜잭션 첫 scope INSERT 실행 구간. 초기화/유니크 충돌 쪽 대기를 따로 관찰한다.
- pool_acquire_mean: Hikari getConnection 시간. DB row-lock 대기와 구분한다.
- avg/max_lock_waiters: 약 20ms 주기로 pg_stat_activity의 load application_name에 대해 wait_event_type='Lock'인 세션 수를 관측한다.
- lock_active_sample_pct: 모든 관측의 Lock 대기 세션 수 합 / active 세션 수 합. 요청 중 락 대기를 한 비율이나 정확한 이벤트 발생 횟수가 아니다.
- blocker_edges: pg_blocking_pids 배열 길이의 합. blocking chain 때문에 한 요청에 여러 blocker가 잡힐 수 있어 세션 수와 다르다.
- db_cpu_one_core_pct: 컨테이너 cpu.stat의 usage_usec 증가 / 실제 샘플 간 경과 시간 ×100. **1코어를 다 쓰면 100%, 2코어면 200%**.
- db_cpu_two_core_pct: 위 값을 2로 나눈 2 CPU quota 대비 비율. Docker식 CPU%와 혼용하지 않는다.
- db_cpu_peak_one_core_pct: 약 1초 구간 샘플 최대. 단일 순간 최대 CPU는 아니다.
- cpu_throttled_ms: PostgreSQL 자식 cgroup의 제한 대기 증가량. 상위 공유 cgroup 제한은 포함하지 않는다.
- observer의 SQL/통계 조회 비용은 PostgreSQL CPU 측정에 포함되고 sampler/JDBC proxy가 측정에 영향을 줄 수 있다. 최적화 전후 API 성능으로 일반화하지 않는다.

원본 `*-wait.csv`, `*-cpu.csv`, `*-timings.csv.gz`를 보존한다. timing CSV의 metric 행은 각각 독립 수집된 분포이며 동일 요청끼리 묶인 행이 아니다. observer가 부하로 지연되면 실제 주기는 20ms보다 길어지므로 고정 주기를 가정한 순수 대기 시간 적분으로 사용하지 않는다.

## 정합성·실패 기준

모든 독립 좌석 요청은 HELD, LIMIT_EXCEEDED·SQL 오류/timeout은 0이어야 한다. DB 선점 행 수가 성공 수와 같아야 한다. 동일 좌석은 성공 1건과 소유자 존재를 확인한다. 락 SQL과 pool 확보 관측 수가 요청 수와 일치해야 한다. 연결 실패/오류/timeout을 정상 충돌로 계산하지 않는다. 테스트가 실패하면 그 단계나 전체를 PASS로 기록하지 않는다.

## 실측 결과

[2026-10-09 측정 보고서와 원본 근거](../test-docs/seat-hold/results/2026-10-09-rdb-lock-contention.md), [이슈 #28](https://github.com/l-wanderer01/FairLine-Ticket/issues/28).
