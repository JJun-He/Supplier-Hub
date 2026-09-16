# 중간 기술 감사: 구조·SOLID·공급사 확장성·예외·테스트

## 1. 결론과 검증 수준

- 작성·재검토일: 2026-09-16
- 기준 커밋: `eff5bb83a940d8d29a778b9de0d041d888771388`
- 범위: 7.5-D. 계층 경계, SOLID 5원칙, Supplier 추가 비용, 검증·예외, 테스트 책임과 누락
- 이번 방법: 제품·테스트 소스 재검토, 기존 실험 기록 대조, 임시 경계 검증 5개와 실제 Boot/Tomcat HTTP 검증 1개
- 이번 실행: **6개 성공, 실패·오류·건너뜀 0개**. 제품 코드·설정·정식 테스트는 변경하지 않았다.

**공급사별 어댑터와 공통 검색 로직의 분리는 적절하다. 8단계에서는 카탈로그의 client/snapshot 계약 검사, 입력 거부와 내부 오류의 구분, 실제 연동 검증을 먼저 보완한다. JPA Entity 분리·검증 유틸 통합·컨테이너 단일화는 필수 수정으로 올리지 않는다.**

기존 `structure-audit.md`의 유용한 분류와 확장 실험 기록을 바탕으로 이 파일을 개정했다. 기존 실험의 [기록과 diff](audit-evidence/structure-extensibility.json)는 보존하고, [이번 재검토 근거](audit-evidence/structure-review.json)에 정정 사항·소스별 테스트 목록·재현 소스·실행 결과를 추가했다. 기존 실험을 이번에 실행한 것으로 기록하지 않는다.

앞선 [정확성 감사](correctness-audit.md), [DB 감사](database-audit.md), [네트워크·메모리 감사](network-memory-audit.md)의 미수정 항목도 계속 유효하다. 기능 문제와 설계 취향을 구분하고, 임시 재현 성공을 문제 해결이나 요구사항 검증 완료로 해석하지 않는다.

### 기존 문서에서 바로잡은 내용

| 기존 설명 | 재검토 결과 |
| --- | --- |
| 버퍼 초과 관측으로 내부 프로그래밍 오류도 이미 재현됐다고 설명 | 서로 다른 사례다. 이번에 `IllegalStateException`과 mapper NPE를 직접 주입해 각각 확인했다. |
| 카탈로그가 예외를 조용히 흡수 | WARN과 stack trace가 있고 이를 확인하는 정식 테스트도 있다. 부족한 것은 실패 분류·지표·마지막 성공 시각이다. |
| application이 포트에만 의존 | 검색도 구체적인 설정 record `SupplierIntegrationProperties`에 의존한다. 프레임워크 없는 application은 아니다. |
| HTTP/transport 매퍼가 B 본문 코드까지 처리 | B 본문 코드는 B catalog/search client의 별도 `bodyFailure` 두 곳에서 처리한다. |
| Reactor 오류 연산자 13곳 | `onErrorMap` 13곳 + `onErrorResume` 1곳 = **14곳**이다. |
| HTTP 테스트가 연결 종료까지 검증 | 정식 client 테스트는 상대 연결 종료를 assert하지 않는다. 해당 근거는 별도의 7.5-C 임시 검증이다. |
| PostgreSQL 테스트라 실행 계획도 동일 | 같은 DB 계열을 검사하는 장점이 있다. 데이터·통계·환경이 다르면 계획은 달라진다. 실제 계획 측정은 7.5-B에 별도로 있다. |
| 컨테이너를 공유해도 정합성 영향 없음 | 데이터 격리·정리·Spring context 및 컨테이너 수명 문제가 추가된다. 성능 비교 없이 필수 변경으로 권하지 않는다. |
| 도메인 둘이 서로를 모름 | search domain은 catalog domain의 `Supplier` enum에 의존한다. |
| A/B 오류 표가 각각 오류 6종 | A는 서로 다른 실패 HTTP 상태 **5개**, B는 성공 `0000` + 실패 코드 **5개**다. |

## 2. 우선순위

| ID | 판단 | 내용 | 권장 시점 |
| --- | --- | --- | --- |
| E-06 (추가) | P2·확장 시 계약 방어 누락 | 카탈로그 client 누락·중복, snapshot의 Supplier 불일치를 검사하지 않음 | 8-A |
| E-01 / E-03 | P2·진단 구분 부족 | adapter 내부 오류는 UNKNOWN, mapper NPE는 항목 거부로 집계 | C-01/C-02와 함께 8-A |
| E-02 | P2·관측성 | 카탈로그 일반 실패 로그는 있지만 실패 지표·갱신 신선도 신호 없음 | N-04와 함께 8-B |
| T-01 / T-02 | P2·검증 공백 | 오류 표와 실제 DB→Mock→검색 API 흐름의 정식 회귀 검증 부족 | 8-A / 8-D |
| X-02 | P3·테스트 결합 | 설정 생성자 5개 파일 9곳을 공급사 추가 때 수정 | 관련 테스트를 수정할 때 |
| E-05 | 유지 | API의 도메인 생성자 검증 방식 | §5.5; Bean Validation 전환 불필요 |
| T-03 | 보강 후보 | 클래스별 개수가 아닌 검증되지 않은 정책·경계값으로 테스트 선정 | §6.3; 관련 구현 변경 시 |
| S-01 | P3·저장 방식 결합 | writer가 application에 있으면서 Spring Data repository를 직접 사용 | DB 변경과 함께 또는 후속 |
| E-04 | P3·응답 형식 | 예기치 않은 500은 입력 오류와 다른 기본 JSON 형식 | 오류 계약 정리 시 |
| S-02 / S-03 / S-04 / X-03 / X-04 / T-04 | 선택 또는 유지 | JPA 결합, 작은 헬퍼, public 검증, 설정 구조, bean, 컨테이너 | 아래 개별 판단 참조 |

P2는 서비스의 실패 격리·확장 시 실수 방지·검증 근거와 직접 연결된다. 이번 추가 검증은 잘못 구현된 test adapter를 주입한 것이다. 현재 A/B adapter가 실제로 다른 Supplier snapshot을 반환한다고 주장하지 않는다.

기존 X-01(확장 실험)은 §4.1, T-05(테스트 인프라 선택)는 §6.1과 §6.5로 통합했다. 별도 결함 ID로 유지하지 않으며, 기존 실험의 검증 범위는 §4.1에 명시한다.

## 3. 계층 경계와 SOLID

### 3.1 현재 의존 방향

| 경계 | 실제 의존 | 판단 |
| --- | --- | --- |
| `search/api` | 검색 application/domain과 결과·실패 enum | 외부 DTO를 직접 해석하지 않음. API 표현 책임이 모여 있음 |
| `search/application` | 매핑 reader, Supplier 포트/DTO/설정, Reactor | 통합 조회·시간 제한·부분 실패를 조정하는 역할. 구체 A/B client에는 의존하지 않음 |
| `search/domain` | Java 표준 타입과 `catalog.domain.Supplier` | Spring·JPA·JSON·Reactor import 없음 |
| `catalog/application` | Supplier 포트, 설정, snapshot store; writer는 Spring Data repository도 사용 | 외부 조회와 DB commit은 분리. writer의 저장 기술 결합은 남음 |
| `catalog/domain` | JPA Entity와 snapshot 값 타입, Lombok 등 | 영속 엔티티와 순수 값 타입이 공존. 전부 프레임워크 독립이라고 설명하지 않음 |
| `supplier/suppliera`, `supplier/supplierb` | WebClient, 전용 외부 DTO, 공통 정규화 | 공급사 프로토콜 차이를 담는 적절한 위치 |
| `supplier/common` | 포트·통합 DTO·설정·HTTP 인프라 | 이름상 공통 패키지에 여러 책임이 공존. 패키지 이름만으로 모두 포트라고 판단하지 않음 |

import 분석은 직접 import 기준이다. 같은 패키지 참조, Spring 주입, 런타임 계약은 별도로 살폈다. 테스트 개수나 패키지 경로만으로 경계의 품질을 판단하지 않는다.

### 3.2 SOLID 5원칙별 판단

| 원칙 | 확인한 근거 | 보완할 경계 |
| --- | --- | --- |
| SRP: 변경 이유를 모은다 | client는 프로토콜 변환, domain은 가격·재고 규칙, API는 응답 표현을 담당 | `IntegratedSearchService`가 계획·실행·집계·결합을 모두 담당한다. 길이만으로 쪼개지 말고 전역 호출 제한은 별도 실행 경계로 분리할 가치가 있음 |
| OCP: 기존 핵심 로직 변경을 줄인다 | Supplier별 구현을 `List<SupplierSearchClient>`로 조합. 통합 서비스에 A/B 분기 없음 | enum·설정·bean 등록은 수정된다. 확장에 기존 파일 0개 변경을 요구하지 않음 |
| LSP: 구현을 교체해도 계약을 지킨다 | 검색은 client 중복·활성 client 누락·응답 Supplier 불일치·empty Mono를 검사 | 카탈로그의 대응 검사는 부족하다(E-06). 새 adapter도 같은 계약을 지키는 공통 검증 필요 |
| ISP: 필요한 인터페이스만 의존한다 | `SupplierCatalogClient`와 `SupplierSearchClient`, reader와 store가 분리됨 | 거대한 공통 Supplier 인터페이스로 합칠 이유 없음 |
| DIP: 정책과 구현 의존을 분리한다 | 검색은 매핑 조회 interface, 동기화는 snapshot store interface에 의존 | writer의 Spring Data import, 서비스의 구체 설정 의존은 남는다. 순수 hexagonal architecture 완성으로 표현하지 않음 |

### 3.3 S-01: writer 위치와 저장 정책

`CatalogSnapshotWriter`는 `CatalogSnapshotStore` 구현이면서 `catalog/application`에서 `PropertyRepository`, `RoomTypeRepository`를 직접 사용한다. 작동 오류는 아니지만 저장 구현 교체 시 snapshot 반영 정책도 같은 파일에서 바뀐다.

작은 정리는 writer를 infrastructure 구현으로 옮기는 방법이다. 다만 **파일 이동만으로 정책과 persistence가 분리되지는 않는다.** Spring scan·`@Import`·트랜잭션 proxy·테스트 import도 함께 확인해야 한다. 기존 11개 writer 테스트만으로 commit/rollback 경계까지 충분하지 않다는 점은 DB 감사에 이미 확인했다.

정책을 DB 없이 재사용할 실제 필요가 생기면 변경 계획 계산과 저장 실행을 나눌 수 있다. 현재 repository interface를 다시 감싸는 interface를 무조건 늘리거나 Entity/별도 domain 이중 모델을 추가하는 것은 우선순위가 낮다.

### 3.4 S-02: JPA Entity와 순수 검색 모델의 공존

카탈로그의 활성 상태·누락 횟수·내부 ID는 영속 상태다. 반면 검색 Offer는 매 요청의 계산 결과다. 서로 다른 모델을 택할 이유가 있으며, JPA annotation의 존재만으로 SOLID 위반이라고 할 수 없다. 9단계 문서에는 이 선택과 search domain에 영속 의존을 도입하지 않는 현재 경계를 설명하면 된다.

## 4. Supplier 추가 비용: 기존 기록과 이번 확인

### 4.1 기존 Supplier C 실험의 해석

기존 JSON에는 enum 추가 시 컴파일 오류 1곳, 기존 메인 4개 파일 변경(+36/−1), 어댑터 2개 추가, 테스트 5개 파일 9곳 변경, 메인 89개 통과가 기록돼 있다. 메인 diff는 보존돼 있지만 추가 adapter 소스·전체 테스트 변경 diff·실행 XML은 그 JSON에 없다. **이번 감사에서 Supplier C worktree를 새로 만들거나 그 89개 실행을 반복하지 않았다.**

현재 코드와 대조해 확인한 수정 범위:

| 구분 | 예상/기록된 변경 | 이번 확인 수준 |
| --- | --- | --- |
| `Supplier.java` | enum 추가 | 현재 enum 사용 및 기존 diff 확인 |
| `SupplierIntegrationProperties.java` | Endpoint 필드 + `isEnabled` switch | 현재 필드·exhaustive switch 확인 |
| `SupplierClientConfiguration.java` | catalog/search WebClient bean 2개 | 공통 생성 함수와 주입 경계 확인 |
| `application.yaml` | C 설정 블록 4줄 | 기존 diff 확인. 이전 문서의 3줄 설명 정정 |
| 신규 `supplier/supplierc` | catalog/search adapter 2개 + 전용 DTO/테스트 | 기존 기록. 서로 다른 실제 프로토콜 통합 성공을 뜻하지 않음 |
| 기존 테스트 | 설정 생성자 5개 파일·9곳 | 현재 소스를 재계수해 확인 |

현재 통합 검색·공통 정규화·고객 API에 공급사별 분기는 없다. DB의 supplier 열은 `VARCHAR(32)`이고 A/B만 허용하는 CHECK는 없으므로 짧은 새 enum 이름 때문에 migration을 추가할 필요는 없다. 다만 Supplier enum 이름은 저장값 및 고객 응답값이므로 변경·삭제는 단순 코드 리팩터링이 아니다.

기존 실험 YAML은 C를 기본 비활성으로 둔다. **기존 테스트 통과만으로 C의 실제 활성화·동기화·검색·오류 경로를 증명하지 못한다.** Supplier 추가 절차 문서에는 등록뿐 아니라 활성화 후 계약 검증을 포함해야 한다.

### 4.2 X-02: 테스트 설정 생성 비용

현재 `new SupplierIntegrationProperties(...)`는 설정 테스트 5곳, 동기화·통합 검색·catalog client·search client 테스트 각각 1곳이다. 기존 fixture가 새 필드 추가에 영향을 받는 것은 맞다. 그러나 이를 업무 실패와 같은 우선순위로 두거나 “무관한 테스트 수정이 완전히 0개”를 완료 조건으로 두는 것은 과하다.

관련 테스트를 수정할 때 `TestSupplierProperties` 같은 작은 factory/builder에 기본값을 모으면 된다. 해당 factory 1곳과 새 공급사의 계약 테스트는 계속 수정될 수 있다. 현재 시작 시 활성 Supplier/client 대응 검사는 유지한다.

### 4.3 X-03 / X-04: 지금 유지할 것

- 이름 있는 A/B Endpoint 필드와 exhaustive switch는 읽기 쉽고 새 공급사의 처리를 빠뜨리면 컴파일 오류를 만든다. 수가 계속 늘어날 때 `Map<Supplier, Endpoint>`와 시작 시 누락 검사로 바꿀 수 있으나 지금 필수는 아니다.
- Supplier당 WebClient bean 2개는 timeout 설정과 명시적 주입에 쓰인다. 공통 생성 함수는 이미 존재한다. 동적 bean 등록 프레임워크를 추가할 근거는 약하다.
- 새 공급사의 특수 변환을 공통 normalizer의 `if (supplier == ...)`로 밀어 넣지 않는다. 전용 adapter에서 공통 의미를 만든다.

## 5. 예외와 검증 경계

### 5.1 E-06: 카탈로그 client와 snapshot 계약 누락 — 이번 추가 발견

실제 `CatalogSynchronizationService`와 기록용 store를 사용해 세 가지를 재현했다.

| 조건 | 관측 결과 |
| --- | --- |
| A/B 활성화, B catalog client만 등록 | 시작 오류 없이 B만 저장 요청. A 누락을 감지하지 않음 |
| A catalog client 2개 등록 | `synchronizeAll()` 1회에 A snapshot 저장 요청 2회 |
| A client가 B snapshot 반환, B 비활성 | B snapshot이 store에 전달됨 |

제품의 A/B client는 현재 자기 Supplier를 고정 반환한다. 위 실험은 잘못된 새 adapter나 중복 bean 등록을 가정한 **계약 방어 검증**이며, 실제 DB에서 타 Supplier 데이터가 오염된 재현은 아니다. 다만 writer는 snapshot의 Supplier로 저장하므로 잘못된 결과를 받아들이면 타 Supplier 반영 위험이 있다. 중복 실행이 누락 확인 횟수에 미치는 영향도 DB 감사의 정책과 함께 봐야 한다.

8-A 최소 변경:

1. 활성 catalog client의 Supplier 중복·누락을 시작 시 검사한다. 스케줄을 비활성화한 환경에서도 수동 동기화를 지원할지 먼저 계약을 정한다.
2. snapshot 저장 전에 `snapshot.supplier() == client.supplier()`를 검사한다.
3. 실패는 해당 Supplier에 한정하고 다음 Supplier는 계속 처리한다.
4. 검색과 카탈로그 검사가 비슷하더라도 작은 중복 제거를 위해 복잡한 범용 registry를 만들지 않는다.

완료 증거는 누락·중복 시작 실패, 잘못된 snapshot 저장 0회, 정상 Supplier 저장 유지다.

### 5.2 E-01 / E-03: 예상 가능한 입력 거부와 내부 오류가 섞인다

이번 직접 주입 결과:

- A search client가 `IllegalStateException`을 던지면 통합 상태 PARTIAL, A 실패 UNKNOWN, B의 유효 Offer 1개가 보존된다.
- 정상 항목을 받는 mapper 내부에서 NPE를 던지면 `rejectedOfferCount=1`이 된다.
- 같은 mapper에서 `IllegalStateException`을 던지면 항목 거부로 처리되지 않고 호출 밖으로 전파된다. 실제 client 경로에서는 검색의 바깥 실패 처리로 이어질 수 있다.

서로 다른 오류가 같은 고객 실패 표현을 가질 수는 있다. 문제는 진단에서도 원인을 구분하기 어렵다는 것이다. UNKNOWN 전체를 곧바로 내부 버그로 재분류해서는 안 된다. 알려지지 않은 HTTP 상태나 아직 변환하지 못한 네트워크 예외도 들어올 수 있다.

정확성 C-01/C-02 수정과 함께:

- 원시 입력의 null·타입·날짜·범위 오류를 좁은 입력 경계에서 명시적으로 검사한다.
- 예상 가능한 항목 거부를 나타내는 예외/결과와 예상 밖 코드 오류를 구분한다. mapper 전체의 모든 RuntimeException을 외부 오류로 감싸면 같은 문제가 반복된다.
- 내부 오류는 원인·stack trace·별도 진단 분류를 ERROR/metric으로 남긴다. 고객에게 내부 메시지를 노출하지 않는다.
- 한 Supplier 내부 실패에서 정상 Supplier를 보존하는 정책은 유지할 수 있다. 공통 DB 조회·전체 결과 불변식 실패까지 무조건 PARTIAL로 바꾸지는 않는다.

새로운 공개 `INTERNAL_ERROR` enum은 가능한 선택이지 필수 해결책이 아니다. 고객 실패 계약과 내부 진단 분류를 분리하는 방법도 있다. 공개 enum을 바꾸면 JSON 계약과 테스트·문서가 함께 바뀐다.

### 5.3 E-02: 카탈로그 실패 격리는 유지하고 관측을 보완한다

`CatalogSynchronizationService`의 마지막 `catch (RuntimeException)`은 WARN과 stack trace를 남긴다. 정식 `logsUnexpectedFailureWithItsStackTrace` 테스트도 있다. 로그가 전혀 없다는 진단은 틀리다.

N-04와 함께 예상 밖 오류의 수준·실패 횟수·마지막 성공 시각을 보완한다. 한 Supplier 실패 후 다음 Supplier를 실행하는 catch 구조는 유지할 가치가 있다. catalog adapter의 `Mono` 바깥 동기 오류도 격리하되 원인은 보존해야 한다.

### 5.4 E-04: 기본 500 형식은 다르지만 메시지 유출은 확인되지 않았다

실제 Boot/Tomcat API에서 search service가 내부 예외를 던지게 했다. JSON 요청에 HTTP 500과 `timestamp/status/error/path`가 반환됐고 `code`는 없었다. 주입한 비공개 예외 메시지는 본문에 포함되지 않았다.

현재 advice는 검색 입력 오류를 `code/message` 형태로 처리한다. 예상 밖 오류까지 같은 형태로 통일하려면 controller 범위의 fallback과 안전한 고정 메시지를 추가할 수 있다. **현재 기본 500이 보안상 내부 메시지를 노출한다거나 서비스 최소 응답 계약을 위반했다고 단정하지 않는다.** 설정·Accept 값에 따른 모든 오류 표현을 검증한 것은 아니다.

### 5.5 E-05 / S-03 / S-04: 검증을 줄일 곳과 남길 곳

| 대상 | 판단 |
| --- | --- |
| API의 `SearchCriteria` 생성 검증 | 유지. 날짜·인원·30박 규칙을 도메인 한곳에 둔다. Bean Validation 미사용 자체가 문제는 아님 |
| `OfferCandidate`와 public `Offer.createAvailable`의 양수 검사 | 현재 주 경로에서 중복이지만 각 public 생성 경계의 불변식 보호에 의미가 있다. 검증 제거를 성능 작업으로 올리지 않음 |
| `createAvailable`의 긴 인자 목록 | 현재 **8개**다. Candidate를 받도록 바꾸는 것은 선택이며 단지 인자를 줄이기 위해 모델 결합을 바꾸지 않음 |
| 여러 클래스의 `requireText` | 5개 정의 확인. 몇 줄을 줄이기 위해 domain을 supplier utility에 의존시키지 않음. 현재 유지 가능 |
| B catalog/search의 `bodyFailure` | 업무 실패 코드 표가 두 번 복제됨. 향후 코드 추가 시 불일치 위험이 있어 작은 Supplier B 전용 매퍼로 모을 가치가 있음 |
| 금액·재고의 엄격한 정수 입력 | C-01의 실제 정확성 문제. 일반 도메인 null 검사보다 우선 |
| DB 길이와 외부 문자열 | 코드 100자·이름 255자 경계는 저장 실패 전에 검증할 후보. 정확한 경계와 snapshot 전체 거부 정책을 테스트 |
| 설정 URL | `@NotNull URI`만으로 http/https·host를 보장하지 않음. 실제 설정 사고 방지가 필요하면 시작 시 검증. 이번에는 잘못된 URI의 런타임 경로 미검증 |

`Price` 합산 중복은 N-05에 기록했다. 도메인 정합성 검사, 입력 검증, DB 제약은 실패 시점과 책임이 다르므로 동일한 값이라는 이유만으로 하나를 삭제하지 않는다.

## 6. 테스트 책임·결합·누락

### 6.1 현재 분류

| 종류 | 정식 메인 테스트 수 | 현재 증명하는 것과 한계 |
| --- | ---: | --- |
| domain 값·규칙 | 18 | 가격·재고·날짜·일부 snapshot 입력. 클래스 수와 branch coverage는 다름 |
| HTTP adapter | 13 | 실제 로컬 HTTP 요청·본문·대표 오류. Boot 주입 codec이나 socket 종료 전체를 증명하지 않음 |
| PostgreSQL repository/writer | 18 | DB 제약·매핑 반영. writer의 테스트 트랜잭션과 독립 commit 검증을 구분 |
| 통합 검색 서비스 | 11 | 배치·부분 실패·timeout. test client 기반이며 전체 네트워크 부하 검증은 아님 |
| 검색 API | 8 | standalone MockMvc + service mock의 HTTP 계약 |
| 설정·스케줄·동기화 서비스 | 11 | 검증·스케줄 시작·retry/실패 격리 |
| normalizer·Supplier 요청 | 9 | 항목 수용/거부와 요청 한도 |
| Boot context | 1 | context 시작과 bean 존재. 업무 E2E는 아님 |
| **합계** | **89** | 별도 Mock 모듈 6개를 합하면 기존 기준점 95개 |

이번에는 기존 95개 전체를 재실행하지 않고 관련 경계 6개를 추가 검증했다. 이전 C 실험 기록의 89개와 이번 실행 수를 합산하지 않는다. 임시 감사 테스트는 정식 회귀 테스트의 대체물이 아니다.

### 6.2 T-01: 오류 표는 실제 분기 위치에서 검사한다

전용 테스트 클래스가 없다는 이유만으로 테스트 부재를 단정하지 않는다. 현재 client 테스트에서 HTTP 401/500, B 본문 E503 등 대표 경로를 간접 검증한다. 부족한 것은 전체 표와 경계 원인의 확인이다.

| 입력 | 현재 구현의 분류 | retryable | 검증 위치 |
| --- | --- | --- | --- |
| HTTP 400 | INVALID_REQUEST | false | 공통 HTTP 매퍼 |
| HTTP 401 | AUTHENTICATION_FAILED | false | 공통 HTTP 매퍼 |
| HTTP 429 | RATE_LIMITED | false | 공통 HTTP 매퍼 |
| HTTP 500 / 503 | UNAVAILABLE | true | 공통 HTTP 매퍼 |
| 기타 4xx | UNKNOWN | false | 공통 HTTP 매퍼의 미정의 상태 경계 |
| B E400 / E401 / E429 | 각 INVALID_REQUEST / AUTHENTICATION_FAILED / RATE_LIMITED | false | **B catalog/search의 bodyFailure** |
| B E500 / E503 | UNAVAILABLE | true | 같은 B 경계 |
| B null / 미정의 코드 | INVALID_RESPONSE | false | 같은 B 경계 |
| 중첩 timeout 원인 | TIMEOUT | true | transport 매퍼의 cause chain |
| 연결 실패, timeout 아님 | UNAVAILABLE | true | transport 매퍼 |

B `0000`은 성공 envelope 검증으로 따로 검사한다. retryable=true는 검색이 실제 재시도한다는 뜻이 아니다. 검색은 현재 application retry를 하지 않고 카탈로그가 제한 retry 정책을 적용한다.

8-A에서는 데이터 표를 순회하는 parameterized test가 적절하다. B 본문 코드를 공통 HTTP 매퍼 테스트에 넣으면 실제 분기를 검사하지 못한다. 작은 B 매퍼로 모으거나 두 실제 client 경로에 같은 표를 적용한다.

### 6.3 T-02 / T-03: 위험에 따라 보강할 사례

| 우선 | 사례 | 넣을 테스트 경계 |
| --- | --- | --- |
| 8-A | 소수·overflow 금액/인원, null·잘못된 날짜와 정상 형제 항목 | 실제 주입 codec + adapter; domain 값 타입만 생성해서 대체하지 않음 |
| 8-A | 동일 Offer 중복·충돌 정책, 특수 코드 URI 보존 | adapter/집계의 계약 경계 |
| 8-A | catalog client 누락·중복·snapshot Supplier 불일치 | 동기화 application 단위 |
| 8-A | HTTP/B 본문 코드 표, timeout 원인 체인, 내부 오류와 항목 거부 | 해당 매퍼·normalizer·서비스 |
| 8-C | 독립 commit 후 ID 유지, 중간 저장 실패 rollback, 허용하는 동기화 경쟁 | 실제 PostgreSQL, 테스트 전체 자동 rollback과 분리 |
| 8-B | 여러 고객 전역 한도, 취소 후 허용량 반환, 한 Supplier 포화 격리 | 실제 HTTP + 서비스 공유 인스턴스 |
| 8-B | 정상 큰 응답·한도 초과·취소 후 회복 | 실제 WebClient codec/연결 |
| 8-D | DB 매핑 동기화→두 Mock→실제 검색 API의 정상/부분/전체 실패 | 실제 애플리케이션 설정을 연결한 E2E |
| 후속/관련 변경 시 | snapshot 중복 숙소/객실 코드, null 요소, 빈 이름, 길이 100/101·255/256 | snapshot/adapter/store의 책임에 맞춰 선택 |
| 후속/관련 변경 시 | 윤일·월/연도 경계, 인원 합산 int 상한, total/breakdown 불일치 | domain 규칙 |

`CatalogSnapshotTests`가 1개라는 사실만으로 얇다고 단정하지 않는다. 현재 그 테스트는 CSV 쉼표 거부만 다루며 중복 코드·리스트 불변성 등 다른 정책은 직접 다루지 않는다는 구체적 차이가 있다. `Money`나 `DailyInventory` 전용 테스트 파일이 없어도 Price/재고 테스트에서 일부 검증한다.

### 6.4 테스트 이름과 분할

- `rejectsMissingDuplicateAndOutOfRangeDates`는 세 독립 사례를 한 메서드에 넣는다. 각 사례 이름이 표시되는 parameterized test로 바꾸면 실패 원인을 찾기 쉽다.
- API의 missing/malformed 검증, 설정의 여러 잘못된 값도 같은 방식이 가능하다. 서로 같은 정책의 경계값은 표로 묶고, 성공·부분 실패·전체 실패는 독립 시나리오로 유지한다.
- 정확한 로그 문구 assert는 관측 계약이 필요한 범위로 줄인다. 상태·횟수·예외 원인을 중심으로 검사한다.
- 동시성 검증의 핵심은 호출 순서가 아니라 한도·격리·취소 후 회복이다. 가능한 곳은 gate/latch로 순서를 제어하고 짧은 sleep의 타이밍 운에 의존하지 않는다.
- 단순 record getter, 모든 null 필드의 반복 테스트, 구현과 같은 계산식을 복사한 테스트를 늘리지 않는다.

### 6.5 T-04: 컨테이너 공유는 후속 성능 선택이다

현재 PostgreSQL 컨테이너를 선언하는 클래스는 3개다. 이것만으로 병목이라고 할 수 없다. 격리된 DB는 테스트 간 오염을 줄이는 이점이 있다.

공유하려면 각 테스트의 데이터 정리·스키마 분리, 병렬 실행, Spring context가 살아 있는 동안 DB 수명 보장을 함께 설계해야 한다. `withReuse`는 테스트 실행 간 재사용이고, 한 테스트 실행 내 공유와 목적이 다르다. 공식 문서도 reusable containers를 실험 기능으로 설명하며 CI 용도로 적합하지 않다고 명시한다. [Testcontainers reusable containers](https://java.testcontainers.org/features/reuse/)

따라서 이번 8단계 필수 작업에서 제외한다. 실제 실행 시간 비중을 측정한 뒤 공유 전후의 독립성·시간을 비교할 때 다시 검토한다.

## 7. 서비스 요구사항과 현재 증거

7.5-A 요구사항 대조표와 현재 구현·테스트를 기준으로 정리했다.

| 중점 | 현재 근거 | 남은 증거 |
| --- | --- | --- |
| 같은 Supplier 상품의 내부 ID 안정성 | writer/repository 테스트 + 7.5-B 독립 commit 검증 | 지원할 동시 실행 모델 확정과 회귀 테스트 |
| 이질적인 가격·재고 정규화 | A/B adapter, Price/재고/normalizer 테스트 | C-01~C-04 해결과 실제 codec 회귀 |
| Supplier 확장 시 수정 범위 | 포트 조합, 기존 C 실험 기록, 이번 설정 생성자 재계수 | 새 Supplier 활성화 후 catalog/search 계약 검증 절차 |
| timeout·병렬·부분 실패·B 본문 실패 | 검색/client 테스트 + 7.5-C HTTP 취소 검증 | 전역 한도·크기 정책·완전 연결 E2E |
| 핵심 흐름을 끊김 없이 실행 | 각 계층별 근거 존재 | 실제 DB→Mock→고객 응답의 일관된 실행 증거 |
| 설명·실행 재현 | 설계 기록·감사 문서·JOURNAL | 9단계 README, 장애 재현 명령, 한계·trade-off 정리 |

현재 핵심 구현은 있지만 미수정 정확성 문제와 E2E 검증 공백이 남아 있다. SOLID 순수성을 높이는 리팩터링보다 이 항목을 먼저 완료한다.

## 8. 다음 구현 순서 — 네 감사 결과 통합

아래 8-A~D는 이번에 정리한 **8단계 내부 작업 묶음**이다. 기존 단계 계획을 이미 구현했거나 대체 완료했다는 뜻은 아니다.

| 묶음 | 최소 범위 | 완료 증거 |
| --- | --- | --- |
| **8-A 정확성·입력/예외 계약** | C-01~04, E-06, E-01/E-03, B 오류 표 및 필요한 fixture 정리 | 엄격 숫자·항목 격리·중복 정책·URI·client/snapshot 계약·오류 분류 회귀 |
| **8-B 자원 제한·관측성** | N-01/N-02/N-04, HTTP pool/대기 예산, E-02 | 동시 고객에서도 한도, 큰 정상 응답 수용/초과 분류, 취소 후 회복, 업무 실패 지표 |
| **8-C DB 정합성·시간 예산** | D-01 지원 범위 결정과 필요한 보호, D-02 timeout; 이때 필요한 writer 경계 정리 | 독립 commit/rollback/경쟁 테스트. 필요하면 갱신 batch 검증. 인덱스는 측정 근거가 있을 때만 |
| **8-D 전체 연결·마지막 검증** | 실제 Mock 정상/부분/전체 실패, timeout, 전체 정식 테스트 | 서로 연결된 실행 증거와 회귀 통과 |
| **9단계 문서화** | README, 실행/장애 명령, 설계 결정, 확장 절차, 미구현 한계 | 요구사항→구현→테스트→문서 최종 대조 |

8-A의 입력 파싱, 오류 분류, 오류 표를 함께 다루면 같은 adapter를 반복해서 다시 고치는 일을 줄인다. 8-B/8-C의 시간 예산도 한 정책으로 맞춘다. DB의 심각한 정합성 결함을 지원 환경에서 확인하면 자원 최적화보다 먼저 수정한다.

이번에 추가한 카탈로그 계약 방어 외에도 앞선 정확성·DB·네트워크 문제는 미수정 상태다. 감사 문서의 모든 P3 항목을 구현 조건으로 삼지 않는다.
