# 과거 계획과 기술 감사

이 디렉터리는 당시의 계획·관측·판단과 측정 근거를 보존한다. 현재 사용법은 [README](../../README.md), 실제 계약은 [설계](../architecture-decisions.md), 최종 대응 상태는 [요구사항 검증](../requirements-verification.md)을 기준으로 읽는다.

| 기록 | 시점과 목적 |
| --- | --- |
| [구현 계획](implementation-plan.md) | 초기 구현 순서와 2026-09-16까지의 진행 상태 |
| [정확성 감사](correctness-audit.md) | 숫자 파싱·항목 격리·중복·코드 보존 문제의 발견 근거 |
| [DB 감사](database-audit.md) | 독립 commit·경쟁·SQL 수·인덱스·잠금 대기 실험 |
| [네트워크·메모리 감사](network-memory-audit.md) | 고객 간 호출 증폭·codec 한도·메모리 구조·취소 경계 실험 |
| [구조·예외·테스트 감사](structure-audit.md) | 실제 의존 관계·확장 비용·검증 공백과 수정 우선순위 |

정확성·호출 자원·DB 예산·전체 연결 검증은 이후 커밋에서 보완했다. 각 감사 상단에 후속 상태가 있으며, 본문의 당시 수치나 미구현 표현을 현재 동작으로 읽지 않는다. 현재 설계에 없는 초기 DTO 계획 등도 당시 계획으로만 보존한다. 문서 정리 과정에서 내부 작업 번호는 주제명으로 바꿨다.

## 측정 근거

- [DB 실행 계획과 측정](audit-evidence/db-query-plans.json)
- [네트워크·메모리 측정과 임시 재현 소스](audit-evidence/network-memory.json)
- [Supplier 확장 실험](audit-evidence/structure-extensibility.json)
- [구조 재검토와 경계 재현](audit-evidence/structure-review.json)

JSON에는 당시 기준 커밋·측정 조건·임시 검증 소스가 담겨 있다. 감사용 `/private/tmp/supplier-hub-*` 경로는 임시 소스를 복원해 실행하기 위한 예시이며, 파일이 저장소 안에 존재한다는 뜻은 아니다. 현재 검증은 정식 테스트로 수행한다. 당시 측정값을 현재 코드의 성능 또는 개선 전후 비교로 사용하지 않는다.
