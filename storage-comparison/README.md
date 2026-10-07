# Redis / PostgreSQL 저장 방식 비교

현재 서비스의 좌석 선점과 대기열 핵심 저장 연산을 같은 계약으로 실행하는 비교 모듈입니다.
`JdbcReservationStore`는 Redis 호출 없이 PostgreSQL만 사용합니다. 기존 API를 RDB 방식으로 전환하는 설정은 아직 추가하지 않았습니다.

## 구현

- Redis 좌석: 기존 `RedisLockRepository.lockSeatWithLimit`, 소유자 확인, 해제·전체 해제를 재사용합니다.
- Redis 입장·토큰 소비: 기존 `QueueService`의 Lua를 `RedisQueueAdmission`으로 추출하고 양쪽에서 재사용합니다. 기존 서비스의 키·TTL·스크립트 동작은 같습니다.
- RDB 좌석: 복합 PK, 만료 조건부 UPSERT, 사용자별 유효 선점 수 검사로 5분 선점과 회차별 4매 제한을 구현합니다.
- RDB 대기열: 사용자별 한 행을 저장하고 `(score, user_id)` 순서로 순번을 구합니다. 같은 점수의 사용자 ID 비교는 Redis의 바이트 순서와 맞추도록 PostgreSQL `COLLATE "C"`를 사용합니다.
- RDB 입장: 회차 행을 `SELECT ... FOR UPDATE`로 잠그고, 정원 검사·대기열 삭제·인원 증가·180초 토큰 발급을 한 트랜잭션으로 처리합니다.
- RDB 토큰 소비: 만료 조건을 포함한 `DELETE ... RETURNING`으로 한 번만 소비합니다.

RDB 구현은 `READ COMMITTED`와 회차별 행 잠금을 사용합니다. 같은 회차의 쓰기는 DB에서 직렬화되므로 여러 애플리케이션 인스턴스에서도 제한을 보호하지만, 같은 회차의 서로 다른 좌석도 경합합니다. 정확성을 우선한 비교 기준 구현이며, 좌석별·사용자별 잠금으로 세분화하면 다른 결과가 나올 수 있습니다.

## 실행

JDK 21과 Docker Compose가 필요합니다. 저장소 루트에서 실행합니다.

```bash
docker compose -p fairline-comparison -f storage-comparison/compose.yaml up -d --wait
bash gradlew :storage-comparison:storageComparison
```

이 작업은 실제 Redis와 PostgreSQL을 사용하며, 연결 실패 시 테스트도 실패합니다. 일반 `test` 작업에서는 비교 테스트를 제외해 Docker를 요구하지 않습니다.

- PostgreSQL: `localhost:15432`, DB/사용자/비밀번호 `comparison`
- Redis: `localhost:16379`
- 두 포트 모두 루프백에만 바인딩됩니다. 데이터는 비교용 컨테이너에 저장됩니다.
- 사용자 지정 연결: `COMPARISON_DB_URL`, `COMPARISON_DB_USER`, `COMPARISON_DB_PASSWORD`, `COMPARISON_REDIS_PORT`
- 지정한 DB에는 `comparison_*` 테이블과 테스트 데이터가 생성됩니다. 비교 전용 DB를 사용하세요.

결과 파일:

- `storage-comparison/build/reports/tests/storageComparison/index.html`: 테스트 결과
- `storage-comparison/build/reports/comparison/results.csv`: 처리 시간, p50/p95/p99, 초당 처리 요청 수

정리:

```bash
docker compose -p fairline-comparison -f storage-comparison/compose.yaml down --volumes
```

## 검증 범위

두 저장소 각각에 아래 9개 시나리오를 실행합니다. 동시 요청은 16개 스레드와 시작 장벽을 사용하며, 반환 결과뿐 아니라 남은 소유권·인원·대기열 상태도 검사합니다.

1. 동일 좌석 100건 요청에서 한 사용자만 선점.
2. 동일 사용자의 서로 다른 좌석 100건 요청에서 정확히 4건 선점, 전체 해제의 반복 안전성.
3. 타인 해제 거부, 본인 해제, 재선점, 회차 간 독립성.
4. 만료 좌석 재선점, 만료 선점의 4매 제한 제외, 이전 소유자의 해제 거부.
5. 점수·동점 사용자 순서, 재진입 갱신, 대기열 이탈, 앞순서 입장 우선.
6. 100명 동시 입장에서 정원 10명 준수, 중복 입장 거부, 인원 감소의 0 하한, 후속 입장.
7. 동일 토큰 100건 동시 소비에서 한 요청만 성공.
8. 만료 토큰 거부.
9. 1,000개 좌석 선점 및 1,000명 대기열 순번 조회 측정.

만료 테스트는 실제 저장소의 만료 시간을 테스트에서 단축합니다. Redis는 짧은 TTL, PostgreSQL은 과거 `expires_at`을 사용하므로 5분/180초를 기다리지 않습니다.

대기열 점수는 테스트에서 직접 지정합니다. 서비스의 팬덤 우선순위 계산과 난수 jitter는 비교 대상에서 제외하고, 계산 이후의 저장·순위·입장 연산을 비교합니다. Redis 어댑터 역시 사용자 확인, 회차 검증, 전역 인덱스 관리, 관측 지표, HTTP 처리를 포함하지 않습니다.

다중 좌석 배치 API, 좌석 화면 입장 권한, 결제 확정, 만료 대기자/토큰/접속자의 스케줄러 정리, SSE·좌석맵·인증·캐시까지 RDB로 전환한 것은 아닙니다. 특히 만료 토큰만으로 active 인원이 자동 감소하지 않는 기존 동작을 따릅니다. 전체 서비스의 Redis 제거 또는 API 단위 부하 비교에는 해당 연동과 정리 작업도 전환해야 합니다.

## 측정 조건과 해석

16개 동시 클라이언트, PostgreSQL 커넥션 풀 최대 16개, 로컬 Docker의 Redis 7.4 / PostgreSQL 17을 사용했습니다. 독립 좌석 선점 측정 전에 각 저장소에 100건 선점을 수행해 워밍업합니다. 정확성 시나리오의 시간은 워밍업을 통제한 성능 벤치마크가 아닙니다.

CSV의 `successes`는 선점/발급/소비가 실제 성공한 수입니다. `requests_per_second`에는 정상적인 거절 응답도 포함됩니다. p50/p95/p99는 작업 실행 시작부터 종료까지의 시간이며, 실행기 대기 시간은 제외합니다. `elapsed_ms`는 전체 요청 처리 시간입니다.

Redis는 디스크 영속성을 끈 설정이고 PostgreSQL은 기본 내구성 설정입니다. JVM JIT, 컨테이너 자원, DB 인덱스, 네트워크, 행 잠금 범위, 대기열 크기에 따라 측정이 달라집니다. 이번 한 번의 실행은 저장소 핵심 연산의 탐색적 비교이며 운영 시스템의 처리량 또는 Redis/RDB 전반의 성능 우열을 증명하지 않습니다.

실제 실행 결과는 [RESULTS.md](RESULTS.md)에 기록했습니다.
