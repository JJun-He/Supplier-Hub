# 구현·요구사항 대조

2026-09-17에 외부 기준 문서 16쪽 전체를 읽고 현재 코드, 테스트, README와 대조했다. 아래 쪽수는 재검토 위치를 찾기 위한 참조다. 외부 원문·예시·기관명은 옮기지 않고, 이 프로젝트의 구현과 검증 근거만 요약한다. 원본 문서는 저장소에 포함하지 않는다.

이 문서는 코드·문서 대조 결과다. 같은 날 별도 디렉터리에서 자동 테스트 285개를 모두 새로 실행하고 고정 포트의 Compose·bootRun·검색 절차도 재현했다. 실행 조건·결과는 [전체 연결 검증](e2e-verification.md)에서 확인한다. 아래의 **구현 확인**은 해당 코드와 테스트 근거가 있다는 뜻이며, 모든 운영 환경의 동작이나 외부 제출 완료를 뜻하지 않는다.

## 1. 필수 구현

| 기준 위치 | 대조 대상 | 구현·설계 근거 | 테스트 근거 | 현재 상태와 한계 |
| --- | --- | --- | --- | --- |
| p.2 | 언어·프레임워크·빌드·관계형 DB | [Gradle 설정][build], [DB 설정][config], [Compose][compose] | [기동 테스트][boot-test], [카탈로그 DB 테스트][repository-test] | **구현 확인.** Java 21, Spring Boot 4.0.8, Gradle Kotlin DSL, PostgreSQL을 사용한다. 실행에는 Java 21과 Docker가 필요하다. |
| p.2, 4 | WebClient 연동과 실행 모델 선택 | [WebClient 구성][client-config], [A 검색 어댑터][a-search], [B 검색 어댑터][b-search], [아키텍처 결정][architecture] | [검색 계약 테스트][search-client-test], [어댑터 계약 테스트][adapter-test] | **구현 확인.** MVC·JPA 위에서 외부 HTTP를 WebClient로 병렬 실행한다. MVC 요청 스레드는 결과를 기다리므로 전체 요청이 비동기인 구조는 아니다. |
| p.3-4, 9 | 숙소·객실 타입·가격·재고의 표준 모델 | [숙소][property], [객실 타입][room-type], [Offer][offer], [가격][price], [연박 재고][inventory], [README][readme] | [가격 테스트][price-test], [재고 테스트][inventory-test], [정규화 테스트][normalizer-test] | **구현 확인.** 숙소와 객실 타입은 영속 매핑, 가격·재고는 검색 시점의 Offer다. 검색 응답은 세금 포함 총액을 기준으로 하며 일별 가격은 반환하지 않는다. 선택과 손실을 README에서 설명한다. |
| p.4, 9 | 외부 코드의 유일성 범위와 내부 ID 유지 | [매핑 스키마][schema], [카탈로그 저장][writer] | [키 중복·범위 테스트][repository-test], [실제 commit 경계 테스트][commit-test] | **구현 확인.** 숙소는 Supplier와 외부 코드, 객실 타입은 내부 숙소와 외부 코드로 유일성을 보장한다. 삭제 대신 비활성화하여 누락·재등장에도 ID를 유지한다. Supplier 간 동일 숙소 추정은 하지 않는다. |
| p.4, 6, 9 | 목록 수집·저장 후 실시간 검색 | [A 목록 어댑터][a-catalog], [B 목록 어댑터][b-catalog], [동기화 서비스][sync], [스케줄러][scheduler] | [목록 계약 테스트][catalog-client-test], [동기화 테스트][sync-test], [스케줄러 테스트][scheduler-test], [E2E][e2e-test] | **구현 확인.** 기동 직후와 이전 실행 완료 후 10분마다 목록을 갱신한다. 검색 중 목록 API를 다시 호출하지 않는다. 동기화 실패 시 기존 매핑을 유지하며 갱신은 단일 인스턴스만 지원한다. |
| p.4 | Supplier 형식 격리와 확장 경로 | [카탈로그 포트][catalog-port], [검색 포트][search-port], [A 검색 어댑터][a-search], [B 검색 어댑터][b-search], [아키텍처 결정][architecture] | [어댑터 계약 테스트][adapter-test], [통합 검색 테스트][search-test] | **구현 확인.** Supplier별 JSON과 오류 규약은 어댑터 안에서 처리한다. 새 Supplier에는 enum·설정·WebClient·목록/검색 구현과 테스트 추가가 필요하며, 공통 모델로 표현 가능하면 검색 집계 로직은 유지한다. |
| p.4-6, 9 | 보유 숙소 조회·Supplier별 묶음·50개 분할·병렬 호출 | [통합 검색 서비스][search], [검색 요청 계약][search-request], [활성 매핑 조회][mapping-reader], [아키텍처 결정][architecture] | [51개 분할·동시 실행·동시성 제한 테스트][search-test], [검색 요청 테스트][request-test] | **구현 확인.** 활성 Supplier의 활성 숙소·객실 매핑을 읽어 최대 50개씩 요청한다. 요청당 Supplier별 동시 호출은 기본 4개다. 수천 개 숙소에서는 전체 시간 예산 안에 끝난 묶음만 반환할 수 있으며 처리 용량은 측정하지 않았다. |
| p.5 | 고객 검색 URL·입력·최소 응답 정보 | [검색 컨트롤러][controller], [응답 모델][response], [검색 조건][criteria] | [고객 HTTP 계약 테스트][controller-test], [E2E][e2e-test] | **구현 확인.** 날짜·성인·아동 조건을 받아 내부 숙소/객실 ID와 이름, 수용 인원, 예약 가능 수, 공급사, 가격, 부분 실패를 반환한다. `children` 기본값은 0이고 최대 30박이며, 원본 Supplier 코드는 응답에서 제외한다. |
| p.5, 8-9 | 숙박일 경계·인원·연박 가용 수량·품절 처리 | [검색 조건][criteria], [날짜 범위 검사][date-coverage], [연박 재고][inventory], [Offer][offer], [README][readme] | [조건 테스트][criteria-test], [재고 테스트][inventory-test], [인원·품절 E2E][e2e-test] | **구현 확인.** 체크아웃 전날까지의 재고가 모두 있어야 하며 전체 최솟값을 사용한다. 품절 또는 성인+아동을 수용할 수 없는 Offer는 제외한다. 여러 객실을 합쳐 인원을 수용하는 계산은 하지 않는다. |
| p.5, 8, 10-14 | 공통 요청 형식·A/B 요금 의미 | [WebClient 구성][client-config], [A 검색 어댑터][a-search], [B 검색 어댑터][b-search], [금액][money], [가격][price] | [A/B 요청·가격 테스트][search-client-test], [가격 테스트][price-test], [엄격 입력 계약 테스트][adapter-test] | **구현 확인.** API 키 헤더·ISO 날짜·분리된 인원·숙소 코드 목록을 전송한다. A는 일별 세전액과 세금을 합산하고 B는 세금 포함 총액을 보존한다. 누락된 B의 일별 가격·세금액은 역산하지 않는다. |
| p.5, 11-14 | HTTP 실패와 본문 실패의 공통 분류 | [HTTP 오류 분류][http-failure], [B 본문 오류 분류][b-failure], [A 검색 어댑터][a-search], [B 검색 어댑터][b-search] | [HTTP 오류 분류 테스트][failure-test], [A/B 계약 테스트][adapter-test], [양쪽 장애 E2E][e2e-test] | **구현 확인.** B는 HTTP 성공이어도 본문 결과를 확인한다. 요청·인증·호출 제한·가용성 등 공통 실패 유형으로 변환하고 실패 상세를 고객 응답에 남긴다. |
| p.5 | 연결·응답 제한과 부분 실패 | [WebClient 구성][client-config], [통합 검색 서비스][search], [설정][config], [아키텍처 결정][architecture] | [무응답 테스트][search-client-test], [부분 결과·전체 시간 예산 테스트][search-test], [취소·회복 테스트][resource-test], [장애·복구 E2E][e2e-test] | **구현 확인.** 검색 초기값은 연결 500ms, 응답 2초, 호출 3초, 전체 검색 예산 5초다. 실패한 Supplier/묶음과 별개로 완료된 결과를 유지한다. 값은 초기 정책이며 응답 직렬화까지 포함한 엄격한 HTTP 완료 상한이 아니다. |
| p.3, 5, 14-15 | 별도 Mock과 정상·오류·무응답 재현 | [Mock 컨트롤러][mock-controller], [Mock 데이터 생성][mock-responses], [Mock 포트 설정][mock-config], [README][readme] | [Mock 테스트][mock-test], [별도 프로세스 E2E][e2e-test] | **구현 확인.** 메인 기본 8080과 Mock 18080을 분리한다. A HTTP 오류, B 본문 오류, 30초 지연으로 기본 검색 제한을 넘는 무응답 상황을 재현한다. Mock은 완전한 외부 서비스 구현이 아니며 키 검증·인원/50개 상한 검사는 하지 않는다. 해당 요청 경계는 메인 코드·계약 테스트에서 확인한다. |
| p.6-8 | 실행 안내·설계 근거·실제 흐름의 일치 | [README][readme], [아키텍처 결정][architecture], [전체 연결 검증][e2e-doc] | [E2E][e2e-test], [빌드의 check 연결][build] | **문서·코드 대조 확인.** 목록의 DB commit부터 실제 고객 HTTP까지 연결한 자동 테스트가 있다. 고정 포트 수동 재현의 완료 여부와 최신 실행 결과는 전체 연결 검증 문서에서 관리한다. |

## 2. 선택 구현과 권장 항목

선택 항목은 도입 여부와 실제 범위를 구분한다. 보류 사유나 검토 조건이 있다는 것만으로 구현 완료로 표시하지 않는다.

| 기준 위치 | 항목 | 구현·설계 근거 | 테스트·기록 근거 | 현재 상태와 한계 |
| --- | --- | --- | --- | --- |
| p.6 | 재시도 | [동기화 서비스][sync], [WebClient 구성][client-config], [아키텍처 결정][architecture] | [동기화 재시도 테스트][sync-test], [검색 미재시도 테스트][search-client-test] | **일부 구현.** 카탈로그의 일시적 실패만 기본 최대 2회, 300ms 기반 backoff로 재시도한다. 검색 추가 재시도와 전송 계층 자동 재시도는 끈다. 호출 제한·인증·잘못된 응답은 즉시 반복하지 않는다. |
| p.3, 6 | 서킷 브레이커·회복탄력성 라이브러리 | [아키텍처 결정][architecture], [README][readme] | 코드·의존성 대조: [Gradle][build] | **보류.** 현재는 유한한 호출 자원·시간 예산·부분 응답을 사용한다. 차단/복구 상태 머신과 Resilience4j는 구현하지 않았다. |
| p.6 | 재고·요금 캐시 | [아키텍처 결정][architecture] | 코드 대조: [통합 검색 서비스][search] | **보류.** 매 검색마다 실시간 조회한다. TTL·동시 갱신 억제·허용 오차를 포함한 캐시 전략을 구현하거나 검증한 상태는 아니다. |
| p.4, 6 | 정규화 실패 격리·검색 이력·원본 보관 | [정규화기][normalizer], [업무 지표][metrics], [아키텍처 결정][architecture] | [정규화 테스트][normalizer-test], [항목별 오류 계약 테스트][adapter-test] | **일부 구현.** 잘못된 항목은 정상 형제 항목과 격리하고 사유 로그·건수를 남긴다. 원문 격리 저장소, 검색 이력 저장, 재처리 경로는 없다. 따라서 원문을 보존하는 실패 데이터 격리까지 완료한 것은 아니다. |
| p.4, 6, 16 | Supplier 간 동일 숙소 병합·판매 조건 차이 | [매핑 스키마][schema], [정규화기][normalizer], [응답 모델][response], [README][readme] | [정확히 같은 Offer만 중복 제거하는 테스트][adapter-test], [공급사별 조건 E2E][e2e-test] | **판단 완료, 교차 Supplier 병합 미구현.** 공급사별 내부 ID와 조식·가격 조건을 각각 보존한다. 같은 Supplier의 완전히 같은 Offer 제거는 서로 다른 공급사의 동일 숙소 판별과 다르다. |
| p.6, 8 | 통화 | [금액][money], [응답 모델][response], [아키텍처 결정][architecture] | [가격·통화 테스트][price-test], [숫자 타입·범위 계약 테스트][adapter-test] | **원본 보존 구현.** ISO 통화 코드와 최소 단위 정수를 유지한다. 환산·환율 시점·비교 통화 선택은 구현하지 않았다. |
| p.6-7 | 예약 생성·취소·보상과 전체 도메인 경계 | [아키텍처 결정][architecture], [README][readme] | 구현 범위 대조 | **보류.** 현재는 검색 흐름까지다. 예약 API와 멱등성·보상 처리의 상세 실행 설계 또는 테스트는 없다. |
| p.2-3 | 테스트·연동 지표 | [테스트 소스][test-root], [업무 지표][metrics], [설정][config], [전체 연결 검증][e2e-doc] | [자원·지표 통합 테스트][resource-test], [E2E][e2e-test] | **구현.** 단위·실제 PostgreSQL·HTTP·별도 프로세스 테스트가 있다. Supplier 호출/실패/지연과 검색 상태·거부/중복·카탈로그 신선도를 기록한다. 대시보드·장기 지표 저장·운영 부하 검증은 없다. |
| p.2, 6-7 | API 문서 자동화·설계/과정 기록 | [README][readme], [아키텍처 결정][architecture], [JOURNAL][journal], [AI 활용 기록][ai] | 문서·코드 대조 | **문서 작성, 자동화 보류.** 요청·응답·오류·설계 근거는 저장소 문서에 있다. SpringDoc/Swagger는 도입하지 않았다. |

인증·인가, 결제, 관리자 화면, 프론트엔드, 실제 상용 Supplier 연동, 지역/키워드 필터, 고객 지정 정렬·페이징은 현재 범위 밖이다(p.6). 내부 결과의 일정한 출력 순서는 고객 정렬 기능을 제공한다는 뜻이 아니다.

## 3. 제출·공개 조건

이 절은 파일로 확인 가능한 사항과 저장소 밖에서 해야 하는 행위를 구분한다. 실제 제출 기한, 원격 저장소 공개 상태, 외부 전달 여부는 이 코드 검토로 확정하지 않는다.

| 기준 위치 | 확인 대상 | 저장소 근거 | 현재 확인 범위 |
| --- | --- | --- | --- |
| p.1, 7 | 실행·설계·과정 산출물의 저장소 포함 | [README][readme], [아키텍처 결정][architecture], [JOURNAL][journal], [AI 활용 기록][ai] | 문서 파일이 존재하고 서로 연결되어 있다. 로컬 변경은 실제 작업 단위로 커밋하며, 원격 반영·공개 상태는 별도 외부 절차다. |
| p.1 | 의미 있는 개발 이력 | [JOURNAL][journal] | 설계 → 기반·Mock → 매핑·정규화 → 검색·API → 감사·보완 → E2E의 로컬 이력을 확인했다. 기존 이력은 재작성하지 않고 문서 정리는 후속 커밋으로 남긴다. |
| p.1-2 | 외부 원문·금지 식별 정보·비밀 값의 공개 제외 | [작업 지침][agents], [ignore 설정][ignore] | 2026-09-17 추적 대상과 도달 가능한 로컬 이력에서 공개 제외 문구·개인 홈 경로·알려진 비밀 형식을 검사했다. 원문·추출물·실제 키를 추가하지 않았으며 `.env`·PDF·생성물의 추적 이력이 없음을 확인했다. |
| p.7-8 | AI 질문·제안의 수용·수정·보류 근거 | [AI 활용 기록][ai], [JOURNAL][journal] | 실제 제안·리뷰 후 변경 사례와 측정하지 않은 토큰/비용 효과를 구분해 기록한다. 작성자가 임의의 코드를 설명할 수 있는지는 문서 검사만으로 검증할 수 없다. |
| p.1 | 최종 공개 저장소와 외부 전달·기한 | 저장소 외부 절차 | **미확인.** 코드·문서 검증과 별개이며 이 검토에서는 공개 설정 변경이나 외부 발송을 수행하지 않았다. |

## 4. 저장소와 검증 결과의 경계

현재 설계·개발 기록은 주제별로 정리하고 완료된 계획·감사는 `docs/archive`로 옮겼다. 감사 JSON 4개는 이동 전후 바이트가 동일하다. Markdown의 로컬 파일·앵커와 기록에 연결한 커밋을 확인했다. 제품 코드·테스트·빌드 설정은 이번 정리에서 바꾸지 않았다. 비밀 형식·문구 검사는 정해진 패턴과 로컬 파일 대조 범위이며, 원격 저장소의 설정·도달 불가능한 객체까지 검사한 것으로 표현하지 않는다.

이 대조에서 필수 기능의 새로운 구현 결함은 발견하지 않았다. 자동 검증의 건수·실행일·기존 결과 재사용 여부는 [전체 연결 검증][e2e-doc]과 [JOURNAL][journal]에 기록한다. 테스트 소스 링크가 있다는 사실과 해당 검증을 이번에 다시 실행했다는 주장을 구분한다.

현재 근거가 보장하지 않는 범위는 다음과 같다.

- 모든 숙소 수·동시 고객 수에서의 처리 용량, 장시간 부하·메모리 안정성, HTTP 요청 전체의 엄격한 완료 상한
- 여러 인스턴스의 동시 카탈로그 갱신, 장시간 migration, 운영 네트워크 단절의 모든 형태
- 실제 외부 서비스와의 인증·호출 제한 계약 및 운영 장애 대응
- 선택 기능의 상세 설계·구현 완료와 외부 제출 완료

[build]: ../build.gradle.kts
[config]: ../src/main/resources/application.yaml
[compose]: ../compose.yaml
[readme]: ../README.md
[architecture]: architecture-decisions.md
[journal]: ../JOURNAL.md
[ai]: ../AI_USAGE.md
[agents]: ../AGENTS.md
[ignore]: ../.gitignore
[e2e-doc]: e2e-verification.md
[property]: ../src/main/java/com/supplierhub/catalog/domain/Property.java
[room-type]: ../src/main/java/com/supplierhub/catalog/domain/RoomType.java
[schema]: ../src/main/resources/db/migration/V1__create_catalog_mappings.sql
[writer]: ../src/main/java/com/supplierhub/catalog/application/CatalogSnapshotWriter.java
[sync]: ../src/main/java/com/supplierhub/catalog/application/CatalogSynchronizationService.java
[scheduler]: ../src/main/java/com/supplierhub/catalog/application/CatalogSynchronizationScheduler.java
[catalog-port]: ../src/main/java/com/supplierhub/supplier/common/SupplierCatalogClient.java
[search-port]: ../src/main/java/com/supplierhub/supplier/common/SupplierSearchClient.java
[client-config]: ../src/main/java/com/supplierhub/supplier/common/SupplierClientConfiguration.java
[a-catalog]: ../src/main/java/com/supplierhub/supplier/suppliera/SupplierACatalogClient.java
[b-catalog]: ../src/main/java/com/supplierhub/supplier/supplierb/SupplierBCatalogClient.java
[a-search]: ../src/main/java/com/supplierhub/supplier/suppliera/SupplierASearchClient.java
[b-search]: ../src/main/java/com/supplierhub/supplier/supplierb/SupplierBSearchClient.java
[http-failure]: ../src/main/java/com/supplierhub/supplier/common/SupplierHttpFailureMapper.java
[b-failure]: ../src/main/java/com/supplierhub/supplier/supplierb/SupplierBFailureMapper.java
[metrics]: ../src/main/java/com/supplierhub/supplier/common/SupplierMetrics.java
[search]: ../src/main/java/com/supplierhub/search/application/IntegratedSearchService.java
[search-request]: ../src/main/java/com/supplierhub/supplier/common/SupplierSearchRequest.java
[mapping-reader]: ../src/main/java/com/supplierhub/catalog/infrastructure/JpaActiveCatalogMappingReader.java
[normalizer]: ../src/main/java/com/supplierhub/search/application/OfferNormalizer.java
[offer]: ../src/main/java/com/supplierhub/search/domain/Offer.java
[money]: ../src/main/java/com/supplierhub/search/domain/Money.java
[price]: ../src/main/java/com/supplierhub/search/domain/Price.java
[inventory]: ../src/main/java/com/supplierhub/search/domain/StayInventory.java
[criteria]: ../src/main/java/com/supplierhub/search/domain/SearchCriteria.java
[date-coverage]: ../src/main/java/com/supplierhub/search/domain/StayDateCoverage.java
[controller]: ../src/main/java/com/supplierhub/search/api/StaySearchController.java
[response]: ../src/main/java/com/supplierhub/search/api/StaySearchResponse.java
[mock-controller]: ../mock-supplier/src/main/java/com/supplierhub/mock/MockSupplierController.java
[mock-responses]: ../mock-supplier/src/main/java/com/supplierhub/mock/MockSupplierResponses.java
[mock-config]: ../mock-supplier/src/main/resources/application.yaml
[test-root]: ../src/test/java/com/supplierhub
[boot-test]: ../src/test/java/com/supplierhub/SupplierHubApplicationTests.java
[repository-test]: ../src/test/java/com/supplierhub/catalog/infrastructure/CatalogRepositoryTests.java
[commit-test]: ../src/test/java/com/supplierhub/catalog/infrastructure/CatalogCommitBoundaryTests.java
[sync-test]: ../src/test/java/com/supplierhub/catalog/application/CatalogSynchronizationServiceTests.java
[scheduler-test]: ../src/test/java/com/supplierhub/catalog/application/CatalogSynchronizationSchedulerTests.java
[catalog-client-test]: ../src/test/java/com/supplierhub/supplier/common/SupplierCatalogClientTests.java
[search-client-test]: ../src/test/java/com/supplierhub/supplier/common/SupplierSearchClientTests.java
[adapter-test]: ../src/test/java/com/supplierhub/supplier/common/SupplierAdapterContractTests.java
[failure-test]: ../src/test/java/com/supplierhub/supplier/common/SupplierFailureMapperTests.java
[resource-test]: ../src/test/java/com/supplierhub/supplier/common/SupplierResourceIntegrationTests.java
[request-test]: ../src/test/java/com/supplierhub/supplier/common/SupplierSearchRequestTests.java
[search-test]: ../src/test/java/com/supplierhub/search/application/IntegratedSearchServiceTests.java
[normalizer-test]: ../src/test/java/com/supplierhub/search/application/OfferNormalizerTests.java
[price-test]: ../src/test/java/com/supplierhub/search/domain/PriceTests.java
[inventory-test]: ../src/test/java/com/supplierhub/search/domain/StayInventoryTests.java
[criteria-test]: ../src/test/java/com/supplierhub/search/domain/SearchCriteriaTests.java
[controller-test]: ../src/test/java/com/supplierhub/search/api/StaySearchControllerTests.java
[mock-test]: ../mock-supplier/src/test/java/com/supplierhub/mock/MockSupplierControllerTests.java
[e2e-test]: ../src/e2eTest/java/com/supplierhub/e2e/SupplierHubEndToEndTests.java
