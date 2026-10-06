# 이슈 #5 준비 결과

- 기준: develop db091c4 + feature/issue5
- 검증일: 2026-10-06 (Asia/Seoul)
- 준비: TC-01,02,05,06,10,17의 명령·사전/사후 조건·reset 시점·두 번 반복 절차
- 결과 양식: MANUAL-SMOKE-TEMPLATE.md, 사용자별 run-01/run-02에 각각 복사

문서 상대 링크, bash 명령 블록 구문, Python 구문을 검사했다.
관찰 도구에서 실제 좌석별 PTTL 측정 시작/종료 시각 출력을 확인했다.
예약 금액/항목/최소 TTL 기반 만료 비교와 시계·왕복 지연/초 단위 절삭의 오차를 안내했다.
실패 증거 보존→별도 결함 이슈→수정/재검증 연결 절차를 작성했다.

| 사용자 수동 케이스 | run-01 | run-02 |
| --- | --- | --- |
| TC-01 | NOT_RUN | NOT_RUN |
| TC-02 | NOT_RUN | NOT_RUN |
| TC-05 | NOT_RUN | NOT_RUN |
| TC-06 | NOT_RUN | NOT_RUN |
| TC-10 | NOT_RUN | NOT_RUN |
| TC-17 | NOT_RUN | NOT_RUN |

절차 준비와 도구 출력 확인은 PASS다. 사용자가 직접 수행할 위 수동 케이스는 실행하지 않았다.
#4의 계약 검증이나 #6 자동 회귀를 이 표의 통과로 옮겨 적지 않는다.
