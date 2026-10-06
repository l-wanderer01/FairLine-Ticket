# 이슈 #2 작업 및 실행 결과

- 검증 일자: 2026-10-06 (Asia/Seoul)
- 기준: develop `c3797ca` + feature/issue2 변경
- 범위: 독립 Compose 및 SQL 준비 전 기동. fixture 기반 좌석 API는 NOT_RUN.

## 구성 검증

`test-infra/seat-hold`에서 `.env.example`을 `.env`로 복사한 뒤 실행했다.
예제는 공개 테스트 값이며 `.env`는 Git 제외 상태를 확인했다.

| 확인 | 결과 |
| --- | --- |
| `docker compose config --quiet` | PASS |
| `docker compose config --services` | postgres, redis, ticketing-service 3개만 포함 |
| JSON config 검사 | 프로젝트 fairline-seat-test, 고정 container_name 없음, 모든 공개 포트 127.0.0.1 |
| `docker compose --env-file /dev/null config --quiet` | 필수 SEAT_TEST 변수 누락 시 거부 |
| SQL mount | sql/ → /docker-entrypoint-initdb.d, read-only |
| `git check-ignore test-infra/seat-hold/.env` | 제외 확인 |
| SQL Git 예외 | sql/01-schema.sql 및 02-fixture.sql은 추후 추적 가능 |

## 빌드 및 회귀

`bash gradlew :ticketing-service:test :ticketing-service:bootJar --no-daemon` 성공.
SeatTestIsolationTest 4개, FanScoreServiceTest 3개: 총 7개 PASS, failures/errors/skipped 모두 0.
`git diff --check` 통과.

관리 환경은 JRE만 제공하여 기존 eclipse-temurin:21-jdk 이미지에서 임시 JDK를 추출했다.
Gradle에는 환경의 지정 HTTP/HTTPS 프록시 및 CA trust를 적용했다.
기본 `docker compose build ticketing-service`는 이 환경의 Java 프록시 자동 적용 부재로
Gradle 배포 URL DNS 해석에서 실패했다. 같은 서비스 Dockerfile의 두 Gradle RUN에만
임시 BuildKit secret으로 프록시 설정과 CA를 적용한 `/tmp/issue2-build` override 빌드는 성공했다.
TLS 검증을 유지했고 CA trust 파일은 해당 RUN에서 제거했다. 런타임 stage는 기존 Dockerfile과 같다.
환경 전용 override, 프록시 주소 및 인증서는 커밋하지 않았다.
일반 네트워크 환경에서 override 없는 이미지 빌드는 별도로 실행하지 않았다.

## 실제 Compose 실행

빌드한 이미지로 `docker compose up -d --no-build --wait --wait-timeout 180`을 실행했다.
첫 PostgreSQL 실행은 관리 환경의 sql/ 디렉토리 권한(700) 때문에 실패했고,
디렉토리 755 및 .gitkeep 644로 수정 후 정상 기동했다. 일반 Git checkout의 디렉토리는 읽기 가능하다.

| 항목 | 결과 |
| --- | --- |
| PostgreSQL | 17.11, 포트 15432, healthy |
| Redis | 7.4.11, 포트 16379, healthy |
| 티켓팅 | seat-test, 포트 28084, healthy |
| GET /actuator/health/readiness | HTTP 200, status UP |
| Redis 중단 후 readiness | HTTP 503, status DOWN |
| Redis 재기동 후 `up --wait` | 3개 서비스 healthy, readiness HTTP 200/UP 복구 |
| JWT 없는 /api/seats/holds | HTTP 401 |
| concert/ticketing 업무 테이블 수 | 0: 빈 DB에 Hibernate가 테이블을 생성하지 않음 |

DB/Redis health 이후 티켓팅이 시작하며 SQL이 없어도 readiness는 연결 상태를 판정한다.
프로젝트 전용 postgres-data, redis-data와 네트워크를 사용했다.
Kafka 및 나머지 MSA 서비스는 이 Compose에 포함하지 않았다.
검증 후 `docker compose down -v`로 이번 프로젝트의 컨테이너·볼륨·네트워크를 정리했다.

## 한계

이슈 #3의 `sql/01-schema.sql`, `sql/02-fixture.sql`은 아직 없다.
SQL 초기화 및 실제 좌석 선점·예약·내부 확정 시나리오는 NOT_RUN이며 기동 성공으로 대체하지 않는다.
SQL을 추가한 후 기존 테스트 볼륨을 초기화해야 entrypoint가 순서대로 실행한다.
실행/초기화/정리 방법은 [Compose 안내](../../../test-infra/seat-hold/README.md)에 기록했다.
