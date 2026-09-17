# 작업 지침

이 지침은 구현과 전체 연결 검증 이후 문서 정리부터 적용한다.
현재 계약은 `docs/architecture-decisions.md`, 실행 절차는 `README.md`를 기준으로 확인한다.

## 코드 진입점

| 작업 | 진입점 |
| --- | --- |
| 검색 HTTP 계약 | `search/api/StaySearchController`, `StaySearchResponse` |
| 검색 분할·실패 격리 | `search/application/IntegratedSearchService` |
| 가격·재고·Offer 정규화 | `search/domain`, `search/application/OfferNormalizer` |
| Supplier 프로토콜 | `supplier/suppliera`, `supplier/supplierb` |
| 호출 자원·지표 | `supplier/common/SupplierCallResources`, `SupplierMetrics` |
| 카탈로그 동기화·저장 | `catalog/application/CatalogSynchronizationService`, `CatalogSnapshotWriter` |
| DB 조회·시간 예산 | `catalog/infrastructure/JpaActiveCatalogMappingReader` |

위 경로는 `src/main/java/com/supplierhub` 기준이다. Mock은 `mock-supplier`에 있다.

## 유지할 계약

- Supplier별 외부 코드를 내부 숙소·객실 ID로 매핑하고 비활성화·재등장 때 ID를 보존한다.
- 가격은 통화의 최소 단위 정수인 세금 포함 숙박 전체 총액이다. 없는 일별 가격은 역산하지 않는다.
- 재고는 숙박일 전체의 최솟값이며 품절·인원 초과 Offer는 반환하지 않는다.
- 한 Supplier나 배치가 실패해도 성공한 결과를 보존한다. 내부 오류를 외부 입력 오류로 숨기지 않는다.
- 요청별 병렬 제한, JVM 전체 허용량, 연결 풀, DB 예산은 서로 다른 경계다.
- 카탈로그 동기화는 한 인스턴스만 지원한다. 현재 수동 HTTP·CLI 트리거는 없다.
- 설정된 시간 예산을 엄격한 HTTP 완료 상한이나 실측 처리 용량으로 설명하지 않는다.

## 변경과 검증

- 가격·재고 변경은 도메인·정규화 테스트, 프로토콜 변경은 어댑터 계약 테스트를 확인한다.
- DB 변경은 실제 PostgreSQL 통합 테스트, 고객 응답·실행 경계 변경은 `e2eTest`를 확인한다.
- `./gradlew test`는 메인·Mock 테스트, `./gradlew check`는 E2E까지 실행한다. Java 21과 Docker가 필요하다.
- 문서만 바뀌면 코드 대조·로컬 링크·`git diff --check`를 확인한다. 실행 안내 변경은 해당 절차를 재현한다.
- 테스트는 변경 범위와 위험에 맞게 실행하고, 실제 재실행과 이전 성공 결과 재사용을 구분해 기록한다.
- 기능 결함을 발견하면 문서 정리와 분리해 수정·검증한다. 명확한 이점 없이 구조나 공통화를 늘리지 않는다.

## 문서와 이력

- API·정규화·설정·실패 정책 변경은 현재 설계와 관련 실행·검증 문서에도 반영한다.
- 현재 설계는 주제별로 한 번 설명한다. 과거 계획·감사 기록은 `docs/archive`에서 시점과 함께 보존한다.
- `JOURNAL.md`에는 의미 있는 판단과 검증을, `AI_USAGE.md`에는 AI 제안의 수용·수정·보류 근거를 남긴다.
- 단순 작업 실수와 반복 보고는 축약하고, 측정하지 않은 성능·GC·토큰·비용 효과는 주장하지 않는다.
- 커밋은 실제 변경 단위로 묶는다. 기존 이력을 재작성하거나 과거부터 지침을 사용한 것처럼 기록하지 않는다.
- 로컬 비밀 값·빌드 생성물·로그·외부 과제 원문은 추적하지 않는다.
