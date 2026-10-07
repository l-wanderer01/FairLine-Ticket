# 사용자 수동 smoke 절차 (#5)

상태: NOT_RUN. 이 문서는 준비된 절차이며 사용자가 직접 실행·관찰·판정한다.
#4의 도구 계약 검증과 #6 자동 회귀 결과를 이 수동 run의 PASS로 대신 기록하지 않는다.
범위는 TC-01,02,05,06,10,17, 단일 티켓팅 + 실제 Redis/PostgreSQL이다.
PG 결제 승인·대기열·Kafka·CDC·성능·다중 인스턴스는 제외한다.

## 실행 준비 및 두 번 반복

[기동 안내](../../test-infra/seat-hold/README.md)대로 시작한 후 아래 명령은
`test-infra/seat-hold`에서 실행한다. Python 3, Docker Compose v2와 curl이 필요하다.

```bash
python3 tools/seat_test.py check
curl --fail http://127.0.0.1:28084/actuator/health/readiness
mkdir -p /tmp/seat-smoke-run-01
```

[수동 결과 양식](results/MANUAL-SMOKE-TEMPLATE.md)을
`results/YYYY-MM-DD-smoke-01.md`와 `...-02.md`로 각각 복사한다.
run ID, 실행자, 코드 커밋(`git rev-parse HEAD`), 미커밋 변경, SQL SHA256,
Docker/Compose/JDK/DB/Redis 버전, 시작/종료 시각·시간대(Asia/Seoul)를 기록한다.
SQL SHA256은 `sha256sum sql/01-schema.sql sql/02-fixture.sql`로 확인한다.
응답 도구의 시각은 UTC이며 현지 기록 시 UTC offset을 함께 남긴다.
환경변수/JWT 생성 출력/실제 PG 키/전체 Compose config는 저장하지 않는다.

각 run의 시작에 이전 실패 증거가 보존됐는지 확인하고
`python3 tools/seat_test.py reset --discard-evidence`를 실행한다.
observe에서 S01~S08 AVAILABLE, S09 RESERVED, 기본 CONFIRMED 예약·항목 각 1개,
Redis owner 없음/PTTL=-2/holds·access·index 없음/active 논리적으로 0을 확인한다.
불일치면 요청을 진행하지 말고 BLOCKED 원인과 상태를 기록한다.
전체 순서를 완료하고 증거를 저장한 뒤 reset하고 run-02에서도 같은 순서를 수행한다.
run-02 증거 경로는 `/tmp/seat-smoke-run-02`로 바꾼다. 명령만 반복했다고 PASS로 처리하지 않는다.

## TC-01 → TC-02 → TC-05: 소유권과 해제

```bash
python3 tools/seat_test.py observe --user 101 --seats 401 > /tmp/seat-smoke-run-01/01-before.txt
python3 tools/seat_test.py request hold --user 101 --seat 401 > /tmp/seat-smoke-run-01/01-response.json
python3 tools/seat_test.py observe --user 101 --seats 401 > /tmp/seat-smoke-run-01/01-after.txt
python3 tools/seat_test.py request hold --user 102 --seat 401 > /tmp/seat-smoke-run-01/02-response.json
python3 tools/seat_test.py observe --user 102 --seats 401 > /tmp/seat-smoke-run-01/02-after.txt
python3 tools/seat_test.py request release --user 102 --seat 401 > /tmp/seat-smoke-run-01/05-other-release.json
python3 tools/seat_test.py observe --user 102 --seats 401 > /tmp/seat-smoke-run-01/05-other-after.txt
python3 tools/seat_test.py request release --user 101 --seat 401 > /tmp/seat-smoke-run-01/05-own-release.json
python3 tools/seat_test.py request release --user 101 --seat 401 > /tmp/seat-smoke-run-01/05-repeat-release.json
python3 tools/seat_test.py observe --user 101 --seats 401 > /tmp/seat-smoke-run-01/05-after.txt
```

TC-01: 200/held,owner=101, 0<PTTL<=300000, holds에 401, DB S01 AVAILABLE.
TC-02: 409,owner=101 유지, B holds에 401 없음. 재시도 때문에 TTL이 늘어나지 않아야 한다.
관측 시각이 달라 TTL은 자연 감소하므로 전후 수치·시각을 함께 비교한다.
TC-05: B 해제 409 및 owner 유지, A 해제 200/released, 재해제 200/already_released.
최종 owner 없음/PTTL=-2/A holds에 401 없음. 각 응답 뒤 필요하면 observe를 추가해 중간 상태도 보존한다.
단일 POST는 토글이므로 실수로 본인 hold를 반복하면 해제된다.

## TC-10 → TC-06: 일괄 4석 및 제한

앞 단계 해제가 확인된 상태에서 실행한다. 상태가 불확실하면 증거 보존 후 reset하고 다시 시작한다.

```bash
python3 tools/seat_test.py request batch --user 101 --seats 401 402 403 404 > /tmp/seat-smoke-run-01/10-response.json
python3 tools/seat_test.py observe --user 101 --seats 401 402 403 404 405 > /tmp/seat-smoke-run-01/10-after.txt
python3 tools/seat_test.py request hold --user 101 --seat 405 > /tmp/seat-smoke-run-01/06-response.json
python3 tools/seat_test.py observe --user 101 --seats 401 402 403 404 405 > /tmp/seat-smoke-run-01/06-after.txt
```

TC-10: 200, heldSeatIds 집합={401,402,403,404}, owner 모두 101, 양수 TTL, holds 4개.
TC-06: 400, 기존 4석 소유권 유지, 405 owner 없음, DB 좌석 AVAILABLE 유지.
순서와 집합을 혼동하지 않고 실제 유효 owner 수·holds 참조를 둘 다 검사한다.
증거 저장 후 reset한다. 예약 단계는 별도 초기 상태에서 진행한다.

## TC-17: 서로 다른 시점의 두 선점과 예약

```bash
python3 tools/seat_test.py reset --discard-evidence
python3 tools/seat_test.py request hold --user 101 --seat 401 > /tmp/seat-smoke-run-01/17-first-hold.json
# 시각 차이를 분명히 하기 위해 3초 정도 후 다음 명령을 직접 실행한다.
python3 tools/seat_test.py request hold --user 101 --seat 402 > /tmp/seat-smoke-run-01/17-second-hold.json
python3 tools/seat_test.py observe --user 101 --seats 401 402 > /tmp/seat-smoke-run-01/17-before.txt
python3 tools/seat_test.py request booking --user 101 --seats 401 402 > /tmp/seat-smoke-run-01/17-response.json
python3 tools/seat_test.py observe --user 101 --seats 401 402 > /tmp/seat-smoke-run-01/17-after.txt
```

201/HOLDING, 새 bookingId의 user=101/schedule=301, total_price=20000, 항목 집합={401,402}를 확인한다.
기본 S09 예약을 새 예약으로 세지 않는다. Redis 소유권은 101이고 DB 좌석은 AVAILABLE이어야 한다.

만료 검사는 응답·DB expires_at 및 created_at, before/after DB now,
각 PTTL 관측 시각을 함께 사용한다. 구현은 예약 생성 시작 시각 `now`에 Redis TTL **초 단위**의
최소값을 더한다. 각 좌석의 추정 만료 시각은 `pttlFinishedAt + PTTL/1000`이다.
`pttlStartedAt`~`pttlFinishedAt`은 측정 시간 범위이며 명령 왕복 지연도 오차에 포함한다.
두 좌석 중 이른 만료를 기준으로 expires_at이 만들어졌는지 확인한다.
초 단위 절삭(<1초), 네트워크 왕복/관측 시간차·앱/DB 시계 차이를 기록하고,
기본 허용 오차 2초보다 크면 실제 처리 시간·시계 차이를 조사한다. 임의로 허용 오차를 늘려 PASS로 바꾸지 않는다.
응답 finishedAt-startedAt이 길거나 TTL 경계에 걸려 판단 불가면 BLOCKED 후 새 run에서 재검증한다.

## 판정과 결함 관리

모든 케이스·두 반복은 NOT_RUN에서 시작한다. 증거가 모든 기대를 만족하면 PASS,
위반을 확인하면 FAIL, 환경/증거 부족이면 BLOCKED를 사용한다. HTTP 성공만으로 PASS를 판정하지 않는다.
실패 시 reset 전에 응답/Redis/DB/관련 로그를 저장하고 인증값을 제거한다.
결과 양식의 최소 재현·기대/실제·영향·시각·커밋·증거로 별도 결함 이슈를 만든다.
기존 FEATURE #5에는 준비 완료를 기록하고 실행 결함은 별도 이슈 번호로 연결한다.
중복 확정·소유권 침해는 정책 미결정을 이유로 보류하지 않는다.
이 문서의 구현 자체가 사용자 수동 검증 PASS를 의미하지 않는다.
