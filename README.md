# Supplier Hub

서로 다른 Supplier의 숙소·객실 목록을 내부 ID로 관리하고, 검색 시 재고와 요금을 조회해 공통 Offer로 반환하는 Spring Boot 서비스입니다.

## 현재 상태

목록 동기화부터 검색 API까지 기본 구현과 8-A 정확성·입력/예외 계약 보완을 마쳤습니다. 중간 기술 감사의 자원 제한·관측성·DB 동시성 작업은 남아 있습니다. 이 문서는 초기 실행 안내이며, 전체 연결 검증과 최종 문서 정리는 후속 단계에 진행합니다.

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

표의 결과는 목록 준비 및 다른 오류가 없는 조건의 예상 동작입니다. 이번 문서 작성에서는 전체 프로세스 연결 시나리오를 새로 실행하지 않았으며, 정식 E2E 검증은 8-D에 남아 있습니다.

## 빌드·테스트·종료

```sh
# 두 애플리케이션 실행 JAR 생성
./gradlew :bootJar :mock-supplier:bootJar

# 메인과 Mock의 정식 테스트
./gradlew test
```

2026-09-16의 8-A 검증 결과는 메인 214개·Mock 6개 통과이며 실패·오류·건너뜀은 없습니다. parameterized test의 각 입력 사례를 포함한 실행 건수입니다. 변경과 검증 기록은 [JOURNAL](JOURNAL.md), 감사 당시 검증과 한계는 [구조 감사](docs/structure-audit.md)에 있습니다.

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

8-A의 엄격한 입력 파싱·항목별 격리·중복/충돌 정책·URI·카탈로그 계약 검사는 완료했습니다.

1. **8-B:** Supplier별 전역 호출 제한, 응답 크기 정책, 업무 실패 지표
2. **8-C:** 동기화 동시 실행 지원 범위와 DB 시간 예산
3. **8-D:** 실제 DB·Mock·고객 API 전체 연결 검증
4. **9단계:** 실행 절차 재검증과 최종 설계·운영 한계 문서화

현재 호출 동시성 4개 제한은 고객 요청마다 적용됩니다. 정상적인 큰 응답이 codec 한도를 넘는 문제도 남아 있습니다. 감사 보고서의 본문은 감사 시점 기록이며, 수정 상태는 각 문서 상단 안내와 구현 진행표로 구분합니다.

SpringDoc/Swagger는 아직 도입하지 않았습니다. 공개 검색 endpoint가 하나인 현재는 위 요청 예시를 제공하고, 응답 계약 안정화 후 자동 문서 추가를 검토합니다. Resilience4j도 아직 사용하지 않습니다. 카탈로그는 Reactor의 제한 retry를 사용하고, 검색은 추가 retry 없이 timeout과 부분 결과를 사용합니다. 전역 허용량과 관측을 먼저 보완하고 반복 장애 양상을 확인한 뒤 circuit breaker 도입을 판단합니다.

현재 Actuator HTTP 노출은 `health`, `info`입니다. Supplier 업무 지표와 대시보드가 구현됐다는 뜻은 아닙니다.

## 문서 안내

- [구현 계획과 진행 상태](docs/implementation-plan.md)
- [정확성 감사](docs/correctness-audit.md)
- [DB 감사](docs/database-audit.md)
- [네트워크·메모리 감사](docs/network-memory-audit.md)
- [구조·예외·테스트 감사 및 통합 우선순위](docs/structure-audit.md)
- [개발·검증 기록](JOURNAL.md)
