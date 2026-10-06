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

## SQL 준비 전: 기동만 검증

현재 `sql/`은 비어 있다. PostgreSQL은 `seat_test` DB만 생성하고 Hibernate는
`ddl-auto=none`, Spring SQL 초기화는 `never`다. 빈 DB에서도 서비스는 기동할 수 있다.
readiness는 애플리케이션 상태와 실제 DB/Redis 연결을 확인한다.
UP은 업무 스키마/fixture 또는 좌석 API 통과를 의미하지 않는다.
좌석·예약 시나리오는 이슈 #3 SQL 및 #4 요청 도구 준비 전까지 NOT_RUN/BLOCKED다.

```bash
docker compose exec -T redis redis-cli ping
docker compose exec -T postgres sh -ec 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
curl -i http://127.0.0.1:28084/api/seats/holds
```

Redis PONG, DB accepting connections, readiness HTTP 200/UP을 확인한다.
JWT 없는 좌석 요청은 HTTP 401이어야 한다. 인증 우회나 테스트 JWT 발급은 이 Compose의 역할이 아니다.

## 이슈 #3 fixture 준비 후: API 검증

공통 SQL의 계약 경로는 `test-infra/seat-hold/sql/01-schema.sql`과 `02-fixture.sql`이다.
Compose는 이 디렉토리를 `/docker-entrypoint-initdb.d`에 읽기 전용 mount한다.
PostgreSQL entrypoint가 새 DB 볼륨에서 파일명 순서로 실행한다. 스키마/FK/ID 계약은
[환경 및 데이터 준비](../../test-docs/seat-hold/02-environment-and-data.md)에 따른다.
이번 이슈에서는 임시 SQL이나 가짜 fixture를 만들지 않는다.

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
