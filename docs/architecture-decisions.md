# 아키텍처 결정 기록

## 1. 문서 목적

이 문서는 Supplier 연동 서비스의 핵심 설계 결정과 그 근거를 기록한다. 특히 다음 질문에 답하는 것을 목표로 한다.

- 서로 다른 Supplier의 상품을 내부에서 어떤 의미로 통일하는가?
- 어떤 정보는 공통 모델에 포함하고 어떤 정보는 제외하는가?
- 외부 연동 실패가 고객 검색 전체 실패로 번지지 않게 어떻게 처리하는가?
- 지금 구현하지 않는 기능은 무엇이며, 어떤 조건이 생기면 다시 검토하는가?

첫 번째 구현은 다음 흐름이 처음부터 끝까지 동작하는 데 집중한다.

1. Supplier의 숙소·객실 타입 목록을 동기화한다.
2. Supplier 코드와 내부 식별자의 매핑을 DB에 저장한다.
3. 고객 검색 요청이 들어오면 활성 매핑을 Supplier별로 조회한다.
4. `WebClient`로 각 Supplier의 재고·요금을 병렬 조회한다.
5. 유효한 응답을 내부 `Offer`로 정규화한다.
6. 정상 결과를 반환하면서 부분 실패 사실을 함께 알린다.

인증·인가, 결제, 관리자 기능, 프론트엔드, 지역 검색, 정렬, 페이징, Supplier 간 동일 숙소 판별, 실제 예약 실행은 첫 번째 구현 범위에서 제외한다.

## 2. 결정 요약

| 주제 | 확정한 결정 |
| --- | --- |
| Supplier 간 숙소 식별 | Supplier가 다르면 각각 별도의 내부 숙소로 관리한다. |
| 판매 상품 단위 | 검색 조건에 대한 객실 타입 판매 조건을 `Offer`로 정의한다. |
| 응답 구조 | `stays[].roomTypes[].offers[]` 구조로 반환한다. |
| 내부 식별자 | DB가 생성하는 `BIGINT`를 사용한다. |
| 매핑 안정성 | Supplier 코드 조합에 고유 제약을 두고 기존 행을 갱신한다. |
| 가격 기준 | 세금 포함 숙박 전체 총액과 통화를 필수로 한다. |
| 날짜별 가격 | Supplier가 제공하면 내부에서 선택적으로 보존하되 검색 응답에는 노출하지 않는다. |
| 연박 재고 | 요청한 모든 숙박일의 재고 최솟값을 사용한다. |
| 품절 상품 | 예약 가능 객실 수가 0인 Offer는 제외한다. |
| 잘못된 Supplier 데이터 | 분리 가능한 경우 잘못된 Offer만 제외하고 정상 Offer는 유지한다. |
| 부분 실패 | HTTP 200과 `PARTIAL` 상태를 반환한다. |
| 전체 실패 | HTTP 503과 `FAILED` 상태를 반환한다. |
| 목록 동기화 | 기동 시 즉시 실행하고 이후 주기적으로 실행한다. |
| 동기화 재시도 | 회복 가능한 일시적 오류만 제한적으로 재시도한다. |
| 목록에서 사라진 상품 | 삭제하지 않고 비활성화하여 내부 ID를 보존한다. |
| 대량 목록 누락 | 누락 10개 이상이면서 기존 활성 목록의 50%를 초과하면 snapshot 전체를 격리한다. |
| Supplier 운영 상태 | Supplier별 `enabled` 설정으로 계약 종료·점검 대상을 호출 및 결과 집계에서 제외한다. |
| 실시간 검색 재시도 | 첫 번째 구현에서는 재시도하지 않는다. |
| 비동기 인프라 | 검색에 WebSocket, MQ, Kafka, Spring Batch를 사용하지 않는다. |
| 요청당 숙소 수 제한 | Supplier별 숙소 코드를 최대 50개씩 나눈다. |
| 동시 호출 수 | Supplier별 최대 4개 요청만 동시에 실행한다. |
| 검색 제한 시간 | 연결 500ms, 응답 2초, 호출 전체 3초, 검색 전체 5초를 초기값으로 사용한다. |
| 통화 | 원본 통화와 최소 단위 정수를 보존하고 환산하지 않는다. |
| 입력 검증 | 날짜 순서와 인원 범위를 검증하고 전체 인원 1명 이상을 요구한다. |
| 실행 모델 | Spring MVC와 JPA를 사용하고 Supplier I/O만 `WebClient`로 병렬 처리한다. |

## 3. 도메인 모델

### 3.1 숙소와 객실 타입

`Property`와 `RoomType`은 비교적 천천히 변하는 Supplier 카탈로그 정보다. 재고·요금 API를 호출하려면 Supplier 코드 목록을 먼저 알고 있어야 하므로 DB에 저장한다.

```text
Property
- id: Long
- supplier: Supplier
- supplierPropertyCode: String
- name: String
- active: boolean
- consecutiveMissingCount: int
- createdAt: Instant
- updatedAt: Instant

RoomType
- id: Long
- propertyId: Long
- supplierRoomTypeCode: String
- name: String
- maxOccupancy: int
- active: boolean
- consecutiveMissingCount: int
- createdAt: Instant
- updatedAt: Instant
```

필요한 고유 제약은 다음과 같다.

```text
Property: UNIQUE(supplier, supplier_property_code)
RoomType: UNIQUE(property_id, supplier_room_type_code)
```

객실 타입 코드는 전체 시스템이나 Supplier 안에서 유일하다고 가정하지 않는다. 반드시 소속 숙소와 함께 식별한다.

### 3.2 Supplier 간 동일 숙소 병합 제외

서로 다른 Supplier의 숙소는 이름과 객실 구성이 비슷하더라도 서로 다른 내부 숙소 ID를 가진다.

현재 데이터에는 동일한 실제 숙소임을 보장하는 공통 키가 없다. 이름 기반 병합을 도입하면 다음 정책이 추가로 필요하다.

- 문자열 정규화와 유사도 기준
- 주소 등 보조 정보가 없을 때의 판정 방식
- 오탐과 누락 처리
- 신뢰도 임계값
- 수동 검수와 예외 관리
- 병합 이후 다시 분리하는 절차

또한 비슷한 객실이라도 조식이나 판매 조건이 다를 수 있다. 불명확한 기준으로 병합하면 서로 다른 상품을 하나로 오인할 수 있다.

향후 동일 숙소 병합이 필요해지면 Supplier 매핑 위에 별도의 표준 숙소 계층을 추가한다. 현재 Supplier 매핑을 삭제하거나 덮어쓰는 방식으로 구현하지 않는다.

### 3.3 판매 상품은 Offer

`Offer`는 하나의 Supplier 객실 타입을 특정 검색 조건으로 조회했을 때 얻은 판매 조건이다.

```text
Offer
- propertyId
- roomTypeId
- supplier
- maxOccupancy
- availableRooms
- breakfastIncluded
- price
```

가격, 재고, 조식 여부는 숙소의 고정 속성이 아니라 검색 시점의 Offer 속성이다. 같은 객실 타입에 조식, 환불, 결제 조건이 다른 상품이 추가되더라도 여러 Offer로 표현할 수 있다.

내부 정규화와 실패 격리는 Offer 단위로 수행한다. 고객 응답에서는 중복을 줄이고 계층을 드러내기 위해 다음처럼 묶는다.

```text
stays[]
└── roomTypes[]
    └── offers[]
```

응답을 그룹화하더라도 가격 계산과 재고 판정의 단위는 Offer로 유지한다.

## 4. 내부 식별자와 매핑 생명주기

숙소와 객실 타입의 내부 ID는 DB가 생성하는 `BIGINT`를 사용한다. UUID도 검토했지만 현재 구조는 하나의 애플리케이션과 하나의 관계형 DB를 사용하므로 분산 ID 생성이나 여러 DB 병합이 필요하지 않다.

기존 상품 여부는 내부 ID가 아니라 Supplier 고유 키로 확인한다.

```text
최초 동기화
(Supplier A, 숙소 코드 X)가 없음
→ 새 행 삽입
→ 내부 ID 10 발급

재동기화
(Supplier A, 숙소 코드 X)가 존재함
→ 같은 행의 이름과 상태를 갱신
→ 내부 ID 10 유지
```

Supplier 코드는 연동 계층의 데이터다. 고객 응답에는 내부 ID만 노출한다.

## 5. 가격 정규화

### 5.1 공통 가격 의미

공통 가격은 다음 의미로 정의한다.

> 요청한 숙박 기간 동안 객실 1실을 이용할 때 고객이 결제하는 세금 포함 전체 금액

```text
Money
- currency: ISO 4217 Currency
- amount: 통화 최소 단위 long

Price
- totalAmount: Money
- nightlyBreakdown: optional List<NightlyPrice>
```

날짜별 세전 가격과 세금을 주는 Supplier는 각 날짜의 두 값을 더한 뒤 숙박일 전체를 합산한다. 이미 세금이 포함된 숙박 전체 총액을 주는 Supplier는 그 값을 그대로 사용한다.

이미 세금이 포함된 총액에 세금을 다시 더하지 않는다. 숙박 전체 금액을 숙박일수로 나누어 날짜별 가격을 만들어내거나, 제공되지 않은 세금액을 추정하지 않는다.

### 5.2 날짜별 가격 상세

Supplier가 날짜별 상세를 제공하면 Supplier DTO를 그대로 보존하지 않고 다음 내부 형태로 바꾼다.

```text
NightlyPrice
- date
- baseAmount: Money
- taxAmount: Money
- totalAmount: baseAmount + taxAmount
```

모든 Supplier가 날짜별 가격을 제공하는 것은 아니므로 이 정보는 선택 값이다.

첫 번째 통합 검색 응답은 `currency`와 `totalAmountIncludingTax`만 노출한다. 필드명으로 세금 포함 숙박 전체 총액이라는 공통 의미를 드러내되, 전체 숙소 검색에서 모든 Offer에 숙박일별 데이터를 붙이면 응답 크기가 상품 수와 숙박일수의 곱만큼 증가하므로 날짜별 상세는 반환하지 않는다.

날짜별 상세가 필요한 상품 상세 기능을 추가할 때는 가격 상세 제공 여부를 명시하는 계약을 별도로 설계한다. Supplier가 제공하지 않은 정보는 그때도 추정하지 않는다.

### 5.3 통화

Supplier가 반환한 ISO 4217 통화와 해당 통화의 최소 단위 정수를 그대로 보존한다. 첫 번째 구현에서는 환산하지 않으며 통화가 다른 금액을 숫자만으로 비교하지 않는다.

환산 기능에는 환율 출처, 적용 시각, 반올림, 수수료, 표시 가격과 결제 가격의 관계가 필요하다. 향후 도입하더라도 원본 금액은 함께 보존한다.

## 6. 재고 정규화

체크아웃일은 숙박일에 포함하지 않는다. 연박 Offer의 예약 가능 객실 수는 요청한 모든 숙박일에 대한 일별 재고의 최솟값이다.

최솟값을 계산하기 전에 다음 조건을 검증한다.

- 체크인일부터 체크아웃 전날까지 모든 날짜가 정확히 한 번씩 존재한다.
- 요청 기간 밖의 날짜가 없다.
- 재고와 가격이 음수가 아니다.
- 통화와 필수 식별자가 존재한다.
- 반환된 숙소와 객실 타입 코드에 활성 내부 매핑이 있다.
- Supplier별 성공 응답 규약을 만족한다.

재고 최솟값이 0이면 Offer를 결과에서 제외한다. Offer가 남지 않은 객실 타입을 제거하고, 객실 타입이 남지 않은 숙소도 최종 응답에서 제거한다.

정상적으로 검색했지만 예약 가능한 상품이 없는 상황과 검색 자체를 수행하지 못한 상황은 구분한다.

```text
모든 Supplier 조회 성공, 예약 가능 상품 없음
→ HTTP 200, COMPLETE, stays=[]

모든 Supplier 조회 실패
→ HTTP 503, FAILED, stays=[]
```

## 7. 잘못된 Supplier 데이터 처리

안전하게 검증할 수 있는 가장 작은 범위까지만 실패시킨다.

다음과 같이 응답을 독립된 Offer 목록으로 해석할 수 없는 경우에는 해당 호출 묶음 전체를 실패 처리한다.

- 연결 실패 또는 응답 실패
- JSON 전체 파싱 실패
- Supplier 수준의 실패 코드
- 성공 응답인데 필수 데이터 컨테이너가 없음

응답 전체는 읽을 수 있고 오류가 특정 항목에 국한된다면 해당 Offer만 제외한다.

- 요청 날짜 누락·중복·범위 초과
- 음수 재고 또는 금액
- 알 수 없는 숙소·객실 매핑
- 잘못된 통화
- Supplier 규약과 맞지 않는 상품 값

정상인 형제 Offer는 유지한다. 하나 이상의 Offer가 제외되면 이후 통합 검색 서비스가 해당 Supplier 결과를 `PARTIAL`로 표시하고, 수용·제외 개수와 공통 실패 유형을 제공한다. Offer 도메인은 Supplier 상태를 직접 계산하지 않는다.

Offer 정규화 결과는 예약 가능한 Offer 목록, 잘못되어 거부된 Offer 수, 정상 데이터지만 검색 조건에 맞지 않아 제외된 Offer 수를 구분한다. 품절과 요청 인원을 수용할 수 없는 객실은 Supplier 데이터 오류가 아니라 정상 제외로 세며, 이 건수만으로 `PARTIAL`을 만들지 않는다. 거부 건수가 있으면 이후 통합 검색 서비스가 공통 실패 유형 `INVALID_RESPONSE`와 `PARTIAL` 상태를 계산한다.

Supplier 어댑터는 원본 응답 항목, 호출 출처 Supplier, 변환 함수를 정규화 경계에 전달한다. DTO 검증부터 `Money`, `Price`, `OfferCandidate`, `Offer` 생성까지 후보별 보호 구간 안에서 실행하여 잘못된 통화나 음수 금액처럼 중간 값 생성 중 발생한 오류도 형제 Offer와 격리한다. 거부된 후보는 application 계층에서 호출 출처 Supplier, 후보 Supplier, 항목 순번, 사용 가능한 내부 식별자와 예외를 구조화 로그로 남긴다. 호출 출처와 후보 Supplier가 다르면 계약 위반으로 거부한다.

`OfferCandidate`는 도메인 변환이 끝난 완전한 후보이므로 생성 시 필수 값과 컬렉션을 검증한다. 최종 `Offer`는 검증 팩토리만 사용할 수 있는 private 생성자의 불변 객체로 두어 검색 조건과 날짜 검증을 우회할 수 없게 한다.

고객 응답에는 Supplier 원본 코드나 내부 예외 메시지를 노출하지 않는다. 상세 원인은 구조화 로그에 남긴다.

## 8. 검색 응답과 실패 정책

### 8.1 응답 구조

고객 상품과 연동 상태를 분리한다.

최소 고객 검색 계약은 다음과 같이 고정한다.

```http
GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0
```

검색 조건은 날짜와 인원만 받는다. 응답 필드와 구조는 아래 계약을 따른다.

```json
{
  "status": "COMPLETE",
  "searchCriteria": {
    "checkIn": "2026-10-01",
    "checkOut": "2026-10-03",
    "adults": 2,
    "children": 0
  },
  "stays": [
    {
      "stayId": 10,
      "stayName": "Example Stay",
      "roomTypes": [
        {
          "roomTypeId": 20,
          "roomTypeName": "Example Room",
          "maxOccupancy": 2,
          "offers": [
            {
              "supplier": "SUPPLIER_A",
              "availableRooms": 1,
              "breakfastIncluded": false,
              "price": {
                "currency": "KRW",
                "totalAmountIncludingTax": 250000
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
      "unavailableOfferCount": 0,
      "failureTypes": []
    }
  ]
}
```

배열 값은 결과가 없을 때 `null` 대신 빈 배열로 반환한다. Supplier 숙소 코드와 객실 타입 코드는 고객 응답에서 제외한다.

### 8.2 전체 상태와 Supplier 상태

```text
전체 검색 상태: COMPLETE | PARTIAL | FAILED
Supplier 상태: SUCCESS | PARTIAL | FAILED
```

| 상황 | HTTP 상태 | 전체 상태 |
| --- | ---: | --- |
| 모든 조회 대상이 정상 완료됨 | 200 | `COMPLETE` |
| 하나 이상 정상 완료되고 일부가 실패함 | 200 | `PARTIAL` |
| 모든 조회 대상이 실패함 | 503 | `FAILED` |

Supplier가 정상적으로 빈 결과를 반환한 경우도 성공으로 계산한다. 예를 들어 A는 정상적으로 예약 가능한 상품이 없고 B는 타임아웃이라면 `200 PARTIAL`이다. 확인하지 못한 B의 결과까지 품절이라고 단정하지 않는다.

`206 Partial Content`는 범위 요청에 사용하는 상태이므로 Supplier 부분 실패를 표현하는 데 사용하지 않는다.

### 8.3 공통 실패 분류

Supplier별 HTTP 상태와 본문 실패 코드는 다음 공통 분류로 변환한다.

```text
INVALID_REQUEST
AUTHENTICATION_FAILED
RATE_LIMITED
UNAVAILABLE
TIMEOUT
INVALID_RESPONSE
CATALOG_UNAVAILABLE
CAPACITY_EXCEEDED
RESPONSE_TOO_LARGE
INTERNAL_ERROR
UNKNOWN
```

모든 Supplier가 실패하더라도 원인이 서로 다를 수 있다. 전체 응답은 HTTP 503으로 표현하고 세부 원인은 `supplierResults`에 남긴다.

## 9. 카탈로그 동기화

### 9.1 실행 시점과 장애 복구

카탈로그 동기화는 애플리케이션 기동 시 즉시 실행하고 이후 설정된 주기로 반복한다. Supplier별 작업은 독립적으로 수행한다.

계획된 점검처럼 외부 호출을 일시 중지해야 하는 Supplier는 `supplier.<id>.enabled=false`로 설정한다. 비활성 Supplier는 카탈로그 동기화와 실시간 검색, 검색 상태 집계에서 함께 제외하지만 기존 DB 매핑은 비활성화하거나 삭제하지 않고 보존한다. 따라서 이 설정은 계약 종료에 따른 데이터 정리 수단이 아니다. 계약 종료에는 별도의 매핑 비활성화 절차가 필요하며, 다시 활성화할 때는 최신 카탈로그 동기화가 성공한 뒤 검색에 참여시키는 activation gate를 후속 운영 기능으로 검토한다. 활성 Supplier에 대응하는 검색 client가 없으면 고객 요청 시점까지 미루지 않고 애플리케이션 시작을 실패시켜 배포 설정 오류를 드러낸다.

다시 시도하면 회복될 가능성이 있는 오류만 짧은 백오프로 제한적으로 재시도한다.

재시도 대상:

- 연결 실패
- 응답 타임아웃
- 일시적인 서버 장애
- 호출 한도 오류에 대해 충분한 대기 조건을 적용할 수 있는 경우

즉시 재시도하지 않는 대상:

- 잘못된 요청
- 인증 실패
- 구조적으로 잘못된 응답

재시도를 모두 소진하면 기존 매핑을 보존한다. 사용 가능한 기존 매핑이 하나도 없는 Supplier는 검색에서 `CATALOG_UNAVAILABLE`로 표시한다. 이후 주기 동기화가 성공하면 자동으로 검색 대상에 복귀한다.

동기화 주기와 재시도 값은 설정으로 분리한다. 개발 환경에서는 장애 복구를 확인하기 쉬운 10분을 초기 주기로 고려하고, 운영 값은 Supplier 호출 제한, 목록 변경 빈도, 복구 목표에 따라 조정한다.

### 9.2 Upsert와 비활성화

정상적인 전체 목록을 받았을 때 다음 순서로 반영한다.

1. 새 숙소와 객실 타입을 삽입한다.
2. 기존 매핑의 이름과 수용 인원을 갱신한다.
3. 다시 등장한 비활성 매핑을 기존 ID로 활성화한다.
4. 이전에는 활성 상태였지만 이번 전체 목록에 없는 항목은 누락 횟수를 기록한다.
5. 정상적인 전체 목록에서 두 번 연속 누락된 항목만 비활성화한다.

비활성 매핑은 하드 삭제하지 않는다. 나중에 같은 상품이 다시 등장했을 때 기존 내부 ID를 유지하기 위해서다.

목록 호출이 실패했거나 응답이 불완전하다면 snapshot 전체를 반영하지 않고 기존 매핑과 누락 횟수를 유지한다. 항목의 부재는 완전한 전체 목록을 받았을 때만 의미가 있다.

정상 snapshot에서 처음 누락된 숙소나 객실 타입은 `consecutive_missing_count=1`로 기록하되 검색 대상에는 유지한다. 다음 정상 snapshot에서도 연속으로 누락되면 2로 올리고 비활성화한다. 그 전에 다시 등장하면 누락 횟수를 0으로 초기화한다. 숙소 전체가 누락된 경우에는 숙소의 누락 횟수만 올린다. 숙소가 비활성화되면 검색 조회가 하위 객실 타입도 함께 제외하므로 동일한 부재를 객실 타입에 중복 기록하지 않는다.

이 정책은 Supplier를 상품 정보의 원천으로 신뢰하면서도, 한 번의 성공 응답이 완전한 전체 목록이었다는 보장은 별도로 확인하려는 절충이다. 비활성화 전까지 남은 매핑은 실시간 검색 호출 대상일 뿐이며 Supplier가 판매 가능한 Offer를 반환하지 않으면 고객 결과에는 노출되지 않는다.

기존 활성 숙소가 있는데 새 숙소 목록이 0건이거나, 기존 활성 객실 타입이 있는데 해당 숙소의 새 객실 타입 목록이 0건이면 장애성 빈 응답일 가능성이 있다고 판단한다. 이 경우 전체 반영을 보류한다. 실제로 모든 상품을 제거해야 하는 상황은 별도 확인 절차 없이 자동 반영하는 것보다 기존 매핑을 보존하는 쪽을 우선한다.

빈 목록이 아니더라도 대형 부분 응답은 2회 연속 누락 정책만으로 막을 수 없다. 따라서 숙소와 객실 타입 각각에 대해 기존 활성 매핑이 전부 누락되면 개수와 무관하게 snapshot 전체를 격리한다. 일부 누락은 누락 수가 10개 이상이면서 기존 활성 매핑의 50%를 초과할 때 격리한다. 두 임계값은 설정으로 분리한 초기 안전값이며, 정상 변동 분포를 관측해 Supplier별 값이나 관리자 승인 절차로 발전시킨다. 임계값 아래의 일반적인 소규모 변경에는 기존 2회 연속 누락 정책을 적용한다.

고객 검색은 숙소와 객실 타입이 모두 활성인 매핑만 사용한다. 동기화 조회는 비활성 매핑도 포함하여 기존 ID로 재활성화할 수 있어야 한다.

## 10. Supplier 호출, 분할, 제한 시간

### 10.1 어댑터 경계

Supplier마다 독립된 어댑터와 전송 DTO를 둔다. Supplier별 요청 파라미터 이름, 응답 껍데기, 성공·실패 표현은 어댑터 밖으로 노출하지 않는다. 검색 서비스는 공통 Supplier 포트에만 의존한다.

한 Supplier는 HTTP 상태로 실패를 표현하고 다른 Supplier는 HTTP 200 응답 본문의 코드로 실패를 표현할 수 있다. 두 경우 모두 정규화 전에 공통 실패로 변환한다.

공통 검색 포트에는 검색 조건과 활성 내부 매핑으로 구성된 Supplier별 호출 묶음을 전달한다. 호출 묶음은 1개 이상 50개 이하의 숙소 매핑만 허용하고, 숙소 및 객실 타입의 외부 코드 중복을 먼저 거부한다. 현재 두 Supplier 모두 숙소 코드를 쉼표로 연결해 전송하므로 구분자와 혼동되는 쉼표 포함 숙소 코드는 카탈로그 수신 경계에서 거부하고 검색 요청 경계에서도 다시 확인한다. 어댑터는 이 매핑에 존재하는 응답 항목만 내부 ID가 있는 Offer로 만들며, 알 수 없는 외부 코드는 해당 항목만 `INVALID_RESPONSE` 후보로 제외한다. 활성 매핑 조회와 50개 단위 분할은 통합 검색 서비스가 담당한다.

응답 전체의 JSON 파싱 실패, Supplier 수준 실패, 필수 데이터 컨테이너 누락은 호출 묶음 실패다. 컨테이너 안의 개별 필드 누락, 잘못된 금액·통화·재고, 활성 매핑 부재는 Offer 정규화 경계에서 항목 단위로 격리한다. 전송 DTO는 각 Supplier 어댑터의 비공개 타입으로 유지한다.

API 키와 기본 URL은 외부 설정으로 관리한다. 실제 키를 저장소에 커밋하거나 로그에 기록하지 않는다.

#### 신규 Supplier 추가 체크리스트

1. `Supplier` enum에 식별자를 추가하고 기본 URL과 API 키 설정을 추가한다. 공통 제한 시간 정책으로 부족할 때만 Supplier별 제한 시간 설정을 분리한다.
2. 카탈로그용·실시간 검색용 `WebClient` bean을 각각 구성한다.
3. `SupplierCatalogClient`를 구현하여 Supplier 목록 DTO를 공통 `CatalogSnapshot`으로 변환한다.
4. `SupplierSearchClient`를 구현하여 Supplier 검색 DTO를 공통 `Offer` 결과로 변환한다.
5. Supplier별 HTTP 상태나 본문 코드를 기존 공통 실패 유형으로 매핑하고, 필요한 의미가 없다면 새 실패 유형을 추가하지 않는다.
6. 전송 DTO는 새 어댑터 내부에 유지하고 Mock의 목록·검색·장애 모드와 계약 테스트를 함께 추가한다.

공통 포트와 도메인 계약으로 표현할 수 있다면 기존 검색 서비스는 수정하지 않는다. 새 Supplier만의 의미가 공통 계약에 꼭 필요할 때에만 도메인 변경을 검토한다.

### 10.2 50개 분할과 제한 병렬성

활성 숙소 코드는 Supplier별로 묶은 뒤 최대 50개씩 나눈다. 서로 다른 Supplier는 동시에 실행하고, 한 Supplier 안에서는 최대 4개 묶음만 동시에 실행한다.

검색용 카탈로그 조회는 Entity 전체가 아니라 내부 숙소·객실 타입 ID와 이름, Supplier 코드만 담은 읽기 전용 projection을 사용한다. 이름은 고객 응답 조립에 사용하고 Supplier 코드는 외부 요청 조립에만 사용하며 직렬화하지 않는다. application의 조회 포트와 JPA 어댑터를 분리하고 짧은 `readOnly` 트랜잭션 안에서 projection을 완성한 뒤 트랜잭션을 종료한다. 외부 HTTP 호출 중에는 DB 트랜잭션을 유지하지 않는다. 활성 객실 타입이 하나도 없는 enabled Supplier는 외부 검색을 호출하지 않고 `CATALOG_UNAVAILABLE` 실패로 기록한다.

Supplier 요청을 만들기 위해 활성 매핑 전체를 읽는 것은 최소 검색 계약상 불가피하지만, 검색 결과에는 전체 카탈로그를 복사하지 않는다. 실제로 수용된 Offer의 객실 타입 ID만 추린 뒤 해당 이름을 application 계층의 `SearchOffer`에 결합한다. 고객 API는 완성된 검색 Offer를 그룹화할 뿐 평행한 카탈로그 목록을 다시 조인하지 않으며, 판매 가능한 Offer가 없다면 응답용 카탈로그 객체도 만들지 않는다.

모든 묶음을 무제한 병렬 호출하면 한 번의 고객 요청이 Supplier 호출 폭증과 호출 한도 초과를 일으킬 수 있다. 모두 순차 호출하면 숙소 수에 비례해 검색 시간이 길어진다. 제한 병렬성은 두 위험 사이의 명시적인 절충이며 Supplier별 설정으로 분리한다.

현재 최대 4개 제한은 한 고객 검색 요청 안에서 Supplier별로 적용한다. 여러 고객을 합친 JVM 호출 한도는 8-B에서 Supplier별 검색 8개로 추가했다. 설정과 지표는 §17을 참고한다.

일부 묶음이 실패해도 완료된 묶음의 Offer는 유지한다. 정상 결과가 하나라도 있고 일부가 실패하면 해당 Supplier와 전체 검색을 `PARTIAL`로 표시한다.

전체 제한 시간은 활성 매핑 조회를 시작하기 직전부터 계산한다. 제한 시간이 끝나면 실행 중이거나 대기 중인 reactive 호출을 취소하고, 이미 완료된 묶음은 보존하며 완료되지 않은 묶음은 `TIMEOUT`으로 집계한다. JPA 조회 자체는 blocking 호출이므로 8-C에서 연결 획득 대기와 PostgreSQL statement/lock timeout을 유한하게 설정하고 남은 검색 예산을 전달했다(§18). 고객 진입 제한과 응답 직렬화를 포함하는 엄격한 HTTP 완료 상한은 별도 경계로 남긴다.

#### 수천 개 숙소에서의 한계

현재 설정에서 Supplier당 숙소 3,000개는 50개씩 60묶음이며, 동시 호출 4개라면 15번의 호출 wave가 필요하다. 묶음당 200ms이면 약 3초지만 p95가 1초이면 약 15초가 필요해 검색 전체 제한 시간 5초 안에 전부 완료할 수 없다. 이 경우 Supplier 호출은 남은 검색 예산에서 취소하고, 완료된 묶음만 보존하여 `PARTIAL` 또는 `FAILED`로 반환한다.

따라서 현재 구조는 호출 수를 제한하여 안전하게 실패하지만, 모든 수천 개 숙소를 항상 5초 안에 조회한다고 보장하지 않는다. 운영 지표와 Supplier 호출 한도를 확인한 뒤 다음 순서로 확장한다.

1. Supplier가 허용하는 범위에서 동시 호출 수를 조정한다.
2. 더 큰 bulk 크기나 별도 대량 조회 계약을 Supplier와 협의한다.
3. 제품에 지역 등 검색 조건이 추가되면 검색 인덱스로 후보 숙소를 먼저 줄인다.
4. 가격·재고 최신성 저하를 허용할 수 있을 때만 짧은 TTL의 읽기 모델을 검토하고 예약 직전에 다시 확인한다.

현재 최소 계약은 보유 숙소 전체 조회이므로 제품 조건 없이 임의로 검색 대상을 줄이지 않는다.

### 10.3 초기 제한 시간

```text
연결 타임아웃: 500ms
응답 타임아웃: 2초
호출 전체 타임아웃: 3초
검색 전체 타임아웃: 5초
```

실제 Supplier의 지연 분포와 고객 API 목표 시간이 없으므로 위 값은 초기 실험값이다. 한 Supplier를 오래 기다리는 것보다 정해진 시간 안에 유효한 부분 결과를 반환하는 방향을 선택했다.

운영에서는 Supplier별 p95·p99 지연, 타임아웃 비율, 고객 API 목표 시간을 관측하여 값을 조정한다. 검색 전체 제한 시간 안에 완료되지 못한 실행·대기 묶음은 취소하고 실패 묶음으로 기록한다.

### 10.4 실시간 검색 재시도

첫 번째 구현에서는 실시간 재고·요금 호출을 재시도하지 않는다. 재시도는 고객의 대기 시간을 사용하고 Supplier 장애 중 호출량을 증가시킨다. 초기 회복 전략은 병렬 조회, 제한 시간, 부분 응답이다.

운영 지표에서 짧은 일시 오류가 충분히 자주 발생하고 전체 응답 시간 안에서 성공률을 개선할 수 있음이 확인되면 제한적인 1회 재시도를 검토한다. 타임아웃, 잘못된 요청, 인증 실패, 데이터 오류는 무조건 재시도하지 않는다.

## 11. 메시징과 배치 프레임워크를 사용하지 않는 이유

WebSocket은 Supplier 호출에 내구성 있는 재시도를 제공하지 않는다. MQ와 Kafka는 작업 메시지를 보존할 수 있지만 Supplier 응답 성공을 보장하지 않으며, 나중에 얻은 가격과 재고는 원래 검색 시점에는 이미 오래된 값일 수 있다.

현재 Supplier API는 숙소 코드, 날짜, 인원에 따라 요청하는 pull 방식이며 가격·재고 변경 이벤트를 발행하지 않는다. 이 데이터를 계속 polling하여 Kafka에 다시 넣으면 원래 Supplier 호출은 그대로 남고 Consumer, offset, 중복 제거, 결과 저장, 만료, 최신성 정책이 추가된다.

Supplier가 변경 이벤트를 제공하고 여러 서비스가 이를 소비하며, 검색 시 fan-out이 실제 병목으로 확인되고, 제한된 데이터 지연을 허용할 수 있을 때 Kafka 기반 읽기 모델을 검토한다. 이 경우에도 예약 직전 재고·가격 재확인이 필요하다.

Spring Batch도 사용하지 않는다. 숙소 코드 50개 제한은 고객 요청 안에서 수행하는 HTTP 요청 분할이며 장시간 실행되는 영속 배치 작업이 아니다. 카탈로그가 대규모 페이지 기반으로 바뀌고 체크포인트 재시작이 필요해질 때 Spring Batch를 검토한다.

## 12. 검색 요청 검증

Supplier를 호출하기 전에 다음 조건을 검증한다.

```text
checkIn 필수
checkOut 필수
checkIn < checkOut
adults >= 0
children >= 0
adults + children >= 1
숙박 기간 <= 30박
```

`children`을 생략하면 0명으로 처리한다. 성인 최소 1명과 과거 날짜 제한은 제품 정책이 제공되지 않았으므로 임의로 추가하지 않는다. 공개 검색 API 한 요청이 생성할 수 있는 일별 가격·재고 데이터의 크기를 제한하기 위해 최대 숙박 기간은 30박으로 정하고, 31박 이상은 Supplier 호출 전에 거부한다. 실제 상품 정책이 제공되면 이 상한을 정책 값에 맞춰 조정한다.

잘못된 입력은 안정적인 애플리케이션 오류 코드와 안전한 메시지를 담아 HTTP 400으로 반환한다. Supplier 오류와 내부 예외 내용을 그대로 노출하지 않는다.

## 13. 애플리케이션 실행 모델

애플리케이션은 Spring MVC와 JPA를 사용한다. Supplier HTTP I/O만 `WebClient`로 구성하고, 요청 묶음은 구독 수요에 맞춰 생성하여 Supplier와 허용된 묶음을 병렬 실행한다.

Blocking JPA 작업은 WebClient 이벤트 루프 밖에서 끝낸다. 컨트롤러는 Spring MVC가 지원하는 비동기 반환 타입을 사용할 수 있지만 영속성 모델은 동기식으로 유지한다.

전체 WebFlux와 R2DBC 전환은 현재 요구사항에 필요하지 않다. 트랜잭션과 디버깅 복잡성을 늘리지 않으면서 WebClient 병렬 연동이라는 핵심을 구현한다.

## 14. 관측 가능성

유효한 부분 결과는 HTTP 200이므로 HTTP 성공률만으로 Supplier 장애를 발견할 수 없다. 다음 Supplier 단위 지표를 기록한다.

- 호출 수와 결과
- 응답 지연
- 타임아웃 수와 비율
- 공통 사유별 제외 Offer 수
- 카탈로그 동기화 성공·실패와 마지막 성공 시각
- 활성 매핑 수
- 전체 검색의 `PARTIAL`·`FAILED` 횟수

Metric label은 값 종류가 제한되어야 한다. Supplier 이름과 공통 결과 코드는 label로 사용할 수 있지만 숙소 코드, 객실 코드, 예외 메시지는 사용하지 않는다.

## 15. 감수한 제약과 확장 조건

현재 설계는 실제로 같은 숙소가 Supplier별로 중복 노출될 수 있고, 품절 숙소를 숨기며, 검색에서 날짜별 가격을 제공하지 않는다. 이는 누락이 아니라 첫 구현에서 의도적으로 선택한 제약이다.

다음 기능은 구체적인 사용 사례와 정확성 정책이 생겼을 때 추가한다.

- Supplier 간 표준 숙소 매칭
- 환율 적용과 비교 통화
- 상품 상세의 날짜별 가격
- 취소·결제 조건을 포함한 요금제
- 재고·요금 캐시와 최신성 기준
- 관측된 장애 패턴에 근거한 서킷 브레이커
- 정규화 실패 원문 격리 저장소
- 이벤트 스트림과 검색용 읽기 모델
- 예약 생성·취소, 멱등성, 보상 처리

첫 번째 검색 흐름에 사용되지 않는 확장 구조를 미리 구현하지 않고, 필요한 경계를 유지하는 수준에서 준비한다.

### 15.1 후속 확장 검토 조건

다음 항목은 현재 구현에 미리 넣지 않고, 해당 조건이 생기거나 관련 검증 단계에 도달하면 다시 판단한다.

| 검토 대상 | 다시 판단할 조건 | 우선 검증하거나 적용할 방법 |
| --- | --- | --- |
| 카탈로그 동기화 중복 실행 | 애플리케이션을 여러 인스턴스로 운영 | PostgreSQL advisory lock 또는 분산 스케줄 잠금 |
| 카탈로그 Supplier 병렬화 | Supplier 수 증가 또는 순차 동기화 지연이 복구 목표를 초과 | Supplier 단위 제한 병렬 실행 |
| 검색·동기화 인덱스 | 활성 매핑과 객실 타입이 대량으로 증가 | 실제 데이터의 `EXPLAIN ANALYZE`를 확인한 뒤 `property_id` 선두 인덱스 검토 |
| 카탈로그 대량 쓰기 | Entity별 INSERT/UPDATE 시간이 동기화 주기를 위협 | JDBC batch, 변경분 갱신, staging table 순서로 검토하며 `IDENTITY`의 batch 제한도 함께 확인 |
| 동기화 상태 테이블 | 재시작 후에도 마지막 성공·실패 상태를 조회하거나 관리자 화면·SLA에 제공해야 함 | Supplier별 `lastAttemptAt`, `lastSuccessAt`, 상태, 건수를 저장하는 상태 테이블 |
| Outbox | DB 변경과 메시지 발행을 하나의 신뢰 가능한 흐름으로 묶어야 함 | 메시지 브로커와 소비자가 실제로 도입될 때 transactional outbox 검토 |

8단계에서는 지연과 호출 수를 측정하여 인덱스와 병렬성의 필요성을 확인하고, 9단계 README에서는 구현한 기능과 위 보류 항목을 구분해 설명한다.

## 16. 8-A 입력·중복·예외 계약 보완

2026-09-16에 적용했다. 감사 시점의 관측과 수정 후 동작을 구분한다.

### 16.1 엄격한 Supplier 입력과 실패 격리

Supplier 응답은 어댑터 안에서 Jackson 3 `JsonNode`로 읽는다. 공통 `SupplierJson`은 JSON 타입·범위 검사만 담당하고, 필드명과 성공 규약은 각 Supplier 어댑터가 해석한다. 도메인에는 JSON 노드를 전달하지 않는다. 전역 ObjectMapper 설정을 바꾸지 않아 고객 API와 외부 입력의 규칙을 분리한다.

- 금액은 64비트, 재고·수용 인원은 32비트 JSON 정수여야 한다. 소수, `1.0`, 지수 표기, 숫자 문자열, boolean, null, 범위 초과는 변환하지 않고 거부한다. 음수와 합산 overflow는 값 객체가 거부한다.
- boolean과 문자열도 실제 JSON 타입을 확인한다. 날짜는 엄격한 ISO 날짜 파싱을 거친다.
- 검색에서 JSON 문법이나 최상위 응답 구조가 잘못되면 배치가 `INVALID_RESPONSE`로 실패한다. 분리 가능한 항목의 타입·날짜·값 오류는 `OfferMappingException`으로 표시하여 그 항목만 거부한다.
- 카탈로그는 누락 판정에 쓰는 전체 snapshot이다. 잘못된 항목을 조용히 빼면 기존 매핑을 비활성화할 수 있으므로 하나라도 잘못되면 snapshot 전체를 거부한다.

잘못된 항목이 계속 반환되면 주기적 동기화 시도는 계속되지만 성공한 snapshot의 갱신은 멈춘다. 기존 매핑으로 검색을 제공하는 대신 데이터가 오래될 수 있다. 마지막 저장 성공 시각과 연속 실패 신호는 8-B에서 추가했다(§17).

응답을 메모리에 모으는 방식은 유지한다. 응답 크기 한도·전역 동시성·메모리 범위는 8-B에서 검증하고 §17에 기록했다. Jackson 2가 간접 의존성에 있어도 실제 Boot WebClient는 Jackson 3을 사용하므로, 회귀 테스트는 애플리케이션에 주입되는 실제 client를 사용한다.

### 16.2 중복과 객실 속성 충돌

정규화된 `Offer`의 모든 값이 같은 경우 `LinkedHashSet`으로 한 건만 유지한다. Supplier A의 일별 가격은 날짜순으로 정렬하여 배열 순서만 다른 중복도 제거한다. 중복 자체는 항목 거부나 부분 실패로 집계하지 않으며 `acceptedOfferCount`는 실제 반환 건수다.

금액·재고·조식·통화·일별 가격 구성이 다르면 각각 보존한다. 현재 응답에는 요금제 식별자가 없으므로 같은 객실이라는 이유만으로 같은 상품의 충돌이라고 확정하거나 최저가 하나를 고르지 않는다. 요금제 계약이 생기면 동일 요금제의 충돌 규칙을 추가한다.

최대 수용 인원은 고객 응답에서 객실 타입 단위 속성이다. 반환 가능한 Offer 사이에서 같은 내부 객실 ID의 수용 인원이 다르면 그 객실의 Offer를 모두 거부하고 다른 객실은 유지한다. 이때 중복 제거 후 제외한 Offer 수를 `rejectedOfferCount`에 더한다. 입력 순서에 따라 임의의 수용 인원을 선택하지 않는다. 서로 다른 숙소의 같은 원본 객실 코드는 별도 내부 ID이므로 영향을 주지 않는다.

현재 검색은 한 숙소의 모든 객실을 하나의 배치에 포함한다. 따라서 동일 내부 객실의 Offer는 하나의 배치에서 충돌 검사된다. 같은 숙소를 여러 배치로 나누거나 중첩된 재시도 결과를 합치는 경우에는 최종 집계 경계의 충돌 검사를 다시 설계해야 한다.

### 16.3 요청과 어댑터 계약

숙소 코드 목록은 URI 템플릿의 값으로 전달하여 `+`, 중괄호, `%`, `&`, 공백, 비ASCII 문자를 보존한다. 기존 쉼표 금지와 최대 50개 분할 계약은 유지한다.

카탈로그 서비스는 client 등록 중복과 활성 Supplier의 client 누락을 생성 시 검사한다. 스케줄러를 끈 상태에서도 수동 동기화를 지원하므로 이 검사는 유지한다. 반환 snapshot의 Supplier가 client와 다르면 저장하지 않고 다음 Supplier를 처리한다. `Mono.defer`로 호출하여 Publisher 생성 전 동기 예외에도 동일한 제한 retry 정책을 적용한다.

### 16.4 오류 분류와 고객 계약

B의 HTTP 200 본문 실패 코드 매핑은 B 전용 매퍼 하나로 합쳤다. HTTP 상태, B 본문 코드, transport timeout은 각각의 계약 경계에서 변환한다.

JSON 타입 검사와 외부 값의 도메인 검증은 `InvalidValueException`으로 명시한다. 금액 overflow는 정확한 덧셈 지점에서 이 예외로 변환한다. 검색은 이 예외만 항목 거부로, 카탈로그는 snapshot 거부로 변환한다. 내부 식별자·계산 결과 불변식 검증은 일반 예외로 남긴다. 예상 밖 mapper의 NPE·상태 오류·일반 IllegalArgumentException을 잘못된 외부 항목으로 숨기지 않는다. 검색 배치나 카탈로그 처리에서 분류되지 않은 내부 예외는 `INTERNAL_ERROR`와 ERROR 로그로 기록하고 원인 stack trace를 보존한다. 정상 Supplier 결과는 유지한다. 알려지지 않은 HTTP 상태와 별도 분류되지 않은 WebClient 응답 읽기 실패는 `UNKNOWN`으로 구분한다. 버퍼 초과는 8-B에서 RESPONSE_TOO_LARGE로 보완했다.

`INTERNAL_ERROR`는 고객 응답의 `supplierResults[].failureTypes[]`에 추가된 enum이다. 부분 실패는 HTTP 200/`PARTIAL`, 모든 조회 실패는 HTTP 503/`FAILED`를 유지한다. 내부 예외 메시지·stack trace는 고객 응답에 넣지 않는다. Supplier 실패 지표는 8-B에서 구현했다(§17).

### 16.5 검증 위치

| 계약 | 정식 테스트 |
| --- | --- |
| 실제 Boot codec, 타입·범위·날짜, 형제 항목 보존, 카탈로그 원자적 거부 | `SupplierAdapterContractTests` |
| 완전 중복·날짜 배열 순서·다른 판매 조건·수용 인원 충돌·특수 코드 | `SupplierAdapterContractTests` |
| HTTP 상태·재시도 가능 여부·중첩 timeout | `SupplierFailureMapperTests` |
| B 본문 코드의 검색·카탈로그 적용 | `SupplierAdapterContractTests` |
| mapper 내부 오류 전파, 정상 Supplier 유지 | `OfferNormalizerTests`, `IntegratedSearchServiceTests` |
| client 누락·중복, 잘못된 snapshot, 빈 Publisher, 동기 실패 retry | `CatalogSynchronizationServiceTests` |
| `INTERNAL_ERROR`의 부분·전체 실패 JSON 및 HTTP 계약 | `StaySearchControllerTests` |

이 검증은 실제 DB→별도 Mock 프로세스→고객 HTTP 요청을 모두 연결한 8-D E2E를 대체하지 않는다.


## 17. 8-B 자원 제한과 업무 지표

2026-09-16에 적용했다. 8-A 리뷰 보완과 별도 변경으로 관리한다.

### 17.1 호출 수와 연결 풀

`SupplierCallResources`는 JVM 안에서 Supplier·작업(SEARCH/CATALOG)별로 공유한다. 검색 서비스의 배치 실행과 카탈로그 서비스의 각 HTTP 시도를 감싸므로 고객 요청이 늘어도 동일한 허용량을 사용한다. 숙소 단위 50개 분할과 요청당 Supplier 동시 4개 규칙은 유지한다.

| 설정 | 검색 | 카탈로그 |
| --- | ---: | ---: |
| Supplier별 동시에 수용하는 호출 | 8 | 1 |
| Supplier별 최대 연결 수 | 8 | 1 |
| 연결 획득 대기 개수 | 8 | 1 |
| 연결 획득 대기 시간 | 200ms | 200ms |
| 응답 codec 한도 | 2 MiB | 8 MiB |

검색 한도 8은 요청당 Supplier 동시 배치 4개를 기준으로 두 요청이 최대 병렬도를 사용하는 상황을 수용하도록 정한 초기값이다. 부하 측정으로 도출한 운영 용량이나 동시 고객 수 보장은 아니다. 세 요청이 각각 같은 Supplier의 배치 4개를 동시에 실행하면 일부 호출이 거부될 수 있지만, 실제 거부 여부는 숙소 수·배치 수·호출이 겹치는 시간에 달려 있다. 거부된 Supplier에 성공 배치가 없으면 해당 Supplier 결과는 FAILED, 성공 배치가 있으면 PARTIAL이 될 수 있다. `SupplierResourceIntegrationTests`는 한도를 2로 낮춰 격리와 반환을 검증하며 기본값 8의 처리 용량을 측정하지 않는다.

운영 조정은 Supplier 계약상 호출 제한과 지연 분포, `supplier.calls.active`·CAPACITY_EXCEEDED 건수·연결 풀 pending·검색 완료 시간, heap 사용량을 함께 보고 결정한다. 대표 배치 수와 동시 요청 수를 사용한 부하 시험에서 Supplier와 애플리케이션의 여유를 확인한 뒤 한도를 바꾼다. 거부 건수만 보고 한도를 늘리면 연결·메모리 부담과 Supplier 지연이 함께 커질 수 있다.

- 기본값은 `supplier.resources.search` / `supplier.resources.catalog`에서 바꾼다. 무제한을 뜻하는 음수·0을 허용하지 않는다. Supplier마다 독립된 자원이지만 현재 설정값은 A/B에 동일하게 적용한다.
- 애플리케이션 허용량에는 대기열을 두지 않는다. 구독 시 `tryAcquire`로 즉시 수용하거나 `CAPACITY_EXCEEDED`로 거부한다. 이벤트 루프에서 blocking acquire를 호출하지 않는다. 짧은 폭주에서도 일부 결과가 빠질 수 있지만 대기 작업이 무한히 쌓이지 않는다. 공정한 고객별 할당을 보장하는 정책은 아니다.
- `Mono.using`의 eager 정리로 정상·오류 신호가 다음 처리에 전달되기 전에 허용량을 반환한다. 구독 취소와 Publisher 생성 전 동기 예외도 반환 경로에 포함한다. 응답 읽기와 파싱·정규화가 완료될 때까지 허용량을 유지한다.
- A/B와 검색/카탈로그의 ConnectionProvider를 분리한다. 같은 호스트 주소를 사용해도 검색이 다른 Supplier나 카탈로그의 연결을 점유하지 않는다. 컨텍스트 종료 시 소유한 풀을 정리한다.
- 연결 풀의 pending 한도는 허용량 반환과 실제 연결 반환 사이의 짧은 경계 및 직접 client 사용을 위한 추가 보호다. 로컬 연결 획득 대기 초과·대기열 초과는 원인 체인을 확인해 `CAPACITY_EXCEEDED`로 분류한다. Reactor Netty 1.3.x의 shaded pool 예외 의존은 transport mapper 한곳에 둔다.
- HTTP client의 연결 reset 자동 재전송을 끈다. 검색은 재시도하지 않는다. 카탈로그의 기존 제한 retry만 유지하며 각 시도마다 허용량을 다시 얻는다. 크기 초과와 로컬 용량 부족은 즉시 retry하지 않는다.

로컬 용량 부족의 `retryable=false`는 현재 점유 중인 작업에 재시도를 더해 경합을 늘리지 않기 위한 선택이다. 카탈로그에서는 이미 진행 중인 동기화가 성공해 매핑을 갱신할 수도 있으므로, 용량 부족이 곧 다음 주기까지 갱신 불가를 뜻하지는 않는다. HTTP 허용량은 조회가 끝나면 반환되므로 DB 저장까지 보호하지 않는다. 8-C에서는 별도의 Supplier별 실행 guard로 조회→재시도→저장 전체를 보호하고, 중복 실행은 건너뛰도록 정했다(§18). 로컬 용량 부족은 즉시 retry하지 않는 선택을 유지한다. `execute`의 retry 여부 한 곳만 바꿔서는 DB 경쟁이나 연결 풀의 용량 부족 분류가 해결되지 않는다.

8-B에서는 검색·카탈로그 application이 구체 클래스 `SupplierCallResources`와 `SupplierMetrics`에 의존한다. 호출 제한과 Reactor Netty 연결 풀 관리가 한 클래스에 있어 application 테스트도 풀 객체와 Micrometer `MeterRegistry`를 생성한다. application이 Micrometer를 직접 import하지는 않지만, 구체 지표 구현과 자원 구성에 대한 결합은 남는다. 자원 수명과 지표 기록을 한곳에서 관리하는 작은 구현을 택한 절충이며 application이 포트만 의존하는 구조는 아니다. 호출 제한 정책을 독립적으로 교체하거나 application 테스트의 transport 의존을 없앨 필요가 생기면 좁은 호출 실행 인터페이스와 연결 풀 소유자를 분리한다. `supplier/common` 전체 재편은 이 변경의 필수 조건으로 삼지 않는다.

풀 설정과 자원 생명주기는 [Reactor Netty 공식 문서](https://projectreactor.io/docs/netty/release/reference/http-client.html#_connection_pool)를 기준으로 현재 의존성의 실제 HTTP 동작을 검증했다.

### 17.2 응답 크기와 메모리 범위

`WebClient.Builder.clone()`으로 각 client의 codec 한도를 분리한다. 검색 2 MiB는 50개 숙소 × 5개 객실 × 30박의 정상 응답, 카탈로그 8 MiB는 숙소 3,000개 fixture를 수용한다. fixture 수용과 모든 형태의 수천 개 숙소 지원을 같은 의미로 보지 않는다. 객실·요금제·문자열 길이에 따라 한도를 넘을 수 있다.

`DataBufferLimitException`은 직접 발생하거나 다른 예외의 원인으로 감싸져도 `RESPONSE_TOO_LARGE`로 변환한다. 카탈로그의 일부만 저장하거나 같은 큰 응답을 즉시 재시도하지 않는다. 다음 정상 응답은 같은 자원으로 처리할 수 있다. codec 설정은 [Spring 공식 문서](https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html#webflux-client-builder-maxinmemorysize)의 유한한 버퍼 한도를 사용한다.

기본 설정에서 두 Supplier의 수용 호출 수 × 응답 한도는 검색 32 MiB + 카탈로그 16 MiB = 48 MiB다. 이는 설정값의 단순 곱이며 실제 peak heap 상한이 아니다. JsonNode와 도메인 객체, 이미 완료된 배치 결과, DB 매핑, Netty 버퍼·HTTP 헤더와 요청별 최종 응답은 추가 비용이다. 애플리케이션 전체 메모리 제한이나 부하 용량을 실측했다고 주장하지 않는다.

로그 건수 계산용 Offer 평탄화 목록과 메타데이터 조인용 임시 Offer 목록은 제거했다. 일별 가격 근거, 검증 불변식, 최종 결과 정렬은 유지했다.

### 17.3 업무 지표와 해석

Actuator HTTP 노출은 `health`, `info`, `metrics`다. 대시보드·외부 trace exporter는 추가하지 않았다.

| 지표 | 의미 |
| --- | --- |
| `supplier.calls` | 수용·거부된 호출 시도의 시간과 건수. supplier/operation/outcome/failure 태그 |
| `supplier.calls.active` | Supplier·작업별 현재 허용량을 점유한 호출 수 |
| `supplier.offers` | ACCEPTED/REJECTED/UNAVAILABLE/DUPLICATE 건수 |
| `supplier.search.results` | 검색 요청별 Supplier 최종 상태 건수 |
| `supplier.search.failures` | 검색 요청별 Supplier에 발생한 실패 유형. 같은 유형은 요청 내 한 번 |
| `search.requests` | 검색 서비스 전체 시간·최종 상태. 내부 오류도 별도 기록 |
| `search.stages` | DATABASE/MAPPING/SUPPLIERS/ASSEMBLY 구간 시간 |
| `search.catalog.read.failures` | 8-C: 예상된 DB 읽기 실패. TIMEOUT/UNAVAILABLE 원인 |
| `supplier.catalog.skipped` | 8-C: 동일 Supplier 동기화가 진행 중이어서 건너뛴 실행 |
| `supplier.catalog.sync` | HTTP 시도·retry·저장까지 포함하는 Supplier별 동기화 시간·결과 |
| `supplier.catalog.last.success` | 저장 성공 후 기록한 epoch seconds. 이 프로세스에서 성공한 적이 없으면 0 |
| `supplier.catalog.consecutive.failures` | 연속 동기화 실패 횟수. 저장 성공 시 0으로 초기화 |
| `reactor.netty.connection.provider.*` | 분리한 연결 풀의 active/idle/pending 등 기본 지표 |

- HTTP 200 안의 B E503은 `supplier.calls`에서 FAILED/UNAVAILABLE이다. 잘못된 항목을 제외하고 정상 Offer를 반환한 호출은 PARTIAL/INVALID_RESPONSE다.
- 완전 중복은 `duplicateOfferCount`와 DUPLICATE 지표로 관측한다. 고객 응답 필드에는 추가하지 않으며, 중복만으로 거부 건수나 PARTIAL 상태를 늘리지 않는다.
- 검색 전체 deadline에서 취소된 진행 호출은 CANCELLED로 기록한다. 시작되지 않은 배치는 호출 시도가 아니므로 `supplier.calls`에 넣지 않는다. 최종 Supplier 결과와 `supplier.search.failures`에는 TIMEOUT이 남는다.
- 카탈로그 HTTP 성공만으로 마지막 동기화 성공 시각을 갱신하지 않는다. snapshot 저장이 실패하면 이전 성공 시각을 유지하고 실패 횟수를 올린다. 이 지표는 메모리에 있으며 재시작하면 초기화된다.
- `supplier.catalog.last.success=0`은 이 프로세스에서 최초 저장 성공이 아직 없다는 뜻이다. 신선도 알림에서 0을 epoch 시각으로 빼면 기동 직후부터 오래된 데이터로 오인하고, 0을 무조건 제외하면 최초 동기화가 계속 실패하는 상태를 놓친다. 프로세스 기동 후 초기 동기화·제한 retry를 수행할 유예 시간을 두고, 유예 이후에도 최초 성공이 없는 상태와 성공 이후 허용 갱신 간격을 초과한 상태를 각각 감시한다. 연속 실패 횟수를 함께 보고 재시작으로 이 값들이 초기화된다는 점도 고려한다. 현재 프로젝트에는 이 알림 규칙을 구현한 외부 모니터링 설정이 없다.
- 사용자 입력·숙소 코드·객실 ID·예외 메시지는 업무 지표 태그로 쓰지 않는다. 풀 지표의 주소는 설정된 Supplier 주소다.

### 17.4 검증과 남은 경계

정식 검증 위치:

- `SupplierCallResourcesTests`: 구독 전 미점유, 즉시 재구독 100회, 동기 오류·취소 후 반환, 거부한 작업 미실행, 유한한 설정 검증.
- `SupplierResourceIntegrationTests`: 실제 HTTP와 공유 검색 서비스에서 A의 2개 호출을 유지한 채 추가 고객 6명의 요청 거부·B 결과 보존, 같은 주소의 카탈로그 격리, 전체 timeout의 소켓 종료·용량 반환·다음 호출 회복, 본문 실패 지표, 큰 정상 응답, 크기 초과 후 회복, 실제 풀 pending 초과·획득 timeout 분류, 중복 지표.
- `SupplierAdapterContractTests.ResponseSizes`: 실제 Boot에 주입된 codec으로 큰 카탈로그·30박 검색 수용, A/B 검색·카탈로그의 한도 초과 분류.
- `CatalogSynchronizationServiceTests`: 저장 성공/실패에 따른 신선도와 연속 실패 지표.
- `StaySearchControllerTests`: 새 두 실패 유형의 부분 실패 HTTP 200·전체 실패 HTTP 503 응답 계약.

메인 232개·Mock 6개 통과, 실패·오류·건너뜀 0개다. 변경 없는 Mock은 최종 실행에서 up-to-date였으며 이 세션의 리뷰 보완 전체 실행에서 6개 성공을 확인했다.

이 한도는 JVM·Supplier·작업별 외부 호출 보호다. DB 조회 이전의 고객 검색 진입 제한, 다중 JVM 합산 한도, 고객 연결 종료의 즉시 취소, 최종 응답 직렬화 비용은 해결하지 않았다. 카탈로그 허용량은 HTTP 시도에 적용되며 snapshot 저장까지 직렬화하는 DB lock을 대체하지 않는다. 8-C에서 별도 동기화 guard와 DB/pool 시간 예산을 추가했다(§18). 전체 5초는 HTTP 응답 완료까지의 엄격한 상한이 아니다.

실제 DB→별도 Mock 프로세스→고객 API 연결은 8-D에서 검증했다(§19). 고정 포트 수동 실행 명령의 최종 재현은 9단계에 남아 있다. 캐시, circuit breaker, 분산 제한, 전체 WebFlux 전환은 이번 범위에 추가하지 않았다.


## 18. 8-C 동기화 중복 실행과 DB 시간 예산

### 18.1 지원하는 실행 범위

카탈로그를 갱신하는 애플리케이션 인스턴스는 하나로 제한한다. 현재 운영 호출처는 `CatalogSynchronizationScheduler`의 동기 `fixedDelay` 메서드 하나이며, 수동 트리거 endpoint나 관리자 실행 경로는 없다. 이전 실행이 끝난 뒤 다음 실행까지의 지연을 계산하므로 이 경로의 직렬화는 8-C 이전부터 스케줄러가 제공한다. 여러 서버에서는 나머지 서버의 스케줄을 `SUPPLIER_CATALOG_ENABLED=false`로 끄고, 추가 호출 경로를 만들 때도 갱신 인스턴스 하나라는 제약을 지켜야 한다. 자동 leader 선출이나 분산 lock은 구현하지 않았다. [Spring fixedDelay](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)

`CatalogSynchronizationService`의 Supplier별 guard는 향후 추가 트리거나 서비스 직접 호출이 겹칠 때의 보조 방어다. 현재 단일 스케줄 경로에서는 중복 실행을 건너뛰는 분기가 발생하지 않는다. guard는 HTTP 조회 전에 획득해 모든 HTTP 시도와 retry backoff, snapshot 저장의 commit/rollback 이후까지 유지하고 `finally`에서 반환한다. 같은 Supplier의 추가 호출은 대기하거나 새 snapshot을 가져오지 않고 건너뛰며 다음 Supplier를 진행한다.

DB 감사에서 재현한 최초 INSERT 경쟁, 누락 횟수 lost update, snapshot 저장 순서 역전의 발생 조건은 현재 지원 범위에서 단일 갱신 인스턴스 정책과 기존 `fixedDelay` 직렬화로 배제한다. 이를 guard가 운영 중 발생하던 DB 경쟁이나 다중 인스턴스 경쟁을 해결한 것으로 해석하지 않는다. Supplier가 직렬 요청에 오래된 데이터를 돌려주는 문제를 판별하는 version 계약도 없다.

서비스 진입은 `Propagation.NEVER`로 외부 트랜잭션 안의 실행을 거부한다. 따라서 HTTP 중에는 DB 트랜잭션을 유지하지 않고, writer의 프록시가 독립 트랜잭션을 끝낸 뒤 guard를 해제한다. `CatalogSnapshotWriter` 직접 호출은 동기화 진입 API가 아니며 guard를 제공하지 않는다.

`CAPACITY_EXCEEDED`의 즉시 재시도 금지는 유지한다. 중복 동기화는 HTTP 허용량을 경쟁하기 전에 건너뛰고, 실제 pool 용량 부족은 기존 실패로 관측한다. 건너뛴 실행은 `supplier.catalog.skipped{supplier}`만 증가시키며 동기화 실패 횟수나 마지막 성공 시각은 바꾸지 않는다. 진행 중인 작업이 성공한 뒤에만 신선도가 갱신된다.

### 18.2 검색과 쓰기의 서로 다른 시간 예산

| 설정 | 초기값 | 적용 범위 |
| --- | ---: | --- |
| Hikari maximum-pool-size | 10 | 검색·쓰기의 공유 DB 연결 수 |
| Hikari connection-timeout / validation-timeout | 500ms / 250ms | 연결 획득·유효성 검사 |
| pgJDBC connectTimeout / socketTimeout | 2초 / 15초 | 연결 수립·각 socket 읽기 |
| PostgreSQL session statement_timeout / lock_timeout | 10초 / 2초 | 공유 DataSource의 기본 SQL / 잠금 대기. 운영 Flyway 포함 |
| catalog.database.read-statement-timeout | 1초 | 검색 projection SQL |
| catalog.database.read-lock-timeout | 300ms | 검색 SQL의 잠금 대기 |
| catalog.database.write-transaction-timeout-seconds | 10초 | Spring/Hibernate 쓰기 트랜잭션 |

검색 시작의 `System.nanoTime()`으로 만든 절대 deadline을 조회 포트에 전달한다. reader는 독립된 `readOnly` 트랜잭션에서 연결을 확보한 뒤 남은 시간을 계산한다. PostgreSQL `set_config(..., true)`로 statement timeout은 `min(읽기 한도, 잔여 시간)`, lock timeout은 `min(잠금 한도, statement 한도)`로 설정한다. 예산이 소진되면 SQL을 실행하지 않고, 양수의 1ms 미만 시간은 1ms로 올려 timeout이 0으로 비활성화되지 않게 한다. 조회와 트랜잭션 종료 뒤에도 deadline을 확인한다. `JdbcTemplate`은 JPA와 같은 DataSource/트랜잭션 연결을 사용한다. [Spring JpaTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html)

정상적으로 조회를 완료하는 검색은 **timeout 설정 SELECT 1개 + 데이터 projection SELECT 1개**를 실행한다. 8-C 이전보다 설정문과 왕복 1회가 추가됐으며, 이는 BEGIN/COMMIT 등 트랜잭션 제어를 제외한 SQL 개수다. 숙소 수에 비례해 SQL이 늘어나는 N+1은 없다. 설정문은 직접 JDBC로 실행하므로 Hibernate 통계만으로 전체 statement 수를 세지 않는다. DB 감사의 0.196ms는 네트워크·JPA 처리를 제외한 EXPLAIN 서버 실행 시간이어서 추가 왕복의 비용 비율을 계산할 근거가 아니다. 변경 전후 DB 구간 지연은 별도로 측정하지 않았다.

추가 왕복은 요청마다 연결 획득에 사용한 시간을 빼고 남은 예산을 ms 단위의 SQL·잠금 제한에 반영하기 위한 비용이다. 연결 초기화 시 고정 한도를 설정하는 대안은 이 왕복을 줄일 수 있지만 요청별 잔여 시간을 그대로 반영하지 못한다. 현재는 동일 연결의 transaction-local 설정과 종료 시 복원을 선택했다.

검색용 설정은 트랜잭션이 끝나면 원래 session 값으로 돌아간다. 쓰기는 검색의 짧은 timeout을 물려받지 않으며 별도의 Spring 트랜잭션 timeout과 유한한 PostgreSQL 기본 한도를 사용한다. statement timeout은 각 SQL의 제한이므로 전체 저장 시간과 같은 의미가 아니다. [PostgreSQL timeout](https://www.postgresql.org/docs/17/runtime-config-client.html)

운영 Flyway에는 별도 DataSource/연결 설정이 없으므로 같은 DataSource의 session 기본 한도가 적용된다. migration SQL이 10초를 넘거나 잠금 획득을 2초 넘게 기다리면 취소되어 애플리케이션 기동이 실패할 수 있다. `ACCESS EXCLUSIVE` 잠금 자체가 실패 조건은 아니다. 현재 migration은 작지만, 장시간 DDL·백필 도입 전에는 migration 전용 연결과 별도 timeout 예산을 구성·검증해야 한다. 이 변경에서는 DataSource를 분리하지 않았다. Testcontainers의 `@ServiceConnection`은 Flyway 전용 연결 정보를 제공할 수 있으므로 기존 전체 테스트 통과를 운영 migration의 동일 DataSource timeout 검증으로 간주하지 않는다. [Spring Boot Flyway DataSource](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

reader는 `REQUIRES_NEW`로 조회 트랜잭션과 설정 복원 경계를 자체 소유한다. 현재 고객 검색은 외부 트랜잭션 없이 진입한다. 외부 트랜잭션이 이미 연결을 확보한 상태에서 검색을 호출하면 그 연결을 유지한 채 reader가 두 번째 연결을 요구하므로 동시 호출에서 풀 고갈이 발생할 수 있다. 검색 진입에는 이를 거부하는 `NEVER` 검사가 없고, 중첩 동작은 별도 통합 검증하지 않았다. 검색 경로를 트랜잭션으로 감싸는 변경은 이 연결 점유와 Supplier HTTP 대기 중 외부 트랜잭션 유지를 함께 검토해야 한다. [Spring REQUIRES_NEW](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)

이 값은 부하 실측으로 정한 처리 용량이 아니다. Hikari의 고정 획득 대기는 요청별로 줄일 수 없고 최소값도 250ms다. 매우 짧은 검색 예산에서는 연결 대기만으로 deadline을 넘길 수 있다. 설정 SQL 자체와 통신 장애는 별도 JDBC 한도에 의존하고, socketTimeout도 전체 요청 상한이 아니다. CPU 매핑·응답 조립·직렬화까지 포함한 5초 hard deadline을 보장한다고 표현하지 않는다. [HikariCP 설정](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby), [pgJDBC 설정](https://jdbc.postgresql.org/documentation/use/)

Hikari의 10개는 실제 DB 연결의 상한이며 별도의 고객 요청 진입 제한이나 연결 대기자 수 제한은 아니다. 기본 Tomcat 작업 스레드도 최대 200개로 유한하다(virtual threads 비활성). DB 연결은 정상 검색에서 Supplier HTTP 호출 전에 반환되므로 DB 10개와 Supplier 검색 8+8개를 숫자만 비교해 병목을 판정하지 않는다. 점유 시간·배치 수·동시 요청 수를 함께 측정해야 한다. [Spring Boot 4.0 기본 설정](https://docs.spring.io/spring-boot/4.0/appendix/application-properties/index.html)

### 18.3 실패와 고객 응답

reader는 트랜잭션 시작·조회·종료 전체 바깥에서 예상된 자원 실패만 `CatalogReadException`으로 변환한다. SQL timeout/취소, lock timeout, deadline 소진은 TIMEOUT이고 연결 확보 실패·연결 단절은 UNAVAILABLE이다. SQL 문법·무결성·미분류 프로그래밍 오류는 내부 오류로 남긴다. PostgreSQL SQLState로 판별하며 메시지 문자열에 의존하지 않는다.

이 두 예상 실패에는 Supplier HTTP를 시작하지 않고 모든 활성 Supplier에 `CATALOG_UNAVAILABLE`을 기록하여 기존 HTTP 503 / FAILED 계약을 사용한다. 이 코드는 해당 Supplier 검색에 필요한 카탈로그를 사용할 수 없다는 뜻이며, Supplier 서버 자체의 장애를 단정하지 않는다. 고객은 이 코드만으로 활성 매핑 미확보, 정상 빈 카탈로그, 우리 DB의 읽기 실패를 구분할 수 없다. 정상 빈 카탈로그와 동기화 미완료를 구별하는 영속 상태가 없어 매핑 조회 결과만으로 두 상태를 구분하지 못하는 기존 절충도 유지한다. Supplier 검색 API가 반환한 정상 빈 결과는 성공으로 처리한다.

현재 공개 응답은 검색 가능 여부를 전달하고, DB 실패의 상세 원인은 로그와 `search.catalog.read.failures{reason=TIMEOUT|UNAVAILABLE}`에서 구분한다. 검색 전체 FAILED·DATABASE 구간 시간·Supplier 실패 지표도 기록한다. 모든 Supplier가 CATALOG_UNAVAILABLE인 응답만으로 여러 Supplier의 동시 장애라고 판단하지 않고 DB 읽기 지표와 로그를 먼저 확인한다. 고객 응답에는 DB 메시지나 SQL을 넣지 않는다. 이는 공개 오류의 상세 수준을 선택한 절충이며 enum 추가를 금지하는 원칙은 아니다. 공개 API에서 원인별 처리가 필요해지면 별도 읽기 실패 코드를 검토한다.

현재 계층 경계는 검색 application이 조회 포트에 같은 JVM의 단조시계 deadline을 전달하고 `CatalogReadException`을 처리하는 형태다. JPA/JDBC 트랜잭션·PostgreSQL timeout·SQLState 분류는 인프라 어댑터가 담당한다. 포트의 `long`은 epoch 시각이나 남은 Duration이 아니며, 기준·단위·만료 동작을 Javadoc에 명시한다. 구체 호출 자원·지표 구현에 대한 application의 결합은 §17.1의 절충으로 유지한다.

### 18.4 검증과 남은 범위

실제 PostgreSQL에서 조회 잠금·느린 SQL·연결 부족과 실패 후 회복, 트랜잭션 범위의 timeout 설정 복원을 검증한다. 독립 commit 이후 누락·비활성·재등장에서 ID를 유지하고, 실제 중간 쓰기 실패의 rollback과 Supplier별 독립 반영을 확인한다. `CatalogSynchronizationConcurrencyTests`는 추가 스레드에서 서비스를 직접 호출해 조회·retry backoff·저장 대기 중 중복 거부와 실패 후 반환 계약을 검증한다. 현재 운영 스케줄에서 중복 실행이 발생한다는 재현 테스트는 아니다. 구체 테스트와 최종 실행 결과는 JOURNAL에 남긴다.

데이터 조회용 단일 projection과 인덱스를 유지하며, 앞서 설명한 설정 SQL 1개가 추가된다. DB 감사의 N+1 부재 결론에 따라 N+1 수정이나 근거 없는 인덱스·batch/upsert 전환을 추가하지 않았다. 다중 동기화 인스턴스, 고객 검색 진입 제한, 데이터 버전, 생산 환경 부하 시험은 별도 확장 범위다. DB·별도 Mock·고객 HTTP 전체 연결은 8-D에서 검증했다(§19).


## 19. 8-D 실제 프로세스 전체 연결 검증

전체 연결 테스트는 별도 `e2eTest` 소스 경로와 Gradle 작업으로 분리한다. PostgreSQL 17 Testcontainers를 띄우고 메인·Mock 실행 JAR을 각각 별도 JVM에서 실행하여, 실제 스케줄 동기화와 commit 이후 고객 HTTP 응답까지 확인한다. 직접 서비스 호출이나 테스트용 Supplier HTTP 대역으로 이 연결을 대신하지 않는다. `check`에는 기존 단위·통합 테스트와 E2E를 함께 포함한다.

E2E 9개는 정상 1박·3박 가격과 연박 재고, DB 내부 ID·이름, 인원 초과, 입력 400, A HTTP 오류·B 본문 오류의 부분 성공, 전체 실패 503, 지연 timeout, 실제 DB 잠금과 정상 복구를 검증한다. 이번 검증에서 메인 270개와 E2E 9개를 실행했고 Mock 6개는 기존 성공 결과를 재사용했다. 실패·오류·건너뜀은 없다.

임시 DB·작업 디렉터리·자동 배정 포트를 사용하여 로컬 설정과 격리하고, 종료 시 프로세스와 DB를 정리한다. 실제 HTTP 응답과 로그를 실행별로 보존한다. 측정 시간은 해당 환경의 단일 관측값이며 처리 용량이나 전체 요청의 엄격한 5초 상한을 증명하지 않는다. 구성·기대값·재실행 방법과 한계는 [전체 연결 검증](e2e-verification.md)에 기록한다. 고정 포트 `bootRun`·Compose 수동 절차와 깨끗한 checkout의 최종 재현은 9단계에서 확인한다.
