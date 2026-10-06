# 요청·관찰·초기화 도구 (#4)

Python 3 표준 라이브러리, Docker Compose v2만 필요하다. 아래 명령은 `test-infra/seat-hold`에서 실행한다.
도구는 위치와 무관하게 이 디렉토리의 Compose/.env와 `fairline-seat-test` 프로젝트만 사용한다.
`.env.example`의 공개 테스트 값을 복사하고 [기동 안내](../README.md)대로 서비스를 시작한다.
JWT 발급 서버·로그인·PG·Kafka 연동을 검증하는 도구가 아니다.

```bash
python3 tools/seat_test.py check
python3 tools/seat_test.py observe --user 101 --seats 401 402
python3 tools/seat_test.py request hold --user 101 --seat 401
python3 tools/seat_test.py request hold --user 102 --seat 401
python3 tools/seat_test.py request release --user 101 --seat 401
python3 tools/seat_test.py request batch --user 101 --seats 401 402
python3 tools/seat_test.py request booking --user 101 --seats 401 402
```

사용자 101/102/103은 U-A/B/C, 기본 concert/schedule은 201/301이다.
단일 요청은 실제 DTO의 scheduleId/section=A/rowNumber=1/seatNumber를 전달한다.
S10은 `--seat 410 --schedule 302`, S11은 `--seat 411 --schedule 303 --concert 202`다.
`batch/booking` 기본 좌석은 401,402이며 요청 예제에는 `--seats`를 명시한다.

요청은 HS256 JWT를 메모리에서 생성한다. `sub`는 user ID 문자열이고 `email/iat/exp`가 포함된다.
실제 JwtAuthenticationFilter/JwtUtil 및 Redis blacklist 검증을 유지한다.
내부 요청은 실제 `X-Internal-Api-Key` 헤더로 `.env`의 내부 토큰을 보낸다.
JWT·내부 토큰·DB 비밀번호는 결과 출력에 포함하지 않는다.

curl 등에서 직접 인증을 준비하려면 다음 명령을 사용하되 출력/셸 trace를 결과에 첨부하지 않는다.

```bash
JWT_A=$(python3 tools/seat_test.py jwt --user 101)
curl --silent --show-error -H "Authorization: Bearer $JWT_A" \
  -H 'Content-Type: application/json' -d '{"concertId":201,"seatIds":[401,402]}' \
  http://127.0.0.1:28084/api/bookings
unset JWT_A
```

예약 생성 응답의 bookingId를 아래 `<UUID>`에 대입한다. 가짜 PG 식별자만 쓰고
서로 다른 결제 시도는 `--payment <새 UUID>`로 구분한다. confirm/cancel/expire는
각각 독립 reset과 예약 생성 후 실행한다. 같은 예약을 닫은 뒤 다른 종료 요청을 보내는 것은 별도 경계 케이스다.

```bash
python3 tools/seat_test.py request confirm --booking <UUID> --amount 20000
python3 tools/seat_test.py request cancel --booking <UUID>
python3 tools/seat_test.py request expire --booking <UUID>
python3 tools/seat_test.py request leave --user 101
python3 tools/seat_test.py observe --user 101 --seats 401 402
```

내부 최종 처리 요청은 전체 PG 승인을 의미하지 않는다. 예약 상세 GET은 외부 개인정보 호출로
seat-test 범위 밖이므로 DB 관찰로 대체한다. 외부 클라이언트가 fake 성공을 반환하지 않는다.

## 증거와 상태 관찰

요청 출력은 UTC 시작/종료, 사용자, action, body, HTTP와 응답이다. 예상 4xx도 결과로 출력한다.
프로세스 종료 코드 0만으로 PASS를 판정하지 말고 HTTP/업무 응답과 before/after를 함께 확인한다.
`observe`는 owner/PTTL/holds/access/accessBySchedule/accessIndex/active와 DB now,
좌석/예약/항목 및 활성 중복 예약 조회를 출력한다. 없음은 GET 빈 문자열, PTTL -2이며
active 키 없음은 논리적으로 0이다. snapshot 여러 명령은 한 시점의 원자적 관측이 아니다.

```bash
mkdir -p /tmp/seat-run-01
python3 tools/seat_test.py observe --seats 401 402 > /tmp/seat-run-01/before.txt
python3 tools/seat_test.py request batch --seats 401 402 > /tmp/seat-run-01/request.json
python3 tools/seat_test.py observe --seats 401 402 > /tmp/seat-run-01/after.txt
```

[결과 양식](../../../test-docs/seat-hold/results/TEMPLATE.md)에 run ID/커밋/SQL 버전·시각·응답·상태를 연결한다.
토큰 생성 명령의 출력과 전체 Compose config, `.env` 및 실 PG 키는 증거로 저장하지 않는다.
실패 증거를 수집하기 전 reset하지 않는다.

## 반복 초기화 및 종료

```bash
python3 tools/seat_test.py reset --discard-evidence
python3 tools/seat_test.py observe --seats 401 402 409
docker compose down
```

reset은 요청을 중지한 상태에서 티켓팅을 정지하고 공통 `02-fixture.sql`로 DB를 복구한 뒤
전용 Redis DB 0을 FLUSHDB하고 서비스를 재기동한다. 사용자가 먼저 증거를 보존했다는
명시적 `--discard-evidence`가 필요하다. 프로젝트/프로필/DB·Redis 연결/loopback 검사를 통과해야 실행한다.
기존 개발 DB/Redis나 임의 URL에는 연결하지 않는다. SQL 실패 시 앱은 정지 상태로 남는다.

최초 빈 볼륨은 01-schema/02-fixture를 자동 실행한다. 재시작은 SQL을 재실행하지 않는다.
스키마 버전 변경은 [기동 안내](../README.md)에 따라 전용 프로젝트 `down -v` 후 새로 시작한다.
`down`은 데이터를 보존하고 `down -v`는 이 프로젝트의 DB/Redis 두 볼륨만 삭제한다.
