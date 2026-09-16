# 중간 기술 감사: 네트워크·메모리·전역 동시성

## 1. 결론과 범위

- 작성일: 2026-09-15
- 기준 커밋: `eff5bb83a940d8d29a778b9de0d041d888771388`
- 범위: 7.5-C. 외부 호출 수, 동시 검색, timeout과 연결 종료, 응답 크기, 메모리 할당, 관측성
- 환경: Java 21.0.12.1, Spring Boot 4.0.8, Reactor Netty 1.3.7. MVC 검증은 실제 Tomcat과 별도 PostgreSQL 17.11 Testcontainers 사용
- 임시 검증 11개 성공: HTTP 7개, 실제 Boot 2개, 메모리 2개. 문제 동작을 재현한 테스트의 통과가 문제 해결을 뜻하지 않는다.

**요청별 동시 호출 제한과 timeout 취소는 동작한다. 다만 동시 고객 수가 늘면 Supplier 호출도 함께 늘며, 정상 형식의 카탈로그·검색 응답이 현재 256 KiB 버퍼 한도를 초과해 실패했다. 8단계는 전역 자원 제한, 응답 크기 정책, 업무 결과 지표를 먼저 보완하는 것이 적절하다.**

제품 코드·설정·정식 테스트 소스는 변경하지 않았다. 이전 감사 문서도 유지했다. [측정 근거](audit-evidence/network-memory.json)에 측정 조건, 원시 수치, 테스트 이름과 임시 검증 소스를 보존했다. [정확성 감사](correctness-audit.md), [DB 감사](database-audit.md)와 함께 읽는다.

## 2. 확인 결과 요약

| 항목 | 관측 결과 | 판단 |
| --- | --- | --- |
| 숙소 251개, Supplier A/B | 각각 6회 호출, 각각 최대 동시 4회 | 요청 단위 제한 정상. A 전송 코드 수는 50×5 + 1 확인 |
| 고객 검색 8개 동시 실행 | A 최대 32회, B 최대 32회 동시 호출 | N-01: 프로세스 전체 Supplier 제한 필요 |
| 전체 timeout | 완료 Offer 1개 보존, 진행 중 연결 종료, 다음 대기 배치 미전송 | 확인한 HTTP 취소 경로 정상 |
| A/B response·call timeout | 모두 TIMEOUT, 각각 전송 1회, 상대 서버 연결 종료 관측 | 확인한 4개 경로에서 재시도 없음 |
| A 카탈로그 3,000개 | 369,797 bytes, 버퍼 초과 실패 | N-02: 수천 개 규모 지원에 직접 영향 |
| A 숙소 50개 × 객실 5개 × 30박 | 613,716 bytes, 버퍼 초과, A 실패 UNKNOWN, 통합 PARTIAL | N-02: 숙소 개수 제한만으로 응답 크기를 제한하지 못함 |
| 고객 TCP 연결 종료 | 300ms 뒤에도 외부 호출 2개 진행 중 | N-03: 고객 연결 종료가 즉시 취소로 전달되지 않음 |
| B HTTP 200 + 본문 E503 | 업무 결과 UNAVAILABLE, 기본 HTTP 지표 SUCCESS | N-04: 업무 실패 지표 필요 |
| 빈 검색, 숙소 6,000개·객실 30,000개 | 호출 스레드에서 약 9.80 MB 할당 | N-05: 요청별 작업량이 전체 카탈로그에 비례 |
| A Offer 1,000개, 30박 | 결과에서 접근 가능한 객체 약 3.24 MB | N-05: 일별 가격 보존 비용 확인. peak heap 측정은 아님 |

## 3. N-01: 요청별 제한은 프로세스 전체 제한이 아니다

### 재현과 영향

같은 `IntegratedSearchService`에 고객 검색 8개를 동시에 실행했다. 각 Supplier의 매핑은 숙소 200개·객실 1개이고, 실제 로컬 HTTP 서버가 응답을 대기시켰다. 설정의 `maxConcurrency=4`에서 A/B 모두 실제 진행 중 요청이 32개에 도달했다. 응답을 해제한 뒤 8개 검색 모두 COMPLETE로 종료됐다.

이 검증은 서비스 동시 호출과 실제 HTTP를 결합했다. DB 조회는 고정 projection으로 대체했으며, 실제 Tomcat을 통한 고객 8명 부하 시험은 아니다. 동시 진입을 안정적으로 관측하기 위해 response/call/overall timeout을 6/7/8초로 늘렸다.

원인은 `IntegratedSearchService.execute`의 `flatMap(..., maxConcurrency)`가 각 검색의 Supplier 계획에 새로 적용되기 때문이다. 4개는 전체 서버의 Supplier 호출 허용량이 아니다. 동시 검색마다 DB projection, 매핑, 배치 결과도 별도로 만들어진다.

**현재 연결 풀이 무제한이라는 뜻은 아니다.** 실제 Boot에서 기본 provider의 `maxConnections=500`, acquire timeout 기본값 45,000ms를 읽었다. 기본 pending 상한은 연결 수의 2배이며, 풀은 원격 주소와 채널 설정으로 나뉜다. 500을 모든 Supplier가 공유하는 단일 전역 허용량으로 해석하면 안 된다. [Reactor Netty 연결 풀](https://projectreactor.io/docs/netty/release/reference/http-client.html#_connection_pool)

현재 A/B 기본 URL은 같은 Mock 서버다. WebClient bean을 분리한 것만으로 독립된 풀을 보장하지 않는다. 실제 운영에서 원격 주소가 다르면 풀도 분리될 수 있다. 풀 포화, pending 거부, 같은 주소의 검색·카탈로그 간 자원 경쟁은 이번에 부하로 재현하지 않았다.

45초는 풀 자체의 대기 기본값이다. 현재 검색의 3초 call timeout과 5초 overall timeout이 더 바깥에서 제한하므로, 고객이 반드시 45초를 기다린다는 진단도 맞지 않는다.

### 8단계 제안

- Supplier별 프로세스 전체 동시 호출 수와 대기 정책을 명시한다. 대기열 없이 빠르게 거부하거나, 짧은 시간·개수로 제한한다.
- 허용량 획득은 실제 구독 시점, 반환은 성공·실패·취소 모두에서 처리한다. 이벤트 루프에서 blocking semaphore 획득을 하지 않는다.
- 용량 초과와 외부 장애를 구분하고, 거부된 Supplier가 있어도 다른 Supplier 결과를 보존한다.
- `ConnectionProvider`의 연결 수·pending 수·대기 시간을 명시하고 애플리케이션 허용량과 맞춘다. 풀 크기를 크게 늘리는 것만으로 해결하지 않는다.
- 검색 진입 단계의 제한도 검토한다. 외부 호출만 제한하면 이미 수행한 DB 조회·매핑 할당은 줄어들지 않는다.

이 제한은 JVM 단위다. 다중 인스턴스 합산 허용량은 별도 문제이며, 1차 범위에서 분산 제한까지 바로 구현할 필요는 없다. 기본적인 자원 제한을 먼저 만들고 필요성이 확인되면 circuit breaker를 추가한다. 검색 retry 추가는 호출 증폭과 남은 시간 예산을 함께 설계해야 한다.

관련 코드: `search/application/IntegratedSearchService.java:79,175`, `supplier/common/SupplierClientConfiguration.java:78`.

## 4. N-02: 정상 크기의 응답이 버퍼 한도에서 실패한다

### 재현

실제 `SupplierClientConfiguration`으로 만든 WebClient와 실제 A client에 합성 JSON을 전달했다. 비정상적으로 긴 문자열이나 소수 금액을 넣지 않았다.

| 응답 | 구성 | UTF-8 크기 | 결과 |
| --- | --- | ---: | --- |
| A 카탈로그 | 숙소 3,000개, 숙소당 객실 1개 | 369,797 bytes | `WebClientResponseException → DataBufferLimitException` |
| A 검색 | 숙소 50개, 객실 5개, 날짜별 가격·재고 30일 | 613,716 bytes | 같은 예외. B 정상 빈 결과와 통합하면 PARTIAL, A UNKNOWN |

로그에 실제 한도 `262144`가 출력됐다. 이 두 검증은 Boot 컨텍스트 전체가 아닌 기본 builder와 제품의 client 설정을 사용했다. 현재 YAML에는 codec 한도 재정의가 없다. Spring 기본 codec의 버퍼 한도와 `maxInMemorySize` 설정 방법은 공식 문서에도 설명돼 있다. [Spring WebClient 메모리 한도](https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html#webflux-client-builder-maxinmemorysize)

`bodyToMono(응답 DTO)`는 전체 응답을 조립한다. 숙소를 50개로 나누어도 객실 수·요금 옵션 수·박수에 따라 응답 크기가 달라진다. 위 검색은 현재 허용하는 30박과 정확히 50개 숙소를 사용했다. 수천 개 카탈로그의 처리 한계와 함께 8단계에서 해결할 가치가 높다.

현재 오류 변환은 `DecodingException`, `WebClientRequestException` 등을 다룬다. 이번 버퍼 초과는 `WebClientResponseException`으로 감싸져 UNKNOWN까지 도달했다. 카탈로그도 코드상 일반 예외 경로에 해당하며 재시도 대상이 아니다. 이번에는 큰 카탈로그를 실제 DB 동기화 끝까지 실행하지 않았으므로 저장 보존까지 추가 검증했다고 주장하지 않는다.

### 8단계 제안

1. 카탈로그와 검색의 지원 크기를 별도로 정한다. 수천 개 숙소 및 50개·30박 fixture를 수용할 근거 있는 유한한 codec 한도를 둔다.
2. 한도 초과를 명시적인 외부 응답 크기 실패로 분류하고 관측한다. 같은 큰 응답을 무조건 재시도하지 않는다.
3. 최대 크기 × 동시 수신 개수와 파싱 후 객체 비용을 함께 계산한다. 버퍼를 무제한으로 바꾸지 않는다.
4. 더 큰 규모가 필요하면 공급사가 지원하는 pagination, 더 작은 검색 배치, 항목별 스트리밍을 검토한다. envelope 안의 배열을 `bodyToFlux`로 이름만 바꾸면 해결된다고 보지 않는다.

숫자 엄격 파싱과 항목별 오류 격리(C-01/C-02)를 수정할 때 응답 크기 경계도 함께 설계한다. 다만 카탈로그는 완전한 snapshot 검증과 원자적 반영이 필요하므로 일부만 파싱된 목록을 정상 전체 목록으로 저장해서는 안 된다.

관련 코드: `supplier/suppliera/SupplierASearchClient.java:76`, `supplier/supplierb/SupplierBSearchClient.java:77`, A/B catalog client의 `bodyToMono`, `search/application/IntegratedSearchService.java:360`.

## 5. timeout과 취소: 정상 경로와 남은 경계

### 확인한 정상 동작

- A/B 각각 응답이 오지 않는 서버를 연결했다. response timeout 180ms 경로와 call timeout 250ms 경로 모두 TIMEOUT으로 분류됐다.
- 관측 시간은 A 194/267ms, B 202/268ms였다. 이 수치는 상대 서버의 연결 종료를 확인하는 대기까지 포함하며 정확한 고객 응답 지연 측정값은 아니다.
- 네 경우 모두 상대 서버 연결 종료를 확인했고 전송은 각각 1회였다.
- 전체 timeout 700ms, A 101개 숙소·3개 배치·동시 1개 조건에서 첫 배치의 Offer 1개를 보존했다. 두 번째 배치는 연결이 닫혔고 세 번째는 전송하지 않았다. 이후 같은 client 설정으로 정상 호출도 성공했다.
- 서버가 요청을 받은 직후 연결을 끊은 별도 A 실험은 전송 1회와 `PrematureCloseException`을 관측했다. 이 실험에서 숨은 재시도가 발생했다고 결론내리지 않는다.

취소 확인은 상대 TCP 연결 및 테스트 서버 publisher 수준이다. 실제 Supplier의 내부 업무 처리까지 취소됨을 보장하지 않는다. Reactor Netty 소스에는 연결 reset 조건에 따른 재시도 분기가 있으므로, 이번 관측을 모든 전송 장애에 재시도가 없다는 보장으로 일반화하지 않는다.

### N-03: 고객 연결 종료는 즉시 전파되지 않는다

실제 Boot/Tomcat 검색 API에 TCP 요청을 보내고 A/B 호출이 시작된 뒤 고객 소켓을 RST로 종료했다. 300ms 뒤에도 외부 요청 2개는 완료되지 않았고 상대 연결도 닫히지 않았다. 테스트 대기를 해제하자 두 요청이 정상 종료됐다.

현재 MVC 컨트롤러는 동기 서비스 호출을 기다리고 서비스는 `.block()`을 사용한다. 외부 호출이 정상 완료되거나 자체 timeout에 도달하기 전까지 고객이 떠난 작업이 이어질 수 있다. **영구적인 연결 누수나 무한 대기를 재현한 것은 아니다.**

8단계에서는 전역 동시성 제한과 시간 예산으로 이 잔여 작업의 비용을 먼저 제한한다. 고객 연결 종료를 이유로 MVC+JPA 전체를 WebFlux로 바꾸는 것은 현재 근거에 비해 변경 범위가 크다. 실제 배포 경로의 연결 종료 감지와 취소 연동은 후속 검증으로 남길 수 있다.

### 전체 시간 예산의 한계

DB 잠금 대기가 overall timeout을 넘는 문제는 [DB 감사 D-02](database-audit.md)에 이미 재현했다. 검색 매핑 조립은 Reactor 구독 전, 결과 정렬·메타데이터 결합과 API 직렬화는 외부 호출 대기 이후에 실행된다. 따라서 현재 5초 설정을 HTTP 응답 완료까지의 엄격한 상한이라고 설명하면 안 된다.

client의 동기 정규화에는 별도 scheduler 전환이나 루프 취소 확인이 없다. CPU 작업·로그 쓰기로 이벤트 루프가 지연될 가능성은 코드상 검토 대상이다. 이번에는 CPU 포화나 정규화 중 취소를 측정하지 않았다. 무조건 `publishOn`을 추가하기 전에 프로파일링하고, 옮긴다면 executor의 작업 수와 대기열도 제한한다.

## 6. N-04: 기본 HTTP 지표로 업무 실패를 구분할 수 없다

실제 Boot에 주입된 B client에 `HTTP 200`과 `{"resultCode":"E503","data":null}`을 반환했다.

- client 결과: `SupplierIntegrationException`, `UNAVAILABLE`
- 완료된 `http.client.requests` 지표: `status=200`, `outcome=SUCCESS`, `error=none`, `exception=none`
- 이 지표의 태그: `client.name=127.0.0.1`, `uri=none`. Supplier 업무 결과 태그 없음

기본 HTTP 지표는 HTTP 응답을 설명하므로 이 기록 자체가 오류는 아니다. 애플리케이션의 부분 실패·전체 실패·본문 실패를 별도로 관측해야 한다. 현재 설정은 Actuator의 health/info만 노출하고, 코드에는 Supplier 업무 지표가 없다. 기존 로그에는 전체 검색 시간·최종 상태·배치 실패가 있어 진단 출발점은 있다.

8단계 최소 지표 제안:

- 검색 전체 시간과 COMPLETE/PARTIAL/FAILED 횟수
- Supplier별 batch 시간·결과·실패 유형·수용/거부 Offer 수
- 진행 중 호출, 대기·용량 초과 횟수, HTTP 풀 active/pending
- DB 조회, 매핑 조립, 외부 호출, 결과 조립 구간별 시간
- 카탈로그 마지막 성공 시각, 실패 횟수, 갱신 소요 시간

Spring/Micrometer의 `Timer`, `Counter`, `Observation`과 pool metrics를 활용하면 별도 측정 프레임워크를 만들 필요가 없다. URI·숙소 코드·고객 요청 ID를 metric tag로 쓰지 않고 제한된 Supplier/작업/결과를 사용한다. trace exporter나 별도 대시보드까지 모두 구현하는 것은 선택 사항이다.

## 7. N-05: 메모리와 중복 작업

### 7.1 빈 결과에서도 카탈로그 크기만큼 할당한다

Supplier A만 활성화하고 모든 HTTP 결과를 즉시 빈 값으로 돌려주는 test client를 사용했다. 실제 검색 서비스를 3회 예열 후 5회 측정했다.

| 숙소 / 객실 | 입력 projection에서 접근 가능한 객체 크기 | 검색 1회 할당량 중앙값 | 검색 시간 중앙값 |
| --- | ---: | ---: | ---: |
| 1,000 / 5,000 | 1,260,064 bytes | 1,633,280 bytes | 2.44ms |
| 6,000 / 30,000 | 7,560,064 bytes | 9,798,000 bytes | 11.23ms |

할당량은 `ThreadMXBean`으로 호출 스레드의 누적 할당 차이를 측정했다. DB projection 생성, 실제 HTTP/JSON, 비동기 스레드, GC와 native buffer는 포함하지 않는다. 고정 fixture를 재사용했으며 로그 및 작은 측정 장치 비용이 포함된다. 값이 커졌다고 같은 양이 모두 동시에 살아 있다는 뜻도 아니다. 위 시간은 서비스 로직의 로컬 빈 결과 측정이며 운영 지연·처리량이 아니다.

7단계에서 전체 카탈로그를 결과에 복사하던 문제는 제거됐다. 그러나 요청 조립에 필요한 원본 rows, 정렬 목록, grouped mapping은 요청마다 만들어진다. 실제 Offer가 0개여도 이 비용은 남는다. 동시 고객 제한과 함께 평가할 대상이다.

### 7.2 일별 가격 보존 비용

Java instrumentation으로 결과 객체에서 접근 가능한 객체의 shallow size를 중복 없이 합산했다. static 필드는 제외하고 enum/Class는 말단으로 처리했다.

| Supplier | Offer 수 | 박수 | application 결과 객체 그래프 | API 응답 객체 그래프 |
| --- | ---: | ---: | ---: | ---: |
| A | 1,000 | 1 | 324,360 bytes / 12,015개 | 276,400 bytes / 10,016개 |
| A | 1,000 | 30 | 3,244,360 bytes / 129,015개 | 276,400 bytes / 10,016개 |
| B | 1,000 | 1 | 228,336 bytes / 8,014개 | 276,400 bytes / 10,016개 |
| B | 1,000 | 30 | 228,336 bytes / 8,014개 | 276,400 bytes / 10,016개 |

현재 A의 `Price`는 검증 후에도 일별 금액·날짜를 유지하고 API는 총액을 출력한다. 이 차이는 측정됐지만 곧바로 `nightlyBreakdown` 삭제가 정답은 아니다. 일별 근거를 내부 모델에 보존하는 설계와 서비스 요구사항을 확인한 뒤, 필요하다면 출력용 내부 결과를 더 작게 만드는 방향을 검토한다.

위 값은 합성 객체 그래프의 크기다. heap dump의 독점 retained heap, 프로세스 RSS, peak heap, 응답 JSON 크기가 아니다. 두 그래프가 공유하는 객체가 있으므로 단순 합산으로 실제 동시 점유량을 계산하지 않는다. JVM 설정과 문자열 공유 방식에 따라서도 달라진다.

### 7.3 변경 비용이 작은 최적화 후보

| 위치 | 확인한 중복 작업 | 판단 |
| --- | --- | --- |
| `IntegratedSearchService:119` | 개수 로그를 위해 `result.offers()` 전체 목록 생성 | 각 Supplier 결과 크기를 합산하는 방식 검토 |
| `IntegratedSearchResult:33,39` | 호출할 때마다 전체 목록 평탄화 | 호출부 요구에 맞게 순회/집계. 무조건 캐시 필드 추가는 불필요 |
| `RoomTypeRepository:46` + `IntegratedSearchService:329` | DB 정렬 후 Java에서 같은 기준 정렬 | reader의 정렬 보장 계약을 정한 뒤 한쪽 제거. 현재 추상 port가 순서를 보장하는지 먼저 확인 |
| `Price.fromNightlyPrices` + 생성자 | 총액 합산과 날짜 중복 검사를 반복 | 외부 생성 경로의 불변식 검증은 보존. 현재 CPU 병목으로 측정되지는 않음 |
| `enrichOutcomes` | 전체 Offer 임시 목록, ID 집합, 메타데이터 map | 추가 목록 제거 후보. 현재 HashMap/HashSet 조인 자체는 적절 |

Supplier 수가 작으므로 `EnumMap/EnumSet`은 자연스럽고, 내부 ID 조인의 HashMap/HashSet도 적절하다. primitive collection 라이브러리, 별도 캐시 서버, 복잡한 객체 풀 도입 근거는 없다. immutable 매핑 snapshot 캐시는 가능하지만 동기화 commit 이후 교체·다중 인스턴스 무효화·이전 snapshot 생존 비용이 생기므로 후속 최적화로 남긴다.

## 8. 8단계 반영 순서와 완료 기준

앞선 정확성·DB 정합성 문제는 이 감사로 대체되지 않는다. 아래는 이번 감사에서 추가되는 작업이다.

| 순서 | 작업 | 완료 기준 |
| --- | --- | --- |
| 1 | Supplier별 전역 허용량과 제한된 대기 | 여러 고객에서도 한도 유지, A 포화 때 B 정상, 취소·실패 후 허용량 반환 |
| 2 | 카탈로그/검색 응답 크기와 실패 분류 | 지원 크기의 정상 JSON 성공, 초과 응답은 명확히 실패, 유한한 메모리 한도 유지 |
| 3 | 전체 시간 예산과 DB/pool 대기 연결 | DB 감사의 잠금 대기 사례 보완, pool 대기를 포함한 실패/취소 회귀 검증 |
| 4 | 최소 업무 지표와 구간별 시간 | HTTP 200 본문 오류를 업무 실패로 측정, 부분 실패·거부·timeout 구분 |
| 5 | 저비용 할당 감소 | 로그용 목록 등 명백한 중복부터 제거, 정상화 불변식과 결과 순서 유지 |

후속 개선: 고객 연결 종료의 즉시 취소, 매핑 snapshot 캐시, 일별 가격 객체 축소, CPU 정규화 scheduler 분리, circuit breaker, 분산 동시성 제한, 스트리밍 카탈로그. 이들을 한꺼번에 구현하지 않는다.

## 9. 검증 범위와 다음 감사

- 7개 HTTP 테스트는 실제 로컬 서버와 제품 client/서비스를 사용했다. 2개 Boot 테스트는 실제 주입 client와 MVC를 검증했다. 2개 메모리 테스트는 합성 fixture 기반 측정이다.
- 기존 95개 테스트는 정확성 감사의 결과를 기준점으로 사용했다. 제품 변경이 없어 다시 전체 실행하지 않았다.
- 기본 풀 500개 포화, DNS/TLS/connect blackhole, 느린 chunk 전송, HTTP/2, 실제 Supplier rate limit, 장시간 GC/RSS/이벤트 루프 포화, 대량 최종 JSON 직렬화, 실제 다중 JVM은 검증하지 않았다.
- 실행 로그에는 macOS native DNS resolver 로딩 실패 후 시스템 기본값으로 대체했다는 메시지가 있었다. 이번 HTTP 검증은 `127.0.0.1`을 사용했으므로 DNS 장애 검증으로 볼 수 없다.
- 임시 테스트 중 일부는 예외를 포괄적으로 assert하고 세부 종류·수치는 로그로 관측했다. 8단계 정식 회귀 테스트에서는 필요한 종류·한도·반환 후 회복까지 명시적으로 assert한다.
- 다음 7.5-D는 API/application/domain/infrastructure 경계, SOLID, Supplier 추가 시 변경 범위, 검증·예외의 중복과 누락, 테스트 분류다. 이후 전체 감사 결과를 하나의 8단계 구현 목록으로 확정한다.
