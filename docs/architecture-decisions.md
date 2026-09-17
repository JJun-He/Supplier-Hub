# 아키텍처 결정

이 문서는 현재 구현의 계약과 선택 이유를 설명한다. 실행 절차는 [README](../README.md), 검증 결과는 [요구사항 검증](requirements-verification.md), 과거 감사·계획은 [보관 기록](archive/)에서 확인한다. 설정의 실제 기본값은 [application.yaml](../src/main/resources/application.yaml)에 있다.

## 목적과 범위

Supplier의 숙소·객실 카탈로그를 내부 ID에 매핑하고, 고객의 날짜·인원 조건으로 여러 Supplier의 재고·요금을 조회하여 일관된 상품으로 반환한다. 한 Supplier나 호출 묶음이 실패해도 완료된 결과를 보존하는 것이 핵심이다.

```text
주기 동기화: Supplier 카탈로그 → 전체 snapshot 검증 → PostgreSQL 매핑 저장
고객 검색: 입력 검증 → 활성 매핑 조회 → Supplier별 50개 분할·병렬 조회
          → Offer 정규화·실패 격리 → 내부 이름 결합 → stays[].roomTypes[].offers[]
```

인증·인가, 결제·예약 실행, 관리자 기능, 프론트엔드, 지역 검색, 고객 지정 정렬·페이징, Supplier 간 동일 숙소 판별은 현재 범위에서 제외한다. 실시간 가격·재고 캐시, 서킷 브레이커, MQ·Kafka, Spring Batch도 구현하지 않았다. 추가 조건은 [확장 검토](#extension-conditions)에 정리한다.

## 용어

| 용어 | 이 문서에서의 의미 |
| --- | --- |
| Supplier | 숙소 카탈로그와 실시간 재고·요금을 제공하는 외부 시스템 |
| Property / stay | 내부 숙소 모델 / 고객 응답에서 사용하는 숙소 이름 |
| RoomType | 특정 숙소에 속하는 객실 타입. 코드는 숙소와 함께 식별 |
| Offer | 특정 날짜·인원으로 조회한 객실 1실의 판매 조건 |
| 카탈로그 snapshot | 한 Supplier가 반환한 숙소·객실 전체 목록 |
| 활성 매핑 | 외부 숙소·객실 코드와 사용 가능한 내부 ID의 대응 |
| 배치 | 한 고객 검색에서 숙소 최대 50개를 조회하는 HTTP 호출 묶음 |
| deadline | 같은 JVM의 `System.nanoTime()`을 기준으로 계산한 검색 예산 종료 시점 |

<a id="domain-model"></a>
## 데이터 출처와 도메인

### 카탈로그와 실시간 Offer의 구분

| 정보 | 원천·저장 위치 | 검색에서의 사용 |
| --- | --- | --- |
| 외부 숙소·객실 코드, 이름 | Supplier 카탈로그 → PostgreSQL | 활성 코드로 Supplier를 호출하고 DB 이름을 응답에 결합 |
| 내부 숙소·객실 ID, 활성 상태·연속 누락 횟수 | DB가 생성·관리 | 고객 식별자와 검색 대상 결정 |
| 카탈로그 최대 수용 인원 | Supplier 카탈로그 → `RoomType.maxOccupancy` | 카탈로그에 보존. 현재 검색 projection에는 포함하지 않음 |
| 검색 최대 수용 인원·조식 여부 | Supplier 검색 응답 → `Offer` | 인원 필터와 고객 응답에 사용 |
| 날짜별 재고·가격·통화 | Supplier 검색 응답 → 요청 안의 값 객체 | 기간 검증·정규화 후 반환. DB에 저장하거나 캐시하지 않음 |

DB 이름과 실시간 Offer를 결합하므로 이름은 마지막 동기화 시점, 가격·재고는 검색 시점의 정보다. 카탈로그와 검색의 수용 인원을 대조하는 정책은 현재 없다. 로컬의 `mock-supplier`는 이 프로토콜을 재현하는 별도 애플리케이션이며 실제 Supplier 데이터나 운영 처리 용량의 근거가 아니다.

`Property`는 Supplier, 외부 숙소 코드, 이름을 가진다. `RoomType`은 소속 숙소, 외부 객실 코드, 이름, 최대 수용 인원을 가진다. 두 Entity 모두 `BIGINT` 내부 ID, 활성 상태, 연속 누락 횟수, 생성·수정 시각을 저장한다.

```text
Property: UNIQUE(supplier, supplier_property_code)
RoomType: UNIQUE(property_id, supplier_room_type_code)
```

객실 코드는 Supplier 전체에서 유일하다고 가정하지 않는다. 같은 외부 키가 다시 들어오면 기존 행을 갱신하고, 비활성화·재등장 때도 내부 ID를 보존한다. 단일 관계형 DB에서 분산 ID 생성이 필요하지 않아 UUID 대신 DB 생성 ID를 선택했다. 외부 숙소·객실 코드는 고객 응답에 노출하지 않는다.

Supplier가 다르면 이름과 객실 구성이 비슷해도 별도 내부 숙소다. 공통 식별 키 없이 이름으로 병합하면 오탐·누락, 검수·분리 정책이 추가된다. 동일 숙소 통합이 필요해지면 기존 Supplier 매핑 위에 표준 숙소 계층을 추가하며 원본 매핑을 덮어쓰지 않는다.

### Offer와 가격 값 객체

`Offer`는 내부 숙소·객실 ID, Supplier, 최대 수용 인원, 예약 가능 객실 수, 조식 여부, `Price`를 담는다. 가격·재고·조식은 검색 시점의 판매 조건이며 숙소의 고정 속성이 아니다. 같은 객실의 다른 판매 조건은 여러 Offer로 표현한다.

`Money`는 ISO 4217 통화와 그 통화의 최소 단위인 음이 아닌 `long` 금액이다. `Price`는 세금 포함 숙박 전체 총액과 선택적인 `NightlyPrice` 목록을 가진다. 일별 상세가 없으면 빈 목록으로 표현하며 `null`을 쓰지 않는다. `OfferCandidate`가 완전한 후보의 필수 값을 검증하고, 최종 `Offer`는 검증 팩토리와 private 생성자를 사용하여 불변식을 유지한다.

<a id="execution-model"></a>
## 패키지 책임과 실행 모델

경로는 `src/main/java/com/supplierhub` 기준이다.

| 경계 | 책임과 실제 의존 |
| --- | --- |
| `search/api` | 요청 파싱·400 분류, 검색 호출, HTTP 상태와 고객 DTO 조립. `search/application`과 `SearchCriteria` 사용 |
| `search/application` | 활성 매핑 조회, 분할·병렬 호출, Offer 정규화·결과 집계. 조회·Supplier 포트 외에 구체 `SupplierCallResources`, `SupplierMetrics`, 설정 클래스에 의존 |
| `search/domain` | 가격·날짜·재고·Offer 불변식. HTTP·JSON·JPA에 의존하지 않으며 카탈로그의 `Supplier`와 공통 `InvalidValueException` 사용 |
| `catalog/application` | 동기화 순서·retry·실행 guard, snapshot 저장 계약. writer는 실제 JPA Repository에 직접 의존 |
| `catalog/domain` | snapshot 검증과 매핑 생명주기. `Property`, `RoomType`은 JPA Entity |
| `catalog/infrastructure` | JPA Repository, 검색 projection, JDBC·PostgreSQL timeout과 DB 실패 분류 |
| `supplier/common` | 공통 호출 포트·결과·설정, JSON 타입 검증, 실패 매핑, 호출 허용량·연결 풀·Micrometer 지표 |
| `supplier/suppliera`, `supplier/supplierb` | 각 프로토콜의 요청·성공 규약·필드 해석. 카탈로그 모델 또는 `OfferCandidate`로 변환하여 공통 정규화 사용 |

포트로 경계를 나눴지만 모든 application이 인터페이스에만 의존하는 구조는 아니다. 호출 제한과 연결 풀 소유권을 `SupplierCallResources`에 함께 두어 자원 수명을 한곳에서 관리한다. application 테스트도 풀 객체와 `MeterRegistry`를 만든다. 호출 정책을 독립 교체하거나 transport 의존을 없앨 필요가 생기면 좁은 호출 실행 인터페이스와 연결 풀 소유자를 분리한다. 명확한 필요 없이 패키지 전체를 재편하지 않는다.

애플리케이션은 Spring MVC·JPA를 사용한다. 현재 컨트롤러는 동기 `ResponseEntity`를 반환하고 검색 서비스는 병렬 `WebClient` 결과를 `.block()`으로 기다린다. 따라서 고객 요청 스레드는 검색 동안 점유된다. JPA 조회는 이 요청 스레드에서 먼저 끝나며 WebClient 이벤트 루프에서 실행하지 않는다. Supplier HTTP I/O는 Reactor Netty로 병렬 처리하고, JSON 파싱·정규화도 해당 reactive 흐름 안에서 수행한다. WebClient 사용만으로 전체 서버가 비동기·비차단이 되지는 않는다.

DB 조회 트랜잭션을 종료한 뒤 Supplier를 호출하고 `open-in-view=false`로 둔다. 전체 WebFlux·R2DBC 전환은 현재 요구에 필요하지 않다. 동기식 영속성 모델을 유지하면서 외부 HTTP의 제한 병렬성을 사용하는 선택이다.

<a id="search-api"></a>
## 검색 HTTP 계약

```http
GET /api/v1/stays/search?checkIn=2026-10-01&checkOut=2026-10-04&adults=2&children=0
```

`checkIn`, `checkOut`, `adults`는 필수이며 `children`은 생략하면 0이다. 날짜는 `checkIn < checkOut`, 숙박 기간은 최대 30박이어야 한다. 두 인원 값은 각각 음이 아닌 정수이고 합계는 1 이상 `Integer.MAX_VALUE` 이하다. 최대 숙박일수는 한 요청의 일별 가격·재고 데이터 크기를 제한한다. 성인 최소 1명과 과거 날짜 금지는 제공된 제품 정책이 없어 추가하지 않았다.

파라미터 누락·형식 오류는 HTTP 400 / `INVALID_REQUEST_PARAMETER`, 검색 조건 위반은 HTTP 400 / `INVALID_SEARCH_CRITERIA`다. 컨트롤러의 조건 생성 구간에서만 입력 오류로 변환하므로 뒤의 내부 `IllegalArgumentException` 등을 고객 입력 오류로 숨기지 않는다.

아래는 위의 3박 요청을 기본 Mock의 정상 A/B로 실제 실행한 응답이다. 내부 ID 숫자는 예시이며 DB 상태에 따라 달라진다. 이름이 같은 숙소도 Supplier별로 별도 ID를 가진다. A의 다른 숙소는 둘째 날 품절이라 정상 제외 1건으로 집계된다.

```json
{
  "status": "COMPLETE",
  "searchCriteria": {
    "checkIn": "2026-10-01",
    "checkOut": "2026-10-04",
    "adults": 2,
    "children": 0
  },
  "stays": [
    {
      "stayId": 1,
      "stayName": "Riverside Hotel Seoul",
      "roomTypes": [
        {
          "roomTypeId": 1,
          "roomTypeName": "Deluxe Twin",
          "maxOccupancy": 2,
          "offers": [
            {
              "supplier": "SUPPLIER_A",
              "availableRooms": 1,
              "breakfastIncluded": false,
              "price": {
                "currency": "KRW",
                "totalAmountIncludingTax": 429000
              }
            }
          ]
        }
      ]
    },
    {
      "stayId": 3,
      "stayName": "Riverside Hotel Seoul",
      "roomTypes": [
        {
          "roomTypeId": 3,
          "roomTypeName": "Deluxe Twin Room",
          "maxOccupancy": 2,
          "offers": [
            {
              "supplier": "SUPPLIER_B",
              "availableRooms": 1,
              "breakfastIncluded": true,
              "price": {
                "currency": "KRW",
                "totalAmountIncludingTax": 452000
              }
            }
          ]
        }
      ]
    }
  ],
  "supplierResults": [
    {
      "supplier": "SUPPLIER_A",
      "status": "SUCCESS",
      "acceptedOfferCount": 1,
      "rejectedOfferCount": 0,
      "unavailableOfferCount": 1,
      "failureTypes": []
    },
    {
      "supplier": "SUPPLIER_B",
      "status": "SUCCESS",
      "acceptedOfferCount": 1,
      "rejectedOfferCount": 0,
      "unavailableOfferCount": 0,
      "failureTypes": []
    }
  ]
}
```

고객 상품과 연동 결과를 분리하고 빈 배열을 `null` 대신 반환한다. Offer가 없는 객실과 숙소는 제거한다. 고객 지정 정렬은 없으며 구현은 내부 ID·Supplier·통화·금액 등으로 결과 순서를 안정화한다. 통화가 다른 금액의 경제적 우열을 비교한다는 뜻은 아니다.

| Supplier 결과 집계 | 전체 상태 | HTTP |
| --- | --- | ---: |
| 모든 활성 Supplier가 `SUCCESS` | `COMPLETE` | 200 |
| 모든 활성 Supplier가 `FAILED` | `FAILED` | 503 |
| 그 밖의 조합 또는 Supplier 자체의 `PARTIAL` | `PARTIAL` | 200 |

Supplier는 정상 완료 배치가 없으면 `FAILED`, 완료 배치가 있고 실패 배치·거부 항목이 없으면 `SUCCESS`, 완료 배치와 실패 사유가 함께 있으면 `PARTIAL`이다. 정상 빈 검색 결과도 완료 배치다. A가 정상 빈 결과이고 B가 timeout이면 `200 PARTIAL, stays=[]`다. 모든 항목이 거부된 정상 응답도 거부 건수 때문에 `PARTIAL`일 수 있으므로 `PARTIAL`은 Offer가 반드시 있다는 뜻이 아니다. 정상 DB 조회에서 활성 Supplier가 모두 설정으로 제외되면 `COMPLETE`와 빈 결과를 반환한다.

`206 Partial Content`는 사용하지 않는다. 상세 원인은 공통 실패 유형으로 제공하고 원본 응답·외부 코드·내부 예외 메시지·stack trace는 노출하지 않는다.

<a id="normalization"></a>
## 가격·재고·Offer 정규화

### A와 B의 의미 통일

공통 가격은 **요청 숙박 기간에 객실 1실을 이용할 때의 세금 포함 전체 금액**이다.

| 항목 | Supplier A | Supplier B | 공통 의미 |
| --- | --- | --- | --- |
| 숙소·객실 코드 | `hotelCode`, `roomTypeCode` | `propertyId`, `roomId` | 활성 매핑의 내부 ID로 변환 |
| 성공 응답 | HTTP 성공과 `items` | HTTP 성공, `resultCode="0000"`, `data.items` | 검증할 수 있는 Offer 목록 |
| 가격 | `dailyRates[].nightlyRate + taxAmount` | `totalPrice`, `taxIncluded=true` 필수 | 통화 최소 단위의 세금 포함 숙박 총액 |
| 일별 상세 | 날짜순 `NightlyPrice`로 보존 | 제공하지 않음 | 선택 값. 검색 HTTP 응답에는 미노출 |
| 재고 | `dailyRates[].remainingRooms` | `inventory[].remainingRooms` | 모든 숙박일의 최솟값 |
| 인원·조식 | `maxOccupancy`, `breakfastIncluded` | 같은 의미의 필드 | Offer 검증·응답에 사용 |

A는 각 날짜의 세전 가격과 세금을 더한 뒤 숙박일 전체를 합산한다. 통화가 다르거나 합산이 `long`을 넘으면 거부한다. B는 이미 세금이 포함된 총액을 그대로 사용하며 세금을 다시 더하지 않는다. 총액을 박수로 나누어 없는 일별 가격을 만들거나 제공되지 않은 세금액을 추정하지 않는다.

통화는 원본 ISO 4217 값을 보존하고 환산하지 않는다. 환산에는 환율 출처·적용 시각·반올림·수수료·표시 가격과 결제 가격의 관계가 필요하다. 도입하더라도 원본 금액을 함께 보존한다. 검색 응답에는 `currency`, `totalAmountIncludingTax`만 노출한다. 전체 검색에 일별 상세를 붙이면 Offer 수와 숙박일수의 곱만큼 응답이 커지므로, 필요한 경우 별도 상품 상세 계약에서 제공 여부를 명시한다.

### 날짜와 정상 제외

체크아웃일은 숙박일에 포함하지 않는다. 반환할 Offer는 체크인부터 체크아웃 전날까지 재고가 정확히 한 번씩 있어야 하고, 일별 가격이 제공되면 그 날짜도 같은 기간을 정확히 덮어야 한다. 누락·중복·범위 밖 날짜, 음수 금액·재고, 잘못된 통화·식별자는 거부한다.

요청 인원이 수용 인원을 넘거나 연박 최소 재고가 0이면 정상 제외다. `unavailableOfferCount`에 기록하며 이 건수만으로 `PARTIAL`이 되지 않는다. 실제 구현은 후보의 필수 값·타입을 검증한 뒤 인원 초과를 먼저 제외하므로, 이미 인원으로 제외된 후보의 기간 커버리지까지 검사하지는 않는다.

### 항목 격리·중복·충돌

검색 응답을 독립된 항목 목록으로 읽을 수 있으면 잘못된 항목만 `rejectedOfferCount`에 넣고 형제 Offer를 보존한다. 어댑터의 원본 항목 변환부터 값 객체 생성까지 후보별 경계 안에서 수행한다. JSON 타입·외부 값 오류는 `InvalidValueException`과 `OfferMappingException`으로 명시하고, 예상하지 못한 NPE·상태 오류·일반 예외는 항목 오류로 삼지 않는다. 오류 범위와 retry는 [실패 정책](#failure-policy)을 따른다.

정규화된 `Offer`의 모든 값이 같으면 `LinkedHashSet`으로 한 건만 유지한다. A의 일별 가격은 먼저 날짜순으로 정렬하므로 배열 순서만 다른 중복도 제거된다. 중복은 `duplicateOfferCount`와 지표로만 기록하고 고객 응답의 거부 건수·`PARTIAL`을 늘리지 않는다. `acceptedOfferCount`는 중복·충돌 처리를 마친 실제 반환 Offer 수다.

가격·재고·조식·통화·일별 가격 구성이 다르면 각각 보존한다. 요금제 식별자가 없으므로 같은 객실이라는 이유로 최저가 하나를 고르거나 같은 상품의 충돌로 단정하지 않는다. 다만 최대 수용 인원은 응답의 객실 타입 속성이므로 반환 가능한 Offer끼리 같은 내부 객실 ID의 수용 인원이 다르면 그 객실의 Offer를 모두 거부한다. 중복 제거 후 제외한 건수를 거부 수에 더하고 다른 객실은 유지한다.

한 숙소의 객실은 현재 하나의 배치에 속하므로 동일 객실의 충돌 검사가 한곳에서 끝난다. 같은 숙소를 여러 배치로 나누거나 재시도 결과를 병합하는 기능을 추가하면 최종 집계 경계의 중복·충돌 정책을 다시 설계해야 한다.

<a id="supplier-adapters"></a>
## Supplier 프로토콜 경계

각 Supplier 어댑터는 Jackson 3 `JsonNode`로 전송 응답을 읽는다. `SupplierJson`은 타입·범위 검사를 공유하고 필드명·성공 규약은 A/B 어댑터가 해석한다. 도메인에는 JSON 노드를 전달하지 않는다. 실제 Boot WebClient codec을 사용하는 계약 테스트로 이 경계를 검증하며 고객용 전역 ObjectMapper 정책은 바꾸지 않는다.

금액은 64비트, 재고·수용 인원은 32비트 JSON 정수만 허용한다. 소수, `1.0`, 지수 표기, 숫자 문자열, boolean, null, 범위 초과를 묵시적으로 변환하지 않는다. 문자열·boolean도 실제 타입을 확인하고 날짜는 엄격한 ISO 날짜로 읽는다. 음수와 합산 overflow는 값 객체에서 거부한다.

검색 포트에는 검색 조건과 해당 Supplier의 활성 매핑을 전달한다. 한 요청은 숙소 1~50개이며 숙소 코드와 숙소 내 객실 코드 중복을 거부한다. A/B는 숙소 코드 목록을 쉼표로 연결하므로 쉼표가 포함된 숙소 코드는 카탈로그 경계와 검색 요청 경계에서 거부한다. URI 템플릿 값으로 전달하여 `+`, 중괄호, `%`, `&`, 공백, 비ASCII 문자는 보존한다. 응답에 요청 매핑이 없는 외부 코드가 나타나면 그 항목만 거부한다.

검색은 잘못된 항목을 분리할 수 있지만 카탈로그는 하나라도 잘못되면 snapshot 전체를 거부한다. 일부 항목을 조용히 빼고 저장하면 정상 매핑을 누락으로 오인할 수 있기 때문이다. 반복 오류로 동기화가 멈추면 기존 매핑으로 검색을 계속할 수 있는 대신 이름·활성 상태가 오래될 수 있어 마지막 저장 성공 시각을 관측한다.

기본 URL과 API 키는 외부 설정이며 client에 `X-Api-Key`로 전달한다. 실제 키를 추적하거나 로그에 기록하지 않는다. client 등록 중복과 활성 Supplier의 client 누락은 시작 시 검사한다. 카탈로그 스케줄을 꺼도 서비스의 등록 계약 검사는 유지되지만 외부 수동 실행 HTTP·CLI 기능이 생기는 것은 아니다.

새 Supplier는 `Supplier` enum·설정, 검색/카탈로그별 WebClient bean, `SupplierCatalogClient`·`SupplierSearchClient` 구현을 추가한다. 고유 프로토콜과 실패 매핑은 해당 어댑터에 두고 Mock·계약 테스트를 함께 추가한다. 기존 공통 계약으로 표현되면 검색 서비스에 Supplier별 분기를 추가하지 않는다.

<a id="failure-policy"></a>
## 실패 분류·재시도·보존 범위

**실시간 검색은 모든 실패 유형에서 재시도하지 않는다.** 고객 예산을 사용하고 장애 중 호출량을 늘리는 대신 제한 시간과 부분 응답을 사용한다. Reactor Netty의 연결 reset 자동 재전송도 껐다. 카탈로그 HTTP 조회만 retryable로 분류된 실패에 기본 최대 2회 추가 시도하며 `Retry.backoff`의 초기 300ms backoff를 사용한다. 아래의 재시도는 같은 동기화 실행 안의 즉시 재시도이며 이후 주기 실행과는 별개다.

| 공통 유형 | 발생 조건 | 검색 retry | 카탈로그 retry |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | HTTP 400 / B `E400` | 없음 | 없음 |
| `AUTHENTICATION_FAILED` | HTTP 401 / B `E401` | 없음 | 없음 |
| `RATE_LIMITED` | HTTP 429 / B `E429` | 없음 | 없음. `Retry-After` 대기 정책 미구현 |
| `UNAVAILABLE` | HTTP 5xx / B `E500`, `E503` / timeout이 아닌 요청 연결 실패 | 없음 | 최대 2회 |
| `TIMEOUT` | 연결·응답·호출 timeout, 검색 전체 예산에서 미완료 배치 | 없음 | HTTP 시도 timeout에 최대 2회 |
| `INVALID_RESPONSE` | JSON·필수 구조 오류, 분리 가능한 외부 항목 오류, B 미정의 본문 코드, 불완전·위험한 snapshot | 없음 | 없음 |
| `CAPACITY_EXCEEDED` | JVM 호출 허용량 부족, 연결 풀 대기 개수·시간 초과 | 없음 | 없음 |
| `RESPONSE_TOO_LARGE` | 응답 codec의 유한한 크기 한도 초과 | 없음 | 없음 |
| `CATALOG_UNAVAILABLE` | 활성 객실 매핑 없음 또는 예상된 DB 읽기 자원 실패 | Supplier 호출 안 함 | 해당 없음 |
| `INTERNAL_ERROR` | Supplier 배치 처리·카탈로그 처리의 미분류 내부 예외 | 없음 | 없음 |
| `UNKNOWN` | 위에 매핑되지 않은 HTTP 오류 상태 또는 응답 읽기 실패 | 없음 | 없음 |

HTTP 오류 상태와 B의 HTTP 200 본문 오류는 별도 경계에서 변환한다. B 본문 실패 매핑은 검색·카탈로그가 하나의 전용 매퍼를 공유한다. transport는 중첩 원인을 확인하여 timeout·버퍼·연결 풀 오류를 분류한다. `403` 등 미정의 HTTP 오류 상태를 임의로 인증 실패에 포함하지 않는다.

| 실패 범위 | 보존하는 결과·상태 |
| --- | --- |
| 검색의 분리 가능한 항목 오류 | 정상 형제 Offer와 다른 배치 유지. 거부 수·`INVALID_RESPONSE`로 `PARTIAL` 계산 |
| 검색 JSON 전체·envelope·호출·내부 mapper 오류 | 해당 배치 실패. 다른 완료 배치·Supplier 결과 유지 |
| 검색 전체 예산 소진 | 완료 배치 유지. 진행 호출 취소, 시작 전·미완료 배치는 `TIMEOUT`으로 집계 |
| 카탈로그 조회·검증·저장 실패 | 해당 Supplier 기존 매핑과 누락 횟수 유지. 저장 중 오류는 트랜잭션 rollback, 다음 Supplier 계속 처리 |
| 예상된 DB 읽기 실패 | Supplier HTTP를 시작하지 않고 모든 활성 Supplier에 `CATALOG_UNAVAILABLE`, HTTP 503 / `FAILED` |

정상 내부 ID·계산 결과의 불변식 위반을 외부 입력 오류로 숨기지 않는다. Supplier 배치 안의 내부 오류는 `INTERNAL_ERROR`와 ERROR 로그로 남기지만, 배치 바깥의 예상하지 못한 DB·응답 조립 오류까지 모두 부분 실패로 바꾸지는 않는다. 그런 오류는 서버 오류로 전파된다. 정규화 거부 로그에는 호출 출처·후보 Supplier, 항목 순번, 사용할 수 있는 내부 ID와 원인 예외를 남긴다. 고객 응답에는 내부 예외 내용을 넣지 않는다.

`CATALOG_UNAVAILABLE`은 Supplier 서버 장애를 단정하는 코드가 아니다. 현재 영속 동기화 상태가 없어 정상 빈 카탈로그·최초 동기화 미완료·사용 가능한 매핑 부재를 구별하지 못하고, 예상된 DB 읽기 실패도 같은 공개 코드로 나타낸다. 상세 DB 원인은 로그와 지표로 구분한다. 공개 API에서 원인별 대응이 필요해지면 별도 코드·상태 모델을 검토한다.

<a id="catalog-sync"></a>
## 카탈로그 동기화와 매핑 생명주기

### 실행과 지원 범위

현재 운영 진입점은 동기 `fixedDelay` 스케줄 하나다. 기본 initial delay는 0, 이전 실행 종료 후 대기는 10분이며 Supplier는 순차 처리한다. HTTP 조회 실패가 다른 Supplier 동기화를 중단시키지 않는다. 수동 HTTP endpoint·CLI·관리자 트리거는 없다.

카탈로그를 갱신하는 애플리케이션 인스턴스는 하나만 지원한다. 여러 검색 서버를 운영하면 나머지의 `SUPPLIER_CATALOG_ENABLED=false`로 스케줄을 끈다. 자동 leader 선출·분산 lock은 없다. 단일 스케줄의 중첩은 `fixedDelay`가 막으며 Supplier별 `AtomicBoolean` guard는 서비스 직접 호출이나 향후 추가 트리거가 겹칠 때의 보조 방어다.

guard는 조회 전부터 모든 HTTP 시도·retry backoff·snapshot 저장 commit/rollback 이후까지 유지한다. 같은 Supplier 실행이 이미 진행 중이면 대기하거나 새 snapshot을 받지 않고 건너뛴다. `supplier.catalog.skipped`만 증가시키고 마지막 성공 시각·연속 실패 수를 바꾸지 않는다. `finally`에서 guard를 반환한다. 서비스 진입의 `Propagation.NEVER`는 외부 트랜잭션 안의 동기화를 거부하여 HTTP 대기 중 DB 트랜잭션이 유지되지 않게 한다. writer 직접 호출은 동기화 진입점이 아니며 guard가 없다.

이 guard는 여러 JVM의 최초 INSERT 경쟁·누락 횟수 갱신 유실·저장 순서 역전을 해결하지 않는다. 현재는 단일 갱신 인스턴스와 직렬 스케줄로 그 실행 조건을 배제한다. Supplier가 순차 요청에도 오래된 snapshot을 반환하는지를 판별할 버전 계약도 없다.

`Mono.defer`로 client를 호출하여 Publisher 생성 전 동기 실패에도 같은 제한 retry 규칙을 적용한다. 빈 Publisher는 잘못된 응답이며, snapshot Supplier가 호출 client와 다르면 저장하지 않고 내부 오류로 기록한다. HTTP 조회가 성공해도 DB 저장 실패는 retry하지 않는다. 마지막 동기화 성공은 저장이 끝난 뒤에만 기록한다.

### Upsert·누락·재등장

전체 snapshot을 검증한 다음 Supplier 하나의 트랜잭션으로 반영한다.

1. 새로운 외부 키는 삽입하고 기존 키의 이름·수용 인원을 갱신한다.
2. 재등장한 매핑은 같은 ID로 활성화하고 누락 횟수를 0으로 초기화한다.
3. 정상 전체 목록에서 처음 누락되면 누락 횟수를 1로 올리되 활성 상태를 유지한다.
4. 다음 정상 전체 목록에서도 연속 누락되면 비활성화한다.

숙소 전체가 누락되면 숙소 누락 횟수만 증가한다. 숙소가 비활성화되는 시점에 하위 활성 객실도 함께 비활성화한다. 같은 부재를 각 객실의 누락 횟수로 중복 기록하지 않는다. 숙소가 다시 등장하면 실제로 snapshot에 함께 등장한 객실만 재활성화한다. 하드 삭제는 하지 않는다.

검색은 숙소와 객실이 모두 활성인 매핑만 읽는다. 한 번 누락되어 남아 있는 매핑은 실시간 조회 대상일 뿐이며 판매 가능한 Offer가 없으면 고객 상품에 나타나지 않는다. 동기화는 비활성 행도 읽어 기존 ID로 복원한다.

### 불완전·대량 누락 방어

카탈로그 호출 실패, 항목 오류·중복, 아래 안전 조건 위반은 snapshot 전체를 거부하며 기존 매핑과 누락 횟수를 바꾸지 않는다.

- 기존 활성 숙소가 있는데 새 숙소 목록이 비어 있음.
- 기존 활성 객실이 있는 숙소가 빈 객실 목록으로 돌아옴.
- Supplier 전체의 활성 숙소 또는 활성 숙소·객실 조합이 모두 누락됨.
- 숙소 또는 객실 조합의 누락이 **10개 이상이면서 기존 활성 목록의 50%를 초과**함.

일반적인 작은 변경에는 2회 연속 누락 규칙을 적용한다. 대형 부분 응답은 두 번 반복되어도 잘못된 비활성화가 될 수 있어 별도 격리가 필요하다. 10개·50%는 설정 가능한 초기 안전값이며 정상 변동의 관측값에서 도출한 운영 기준이 아니다. 실제 전체 상품 제거에는 별도 확인·운영 절차가 필요하지만 현재 관리자 승인 기능은 없다.

### Supplier 비활성 설정

`supplier.a.enabled` 또는 `supplier.b.enabled=false`는 해당 Supplier를 검색·동기화·검색 상태 집계에서 제외한다. 기존 DB 매핑을 삭제하거나 비활성화하지 않으므로 계약 종료에 따른 데이터 정리와는 별개다. 재활성화하면 기존 매핑으로 검색에 참여할 수 있으며, 최신 동기화 성공까지 기다리는 activation gate는 미구현이다. 필요한 경우 후속 운영 정책으로 추가한다.

<a id="call-resources"></a>
## 검색 분할·호출 자원·시간 예산

### 요청별 분할과 전체 예산

활성 매핑을 Supplier별 숙소로 묶어 최대 50개씩 나누고 각 Supplier 안에서 최대 4개 배치를 동시에 실행한다. 서로 다른 Supplier도 동시에 실행한다. 한 고객 요청의 폭주를 막으면서 모두 순차 호출할 때의 지연을 줄이는 절충이다. 배치는 reactive 구독 수요에 맞춰 생성한다.

검색 예산은 DB 조회 직전부터 기본 5초로 계산한다. DB reader에 절대 deadline을 전달하고 매핑·계획 생성 후 남은 예산만 Supplier reactive 흐름에 사용한다. 예산이 끝나면 진행·대기 호출을 취소하고 완료된 배치만 보존한다. **5초는 고객 진입·CPU 처리·응답 직렬화까지 포함한 엄격한 HTTP 완료 상한이 아니다.** DB 세부 경계는 [DB 시간 예산](#database-budget)을 따른다.

아래는 **이번 문서 검토에서 현재 초기값을 유지하는 이유**다. 실제 Supplier 지연 분포나 고객 SLA에서 산출한 수치가 아니며, 과거에 측정·합의한 목표가 있었던 것처럼 설명하지 않는다.

| 시간 설정 | 현재값과 적용 범위 | 유지 이유와 감수하는 절충 |
| --- | --- | --- |
| 연결 timeout | 검색·카탈로그 500ms. `CONNECT_TIMEOUT_MILLIS`로 TCP 연결 수립 제한 | 연결되지 않는 대상이 초 단위 호출 예산 대부분을 사용하기 전에 실패를 드러낸다. 지연이 큰 정상 경로를 일찍 거부할 수 있어 운영 연결 지연을 보고 조정한다. DNS·풀 대기·TLS 전체를 합쳐 500ms라는 뜻은 아니다. |
| 검색 응답 timeout | 2초. 요청 전송 후 응답의 네트워크 읽기 사이 최대 대기 간격 | 데이터 수신이 멈춘 배치에 호출 예산 3초를 모두 쓰지 않도록 한다. 정상 서버 처리나 전송 간격이 2초를 넘으면 거부할 수 있다. 응답 전체가 2초 안에 끝난다는 뜻은 아니다. |
| 검색 호출 timeout | 3초. client의 reactive 구독부터 응답 디코딩·정규화 결과까지 | 2초보다 짧은 간격으로 데이터를 보내며 오래 지속되는 응답에도 배치 전체 제한을 둔다. 연결·풀 대기 등도 이 흐름 안에서 예산을 사용하므로 큰 정상 응답을 중단할 수 있다. `응답 2초 + 파싱 1초`로 시간을 예약한 설정은 아니다. |
| 검색 전체 예산 | 5초. DB 조회 시작부터 계산하여 남은 시간을 Supplier 흐름에 적용 | 여러 배치가 각자 3초씩 대기하면서 검색이 계속 늘어지는 것을 막고 완료 결과를 반환한다. 단일 배치 예산보다 여유를 두되 모든 배치의 완료를 기다리지 않는 선택이다. DB·후속 배치에 정확히 2초를 배정하거나 5초 HTTP SLA를 보장하지 않는다. |
| 카탈로그 응답 timeout | 3초. 검색과 같은 네트워크 읽기 간격 제한 | 전체 목록을 읽는 주기 작업에는 검색보다 긴 수신 대기를 허용하여 일시적인 지연으로 snapshot 전체를 버리는 빈도를 줄일 여지를 둔다. 그만큼 장애 감지와 다음 Supplier 처리가 늦어질 수 있다. |
| 카탈로그 호출 timeout | 4초 / HTTP 시도. snapshot 변환 완료까지 | 검색보다 큰 전체 목록을 받을 여유를 두면서 느린 수신이 한 시도를 계속 점유하지 않게 한다. retry·backoff·DB 저장은 별도이며, 4초가 동기화 전체 상한은 아니다. |

`responseTimeout`의 읽기 간격과 TCP·TLS의 서로 다른 경계는 [Reactor Netty HTTP client 설명](https://projectreactor.io/docs/netty/release/reference/http-client.html)과 [responseTimeout API](https://projectreactor.io/docs/netty/release/api/reactor/netty/http/client/HttpClient.html#responseTimeout(java.time.Duration))를 따른다. 현재 client는 TLS handshake 전용값을 따로 설정하지 않는다.

`Mono.timeout`과 전체 예산의 취소는 동기 CPU 파싱·정규화나 응답 직렬화를 강제로 중단하는 보장이 아니다. `500ms < 2초 < 3초 < 5초`도 timer 발동 순서를 보장하지 않는다. 예를 들어 늦게 시작한 배치에 남은 전체 예산이 1초면 호출 timeout 3초보다 전체 취소가 먼저 올 수 있다. 아래의 3,000개 숙소 계산은 규모의 한계 예시이며 5초 선택의 실측 근거가 아니다.

대표 숙소·객실·숙박일수와 동시 요청을 정한 뒤 연결 지연, 응답 읽기 정체, 배치·전체 검색 지연, timeout·부분 성공 비율을 측정하여 조정한다. p95·p99와 고객 목표는 그때 확인할 입력이며 현재 지표에 percentile 집계나 운영 SLA가 구성됐다고 주장하지 않는다. 짧게 줄이면 지연과 점유는 줄일 수 있지만 정상 결과를 더 잃고, 늘리면 완료 기회와 함께 대기·자원 점유도 증가한다.

### JVM 허용량과 연결 풀

`SupplierCallResources`는 JVM 안에서 **Supplier × 작업(SEARCH/CATALOG)** 별로 공유한다. 요청당 4개 병렬 제한과 별개로 여러 고객 요청의 합계에 적용한다.

| 자원 설정 | 검색 / Supplier | 카탈로그 / Supplier |
| --- | ---: | ---: |
| 동시에 수용하는 호출 | 8 | 1 |
| 전용 풀 최대 연결 수 | 8 | 1 |
| 연결 획득 대기 개수 | 8 | 1 |
| 연결 획득 대기 시간 | 200ms | 200ms |
| 응답 codec 한도 | 2 MiB | 8 MiB |

값은 `supplier.resources.search` / `supplier.resources.catalog`에서 바꾸며 현재 A/B에 동일한 값을 적용하되 실제 자원은 분리한다. 무제한을 뜻하는 0·음수는 허용하지 않는다. 검색 8개는 요청당 최대 배치 4개를 기준으로 두 요청의 최대 병렬도를 수용하도록 선택한 초기값이며 부하 측정으로 얻은 동시 고객 수 보장이 아니다. 실제 거부 여부는 배치 수와 실행이 겹치는 시간에 달려 있다.

애플리케이션 허용량에는 대기열이 없다. 구독 시 `tryAcquire`로 즉시 수용하거나 `CAPACITY_EXCEEDED`로 거부한다. 이벤트 루프에서 blocking acquire를 하지 않으며 고객별 공정 배분을 보장하지 않는다. `Mono.using`의 eager 정리로 정상·오류 신호 전달 전에 허용량을 반환하고 동기 예외·취소도 정리한다. 허용량은 응답 읽기·파싱·정규화 완료까지 유지하지만 카탈로그 DB 저장은 포함하지 않는다.

A/B와 검색/카탈로그는 별도 ConnectionProvider를 사용하므로 같은 호스트 주소여도 서로의 풀을 점유하지 않는다. 연결 풀의 pending 한도는 호출 허용량과 실제 연결 반환 사이의 짧은 경계 및 client 직접 사용을 추가로 보호한다. 풀 대기 개수·시간 초과는 원인 체인을 검사하여 `CAPACITY_EXCEEDED`로 분류한다. Reactor Netty의 shaded pool 예외 의존은 transport mapper에 모으며 애플리케이션 종료 시 소유한 풀을 정리한다.

카탈로그는 HTTP 각 retry 시도마다 허용량을 다시 얻는다. 로컬 용량 부족·응답 크기 초과를 즉시 retry하면 같은 자원을 더 경쟁하거나 같은 큰 응답을 다시 읽으므로 재시도하지 않는다. 카탈로그 조회부터 저장까지의 직렬화는 이 HTTP 허용량이 아니라 별도 동기화 guard가 담당한다.

### 응답 크기·메모리·GC

각 WebClient builder를 복제하여 검색·카탈로그 codec 한도를 분리한다. 중첩된 `DataBufferLimitException`도 `RESPONSE_TOO_LARGE`로 분류하며 큰 카탈로그의 일부만 저장하지 않는다. 검색 2 MiB는 테스트의 50개 숙소 × 5개 객실 × 30박 응답, 카탈로그 8 MiB는 3,000개 숙소 fixture를 수용한다. 요금제·객실 수·문자열 길이가 달라지면 같은 숙소 수라도 한도를 넘을 수 있다.

두 Supplier의 수용 호출 수와 codec 한도를 단순히 곱하면 검색 32 MiB + 카탈로그 16 MiB = 48 MiB다. 이는 실제 peak heap 상한이 아니다. 메모리에 모은 JSON tree, 값 객체, 완료 배치, DB 매핑, Netty 버퍼·HTTP 헤더, 최종 응답에 별도 비용이 든다. 요청 전체나 여러 JVM에 대한 메모리·진입 제한은 없다.

수용된 객실 ID에 필요한 이름만 `SearchOffer`에 결합하고, 응답에 전체 카탈로그를 복제하지 않는다. 건수 로그·메타데이터 조인만을 위한 임시 Offer 평탄화 목록도 만들지 않는다. 일별 가격 근거와 불변식·최종 정렬은 유지한다. 이 선택이 GC 정지 시간·처리량을 얼마나 개선했는지는 측정하지 않았다. 운영 GC 효과를 주장하려면 대표 부하에서 heap·할당량·GC pause와 전후 지연을 함께 측정해야 한다.

### 수천 개 숙소에서의 한계

Supplier당 3,000개 숙소는 50개씩 60배치다. 동시 4개이면 최소 15차례의 실행이 필요하다. 모든 배치가 약 200ms라는 단순 가정에서는 약 3초, 약 1초라면 약 15초다. 이는 DB·매핑 등 부가 비용을 제외한 계산 예시이며 p95 하나로 전체 검색 시간을 예측하는 부하 측정이 아니다. 모든 숙소를 항상 5초 안에 조회한다고 보장하지 않는다.

운영에서는 Supplier 계약상 한도와 지연, `supplier.calls.active`, 용량 거부, 연결 풀 pending, 검색 완료 시간, heap을 함께 보고 병렬도를 조정한다. 거부 건수만 보고 한도를 늘리지 않는다. 그 다음 더 큰 bulk 계약, 제품에 지역 조건이 추가된 경우 후보 축소, 최신성 저하를 허용할 수 있는 읽기 모델 순서로 검토한다. 현재 최소 계약인 전체 숙소 조회에서 임의로 대상을 줄이지 않는다.

<a id="database-budget"></a>
## DB 조회·쓰기 시간 예산

### 검색 projection과 연결 수명

검색은 숙소·객실 내부 ID와 이름, 외부 코드만 담은 읽기 전용 projection을 조회한다. 데이터 projection은 SQL 한 번이며 Entity 전체 로딩이나 숙소 수에 비례하는 N+1은 없다. 활성 객실 매핑이 없는 enabled Supplier에는 HTTP를 보내지 않고 `CATALOG_UNAVAILABLE`을 기록한다.

`ActiveCatalogMappingReader` 포트의 `long`은 epoch 시각이나 남은 Duration이 아니라 같은 JVM의 단조시계 절대 deadline이다. 검색 application은 이 시간과 `CatalogReadException`만 전달·처리하며 JPA/JDBC 트랜잭션, PostgreSQL timeout, SQLState 해석은 인프라 어댑터가 담당한다.

reader는 `REQUIRES_NEW`, `readOnly` 트랜잭션으로 연결·설정 수명을 소유한다. 연결 획득 뒤 남은 예산을 다시 계산하고 같은 연결의 `set_config(..., true)`로 다음 값을 설정한다.

```text
statement timeout = min(읽기 statement 한도, 잔여 예산)
lock timeout      = min(읽기 lock 한도, 잔여 예산)
```

설정 검증에서 lock 한도가 statement 한도 이하임을 강제한다. 예산이 소진되면 데이터 SQL을 실행하지 않고, 양수의 밀리초 미만 잔여 시간은 올림하여 0이 무제한으로 해석되지 않게 한다. 데이터 조회와 트랜잭션 종료 뒤에도 deadline을 검사한다. `JdbcTemplate`은 JPA와 같은 DataSource·트랜잭션 연결을 사용하고, transaction-local 설정은 종료 후 원래 session 값으로 돌아간다.

정상 조회는 **timeout 설정 SELECT 1개 + 데이터 projection SELECT 1개**를 실행한다. BEGIN/COMMIT 등 제어문은 제외한 개수다. 설정문은 직접 JDBC로 실행하므로 Hibernate 통계만으로 총 SQL 수를 세지 않는다. 추가 왕복은 연결 획득에 사용한 시간을 제외한 요청별 잔여 예산을 반영하는 비용이다. 연결 초기화의 고정 timeout은 왕복을 줄일 수 있지만 이 잔여 예산을 반영하지 못한다. 변경 전후 DB 구간 지연은 실측하지 않았다.

### 기본값과 각 경계의 한계

| 설정 | 초기값 | 경계 |
| --- | ---: | --- |
| Hikari maximum-pool-size | 10 | 검색·쓰기의 공유 DB 연결 수 |
| Hikari connection-timeout / validation-timeout | 500ms / 250ms | 연결 획득·유효성 검사 |
| pgJDBC connectTimeout / socketTimeout | 2초 / 15초 | 연결 수립·socket 읽기 |
| PostgreSQL session statement_timeout / lock_timeout | 10초 / 2초 | 공유 DataSource의 기본 SQL·잠금 대기 |
| catalog.database.read-statement-timeout | 1초 | 검색 projection SQL |
| catalog.database.read-lock-timeout | 300ms | 검색 SQL의 잠금 대기 |
| catalog.database.write-transaction-timeout-seconds | 10초 | Spring/Hibernate 쓰기 트랜잭션 |

쓰기에는 검색의 짧은 timeout이 남지 않으며 별도 트랜잭션 예산과 유한한 session 기본값을 사용한다. SQL 한 문장의 statement timeout은 전체 snapshot 저장 시간과 같지 않다. 운영 Flyway도 별도 DataSource를 지정하지 않아 같은 기본 한도를 받는다. migration SQL이 10초를 넘거나 잠금을 2초 넘게 기다리면 기동이 실패할 수 있으므로 장시간 DDL·백필 전에는 전용 연결·예산을 구성하고 검증한다. Testcontainers의 `@ServiceConnection`이 Flyway 전용 연결 정보를 줄 수 있어 테스트 통과만으로 운영 Flyway의 같은 timeout 적용을 검증했다고 보지 않는다.

Hikari 획득 대기는 요청별 잔여 시간으로 줄이지 않으므로 짧은 검색 예산에서는 연결 대기만으로 deadline을 넘을 수 있다. timeout 설정 SQL 자체와 통신 장애도 별도 JDBC 한도에 의존하고 socketTimeout은 HTTP 요청 전체 상한이 아니다. CPU 매핑·조립·직렬화도 강제 중단하지 않는다.

Hikari의 10개는 DB 연결 한도이며 고객 검색 진입 수·연결 대기자 수 한도가 아니다. 현재 MVC 요청 스레드는 유한하지만 별도 고객 진입 제어는 없으며, DB 연결은 정상 검색에서 Supplier HTTP 전에 반환된다. DB 10개와 Supplier 검색 8+8개만 비교해 병목을 판정할 수 없고 각 점유 시간·배치 수·동시 요청 수가 필요하다.

현재 고객 검색은 외부 트랜잭션 없이 들어온다. 이미 연결을 가진 외부 트랜잭션에서 검색을 호출하면 reader의 `REQUIRES_NEW`가 두 번째 연결을 요구하고 외부 연결은 HTTP 대기 중에도 유지될 수 있다. 검색 진입에는 이를 거부하는 `NEVER` 검사가 없고 중첩 동작을 별도로 통합 검증하지 않았다. 검색 경로를 트랜잭션으로 감싸는 변경은 이 연결 점유를 검토해야 한다.

### 예상된 DB 실패와 내부 오류

reader는 트랜잭션 시작·조회·종료 전체 바깥에서 알려진 자원 실패만 변환한다. SQL timeout·취소, 잠금 timeout, deadline 소진은 `CatalogReadException.TIMEOUT`, 연결 확보 실패·단절은 `UNAVAILABLE`이다. SQLState와 예외 타입을 사용하며 메시지 문자열로 판정하지 않는다. SQL 문법·무결성·미분류 프로그래밍 오류는 내부 오류로 남긴다.

예상된 두 실패는 공개 `CATALOG_UNAVAILABLE` 계약을 사용하고 DB 메시지·SQL은 응답에 넣지 않는다. `search.catalog.read.failures{reason=TIMEOUT|UNAVAILABLE}`, DATABASE 구간 시간과 로그를 함께 확인한다. 모든 Supplier의 `CATALOG_UNAVAILABLE`만으로 외부 시스템 여러 곳이 동시에 장애라고 판단하지 않는다.

<a id="observability"></a>
## 관측 가능성과 해석

부분 실패도 HTTP 200이므로 HTTP 성공률만으로 연동 상태를 판단하지 않는다. Actuator는 `health`, `info`, `metrics`를 노출하고 대시보드·외부 trace exporter는 추가하지 않았다.

| 지표 | 의미 |
| --- | --- |
| `supplier.calls` | 수용·거부된 호출 시도의 시간·건수. supplier/operation/outcome/failure 태그 |
| `supplier.calls.active` | Supplier·작업별 현재 허용량을 점유한 호출 수 |
| `supplier.offers` | ACCEPTED/REJECTED/UNAVAILABLE/DUPLICATE 건수 |
| `supplier.search.results` | 검색 요청별 Supplier 최종 상태 건수 |
| `supplier.search.failures` | 검색 요청별 Supplier 실패 유형. 같은 유형은 요청 안에서 한 번 |
| `search.requests` | 검색 서비스 시간·최종 상태. 내부 오류도 별도 outcome |
| `search.stages` | DATABASE/MAPPING/SUPPLIERS/ASSEMBLY 구간 시간 |
| `search.catalog.read.failures` | 예상된 DB 읽기 실패의 TIMEOUT/UNAVAILABLE 원인 |
| `supplier.catalog.skipped` | 같은 Supplier 동기화가 진행 중이어서 건너뛴 실행 |
| `supplier.catalog.sync` | HTTP 시도·retry·저장까지 포함한 Supplier 동기화 시간·결과 |
| `supplier.catalog.last.success` | 저장 성공 시각의 epoch seconds. 이 프로세스에서 미성공이면 0 |
| `supplier.catalog.consecutive.failures` | 연속 동기화 실패 수. 저장 성공 시 0 |
| `reactor.netty.connection.provider.*` | 분리한 연결 풀의 active/idle/pending 등 기본 지표 |

별도의 활성 카탈로그 매핑 수 지표와 거부 항목별 상세 사유 지표는 현재 없다. `supplier.offers{outcome=REJECTED}`는 건수, 구체 원인은 정규화 로그에서 확인한다. Supplier·작업·정해진 결과 코드처럼 종류가 제한된 태그를 사용하고 숙소·객실 코드나 예외 메시지를 태그로 넣지 않는다.

B의 HTTP 200 본문 `E503`은 호출 지표에서 FAILED/UNAVAILABLE이며 잘못된 항목만 제외한 호출은 PARTIAL/INVALID_RESPONSE다. 검색 deadline에서 취소된 진행 호출은 CANCELLED다. 시작되지 않은 배치는 호출 시도 지표에 넣지 않지만 최종 Supplier 결과에는 TIMEOUT이 남는다. `search.requests`는 검색 서비스 구간으로 고객 HTTP 전체 완료 시간을 뜻하지 않는다.

카탈로그 HTTP 성공만으로 신선도를 갱신하지 않는다. 저장 실패면 마지막 성공을 유지하고 연속 실패 수를 늘린다. 이 상태는 메모리에 있어 재시작하면 초기화된다. 마지막 성공 0을 epoch부터의 경과 시간으로 빼면 기동 직후부터 오래된 데이터로 오인하고, 무조건 제외하면 최초 동기화가 계속 실패하는 상태를 놓친다. 운영 알림은 기동 유예 기간 뒤에도 0인지, 마지막 성공 이후 경과 시간과 연속 실패 수가 어떤지를 나눠 보아야 한다. 이러한 알림 규칙 자체는 구현하지 않았다.

<a id="extension-conditions"></a>
## 감수한 제약과 확장 조건

실제로 같은 숙소가 Supplier별로 중복 노출될 수 있고 품절 상품·일별 상세를 검색 응답에서 제외한다. 이러한 정책과 최신성·정확성 요구가 바뀔 때 다음 기능을 검토한다.

| 검토 대상 | 도입 조건과 우선 접근 |
| --- | --- |
| 서킷 브레이커 | 반복 장애가 관측되고 빠른 차단이 호출 한도·고객 지연에 이점이 있을 때. [미구현 제안 설계](#circuit-breaker-design)의 판정·복구·retry 조합을 검증한 뒤 도입 |
| 가격·재고 캐시 | 허용 가능한 데이터 나이를 제품이 정했을 때. Supplier·숙소·날짜·인원 등 결과 결정 요소를 키에 포함하고 TTL·실패 결과 저장 여부·동시 갱신 정책을 명시. 예약 직전 재확인 필요 |
| 검색 재시도 | 짧은 일시 오류를 1회 재시도하는 것이 남은 검색 예산 안에서 성공률을 개선한다는 관측이 있을 때. 인증·입력·데이터 오류나 로컬 용량 부족을 무조건 재시도하지 않음 |
| 다중 동기화 인스턴스 | 자동 failover나 다중 writer가 필요할 때 PostgreSQL advisory lock 또는 분산 스케줄 잠금 검토 |
| 카탈로그 Supplier 병렬화 | Supplier 수·순차 지연이 복구 목표를 넘을 때 Supplier 단위 제한 병렬 실행 |
| 검색·쓰기 최적화 | 대표 데이터의 `EXPLAIN ANALYZE`와 쓰기 시간으로 병목을 확인한 뒤 인덱스, JDBC batch·변경분 갱신·staging 검토. `IDENTITY`의 batch 제약도 확인 |
| 영속 동기화 상태 | 재시작을 넘어 lastAttempt/lastSuccess·상태·건수나 정상 빈 목록을 구분해야 할 때 상태 테이블 도입 |
| 정규화 실패 격리 저장 | 구조화 로그만으로 반복 오류 분석이 어려워질 때 보존 대상·기간·민감 값 처리 정책과 함께 검토 |
| 표준 숙소·통화·요금제 | 공통 식별 근거, 환율·반올림, 취소·결제 조건과 상품 식별 계약이 생겼을 때 각각 확장 |
| 예약·취소 | 실제 실행 요구가 생기면 재고·가격 재확인, 멱등성·보상 정책을 함께 설계 |

캐시·서킷 브레이커의 운영 수치·라이브러리는 확정하지 않았다. 캐시는 실패한 Supplier의 값을 조용히 최신 결과처럼 반환하는 정책과 다르므로, 오래된 값을 허용하면 응답에 그 의미를 표현해야 한다. 아래 서킷 브레이커 수치는 후속 실험을 위한 잠정안이다.

현재 Supplier는 날짜·인원·숙소 코드로 조회하는 pull API이고 변경 이벤트를 주지 않는다. WebSocket은 내구성 있는 retry를 제공하지 않으며, polling 결과를 MQ·Kafka에 넣어도 기존 Supplier 호출이 사라지지 않는다. 변경 이벤트를 여러 서비스가 소비하고 검색 fan-out이 병목이며 데이터 지연을 허용할 때 이벤트 읽기 모델을 검토한다. 브로커·소비자가 실제로 생기고 DB 변경과 발행을 함께 보장해야 할 때만 outbox를 고려한다.

50개 분할은 고객 요청 안의 HTTP 배치이며 영속 실행 작업이 아니므로 Spring Batch를 사용하지 않는다. 카탈로그가 대규모 페이지 기반으로 바뀌고 체크포인트 재시작이 필요해지면 검토한다. 전체 WebFlux 전환, 다중 JVM 합산 호출 한도, 고객 진입 제어·연결 종료 시 즉시 취소, 생산 환경 부하 시험은 현재 구현으로 해결했다고 주장하지 않는다.

<a id="circuit-breaker-design"></a>
### 서킷 브레이커 제안 설계 — 미구현

다음은 반복 장애에서 이미 실패할 가능성이 큰 외부 호출을 잠시 멈추기 위한 **후속 설계안**이다. 현재 코드·설정·의존성에는 서킷 브레이커가 없고 `SupplierFailureType`에 `CIRCUIT_OPEN`도 없다. 아래 상태·수치·오류 계약·검증은 아직 구현하거나 시험한 결과가 아니다.

회로는 **Supplier × 작업(SEARCH/CATALOG) × JVM**마다 분리한다. A 검색 장애가 B나 A 카탈로그를 차단하지 않으며 여러 JVM의 상태는 공유하지 않는다. 요청별·JVM별 호출 허용량과 timeout은 계속 필요하다. 상태 모델·실패율·느린 호출 비율·제외 예외의 의미는 [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker)를 참고하되, 다음 수치는 이 서비스에서 검증할 가설이다.

| 잠정 정책 | 검색 | 카탈로그 |
| --- | --- | --- |
| CLOSED 관측 창 / 최소 유효 표본 | 최근 60초 / 20시도 | 최근 10시도 / 3시도 |
| OPEN 전환 조건 | 최소 표본 충족 후 실패율 **50% 이상 또는** 느린 호출 비율 **50% 이상** | 동일 |
| 느린 호출 판정 | 시도 시간 **1.5초 초과** | 시도 시간 **2.5초 초과** |
| OPEN 최소 대기 | 30초 | 60초 |
| HALF_OPEN 허용 탐색 수 / 최대 체류 | 3시도 / 10초 | 1시도 / 5초 |

검색은 단발성 오류에 반응하지 않도록 20개 표본을 요구하고, 카탈로그는 10분 주기라 짧은 시간창에서 표본이 사라지지 않도록 건수 창을 제안한다. 건수 창에는 오래된 결과가 남을 수 있고 한 동기화의 retry도 각각 표본이 된다는 절충이 있다. 느림 기준은 현재 호출 timeout 3초·4초에 닿기 전의 지연 악화를 관측할 초기값이며 p95를 측정해 도출한 값은 아니다. 시도 시간은 애플리케이션 호출 허용량을 얻은 뒤 client Publisher를 구독하는 시점부터 응답 판정까지다. 연결 풀 대기·연결·수신·변환 시간이 포함되며 회로 거부·retry backoff·DB 저장은 포함하지 않는다. 따라서 네트워크 지연만 뜻하지 않는다. 최소 표본 미달일 때는 회로를 열지 않고 기존 timeout·허용량으로 보호한다.

OPEN 대기 뒤 **다음 실제 요청**이 HALF_OPEN을 시작하며 별도 probe 스케줄러는 두지 않는다. 따라서 카탈로그의 60초가 지나도 호출이 없으면 자동으로 HTTP를 보내지 않고 다음 기본 10분 스케줄에서 1회 탐색한다. 복구 확인은 그만큼 늦을 수 있다. 더 빠른 복구 목표가 생기면 Supplier 한도와 단일 동기화 guard를 지키는 별도 probe를 설계해야 한다. HALF_OPEN은 CLOSED 때의 표본과 분리하여 탐색 결과만 집계한다. 유효 탐색이 모두 끝나 두 비율이 모두 50% 미만이면 CLOSED로 돌아가고 과거 관측 창을 비운다. 검색 3회 중 실패와 느림이 각각 1회 이하면 각각 최대 33.3%이므로 닫히지만, 어느 한쪽이 2회면 다시 열린다. 카탈로그는 한 번이라도 실패하거나 느리면 다시 열린다. 최대 체류 안에 유효 탐색 수를 채우지 못해도 OPEN으로 돌아가되, 이는 탐색 미완료 정책이며 외부 실패 표본을 추가하지 않는다.

| 회로 판정 대상 | 제안 집계 |
| --- | --- |
| HTTP 5xx, B 본문 `E500`·`E503`, 실제 외부 연결 실패·연결/읽기/개별 호출 timeout | 실패 표본. HTTP 200인 B 본문 오류도 성공으로 세지 않음 |
| HTTP 429 / B `E429` | 외부의 요청 수용 불가 신호로 실패 표본에 포함. 현재 즉시 retry 금지는 유지하며 `Retry-After` 처리는 별도 정책으로 검토 |
| 정상 응답, 정상 빈 검색 결과, 개별 Offer 거부를 포함해 정상 완료한 검색 응답 | 가용성 성공 표본. 모든 항목이 거부되어도 같은 기준이며, 데이터 품질은 기존 `INVALID_RESPONSE`·부분 성공·거부 지표로 별도 관측 |
| 로컬 `CAPACITY_EXCEEDED`(허용량·연결 풀), DB 읽기·저장 실패, 입력·인증 오류, 전체 응답 구조의 `INVALID_RESPONSE` 예외·`RESPONSE_TOO_LARGE`, `INTERNAL_ERROR`·`UNKNOWN` | 회로의 실패·성공 표본 모두에서 제외. 원인별 기존 지표·로그는 유지 |
| 아직 외부 연결 시도도 하지 않은 배치, 전체 검색 예산·고객 취소에 의한 중단, OPEN/HALF_OPEN의 호출 거부 | 외부 성공·실패를 판정하지 않음. 미시작·취소·회로 거부를 별도 관측하고 획득한 자원·탐색 허용량 정리 |

제외된 사건은 실패율·느린 호출 비율의 **분자와 분모 모두에서 빠져야 한다**. 특히 로컬 용량 부족을 성공으로 바꾸어 실패율을 낮추지 않는다. 외부 연결 시도 중 연결 실패는 HTTP 요청을 아직 전송하지 못했어도 외부 실패 표본이며, 시작하지 못한 대기 배치와 구분한다. 전체 예산 때문에 취소된 호출을 Supplier의 개별 timeout과 섞지 않는다. 전체 응답 구조·크기 오류와 인증 오류는 일시적 가용성 회로로 해결하기보다 계약·설정을 바로잡을 사건으로 분리한 잠정 정책이다. DB 저장·대량 누락 snapshot 거부는 HTTP 관측 밖의 사건으로, 이미 성공한 HTTP 시도의 표본을 취소하지 않는다. 같은 `INVALID_RESPONSE`라도 항목 거부·envelope 실패·저장 전 안전 검사에서 발생하므로 enum 하나만 보고 회로 표본을 결정하지 않는다.

카탈로그는 `retry { 회로 허용 확인 → 호출 허용량 획득 → timeout을 적용한 HTTP·본문 판정 → 자원 반환 }` 순서로 **매 시도마다** 회로를 확인한다. B 본문 실패 변환도 회로 관측 안에 둔다. CLOSED에서는 기존 최대 2회 retry를 유지하지만 OPEN 거부와 HALF_OPEN 탐색에는 추가 retry를 하지 않아 호출·탐색 수가 늘어나지 않게 한다. backoff·DB 저장은 회로의 HTTP 시도 시간 밖이다. 검색은 계속 retry하지 않는다. 정상·오류·취소·호출 전 동기 예외·허용량 거부에서 호출 허용량과 사용하지 못한 탐색 허용량을 정확히 반환하도록 연결하며, 회로를 도입했다고 동기 CPU 작업까지 강제 중단되는 것은 아니다.

회로 거부를 구별하려면 도입 시 전용 `CIRCUIT_OPEN` 실패 유형과 고객 계약·지표를 함께 추가한다는 제안이다. 성공 배치·다른 Supplier 결과는 보존하여 일부만 실패하면 `200 PARTIAL`, 모든 활성 Supplier가 실패하면 `503 FAILED`를 유지한다. 정상 빈 응답도 성공이라는 기존 기준을 바꾸지 않는다. 카탈로그 거부는 기존 매핑과 마지막 저장 성공 시각을 유지하되 동기화 실패로 기록하고 연속 실패 수를 늘린다. 연속 실패 수를 바꾸지 않는 동기화 guard의 중복 실행 skip과 구분한다.

도입 전 수용 검증은 다음을 포함한다. 현재 수행한 테스트 목록과 구분한다.

- 최소 표본 직전·직후와 50% 경계, 실패·느림의 OR 조건, 제외 사건의 분모·분자 불변, B 본문 실패를 검증한다.
- Supplier·작업·JVM 격리, OPEN에서 HTTP 미전송, 대기 후 탐색 수 제한, HALF_OPEN의 성공·실패·미완료 복귀와 CLOSED 창 초기화를 확인한다.
- 카탈로그 10분 주기와 retry를 가상 시간으로 재현하고 OPEN·HALF_OPEN에서 재시도가 탐색을 늘리지 않는지 확인한다.
- 동시 호출·timeout·취소·동기 예외·로컬 용량 부족 후 허용량 누수 없이 다음 정상 호출이 회복되는지 확인한다.
- 실제 HTTP·DB 경로에서 정상 결과 보존, `PARTIAL`/`FAILED`, 카탈로그 기존 ID·마지막 저장 성공 유지와 새 오류 유형 직렬화를 검증한다.

<a id="verification"></a>
## 검증 근거

| 검증 경계 | 정식 테스트 |
| --- | --- |
| 금액·날짜·재고·인원·불변식 | `PriceTests`, `StayInventoryTests`, `SearchCriteriaTests`, `OfferCandidateTests` |
| 후보 오류 격리·내부 mapper 오류 | `OfferNormalizerTests`, `SupplierMappingFailureTests` |
| A/B 프로토콜·실제 Boot codec·중복·수용 인원 충돌·특수 코드·응답 크기 | `SupplierAdapterContractTests`, `SupplierSearchClientTests`, `SupplierCatalogClientTests` |
| HTTP·B 본문·transport 오류와 retry 가능 여부 | `SupplierFailureMapperTests`, `SupplierAdapterContractTests` |
| 분할·병렬성·deadline·부분/전체 실패·메타데이터 결합 | `IntegratedSearchServiceTests` |
| 고객 JSON·입력 400·부분 200·전체 503 | `StaySearchControllerTests` |
| snapshot 검증·누락·재활성화·client 계약·retry·저장 지표 | `CatalogSnapshotTests`, `CatalogSnapshotWriterTests`, `CatalogSynchronizationServiceTests` |
| 실제 PostgreSQL commit/rollback·ID 유지·Supplier별 독립 반영 | `CatalogCommitBoundaryTests`, `CatalogRepositoryTests` |
| PostgreSQL 잠금·느린 SQL·연결 부족·회복·transaction-local 설정 복원 | `CatalogReadTimeoutIntegrationTests`, `JpaActiveCatalogMappingReaderTests` |
| 서비스 직접 호출의 중복 guard·조회/retry/저장 보호 | `CatalogSynchronizationConcurrencyTests` |
| 허용량 반환·실제 HTTP와 연결 풀 격리·취소·큰 응답·지표 | `SupplierCallResourcesTests`, `SupplierResourceIntegrationTests` |
| 별도 메인/Mock JVM·PostgreSQL·스케줄 commit·고객 HTTP 전체 연결 | `SupplierHubEndToEndTests` (`e2eTest`) |

E2E는 PostgreSQL 17 Testcontainers와 메인·Mock 실행 JAR을 별도 JVM으로 실행한다. 정상 1박·3박 가격과 연박 재고, DB 내부 ID·이름, 인원 초과, 입력 400, A HTTP·B 본문 오류, 전체 503, 지연 timeout, 실제 DB 잠금과 회복을 확인한다. 직접 서비스 호출이나 HTTP 테스트 대역만으로 이 연결을 대신하지 않는다. 임시 디렉터리·자동 배정 포트를 사용하고 종료 시 자원을 정리한다.

`./gradlew test`는 메인·Mock 테스트, `./gradlew check`는 E2E까지 실행한다. 최근 실행 결과와 재사용 여부는 [개발 기록](../JOURNAL.md)과 [전체 연결 검증](e2e-verification.md)에 남기며 이 설계 문서에 시점별 테스트 건수를 중복 기록하지 않는다. 자원 테스트에서 낮춘 한도의 격리·반환을 확인한 결과는 기본값의 운영 처리 용량 측정과 다르다. 단일 실행 시간과 fixture 수용을 실제 부하 용량·GC 개선·엄격한 HTTP 완료 상한의 증명으로 사용하지 않는다.
