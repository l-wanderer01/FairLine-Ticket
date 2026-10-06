# 이슈 #4 도구 계약 검증

- 일자: 2026-10-06 (Asia/Seoul)
- 기준: develop 9c4c169 + feature/issue4, 공통 SQL #3
- PostgreSQL 17.11, Redis 7.4.11, Compose 단일 티켓팅 인스턴스

Python 표준 라이브러리 도구를 실제 서비스에 연결해 다음 계약을 확인했다.
도구 검증이며 #5에서 사용자가 수행할 수동 run을 대신한 결과가 아니다.

| 요청/관찰 | 결과 |
| --- | --- |
| check / Python 문법 | PASS |
| U-A 정상 단일 선점 | 200 held |
| U-B 같은 좌석 선점 / 타인 해제 | 각각 409 |
| U-A 본인 해제 | 200 released |
| 401,402 일괄 / 예약 | 200 / 201 HOLDING, 총액 20000 |
| 내부 confirm / cancel / expire | 각 유효 예약에서 200 |
| leave | 200 |
| 만료 JWT | 401 |
| 잘못된 내부 토큰 | 403 |
| observe | owner/PTTL/holds/access/active, DB 시각/좌석/예약/항목/중복 쿼리 출력 |
| 삭제 옵션 없는 reset | 거부, 데이터 변경 없음 |
| reset 두 번 | 앱 정지→공통 SQL→전용 Redis flush→healthy 복구 |
| reset 이후 | S01/S02 AVAILABLE, S09 RESERVED, 기본 예약·항목 각각 1, Redis 빈 상태 |
| 출력 검사 | 요청 headers/JWT/내부 토큰/DB 비밀번호 기록 없음 |

SQL mount 권한이 restrictive checkout에서 600으로 바뀌는 문제는 공개 SQL에 읽기 권한을
부여한 뒤 새 전용 볼륨으로 재초기화했다. 해당 준비 명령을 기동 README에 추가했다.
실패 증거 보존/reset 범위, 최초 초기화와 재시작 차이 및 가짜 PG 식별자 제한을 문서화했다.
전체 MSA/로그인/실제 PG 승인은 NOT_RUN이며 이 도구 범위 밖이다.
