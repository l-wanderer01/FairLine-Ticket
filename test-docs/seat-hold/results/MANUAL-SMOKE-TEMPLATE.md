# 사용자 수동 smoke 결과 (run별 복사)

상태: NOT_RUN. 실행자가 직접 관찰·판정하기 전에는 상태를 바꾸지 않는다.
절차: [사용자 smoke](../05-manual-smoke.md), 케이스 기준: [TC 목록](../03-test-cases.md).

## 실행 정보

| 항목 | 값 |
| --- | --- |
| run ID / 반복 번호 (01 또는 02) / 실행자 | 미입력 |
| 시작·종료 / 시간대·UTC offset | 미입력 / Asia/Seoul +09:00 |
| 코드 커밋 / 미커밋 변경 | 미입력 |
| 절차 버전 / SQL 두 파일 SHA256 | 미입력 |
| fixture 실제 ID / 인스턴스·routing | U-A=101, U-B=102, C-A=201, SCH-A1=301, S01~S09=401~409 / 미입력 |
| JDK / PostgreSQL / Redis / Docker·Compose / Python | 미입력 |
| 프로필 / 스케줄러·Kafka / TTL 변경 | seat-test / 비활성 / 변경 없음(실행 시 확인) |
| reset 전 증거 보관 및 reset 완료 시각 | 미입력 |
| 초기 DB·Redis 검증 증거 | 미입력 |

## 케이스 결과

| 케이스 | 상태 | 기대 HTTP/업무 응답 | 실제 응답 | Redis before/after | DB before/after | 결함/차단 이유 |
| --- | --- | --- | --- | --- | --- | --- |
| TC-01 | NOT_RUN | 200 held | 미입력 | owner=101, 양수 TTL, holds 포함 | S01 AVAILABLE | 미입력 |
| TC-02 | NOT_RUN | 409 conflict | 미입력 | owner=101 유지, B holds 없음, TTL 갱신 없음 | 변경 없음 | 미입력 |
| TC-05 타인 해제 | NOT_RUN | 409 | 미입력 | owner=101 유지 | 변경 없음 | 미입력 |
| TC-05 본인/재해제 | NOT_RUN | 200 released / already_released | 미입력 | owner·참조 제거 | AVAILABLE | 미입력 |
| TC-10 | NOT_RUN | 200, heldSeatIds 4개 | 미입력 | 401~404 owner=101, holds 4개 | AVAILABLE | 미입력 |
| TC-06 | NOT_RUN | 400 | 미입력 | 기존 4개 유지, 405 없음 | 변경 없음 | 미입력 |
| TC-17 | NOT_RUN | 201 HOLDING | 미입력 | 401,402 owner=101, 양수 TTL | 새 예약·항목 2개, 20000, 최소 TTL 만료 | 미입력 |

각 셀에는 관측값과 증거 경로를 연결한다. 응답/프로세스 종료 코드만으로 PASS를 판정하지 않는다.
두 run을 하나로 합치지 않는다. 각 케이스 실행 시각, 사용자·대상 인스턴스·요청 횟수를 별도 기록한다.

## TC-17 만료 비교

| 값 | 관측값 / 증거 |
| --- | --- |
| S01 hold 시작/종료 | 미입력 |
| S02 hold 시작/종료 | 미입력 |
| S01 before PTTL 및 측정 시작/종료 | 미입력 |
| S02 before PTTL 및 측정 시작/종료 | 미입력 |
| 각 관측 종료 시각 + PTTL/1000 / 최소 추정 만료 | 미입력 |
| 예약 응답 시작/종료 / 실제 처리 시간 | 미입력 |
| bookingId / created_at / expires_at / DB now before/after | 미입력 |
| 금액 / 항목 집합 / DB 상태 | 미입력 |
| 시계 차이 / TTL 초 단위 절삭 / 관측 지연 / 차이 | 미입력 |
| 2초 기준 판정 / 초과 조사 / BLOCKED 사유 | NOT_RUN / 미입력 |

## 결함 및 실행 판단

- NOT_RUN/PASS/FAIL/BLOCKED 건수:
- 기대 위반 또는 증거 누락:
- reset 전 보존한 최소 재현·로그·Redis/DB/응답 증거:
- 별도 결함 이슈 번호 / 우선순위 / 불변 조건:
- 환경·정책에 대한 가설과 확인 사실:
- 수정/정책 결정 커밋 / 재검증 run:
- 두 번째 반복의 초기 상태 재현 결과:

JWT·내부 토큰·DB 비밀번호·실 PG 키·`.env`·전체 Compose config는 첨부하지 않는다.
미실행 케이스를 PASS로 집계하지 않는다. 수동 run 완료와 #5 절차 준비 완료는 별개다.
