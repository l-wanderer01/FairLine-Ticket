# 좌석 수동 검증용 독립 환경 (#2)

티켓팅 서비스, Redis 7, PostgreSQL 17만 실행한다. 저장소 루트의 전체 MSA Compose와
별개의 `fairline-seat-test` 프로젝트, 네트워크, 볼륨을 사용하며 고정 container_name은 없다.
호스트 포트는 localhost에만 열고 기본값은 DB 15432, Redis 16379, 티켓팅 28084다.
포트가 이미 사용 중이면 `.env`에서 바꾼다. 병렬 실행은 다른 포트와 `docker compose -p <이름>`을
함께 사용하고 이후 모든 명령에도 동일한 프로젝트명을 지정한다.

## 준비

Docker Engine과 Docker Compose v2 (`up --wait` 지원)가 필요하다.
이미지 빌드 context는 저장소 루트이며 기존 `ticketing-service/Dockerfile`을 그대로 사용한다.
JDK 21 빌드에 Gradle 설정, `shared-kernel`, 티켓팅 소스를 포함한다.

이후 명령은 모두 아래 디렉토리에서 실행한다.

```bash
cd test-infra/seat-hold
cp .env.example .env
docker compose config --quiet
docker compose build ticketing-service
docker compose up -d --wait --wait-timeout 180
docker compose ps
curl --fail http://127.0.0.1:28084/actuator/health/readiness
```

`.env.example`의 값은 공개 테스트용 값이다. 실제 비밀값을 넣거나 커밋하지 않는다.
`.env`는 Git에서 제외된다. `SEAT_TEST_*`만 사용하므로 기존 MSA의 DB/JWT 변수와 구분된다.
JWT는 UTF-8 32바이트 이상으로 설정한다. 포트를 변경하면 curl 주소도 바꾼다.
`docker compose config`의 전체 출력에는 주입된 값이 표시되므로 공유하지 않는다.

`seat-test`만 활성화하고 DB/Redis 주소, JWT 및 내부 토큰을 주입한다. 실제 인증 필터,
내부 API guard, Redis Lua와 DB/outbox 로직은 유지한다. Kafka, 팬 점수 초기화·동기화,
정합성 스케줄러, 외부 사용자/공연 HTTP 호출과 tracing export는 비활성이다.
상세 범위는 [단독 실행 의존성](../../test-docs/seat-hold/01-standalone-dependencies.md)을 참고한다.

## SQL/fixture 확인

이슈 #3에서 [공통 SQL과 ID 계약](sql/README.md)을 추가했다. 새 PostgreSQL 볼륨에
스키마/fixture를 초기화하고 Compose는 `ddl-auto=validate`로 엔티티 호환성을 확인한다.
Hibernate가 테이블을 생성하지 않으며 Spring SQL 초기화는 `never`다.
readiness는 앱·DB·Redis 연결 상태다. UP만으로 fixture 상태나 좌석 API 정합성을 판정하지 않는다.
이슈 #2 시점의 SQL 없는 기동 검증은 [당시 결과](../../test-docs/seat-hold/results/issue-2.md)에 남겨 두었다.
일반 `seat-test` 프로필 자체는 `ddl-auto=none`이므로 빈 DB 기동은 가능하지만,
현재 Compose는 SQL이 없거나 이전 빈 볼륨이면 validation 오류로 기동하지 않는다.

```bash
docker compose exec -T redis redis-cli ping
docker compose exec -T postgres sh -ec 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
curl -i http://127.0.0.1:28084/api/seats/holds
```

Redis PONG, DB accepting connections, readiness HTTP 200/UP을 확인한다.
JWT 없는 좌석 요청은 HTTP 401이어야 한다. 인증 우회나 테스트 JWT 발급은 이 Compose의 역할이 아니다.

## 최초 초기화 및 반복 실행

공통 SQL의 계약 경로는 `test-infra/seat-hold/sql/01-schema.sql`과 `02-fixture.sql`이다.
Compose는 이 디렉토리를 `/docker-entrypoint-initdb.d`에 읽기 전용 mount한다.
PostgreSQL entrypoint가 새 DB 볼륨에서 파일명 순서로 실행한다. 스키마/FK/ID 계약은
[환경 및 데이터 준비](../../test-docs/seat-hold/02-environment-and-data.md)에 따른다.
공통 SQL은 Compose와 자동 회귀 테스트에서 함께 사용한다.

SQL을 추가해도 기존 볼륨에는 자동 재실행되지 않는다. 실패 증거를 보관한 뒤,
이 테스트 프로젝트의 데이터를 버려도 될 때만 다음 명령으로 DB와 Redis를 함께 재생성한다.

```bash
test -f sql/01-schema.sql && test -f sql/02-fixture.sql
docker compose down -v
docker compose up -d --wait --wait-timeout 180
docker compose logs postgres ticketing-service
```

SQL 초기화 성공, 실제 테이블과 fixture ID·초기 Redis 상태를 확인한 뒤 유효 JWT/내부 토큰으로
[실행 시나리오](../../test-docs/seat-hold/04-execution-scenarios.md)를 수행한다.
결과에 코드/SQL 버전, 포트, DB·Redis 버전과 증거를 기록하고 토큰·비밀번호는 제외한다.
SQL 오류나 업무 테이블 누락은 health UP과 별개로 실패/차단 사유다.

## 종료 및 문제 확인

```bash
docker compose logs --tail=100 ticketing-service postgres redis
docker compose down
```

`down`은 테스트 데이터를 보존한다. `down -v`는 이 프로젝트의 두 볼륨을 삭제한다.
다른 프로젝트의 컨테이너/볼륨은 삭제하지 않는다. readiness 503/타임아웃이면 DB·Redis
health와 티켓팅 로그를 확인한다. 포트 충돌은 `.env`에서 해결하고 운영 DB 주소로 대체하지 않는다.
