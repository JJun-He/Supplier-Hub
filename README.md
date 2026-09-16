# Supplier Hub

서로 다른 Supplier의 숙소·객실 목록을 내부 ID로 관리하고, 검색 시 재고와 요금을 조회해 공통 Offer로 반환하는 Spring Boot 서비스입니다.

## 현재 상태

목록 동기화부터 검색 API까지 구현하고 8-A~8-D 검증을 마쳤습니다. 실제 PostgreSQL·메인·Mock 프로세스를 연결해 정상 검색, 부분·전체 실패, timeout과 DB 잠금 후 복구를 확인했습니다. 고정 포트의 수동 실행 절차 재검증과 최종 문서 대조는 9단계에 남아 있습니다.

- Java 21, Spring Boot 4.0.8, Gradle Wrapper
- PostgreSQL 17, Flyway, Spring Data JPA
- Spring MVC, Supplier HTTP 호출은 WebClient
- 메인 API: `8080`, 로컬 Mock: `18080`, 기본 DB: `5432`

## 로컬 실행

저장소 루트에서 실행합니다. Java 21과 Docker Compose가 필요합니다. 테스트의 PostgreSQL 컨테이너에도 실행 중인 Docker가 필요합니다.

### 1. 설정과 DB

```sh
cp -n .env.example .env
```

기존 `.env`가 있으면 유지됩니다. `.env`의 `DB_PASSWORD`에 로컬 비밀번호를 설정한 뒤 DB를 시작합니다. `.env`는 Git 추적 대상에서 제외됩니다. `DB_PORT`를 변경하면 `DB_URL`의 포트도 맞춥니다.

```sh
docker compose up -d --wait postgres
```

Compose는 `.env`를 읽고, 메인 애플리케이션도 저장소 루트의 `.env`를 Spring 설정으로 읽습니다. Flyway가 시작 시 스키마를 생성·검증합니다.

### 2. Mock 시작 — 터미널 A

```sh
./gradlew :mock-supplier:bootRun
```

Mock이 `18080`에서 시작한 뒤 다음으로 진행합니다. `.env.example`의 Supplier URL과 API 키는 로컬 Mock용 기본값입니다. 서비스는 `X-Api-Key`를 전송하지만 Mock은 키의 유효성을 검사하지 않습니다.

### 3. 메인 서비스 시작 — 터미널 B

```sh
./gradlew :bootRun
```

기동 직후 비동기 목록 동기화가 실행됩니다. 로그에서 A/B 각각 `Supplier catalog synchronization succeeded`를 확인한 뒤 검색합니다. `/actuator/health`의 성공만으로 목록 준비까지 완료됐다고 판단하지 않습니다.

Mock보다 메인을 먼저 시작해 초기 동기화가 실패했다면, Mock 준비 후 메인을 재시작하거나 다음 동기화까지 기다립니다. 기본 주기는 이전 실행 완료 후 10분입니다.

### 4. 검색

```sh
curl -i --get 'http://localhost:8080/api/v1/stays/search' \
  --data-urlencode 'checkIn=2026-10-01' \
  --data-urlencode 'checkOut=2026-10-04' \
  --data-urlencode 'adults=2' \
  --data-urlencode 'children=0'
```

응답은 `stays[].roomTypes[].offers[]`로 묶입니다. `totalAmountIncludingTax`는 통화의 최소 단위 정수로 표현한 세금 포함 숙박 전체 총액입니다. 숙소·객실의 Supplier 원본 코드는 반환하지 않습니다.

| 상황 | HTTP | 검색 상태 |
| --- | --- | --- |
| 모든 Supplier 정상, 정상 빈 결과 포함 | 200 | COMPLETE |
| 일부 Supplier 또는 배치 실패 | 200 | PARTIAL |
| 모든 Supplier 조회 실패 | 503 | FAILED |
| 잘못된 날짜·인원 등 요청 오류 | 400 | 입력 오류 본문 |

현재는 1~30박과 전체 인원 1명 이상을 허용합니다. `children`은 생략하면 0입니다. 모든 항목 거부·카탈로그 미준비 등 세부 정책은 [정확성 감사](docs/correctness-audit.md)에 정리돼 있습니다.

8-A에서 Supplier의 잘못된 숫자·날짜 항목을 개별 거부하고, 정규화된 완전 중복 Offer를 제거하도록 보완했습니다. 같은 객실의 반환 가능한 Offer 사이에서 최대 수용 인원이 충돌하면 해당 객실의 Offer를 거부합니다. 내부 처리 오류는 `supplierResults[].failureTypes[]`의 `INTERNAL_ERROR`로 구분하며 정상 Supplier 결과는 유지합니다. 상세 입력·중복·실패 계약은 [설계 §16](docs/architecture-decisions.md#16-8-a-입력중복예외-계약-보완)에 있습니다.

## Mock 장애 재현

아래 제어 API는 Mock 검색 응답의 상태를 변경합니다. 메인 API의 설정을 바꾸지는 않습니다. 각 단계 후 위 검색 명령을 다시 실행합니다.

```sh
# A 오류, B 정상: PARTIAL 예상
curl -X POST 'http://localhost:18080/control/a/mode?value=error'
curl -X POST 'http://localhost:18080/control/b/mode?value=normal'

# B도 오류: FAILED / HTTP 503 예상
curl -X POST 'http://localhost:18080/control/b/mode?value=error'

# A 지연, B 정상: A timeout을 포함한 PARTIAL 예상
curl -X POST 'http://localhost:18080/control/a/mode?value=no-response'
curl -X POST 'http://localhost:18080/control/b/mode?value=normal'

# 정상 복구
curl -X POST 'http://localhost:18080/control/a/mode?value=normal'
curl -X POST 'http://localhost:18080/control/b/mode?value=normal'
```

`no-response`는 Mock이 응답을 30초 지연하는 방식입니다. A의 `error`는 HTTP 오류, B의 `error`는 HTTP 200 안의 본문 오류입니다. 모드는 Mock 프로세스 메모리에 유지되고 재시작하면 초기화됩니다. 위 제어 API는 카탈로그 응답에는 적용되지 않습니다.

위 장애·지연 모드의 고객 응답은 실제 프로세스를 연결한 [8-D E2E](docs/e2e-verification.md)에서 검증했습니다. E2E는 임시 DB와 자동 배정 포트를 사용하며, 위 고정 포트 수동 명령의 최종 재현 점검은 9단계에서 진행합니다.

## 빌드·테스트·종료

```sh
# 두 애플리케이션 실행 JAR 생성
./gradlew :bootJar :mock-supplier:bootJar

# 메인과 Mock의 단위·통합 테스트
./gradlew test

# 실제 DB·메인·Mock을 연결한 E2E
./gradlew e2eTest

# 위 테스트를 모두 포함한 검증
./gradlew check
```

2026-09-16의 8-D 검증 결과는 **메인 270개·Mock 6개·E2E 9개, 실패·오류·건너뜀 0개**입니다. 이번 작업에서 메인과 E2E는 실제 실행했고 변경 없는 Mock은 기존 성공 결과를 재사용했습니다. parameterized test의 각 입력 사례를 포함한 건수입니다. E2E 실행 구성·검증 범위와 로그 위치는 [전체 연결 검증](docs/e2e-verification.md), 변경 기록은 [JOURNAL](JOURNAL.md)에 있습니다.

실행 중인 두 `bootRun`은 각각 Ctrl+C로 종료합니다. DB 종료 명령은 다음과 같으며 데이터 볼륨은 유지됩니다.

```sh
docker compose down
```

## 모델과 설계 선택

| 선택 | 이유 |
| --- | --- |
| Supplier별 숙소를 별도 내부 ID로 관리 | 동일 실물 숙소임을 보장하는 공통 키가 없어 이름으로 병합하지 않음 |
| 세금 포함 총액과 통화를 공통 가격으로 사용 | 일별 가격과 전체 숙박 가격을 같은 의미로 비교·표현 |
| 제공받은 일별 가격은 내부 보존, 검색 응답에서는 제외 | 응답 크기를 줄이며, 없는 일별 가격은 역산하지 않음 |
| Supplier 전용 JSON·원본 코드는 공통 고객 응답에서 제외 | 고객 모델을 특정 프로토콜과 분리. 원본 응답 전체 보관도 현재 제외 |
| 연박 재고는 숙박일별 최솟값, 품절·인원 초과 Offer는 제외 | 실제 예약 가능한 조건만 검색에 노출 |
| 기동 직후 + 주기적 카탈로그 동기화 | 검색에 필요한 코드 목록을 확보하고 요청마다 전체 목록을 다시 받지 않음 |
| 누락 즉시 삭제 대신 확인 후 비활성화 | 일시적인 누락을 방어하고 재등장 시 내부 ID 보존 |

상세 근거는 [아키텍처 결정](docs/architecture-decisions.md)에 있습니다. 예약 실행, 통화 환산, Supplier 간 숙소 병합은 현재 범위에 포함하지 않습니다.

## 다음 작업과 기술 선택

8-A 입력/예외 계약, 8-B 자원 제한·업무 지표, 8-C 동기화 보호·DB 시간 예산, 8-D 전체 연결 검증을 완료했습니다. 다음은 **9단계 실행 절차 재검증과 최종 설계·운영 한계 문서화**입니다.

요청당 Supplier 동시 호출은 4개이며, JVM 전체에서 Supplier별 검색 8개·카탈로그 1개로 추가 제한합니다. 한도를 넘으면 대기열 없이 `CAPACITY_EXCEEDED`로 분류하고 정상 Supplier 결과는 유지합니다. 응답 한도는 검색 2 MiB·카탈로그 8 MiB이며 초과하면 `RESPONSE_TOO_LARGE`입니다. 설정과 검증 범위는 [설계 §17](docs/architecture-decisions.md#17-8-b-자원-제한과-업무-지표)을 참고하세요. 감사 보고서의 본문은 감사 시점 기록이며, 수정 상태는 각 문서 상단 안내와 구현 진행표로 구분합니다.

카탈로그 갱신은 한 인스턴스에서 실행합니다. 현재 운영 호출처인 `fixedDelay` 스케줄이 실행을 직렬화하며, 서비스의 중복 실행 guard는 향후 추가 호출을 위한 보조 방어입니다. 현재 수동 트리거 API는 없습니다. 여러 서버에서는 나머지 서버의 스케줄을 `SUPPLIER_CATALOG_ENABLED=false`로 꺼야 합니다.

검색 DB 조회는 남은 요청 예산과 기본 1초 SQL·300ms 잠금 한도를 적용하고, 연결 획득은 기본 500ms로 제한합니다. 정상 조회에는 timeout 설정 SQL 1개와 데이터 조회 SQL 1개가 필요합니다. DB 자원 실패는 Supplier 호출 없이 `CATALOG_UNAVAILABLE` / HTTP 503으로 반환합니다. 이 코드는 카탈로그를 사용할 수 없다는 뜻으로, 고객은 매핑 미확보·정상 빈 카탈로그·DB 읽기 실패를 구분할 수 없습니다. DB 원인은 `search.catalog.read.failures`와 로그에서 확인합니다. Supplier 검색의 정상 빈 응답은 성공으로 처리합니다.

연결·통신·응답 조립을 포함하는 엄격한 5초 완료 상한은 아니며, 쓰기에는 별도 예산을 둡니다. 운영 Flyway도 공유 DataSource의 기본 SQL 10초·잠금 대기 2초 제한을 받으므로 장시간 migration을 도입하기 전에 전용 예산을 검토해야 합니다. [설계 §18](docs/architecture-decisions.md#18-8-c-동기화-중복-실행과-db-시간-예산)에 비용·지원 범위·트랜잭션 중첩 제약을 정리했습니다.

SpringDoc/Swagger는 아직 도입하지 않았습니다. 공개 검색 endpoint가 하나인 현재는 위 요청 예시를 제공하고, 응답 계약 안정화 후 자동 문서 추가를 검토합니다. Resilience4j도 아직 사용하지 않습니다. 카탈로그는 Reactor의 제한 retry를 사용하고, 검색은 추가 retry 없이 timeout과 부분 결과를 사용합니다. 전역 허용량과 지표를 바탕으로 반복 장애 양상을 확인한 뒤 circuit breaker 도입을 판단합니다.

Actuator HTTP 노출은 `health`, `info`, `metrics`입니다. 검색·Supplier 호출·중복/거부 Offer·카탈로그 신선도 지표를 제공합니다. 카탈로그의 잘못된 항목이 계속되면 기존 매핑으로 검색하면서 갱신 성공이 멈출 수 있으므로 마지막 저장 성공 시각과 연속 실패를 함께 확인합니다. 지표는 프로세스 재시작 시 초기화되며 별도 대시보드는 없습니다.

```sh
curl -s http://localhost:8080/actuator/metrics/supplier.calls
curl -s 'http://localhost:8080/actuator/metrics/supplier.offers?tag=outcome:DUPLICATE'
curl -s http://localhost:8080/actuator/metrics/supplier.catalog.last.success
curl -s http://localhost:8080/actuator/metrics/supplier.catalog.consecutive.failures
```

`last.success`는 epoch seconds이며 0은 이 프로세스에서 저장 성공이 아직 없다는 뜻입니다. `supplier.calls`의 COUNT는 호출 시도 건수이고, 최종 검색 실패 유형은 `supplier.search.failures`에서 확인합니다.

## 문서 안내

- [구현 계획과 진행 상태](docs/implementation-plan.md)
- [전체 연결 E2E 검증](docs/e2e-verification.md)
- [정확성 감사](docs/correctness-audit.md)
- [DB 감사](docs/database-audit.md)
- [네트워크·메모리 감사](docs/network-memory-audit.md)
- [구조·예외·테스트 감사 및 통합 우선순위](docs/structure-audit.md)
- [개발·검증 기록](JOURNAL.md)
