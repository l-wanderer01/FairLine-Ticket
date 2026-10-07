# 실행 시나리오와 검증

## 공통 실행 절차

1. run ID, 코드 커밋, 환경·fixture 버전, 적용 정책 기록.
2. 의존성 readiness와 기본 fixture 확인. 테스트 데이터 외 스케줄러 간섭 차단.
3. 사전 조건 생성 후 Redis/DB 상태를 before 증거로 저장.
4. 지정 케이스 실행. 요청별 ID·사용자·인스턴스·시각·응답 수집.
5. 모든 요청과 커밋/보상 완료를 기다린 후 after 상태 저장. timeout이면 성공으로 간주하지 말고 DB/Redis 결과 확인.
6. 응답, Redis, DB, 이벤트 업무 효과를 함께 판정. 증거 누락은 PASS 불가.
7. 결함 또는 BLOCKED 이유 기록 후 fixture 초기화. 수정 시 같은 시나리오와 관련 회귀 테스트 재실행.

## 시나리오 구성

| ID | 목적 / 케이스 | 실행 흐름 | 완료 기준 |
| --- | --- | --- | --- |
| SC-01 | 기본 계약 / TC-01,02,04,05,06,08,09,10,11,13,14,17,18 | 케이스별 초기화 → 정상 선점 → 소유권/제한/입력 → 예약 생성 | 응답·Redis·DB 검증, 미결정 정책 별도 기록 |
| SC-02 | 경쟁 / TC-03,07,12,19,23 | 단일 인스턴스 경쟁 각 20회 → TC-26으로 다중 인스턴스 각 20회 | 지정 불변 조건 모두 충족, 실제 경합 증거 |
| SC-03 | 만료/늦은 요청 / TC-15,16,20,24 | 실제 5분 만료 → 짧은 TTL 경계 재현 → 늦은 확정과 새 예약 경합 | 중복 확정·새 소유권 침해 없음; 정책 의존 항목 분리 |
| SC-04 | 확정·종료 / TC-21,22 | 정상 확정·재전송 → 취소/만료 경합 | 좌석·예약·업무 효과 일치 |
| SC-05 | 장애 복구 / TC-25 | Redis 예외 주입 → 별도 프로세스 종료 실행 → 복구·TTL 관찰 | 잔여 상태·복구 시간 측정, 거짓 성공 없음 |
| SC-06 | 성능 / TC-27 | 경쟁 집중·분산·일괄을 별도 workload로 점진 증가 | 정합성 유지, 지표 수집, 성능 목표와 비교 |

## 경쟁 재현 방법

단순 루프나 sleep만으로 경쟁을 보장하지 않는다. 전체 참여자가 준비된 뒤 barrier로 시작한다. TC-19는 두 트랜잭션 모두 충돌 조회를 마치고 저장 전 대기하도록 latch/test hook을 사용한다. TC-23은 두 호출 모두 접근 키 존재를 읽은 직후 멈춘다. TC-16은 이전 소유자 조회 후 멈추고, 새 소유자의 선점이 성공한 것을 확인한 뒤 재개한다.

hook은 테스트 구성에서만 활성화한다. TC-19는 [Testcontainers 회귀](06-testcontainers.md)의 test-only Aspect로 실제 충돌 조회 이후 barrier를 구현했다. TC-16/23 hook·장애 프록시는 아직 준비되지 않았으므로 해당 결정적 재현은 BLOCKED다. 일반 HTTP 동시 요청은 보조 관찰 실행으로 기록할 수 있다. 각 작업은 독립된 트랜잭션·커넥션을 사용하고, 테스트 전체를 하나의 rollback 트랜잭션으로 감싸지 않는다.

## 늦은 확정의 상세 순서 (TC-20)

1. A가 S01을 선점하고 예약 BA 생성.
2. BA의 expires_at 경과와 Redis 선점 만료를 모두 확인. 자동 expire 처리를 격리해 BA 상태는 HOLDING으로 남김.
3. B가 S01 재선점하고 예약 BB 생성.
4. BA 확정 후 BB 확정. fixture를 새로 만들고 BB 확정 후 BA 확정 순서도 실행.
5. 두 응답, BA/BB 상태, 좌석 RESERVED 상태, Redis 소유자, 업무 이벤트를 수집.
6. S01이 두 CONFIRMED 예약에 포함되면 INV-04 위반으로 FAIL. 예약 확정 응답만 보고 PASS로 처리하지 않음.

## DB 검증 쿼리

전용 테스트 DB에서 실행한다. fixture 좌석 ID 조건을 추가해 run의 데이터만 검사한다. 아래 조회 결과는 위반 후보이며 원인을 로그와 함께 확인한다.

```sql
-- 같은 좌석에 확정 예약이 둘 이상 있는지 (정상: 0행)
SELECT bi.seat_id, COUNT(DISTINCT b.id) AS confirmed_count
FROM ticketing.booking_items bi
JOIN ticketing.bookings b ON b.id = bi.booking_id
WHERE b.status = 'CONFIRMED'
GROUP BY bi.seat_id
HAVING COUNT(DISTINCT b.id) > 1;

-- 같은 좌석에 동시에 유효한 예약이 둘 이상 있는지 (정상: 0행)
SELECT bi.seat_id, COUNT(DISTINCT b.id) AS active_count
FROM ticketing.booking_items bi
JOIN ticketing.bookings b ON b.id = bi.booking_id
WHERE b.status = 'CONFIRMED'
   OR (b.status = 'HOLDING' AND b.expires_at > CURRENT_TIMESTAMP)
GROUP BY bi.seat_id
HAVING COUNT(DISTINCT b.id) > 1;

-- 확정 예약의 좌석 상태 불일치 (정상: 0행)
SELECT b.id AS booking_id, bi.seat_id, s.status
FROM ticketing.bookings b
JOIN ticketing.booking_items bi ON bi.booking_id = b.id
JOIN concert.seats s ON s.id = bi.seat_id
WHERE b.status = 'CONFIRMED' AND s.status <> 'RESERVED';
```

최종 조회 외에 케이스별 예약 ID로 상태·항목 수·금액·만료 시각을 확인한다. 취소 예약의 좌석이 AVAILABLE인지 검사할 때는 다른 CONFIRMED 예약 소유권을 반드시 함께 확인한다. DB와 Redis 간 하나의 원자적 스냅샷은 없으므로 읽은 시각을 기록한다.

## 실패 이후 개선 절차

증거와 최소 재현 순서를 고정 → 원인 분석 → 코드/정책 수정 → 같은 fixture·경쟁 지점으로 재검증 → 관련 케이스 회귀 실행. 중복 확정과 소유권 침해가 남아 있으면 성능 통과나 전체 서비스 검증 완료로 보고하지 않는다.
