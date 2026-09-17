# 중간 기술 감사: 요구사항과 정확성

> 보관 기록: 감사·계획 당시의 판단과 수치를 보존한다. 현재 계약은 [설계](../architecture-decisions.md), 최종 대응은 [요구사항 검증](../requirements-verification.md)을 확인한다.
> 후속 상태(2026-09-16): C-01~C-04의 입력·예외 보완 수정과 정식 회귀 검증을 완료했다. 중복·수용 인원 충돌의 최종 정책과 근거는 [설계](../architecture-decisions.md)에 기록했다. 아래 관측·줄 번호·미수정 표현은 감사 기준 커밋 당시 기록이다.

## 1. 범위와 기준점

- 작성일: 2026-09-15
- 기준 커밋: `eff5bb83a940d8d29a778b9de0d041d888771388`
- 범위: 정확성. 통합 모델, Supplier 계약, 검색 결과, 매핑 생명주기, 실패 의미, 관련 테스트와 설계 문서 대조
- 산출물: 확인한 문제, 재현 조건, 수정 후보, 미검증 항목. 이번 감사에서는 제품 코드와 저장소의 테스트 소스를 변경하지 않았다.
- 후속 범위: SQL 수와 실행 계획, 동시 동기화, 전역 자원 제한, 메모리 프로파일, 전체 구조 감사는 각각 DB·네트워크·구조 감사에서 수행한다.

아래 내용은 현재 구현에 대한 자체 분석이다. 요구사항 충족 여부와 검증의 깊이를 구분한다.

## 2. 결론

핵심 정상 흐름의 구현과 테스트 근거는 있다. 그러나 입력 역직렬화와 응답 이상 데이터 처리에서 아래 네 가지 문제가 재현되므로 정확성 검증 완료로 판단할 수 없다.

| ID | 우선순위 | 확인한 문제 | 제안 시점 |
| --- | --- | --- | --- |
| C-01 | P1 | 소수 금액·재고를 정수로 변환하며 잘못된 값을 정상 수용한다. 음수 소수 금액도 0원 상품이 된다. | 입력·예외 보완 최우선 |
| C-02 | P2 | 정상 JSON 내 한 항목의 타입·날짜 오류가 정상 형제 Offer까지 제거한다. | C-01과 같은 입력 경계 변경에서 해결 |
| C-03 | P2 | 동일 Offer를 중복 수용하며 충돌하는 동일 상품의 처리 규칙이 없다. | 입력·예외 보완 |
| C-04 | P2 | 허용한 숙소 코드의 특수문자가 요청에서 바뀌거나 URI 생성에 실패한다. | 입력·예외 보완 |

P1은 잘못된 금액을 고객에게 제공하는 문제, P2는 특정 응답·식별자에서 결과가 중복되거나 정상 상품 조회가 손실되는 문제로 사용한다. 네트워크 규모나 DB 동시성에 대해서는 이번 결과만으로 결함 또는 안전성을 단정하지 않는다.

## 3. 실행한 검증

### 3.1 기존 테스트

Java 21로 다음 명령을 캐시된 테스트 결과에 의존하지 않고 실행했다.

```sh
./gradlew test --rerun-tasks --console=plain
```

| 대상 | 테스트 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: |
| 메인 애플리케이션 | 89 | 0 | 0 | 0 |
| Mock 모듈 | 6 | 0 | 0 | 0 |
| 합계 | 95 | 0 | 0 | 0 |

PostgreSQL 17 Testcontainers 기반 테스트도 실행됐다. 이 결과는 기존 테스트가 증명하는 범위에 대한 통과이며, 아래 새 엣지케이스까지 충족한다는 뜻은 아니다.

### 3.2 임시 재현 테스트

저장소 밖의 임시 Java 소스와 Gradle init script를 사용해 10개 재현 테스트를 실행했다. 기존 테스트 소스와 빌드 설정 파일은 수정하지 않았다. 임시 검증은 현재 잘못된 동작을 확인하는 characterization test이므로 테스트 통과를 요구사항 충족으로 해석하면 안 된다.

| 재현 | 실제 관측 결과 |
| --- | --- |
| A: 동일 정상 항목 2개 | Offer 2개 수용, 거부 0개 |
| B: 동일 정상 항목 2개 | Offer 2개 수용, 거부 0개 |
| A: 정상 항목 + 날짜가 `not-a-date`인 항목 | 호출 묶음 전체 `INVALID_RESPONSE` |
| B: 정상 항목 + 금액이 문자열 `oops`인 항목 | 호출 묶음 전체 `INVALID_RESPONSE` |
| B: 총액 `452000.75` | `452000`으로 수용, 거부 0개 |
| B: 총액 `-0.75` | `0`으로 수용, 거부 0개 |
| A: 소수 일별 금액·재고 | 소수 부분이 제거된 정수 금액·재고로 수용 |
| A: 숙소 코드 `A+1` | 서버의 일반 query decoding 결과 `A 1` |
| A: 숙소 코드 `A{1}` | URI 변수 확장 예외, HTTP 호출 0회 |
| 실제 Spring Boot 주입 B client: 총액 `-0.75` | 유효 재고가 있는 0원 Offer 수용 |

마지막 검증은 애플리케이션 컨텍스트와 실제 설정된 `SupplierBSearchClient`를 사용했다. 따라서 금액 변환 문제는 수동 생성한 테스트 client만의 설정 차이가 아니다. 별도 포트의 로컬 HTTP 서버를 사용했으며 외부 Supplier 서비스는 호출하지 않았다.

임시 재현 코드는 정식 회귀 테스트에 포함되어 있지 않다. 수정 단계에서는 위 입력을 기존 HTTP 어댑터 테스트에 옮겨, 기대 동작을 검증하는 테스트로 남겨야 한다.

## 4. 확인한 문제와 최소 수정 방향

### C-01. 정수 계약이 역직렬화 과정에서 손실된다

**위치**

- `src/main/java/com/supplierhub/supplier/suppliera/SupplierASearchClient.java:76`의 DTO 역직렬화
- `src/main/java/com/supplierhub/supplier/supplierb/SupplierBSearchClient.java:77`의 DTO 역직렬화와 `:266`의 `Long totalPrice`
- `src/main/java/com/supplierhub/search/domain/Money.java:10`의 음수 검증

**원인과 영향**

도메인 검증 전에 JSON 숫자가 `Long` 또는 `Integer`로 변환된다. 실제 설정에서는 소수 숫자의 정수 변환이 허용돼 원래의 잘못된 값이 사라진다. `Money`는 이미 변환된 값만 받으므로 `-0.75`가 `0`으로 바뀐 경우 음수 검증으로 막을 수 없다. 금액과 재고를 임의로 보정하면서 거부 건수도 증가하지 않는다.

**최소 수정 방향**

Supplier 입력 경계에서 정수 계약을 엄격히 검사한다. 소수 값의 묵시적 변환을 차단하고, 필드의 유효 범위와 `long` 합산 overflow 검증을 유지한다. 실제 사용 중인 JSON codec에 맞는 설정 또는 항목별 변환 방식을 선택하되 고객 API의 모든 JSON 처리를 일괄 변경할 필요는 없다. 카탈로그의 `maxOccupancy`도 같은 정수 계약이므로 적용 범위에 포함해 확인한다. 카탈로그 소수 입력 자체는 이번 임시 테스트에서 별도 재현하지 않았다.

**완료 조건**

- A 금액·세금·재고, B 총액·재고, 수용 인원에 대한 소수·범위 초과 값이 정상 상품으로 수용되지 않는다.
- `-0.75`가 0원 상품으로 바뀌지 않는다. 계약에 맞는 실제 정수 0의 허용 여부와 구분한다.
- 정상 형제 Offer는 유지하고 잘못된 항목을 `INVALID_RESPONSE`로 집계한다.
- DTO 숫자 범위 초과와, 여러 정상 정수 금액을 더할 때의 합산 overflow를 각각 검증한다.

### C-02. 항목 단위 오류 격리가 DTO 변환 이전에는 적용되지 않는다

**위치**

- A/B 검색 client의 `bodyToMono(...Response.class)`
- `src/main/java/com/supplierhub/search/application/OfferNormalizer.java:70` 이후 항목별 보호 구간
- `docs/architecture-decisions.md`의 잘못된 Supplier 데이터 처리 및 어댑터 경계 설명

**원인과 영향**

전체 `items`를 타입이 정해진 DTO 목록으로 먼저 읽는다. 날짜 파싱 실패나 숫자 필드의 문자열 오류는 `OfferNormalizer`에 도달하기 전에 전체 역직렬화를 실패시킨다. 정상 JSON 문서이고 오류 항목의 위치를 구분할 수 있어도 같은 묶음의 정상 Offer가 사라진다.

**최소 수정 방향**

응답 컨테이너와 항목 변환의 경계를 분리해 개별 항목의 DTO 변환 오류도 항목 단위로 격리한다. 문법 자체가 깨진 JSON, Supplier 수준 실패, 필수 컨테이너 부재는 계속 묶음 실패로 처리한다. 도메인에 JSON 타입을 전달하지 않는다.

단순히 C-01의 숫자 변환만 엄격하게 바꾸면 기존에 잘못 수용하던 소수 항목이 묶음 전체 실패로 바뀔 수 있다. 따라서 C-01과 C-02를 같은 입력 경계 설계에서 해결하는 편이 재작업을 줄인다.

**완료 조건**

A/B 각각 정상 항목과 잘못된 날짜·타입·범위 초과 숫자·null 항목을 섞어 정상 결과 보존을 확인한다. JSON 문법 오류 및 최상위 컨테이너 오류와 별도 테스트로 구분한다.

### C-03. 응답 Offer 중복·충돌 정책이 없다

**위치**

- `src/main/java/com/supplierhub/search/application/OfferNormalizer.java:79`: 수용된 Offer를 그대로 추가
- `src/main/java/com/supplierhub/search/application/IntegratedSearchService.java:259`: 정렬만 수행
- `src/main/java/com/supplierhub/search/api/StaySearchResponse.java:188`: 같은 객실 타입의 Offer를 모두 추가

**재현과 영향**

A/B가 같은 정상 항목을 두 번 반환하면 두 번 수용되고 거부 0개로 집계된다. 코드상 이후 검색 집계와 API 조립에도 제거 단계가 없어 고객 결과와 수용 건수가 중복된다. API 끝까지 이어진 중복 시나리오는 별도 E2E로 실행하지 않았다.

동일 객실 타입의 항목끼리 수용 인원이 다르면 API는 처음 사용한 `maxOccupancy`를 객실 타입 전체 값으로 선택한다. 이 충돌 결과는 코드 검토에 따른 위험이며 임시 테스트로 별도 재현하지 않았다.

**최소 수정 방향**

완전히 같은 중복과 값이 충돌하는 동일 상품을 구분한다. 완전 중복은 한 번만 반환하고 관측할 수 있도록 한다. 같은 판매 조건에서 금액·재고·수용 인원이 충돌하면 임의로 싼 값 또는 마지막 값을 선택하지 말고 해당 충돌 묶음을 거부하는 등의 명시적 정책을 정한다.

공급사·숙소·객실 타입뿐 아니라 조식·통화 등 판매 조건을 고려해야 한다. 다른 조건의 유효 Offer를 중복이라고 삭제해서는 안 된다. Supplier 간 동일 숙소 병합과는 별개 작업이다.

**완료 조건**

완전 중복, 같은 상품의 값 충돌, 다른 조식 조건, 다른 통화, 다른 숙소의 같은 객실 코드가 구분되고 `acceptedOfferCount`가 실제 반환 건수와 일치한다.

### C-04. 허용된 숙소 코드가 요청에서 보존되지 않는다

**위치**

- `src/main/java/com/supplierhub/supplier/suppliera/SupplierASearchClient.java:66`
- `src/main/java/com/supplierhub/supplier/supplierb/SupplierBSearchClient.java:67`
- `CatalogSnapshot.CatalogProperty` 및 `SupplierSearchRequest.PropertyMapping`의 코드 검증

**재현과 영향**

현재 수신·요청 경계는 빈 코드와 쉼표를 검사하지만 `+`, `{`, `}`를 허용한다. A 어댑터에 `A+1`을 전달하면 서버에서 `A 1`로 해석됐고, `A{1}`은 URI 템플릿 변수로 취급되어 HTTP 요청 전에 실패했다. 여러 숙소를 묶으면 한 코드 때문에 그 묶음 조회가 실패할 수 있다. B는 같은 요청 조립 형태이지만 특수문자 전송은 A에서 직접 재현했다.

**최소 수정 방향**

숙소 코드 목록을 URI 템플릿 문법이 아닌 값으로 전달하고, 서버에서 decoding했을 때 원래 값이 보존되는지 검증한다. 이미 encoding한 문자열을 다시 encoding하지 않도록 한다. 지원 코드 문자를 제한하기로 선택한다면 목록을 수용해 DB에 넣기 전에 제한 규칙을 명시해야 한다.

**완료 조건**

A/B의 `+`, 중괄호, `%`, `&`, `=`, 공백·비ASCII 코드의 서버 수신값을 확인한다. 쉼표 구분 계약과 50개 분할도 유지한다.

## 5. 요구사항과 구현·테스트·문서 대조

아래 파일명은 저장소 내 실제 타입·테스트 이름이다. '근거 있음'은 해당 범위의 코드와 테스트를 확인했다는 의미이며, 전체 운영 상황 검증 완료를 뜻하지 않는다.

| 항목 | 구현 근거 | 테스트 근거 | 문서 / 판단 |
| --- | --- | --- | --- |
| Java·Spring Boot·Gradle·관계형 DB | `build.gradle.kts`, migration, datasource 설정 | 빌드 및 PostgreSQL 테스트 성공 | 기본 스택 근거 있음 |
| Supplier 인증 헤더 | `SupplierClientConfiguration`의 `X-Api-Key` 기본 헤더 | `SupplierSearchClientTests.supplierARequestsExactContractAndNormalizesNightlyPrices`, `SupplierCatalogClientTests`의 A/B 헤더 확인 | 전송 계약 근거 있음. Mock 자체의 인증 검증을 의미하지 않음 |
| 날짜별 가격·세금 합산 | `SupplierASearchClient`, `Price`, `Money` | `PriceTests.sumsNightlyNetRatesAndTaxesAsGrossStayTotal`, `SupplierSearchClientTests.supplierARequestsExactContractAndNormalizesNightlyPrices` | 설계 정수 정상 입력 근거 있음; C-01 |
| 세금 포함 총액 유지 | `SupplierBSearchClient`, `Price.totalOnly` | `supplierBRequestsExactContractAndPreservesTaxIncludedTotal`, `supplierBExcludesItemWhoseTotalDoesNotIncludeTax` | 설계 정상·세금 규약 검증 있음 |
| 통화·조식 의미 보존 | `Money`, `Offer`, `StaySearchResponse` | B 계약 및 API 응답 테스트 | 설계/5. 환산·임의 통합 제외는 합리적 |
| 숙박일 경계·연박 재고 | `StayDateCoverage`, `StayInventory` | `StayInventoryTests`, `SearchCriteriaTests` | 설계 누락·중복·범위 초과·품절 검증 있음 |
| 내부 ID 안정성 | `CatalogSnapshotWriter`, `Property`, `RoomType` | `CatalogSnapshotWriterTests`의 갱신·누락·재등장 테스트 | 설계/9. 트랜잭션을 나눈 재시작 후 검증은 보강 필요 |
| 객실 코드의 숙소별 유일성 | DB unique, `SupplierItemKey` | `CatalogRepositoryTests.allowsSameRoomTypeCodeInDifferentProperties` 등 | 설계/4. DB 근거 있음; HTTP 두 숙소 동일 객실 코드 검증 추가 후보 |
| 목록 동기화·실패 보존 | catalog clients, scheduler, writer | catalog client/service/writer/scheduler 테스트 | 설계 DB 쓰기 중 실패의 rollback·다른 Supplier 성공 commit 검증은 미완 |
| Supplier별 DTO 격리 | 각 client의 private record, 공통 포트 | A/B 요청 계약 테스트 | 설계 근거 있음; 변환 전 오류 격리는 C-02 |
| 활성 매핑 전체 조회·50개 분할 | `JpaActiveCatalogMappingReader`, `IntegratedSearchService` | repository projection, `splitsFiftyOnePropertiesIntoFiftyAndOne` | 설계 근거 있음 |
| Supplier 병렬 실행 | 서비스의 `flatMap` | `startsDifferentSuppliersBeforeEitherOneCompletes` | 서비스 수준 동시 시작 검증 있음; 실제 HTTP 병렬 E2E는 미완 |
| 연결·응답·호출 timeout | `SupplierClientConfiguration`, 검색 client | A call timeout 테스트, 서비스 전체 시간 제한 테스트 | 설계 값·이유 있음; 각 timeout 독립 검증은 미완 |
| HTTP·본문 실패 통일 | HTTP mapper, B `bodyFailure` | A 상태 오류와 B `E503`, HTTP 429 테스트 | 설계 근거 있음; 전체 오류 코드 표 테스트는 보강 후보 |
| 정상 빈 결과·부분·전체 실패 | 서비스 상태 집계, controller | `treatsNormalEmptyResultAsSuccessBesideFailedSupplier`, `reportsFailedWhenEverySupplierCallFails`, API 상태 테스트 | 설계 대표 상태 근거 있음; 아래 정책 경계 확인 필요 |
| 고객 응답 최소 정보·원본 코드 비노출 | `StaySearchResponse` | `returnsGroupedCustomerSearchResponseWithoutSupplierCodes` | 설계 Supplier 출처 표시는 유지하고 원본 숙소·객실 코드를 숨김 |
| 정상·장애·무응답 Mock | 별도 모듈, 모드 전환, 기본 포트 18080 | Mock 6개 테스트, 별도 HTTP 서버의 A timeout 테스트 | 전체 실제 연동 E2E는 전체 연결 검증 |
| 신규 Supplier 추가 절차 | enum, 설정, client bean, 공통 포트 | 기존 A/B 계약 테스트 | 설계 기존 파일 변경은 남지만 검색 로직 분기 추가는 불필요 |
| 실행·설계 문서 | `docs/architecture-decisions.md`, `JOURNAL.md` | 문서와 주요 구현 대조 | 루트 `README.md`에 초기 실행 안내 제공. 최종 문서·실행 검증에서 전체 재현·최종 내용 확정 |
| 선택 기능과 규모 한계 | 제한 병렬성, 재시도 선택 | 관련 서비스 테스트 | 설계/15. 캐시·서킷 브레이커·상품 병합 구현을 추가 요구하지 않음 |

## 6. 오류로 단정하지 않는 정책·검증 경계

### 6.1 모든 항목이 잘못된 응답

`IntegratedSearchService.aggregate`는 예외 없이 반환된 `SupplierSearchResult`를 성공한 호출 묶음으로 센다. 따라서 모든 항목이 거부돼도 Supplier는 `PARTIAL`이며, 모든 Supplier가 이 상태라면 전체도 `PARTIAL`이다. 이는 현재 설계의 거부 건수 정책과 일치하므로 확정 버그로 분류하지 않는다.

다만 '일부 결과를 검증할 수 있음'과 '반환된 항목을 하나도 신뢰할 수 없음'을 같은 상태로 둘 것인지 명시해야 한다. 정상 빈 목록, 정상 품절만 있는 목록, 오류 항목만 있는 목록, 오류·품절 혼합, 전부 장애를 각각 테스트한다. 상태를 바꾼다면 API와 지표 의미를 함께 바꾼다.

### 6.2 모든 Supplier 비활성화

빈 `supplierResults`에 대한 `allMatch`로 `COMPLETE`가 반환되는 코드 경로가 있다. 점검 설정의 결과로 수용할지, 서비스 조회 불가로 표현할지 현재 문서와 테스트에서 명확하지 않다. 즉시 결함으로 단정하지 않고 정책과 전용 테스트를 보강한다.

### 6.3 정상 빈 카탈로그와 미확보 카탈로그

검색은 활성 객실 매핑이 없는 Supplier를 모두 `CATALOG_UNAVAILABLE`로 취급한다. 첫 동기화 실패와 정상적으로 상품이 0개인 Supplier를 구분할 상태 저장이 없다. 현재 설계에 명시된 절충이다. 전용 상태 테이블 도입은 후속 범위로 두되 정상 빈 검색 응답과 혼동하지 않도록 README에 설명한다.

### 6.4 정상적인 대량 삭제와 안전 가드

빈 목록과 큰 누락을 반복해서 거부하는 정책은 설계에 명시돼 있고 테스트도 있다. 이 동작 자체는 버그가 아니다. 다만 실제 전량 철수나 코드 일괄 변경을 자동 수렴시키는 경로는 없다. 별도 확인 절차가 미구현임을 문서에 남기고, 이번 단계에서 관리자 시스템을 추가하지 않는다.

### 6.5 DB 테스트가 아직 증명하지 않는 것

writer 테스트는 `@DataJpaTest` 트랜잭션 안에서 여러 동기화를 실행한다. ID 유지·누락 로직은 검증하지만 서로 다른 commit 이후의 재조회, DB 쓰기 도중 실패의 전체 rollback, A 저장 실패 이후 B 성공 commit을 그대로 증명하지는 않는다. `replacesEachSupplierCatalogIndependently`도 정상 저장끼리의 분리를 확인하며 한쪽 DB 실패를 주입하지 않는다.

다음 DB 감사에서 실제 트랜잭션 경계를 나눠 확인한다. 같은 Supplier의 동시 실행과 오래된 snapshot 역전 반영도 함께 다룬다.

### 6.6 불필요하게 추가하지 않을 검증·기능

- 과거 날짜와 성인 없는 검색을 막는 사업 규칙은 현재 계약에서 근거가 부족하다. 성인 0명·아동만 있는 입력은 의도적으로 허용하고 테스트한다.
- 30박 제한은 문서화된 초기 자원 상한이다. 일률적인 정책 오류로 분류하지 않는다.
- 이름만으로 Supplier 간 동일 숙소를 합치지 않는다.
- DB 제약, 외부 입력 검증, 도메인 불변식은 서로 다른 책임이다. 같은 값을 검사한다는 이유만으로 일괄 제거하지 않는다.
- 새 Supplier를 위해 기존 파일을 전혀 수정하지 않는 범용 프레임워크는 이번 정확성 문제의 해결책이 아니다.

## 7. 테스트 보강 우선순위

### 입력·예외 보완 수정과 함께 남길 테스트

1. C-01/C-02: 엄격한 정수 검사와 항목별 파싱 격리를 A/B 모두 검증한다. 실제 Boot codec 설정 검증을 적어도 하나 유지한다.
2. C-03: 완전 중복과 충돌, 유효한 서로 다른 판매 조건을 구분한다.
3. C-04: HTTP 서버가 실제로 받은 코드가 원래 코드와 일치하는지 확인한다.
4. 금액 합산 overflow, 인원 합산 overflow를 직접 검증한다. 기존 `Math.addExact` 및 `long` 인원 합산 코드는 있으나 해당 경계값의 직접 테스트는 확인되지 않았다.

### 다음 감사·견고성 보완에서 남길 테스트

- DB: 독립 트랜잭션 재동기화, 실패 rollback, Supplier 간 commit 격리, 동시 실행.
- HTTP: A/B 오류 코드 표, 빈 body·필수 컨테이너 부재, 정상 빈 결과, 응답 timeout과 call timeout 구분.
- 검색 상태: 전부 잘못된 항목, 품절·오류 혼합, 전부 비활성 Supplier.
- 실제 연동: DB 준비 → 목록 동기화 → 고객 API → 두 HTTP Supplier → 응답까지 정상·부분·전체 실패 시나리오.
- 네트워크 감사: timeout 이후 실제 HTTP 취소와 자원 반환. 현재 `Mono.never()` 테스트는 실행 수·결과·경과 시간을 확인하며 실제 socket 반환까지 검증하지 않는다.

## 8. 다음 작업과 종료 기준

1. DB 감사에서 DB 접근과 동시성 정합성을 감사한다.
2. 네트워크·구조 감사 결과와 합쳐 견고성 보완 수정 범위를 확정한다.
3. 입력·예외 보완에서는 C-01/C-02를 먼저 함께 해결하고 C-03/C-04를 각각 검증 가능한 변경으로 처리한다.
4. 최종 문서·실행 검증에 README와 설계·구현 대조를 완성한다.

이번 정확성 감사는 완료했다. 정확성 개선 구현은 아직 완료하지 않았다. 기존 테스트 통과와 새로 재현한 문제를 모두 기준점으로 유지한다.

## 9. DB 감사 후속 결과

2026-09-15의 [DB·동시성 감사](database-audit.md)에서 위 §6.5의 독립 commit 이후 ID 유지, 저장 중간 실패 rollback, A 저장 실패 후 B commit을 실제 PostgreSQL로 추가 확인했다. 이 세 항목의 당시 검증 공백은 보완됐다. 동일 Supplier의 중첩 실행에서는 누락 횟수 갱신 유실과 과거 snapshot에 의한 상태 복원이 재현됐다. C-01~C-04의 제품 코드 수정은 아직 수행하지 않았다.
