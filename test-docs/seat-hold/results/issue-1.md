# 이슈 #1 작업 및 실행 결과

- 검증 일자: 2026-10-06 (Asia/Seoul)
- 기준 체크아웃: `9b048e8` + 이번 작업의 미커밋 변경
- 상태: #1 의존성 격리 구현, 단위/설정 회귀 및 실제 기동 검증 PASS

## 변경 내용

- `application-seat-test.properties` 추가: Kafka 자동 설정 제외, 팬 점수 초기화/동기화와
  정합성 스케줄러 비활성, 외부 클라이언트 호출 차단, tracing export 비활성.
- Kafka enable 설정을 scan되는 조건부 configuration으로 이동하고 payment consumer 및
  토픽 설정에 기본 활성 조건 추가. 기존 topic config는 기본 component scan 밖이라는 점을 기록.
- 확정 시 FanScoreService의 단건 경로와 배치 경로를 함께 비활성화.
  테스트에서 팬 점수가 실제 반영된 것처럼 DB applied 표시를 남기지 않음.
- 사용자/공연 외부 클라이언트는 테스트에서 HTTP 전에 명시적 범위 제외 오류를 발생시킴.
  예약 상세 개인정보 조회를 검증 범위에서 제외하며 fake profile을 반환하지 않음.
- 일반 프로필의 신규 조건은 기본 활성(`matchIfMissing=true`, Value 기본값 true).
- 실제 JWT 필터·내부 guard·Redis Lua·DB/outbox 기록 로직 유지.
- 단독 실행 안내, 의존성 표, API 검증 범위 및 기존 늦은 확정 동작의 한계를 문서화.

## 빌드와 자동 테스트

```bash
bash gradlew :ticketing-service:test :ticketing-service:bootJar --no-daemon
```

- 결과: BUILD SUCCESSFUL.
- 기존 FanScoreServiceTest: 3개 PASS.
- 신규 SeatTestIsolationTest: 4개 PASS.
- 합계: 7개, failures=0, errors=0, skipped=0.
- 검증 대상: 기본 background Bean 유지, 테스트 설정에서 Kafka/초기화/스케줄러 제외,
  팬 점수 비활성 시 DB 조회/반영 및 외부 호출 없음, 제외된 클라이언트 메서드의 HTTP 이전 실패.
- 실행 JAR 빌드 성공. `git diff --check` 통과.

관리 환경에는 JRE만 있어 공식 Java 21 JDK를 `/tmp/issue1-jdk`에 준비했다.
Gradle cache는 `/tmp/issue1-gradle`을 사용하고 관리 환경의 프록시와 CA를 적용했다.
프로젝트의 Java 버전이나 시스템 설치, Gradle wrapper 설정은 변경하지 않았다.

## 실제 기동 확인

다른 컨테이너가 없는 환경에서 임시 Redis 7과 PostgreSQL 17만 실행했다.
Kafka, user-auth-service, concert-service, payment-service, Debezium은 실행하지 않았다.
컨테이너는 테스트 후 삭제하며 공용 개발 데이터/볼륨을 사용하지 않았다.

- Redis: `redis:7-alpine`, 실제 health 버전 7.4.11, 로컬 포트 16379.
- PostgreSQL: `postgres:17-alpine`, 실제 기동 로그 버전 17.11, 로컬 포트 15432.
- DB: 빈 `seat_test` DB. 공통 fixture를 생성하지 않았고 `ddl-auto=none`을 유지했다.
- 애플리케이션: `seat-test`, 로컬 포트 18080. 기동 로그상 약 10.1초에 Started 확인.

| 확인 항목 | 결과 |
| --- | --- |
| GET /actuator/health | HTTP 200, status UP |
| health의 DB component | UP, PostgreSQL |
| health의 Redis component | UP, 7.4.11 |
| GET /actuator/health/readiness | HTTP 200, status UP |
| JWT 없이 /api/seats 요청 | HTTP 401 |
| 실행 중인 의존 컨테이너 | 검증용 PostgreSQL·Redis 두 개만 존재 |

health 상세 출력은 검증 실행 옵션으로만 활성화했다. 토큰·비밀번호를 결과 증거에 기록하지 않았다.
프로필의 공개 테스트 기본값은 단독 실행 안내에서 테스트 전용임을 명시한다.

## 검증 한계 / 후속

- 일반 프로필의 기존 단위 테스트와 Bean 기본 활성 유지 확인은 PASS.
  전체 MSA를 실행한 일반 프로필 통합 회귀는 수행하지 않았다.
- 좌석 선점·해제·예약 생성·내부 확정 API의 실제 fixture 기반 시나리오는 NOT_RUN.
  #3 공통 SQL/fixture 및 #4 요청 도구 준비 후 진행한다. health UP을 기능 통과로 해석하지 않는다.
- Kafka/CDC 전송, 결제/PG 승인, 외부 개인정보/팬 점수 동기화는 프로필 검증 범위 밖이다.
- 늦은 확정·예약 중복 등 기존 정합성 위험을 이번 작업에서 해결하거나 통과 처리하지 않았다.
