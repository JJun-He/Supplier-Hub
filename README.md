# Supplier Hub

서로 다른 Supplier의 숙소·객실 목록을 내부 ID로 관리하고, 검색 시 재고와 요금을 조회해 공통 Offer로 반환하는 서비스입니다.

Java 21 · Spring Boot 4.0.8 · Gradle · PostgreSQL 17 · Flyway · Spring Data JPA를 사용합니다. 고객 API는 Spring MVC, Supplier HTTP 호출은 WebClient입니다. 메인 API는 `8080`, 별도 Mock은 `18080`에서 실행합니다.

## 핵심 설계

| 선택 | 이유 |
| --- | --- |
| Supplier별 숙소·객실에 내부 ID 부여 | 원본 코드를 고객에게 노출하지 않고, 누락 후 재등장에도 ID 유지 |
| 같은 이름의 숙소도 Supplier별로 구분 | 동일 실물 숙소임을 보장할 공통 키가 없어 임의로 병합하지 않음 |
| 통화의 최소 단위 정수인 세금 포함 숙박 전체 총액 | 일별 요금·숙박 총액·세금 구성이 다른 A/B 가격을 같은 의미로 표현 |
| 일별 가격은 제공된 경우만 내부 보존 | 고객 응답에서는 제외해 응답 크기를 줄이고, 없는 일별 가격은 역산하지 않음 |
| 원본 JSON·Supplier 전용 필드는 공통 응답에서 제외 | 고객 모델과 외부 프로토콜을 분리하며, 원본 응답 전체의 영구 보관도 제외 |
| 연박 재고는 날짜별 최솟값, 품절·인원 초과 Offer 제외 | 전체 숙박 기간에 예약 가능한 조건만 노출 |
| 카탈로그는 기동 직후·주기적으로 동기화 | 검색마다 전체 목록을 다시 받지 않고 저장된 매핑·이름을 사용 |
| 일부 Supplier·배치 실패 시 성공 결과 보존 | 한 곳의 장애로 전체 검색 결과가 사라지는 것을 방지 |

가격·재고·오류 처리의 세부 계약은 [설계 문서](docs/architecture-decisions.md), 구현 항목별 근거는 [요구사항 검증](docs/requirements-verification.md)에 있습니다.

## 로컬 실행

Java 21과 실행 중인 Docker Compose가 필요합니다. 아래 명령은 모두 저장소 루트에서 실행합니다. `java -version`으로 Gradle을 실행할 JDK도 21인지 확인합니다.

### 1. 설정과 DB

```sh
cp -n .env.example .env
```

기존 `.env`는 유지됩니다. `.env`의 `DB_PASSWORD`에 로컬 비밀번호를 넣습니다. 기본 DB 포트 `5432`가 사용 중이면 `DB_PORT`와 `DB_URL`의 포트를 함께 바꿉니다(예: `55432`). `.env`는 Git에서 제외됩니다.

```sh
docker compose up -d --wait postgres
```

Compose와 메인 서비스는 루트의 `.env`를 읽습니다. 메인 서비스 기동 시 Flyway가 migration을 적용하고 Hibernate가 Entity와 스키마의 일치를 검증합니다.

### 2. Mock 시작 — 터미널 A

```sh
./gradlew :mock-supplier:bootRun
```

Mock이 `18080`에서 시작한 뒤 다음으로 진행합니다. 기본 URL과 API 키는 로컬 Mock용이며, Mock은 키의 유효성을 검사하지 않습니다.

### 3. 메인 서비스 시작 — 터미널 B

```sh
./gradlew :bootRun
```

로그에서 A/B 각각 `Supplier catalog synchronization succeeded`를 확인한 뒤 검색합니다. `/actuator/health` 성공만으로 카탈로그 준비를 판단하지 않습니다. 초기 동기화가 실패했다면 Mock 준비 후 메인을 재시작하거나 다음 동기화를 기다립니다. 기본 주기는 이전 동기화 완료 후 10분입니다.

### 4. 검색 — 터미널 C

```sh
curl -i --get 'http://localhost:8080/api/v1/stays/search' \
  --data-urlencode 'checkIn=2026-10-01' \
  --data-urlencode 'checkOut=2026-10-04' \
  --data-urlencode 'adults=2' \
  --data-urlencode 'children=0'
```

응답은 `stays[].roomTypes[].offers[]`로 묶이며, 가격은 `totalAmountIncludingTax`입니다. 기본 Mock의 이 3박 요청은 서로 다른 내부 ID의 숙소 2개, A 429,000원·B 452,000원, 각각 재고 1개를 반환합니다. 둘째 날 품절인 A의 다른 숙소는 제외됩니다. 내부 ID의 숫자는 DB 상태에 따라 달라집니다.

| 상황 | HTTP | `status` | 결과 |
| --- | --- | --- | --- |
| 모든 Supplier 정상 | 200 | `COMPLETE` | 예약 가능한 Offer, 정상 빈 결과도 성공 |
| 일부 Supplier 또는 배치 실패 | 200 | `PARTIAL` | 성공한 Offer와 `supplierResults`의 실패 유형 |
| 모든 Supplier 조회 실패 | 503 | `FAILED` | 빈 `stays`와 Supplier별 실패 유형 |
| 전체 카탈로그 미준비 또는 DB 읽기 실패 | 503 | `FAILED` | `CATALOG_UNAVAILABLE`, Supplier 검색 생략 |
| 잘못된 날짜·인원 등 요청 오류 | 400 | 입력 오류 본문 | `code`와 `message` |

1~30박, 성인·아동 합계 1명 이상을 허용하며 `children`의 기본값은 0입니다. 요청·응답 필드와 전체 실패 정책은 [검색 API 계약](docs/architecture-decisions.md#search-api)을 참고하세요.

## 장애와 복구 재현

아래 Mock 제어 요청 후 검색 명령을 다시 실행합니다. A의 `error`는 HTTP 503, B는 HTTP 200 본문의 오류입니다.

```sh
# A 오류, B 정상 → PARTIAL, B 결과 유지
curl -X POST 'http://localhost:18080/control/a/mode?value=error'
curl -X POST 'http://localhost:18080/control/b/mode?value=normal'

# B도 오류 → HTTP 503 / FAILED
curl -X POST 'http://localhost:18080/control/b/mode?value=error'

# A 지연, B 정상 → PARTIAL, A TIMEOUT
curl -X POST 'http://localhost:18080/control/a/mode?value=no-response'
curl -X POST 'http://localhost:18080/control/b/mode?value=normal'

# 정상 복구 → COMPLETE
curl -X POST 'http://localhost:18080/control/a/mode?value=normal'
curl -X POST 'http://localhost:18080/control/b/mode?value=normal'
```

`no-response`는 30초 응답 지연입니다. 모드는 검색 응답에만 적용되며 Mock 재시작 시 초기화됩니다. 기본 검색 response timeout은 2초입니다. 시간 제한을 전체 HTTP 완료 시간의 엄격한 상한으로 보장하지는 않습니다.

## 빌드·테스트·종료

```sh
# 메인·Mock 실행 JAR
./gradlew :bootJar :mock-supplier:bootJar

# 메인·Mock 단위·통합 테스트
./gradlew test

# 실제 PostgreSQL·메인·Mock 별도 프로세스 E2E
./gradlew e2eTest

# 모든 테스트를 포함한 검증
./gradlew check
```

검증 구성은 **메인 270개 + Mock 6개 + E2E 9개**입니다. 실행 일자·성공 결과 재사용 여부·README 재현 결과와 미검증 범위는 [실행 검증 기록](docs/e2e-verification.md)에 정리합니다. 테스트의 PostgreSQL 컨테이너에도 Docker가 필요합니다.

두 `bootRun`을 각각 Ctrl+C로 종료한 뒤 DB를 내립니다. 데이터 볼륨은 유지됩니다.

```sh
docker compose down
```

## 지원 범위

- 카탈로그 동기화는 한 인스턴스에서 실행합니다. 여러 서버에서는 나머지 서버의 `SUPPLIER_CATALOG_ENABLED=false`로 스케줄을 끕니다. 수동 동기화 HTTP·CLI는 없습니다.
- 요청당 Supplier별 동시 호출은 4개, JVM 전체에서는 Supplier별 검색 8개·카탈로그 1개입니다. 초기 설정값이며 실측 처리 용량은 아닙니다.
- 검색은 재시도하지 않고, 카탈로그의 일시적 실패만 제한 재시도합니다. 서킷 브레이커·검색 캐시·예약 실행·통화 환산은 구현하지 않았습니다.
- `health`, `info`, `metrics`를 Actuator에서 제공합니다. 지표의 의미와 DB·메모리·스레드 한계는 [상세 설계](docs/architecture-decisions.md)에 있습니다.

## 문서

- [현재 설계와 확장 조건](docs/architecture-decisions.md)
- [요구사항과 구현·검증 근거](docs/requirements-verification.md)
- [실행·E2E 검증](docs/e2e-verification.md)
- [AI 활용과 판단 사례](AI_USAGE.md) · [주요 개발 기록](JOURNAL.md)
- [과거 계획·감사와 측정 근거](docs/archive/README.md)
