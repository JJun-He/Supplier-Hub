# 개발 기록

의미 있는 판단과 검증을 당시 시점에 맞춰 기록한다. 현재 계약은 [설계 문서](docs/architecture-decisions.md), 실행 절차는 [README](README.md), AI 제안의 수용·수정·보류 근거는 [AI_USAGE](AI_USAGE.md)를 참고한다. 과거 계획과 감사 자료는 [보관 문서](docs/archive/)에 남겼으며 기존 커밋 이력은 유지했다.

## 2026-09-12 — 도메인과 연동 정책

공통 식별자가 없는 Supplier의 숙소를 추정해 병합하지 않고 각각 내부 ID에 매핑하기로 했다. UUID도 검토했지만 하나의 관계형 DB가 ID를 발급하는 현재 범위에서는 `BIGINT IDENTITY`를 선택했다. 가격·재고·조식 조건은 Offer로 모델링하고, 고객 응답은 숙소와 객실 타입으로 묶는다.

- 가격은 통화의 최소 단위 정수로 표현하는 세금 포함 숙박 전체 총액이다. 원본 통화를 유지하고 Supplier가 제공하지 않은 일별 가격은 역산하지 않는다. 일별 가격 근거는 내부에 보존하되 검색 응답에서는 제외했다.
- 예약 가능한 상품을 검색하므로 품절 Offer는 제외한다. 독립적으로 검증할 수 있는 정상 Offer는 같은 응답의 다른 항목이 잘못되어도 보존한다. 부분 결과는 HTTP 200, 전 Supplier 실패는 HTTP 503으로 구분했다.
- 기동 시 한 번만 동기화하면 최초 장애가 복구되어도 상품을 확보하지 못하므로 주기 동기화를 추가했다. 사라진 매핑은 삭제하지 않아 재등장 시 ID를 유지한다.
- Supplier 계약이 날짜·인원을 받는 요청/응답 방식이므로 WebSocket·MQ·Kafka를 도입하지 않았다. 검색 요청의 50개 분할은 영속 배치 작업이 아니므로 Spring Batch도 보류했다. Spring MVC·JPA를 유지하고 외부 HTTP만 `WebClient`로 병렬 처리한다.
- 실시간 검색 재시도는 응답 지연과 장애 중 호출량을 늘리므로 제외했다. 카탈로그의 일시 장애에는 제한 재시도를 적용하기로 했다.

이날은 제품 코드 구현 전 대안을 비교하고 정책을 정했다. [설계·계획 커밋 `6ab923e`](https://github.com/JJun-He/Supplier-Hub/commit/6ab923e)에 최초 근거가 있다.

## 2026-09-13 — 실행 기반과 카탈로그 생명주기

Compose PostgreSQL, Flyway, Hibernate 스키마 검증과 별도 Mock Supplier를 구성했다. 테스트 DB는 제품 DB와 같은 PostgreSQL의 Testcontainers를 사용해 로컬 개발 DB와 격리했다.

숙소는 Supplier·외부 숙소 코드, 객실 타입은 내부 숙소 ID·외부 객실 코드에 고유 제약을 두었다. 동기화는 비활성 매핑까지 읽고 검색은 숙소·객실이 모두 활성인 매핑만 읽는다. HTTP 호출은 트랜잭션 밖에서 끝내고, 검증한 전체 snapshot을 Supplier별 트랜잭션에서 반영했다.

구조 오류나 장애성 빈 목록은 기존 데이터를 지우지 않도록 거부했다. 최초 동기화는 준비 완료 흐름을 막지 않도록 스케줄러 스레드에서 실행했고, 기존 객실 조회는 숙소별 반복 대신 Supplier 단위 조회로 모았다. 당시에는 정상 snapshot의 한 번 누락으로 비활성화했으며, 이후 아래의 연속 누락 정책으로 바꿨다.

**검증:** Compose 기동과 health `UP`, Mock의 정상·오류·무응답, DB 고유 제약, 이름 변경·재등장 시 ID 유지, Supplier별 실패 격리와 최초 동기화 스레드를 확인했다. 동기화 구현 시점 `./gradlew test`는 20개가 성공했다.

관련 커밋: [실행 기반 `8fd29e2`](https://github.com/JJun-He/Supplier-Hub/commit/8fd29e2), [Mock `c41d5ce`](https://github.com/JJun-He/Supplier-Hub/commit/c41d5ce), [매핑 저장 `c0f069e`](https://github.com/JJun-He/Supplier-Hub/commit/c0f069e), [동기화 `c6e5264`](https://github.com/JJun-He/Supplier-Hub/commit/c6e5264).

## 2026-09-13~14 — 가격·재고 정규화와 Supplier 어댑터

Supplier A의 일별 세전가와 세금을 합산하고 B의 세금 포함 총액은 그대로 보존했다. 금액은 `long`으로 다루고 오버플로와 다른 통화의 합산을 거부한다. 체크아웃일을 제외한 모든 숙박일의 재고 중 최솟값을 연박 재고로 사용한다.

초기 정규화는 완성된 후보만 보호하여 통화·금액 변환에서 실패한 형제 Offer를 잃을 수 있었다. 원본 항목을 도메인 후보로 바꾸는 과정 전체를 격리 범위에 넣었다. `Offer`도 검증을 우회할 수 있는 public record에서 private 생성자를 가진 불변 클래스로 바꿨다.

어댑터는 전달받은 1~50개 활성 숙소 매핑으로 요청한다. A의 HTTP 오류와 B의 HTTP 200 본문 오류를 공통 실패로 변환하고, 카탈로그와 검색의 제한 시간을 분리했다. Mock에는 검색에만 적용되는 장애 모드와 요청 날짜·숙소 코드에 맞는 동적 응답을 추가했다. 카탈로그에는 응답 제한과 별도의 호출 전체 제한을 적용했다.

**검증:** 가격·날짜·인원 경계, 재고 `3, 1, 5`의 최솟값, 품절 제외, 후보별 오류 격리, 요청 경로·인증 헤더, 본문 오류, 무응답과 재시도 정책을 확인했다. 연동 안정성 보완 후 메인 57개·Mock 6개가 성공했다.

관련 커밋: [도메인 `d431183`](https://github.com/JJun-He/Supplier-Hub/commit/d431183), [검색 어댑터 `4cc38da`](https://github.com/JJun-He/Supplier-Hub/commit/4cc38da), [장애 재현 `e3ab3c0`](https://github.com/JJun-He/Supplier-Hub/commit/e3ab3c0), [연동 안정성 `66354c8`](https://github.com/JJun-He/Supplier-Hub/commit/66354c8).

## 2026-09-14~15 — 카탈로그 누락 보호와 제한 병렬 검색

HTTP 성공만으로 전체 목록의 완전성을 확정하기 어려워 정상 snapshot에서 2회 연속 누락될 때 비활성화하도록 바꿨다. 재등장하면 ID를 유지하고 누락 횟수를 초기화한다. 실패·거부된 snapshot은 횟수를 바꾸지 않는다.

이후 기존 활성 매핑이 전부 사라지거나, 10개 이상이면서 50%를 초과해 누락되면 전체 snapshot을 거부하는 급감 가드를 추가했다. 이 수치는 관측 데이터가 없는 상태의 보수적인 초기값이다. 방어가 반복되면 기존 매핑이 오래 유지되는 절충이 있으므로 운영 관측 없이 정상 변동에 대한 완전한 정책으로 설명하지 않는다.

검색은 활성 projection을 Supplier별로 묶고 숙소 50개씩 나눈다. Supplier 간에는 동시에 시작하고, Supplier 안에서는 고객 요청별 병렬 제한을 적용한다. 전체 예산 소진 시 완료 결과를 보존하고 미완료 배치만 `TIMEOUT`으로 집계한다. 정상 빈 결과는 성공이며, 인원 초과는 잘못된 데이터가 아닌 정상 제외다.

Supplier 비활성 설정은 외부 호출·결과 집계에서 제외하되 DB 매핑을 보존하는 운영 스위치로 정했다. DB 트랜잭션은 projection 조회에만 두어 외부 호출 동안 연결을 붙잡지 않는다. 숙소 코드의 쉼표는 목록 구분자와 충돌하므로 수신·요청 경계에서 거부한다.

**검증:** 누락→비활성→재등장 ID 유지, 급감 snapshot 거부, 51개 숙소의 50+1 분할, Supplier 간 동시 시작, 요청별 동시 호출 4개 제한, 부분 실패·취소 시 완료 Offer 보존, 정상 빈 결과와 비활성 Supplier를 확인했다. 경계 보완 후 메인 80개·Mock 6개가 성공했다.

관련 커밋: [연속 누락 `a7c86be`](https://github.com/JJun-He/Supplier-Hub/commit/a7c86be), [통합 검색 `5538798`](https://github.com/JJun-He/Supplier-Hub/commit/5538798), [카탈로그·코드 경계 `109b3a4`](https://github.com/JJun-He/Supplier-Hub/commit/109b3a4).

## 2026-09-15 — 고객 API와 결과 경량화

`GET /api/v1/stays/search`에서 내부 ID·이름과 Offer를 숙소·객실별로 반환하고 Supplier 원본 코드는 감췄다. 부분 실패는 HTTP 200, 전체 실패는 상세 본문을 가진 HTTP 503, 잘못된 고객 입력은 Supplier 호출 전 HTTP 400으로 처리한다.

처음에는 전체 활성 카탈로그를 검색 결과에 복사하고 API에서 Offer와 조인했다. 응답에 필요한 것은 반환된 Offer의 이름뿐이므로 application의 `SearchOffer`에서 결합하도록 바꿨다. 전체 projection 조회·순회는 여전히 필요하지만 응답용 메타데이터는 반환된 객실에 대해서만 구성한다. 이름·ID 불일치는 내부 불변식 위반으로 드러내며 정상 빈 결과로 숨기지 않는다.

공개 요청의 자원 사용량을 제한하는 초기값으로 최대 30박을 두고 `children` 생략은 0명으로 처리했다. 총액 필드는 `totalAmountIncludingTax`로 명시했다. 이 구조 변경의 처리 시간·GC·peak heap 개선율은 측정하지 않았다.

**검증:** 정상·부분·전체 실패 JSON, 원본 코드 비노출, 빈 배열, 30박 허용·31박 거부, 자녀 수 기본값과 반환 Offer만의 메타데이터 결합을 확인했다. 메인 89개·Mock 6개가 성공했다.

관련 커밋: [검색 API `205949c`](https://github.com/JJun-He/Supplier-Hub/commit/205949c), [결과 경량화 `eff5bb8`](https://github.com/JJun-He/Supplier-Hub/commit/eff5bb8).

## 2026-09-15~16 — 기술 감사와 개선 범위 확정

구현 완료 여부와 별도로 실제 Boot 설정·HTTP·PostgreSQL에서 의심 경로를 재현했다. 감사의 임시 테스트는 당시 문제의 존재를 확인하며, 제품 수정이나 정식 회귀 테스트를 대신하지 않는다. 당시 정식 메인 89개·Mock 6개는 정확성 감사에서 실제 재실행했고 뒤의 읽기 전용 감사에서는 그 성공 결과를 재사용했다.

| 감사 | 확인한 사실과 결정 | 검증 근거 |
| --- | --- | --- |
| [정확성](docs/archive/correctness-audit.md) | 실제 Boot codec에서 소수 금액이 정수로 바뀌고 항목 파싱 오류가 정상 형제 결과까지 제거됐다. 중복 Offer와 특수문자 코드 전송도 수정 대상으로 확정했다. | 임시 재현 10개 |
| [DB](docs/archive/database-audit.md) | 별도 commit·rollback과 ID 보존은 동작했다. 중복 동기화의 INSERT 경쟁·갱신 유실·과거 snapshot 덮어쓰기, 검색 예산보다 긴 DB 잠금 대기를 재현했다. | PostgreSQL 임시 검증 12개, [SQL·계획](docs/archive/audit-evidence/db-query-plans.json) |
| [네트워크·메모리](docs/archive/network-memory-audit.md) | 고객 8명이 병렬 검색하면 요청당 4개 제한이 Supplier별 32개까지 늘었다. 정상 큰 응답은 기본 256 KiB 한도를 넘었고 B 본문 오류는 HTTP 성공 지표에 잡혔다. | HTTP·Boot·메모리 임시 검증 11개, [측정 조건](docs/archive/audit-evidence/network-memory.json) |
| [구조](docs/archive/structure-audit.md) | catalog client 누락·중복·다른 Supplier snapshot, 내부 예외 분류에 방어 공백이 있었다. 파일 이동만으로 책임이 분리된다는 제안은 철회했다. | 경계·Boot 임시 검증 6개, [재검토 근거](docs/archive/audit-evidence/structure-review.json) |

DB 감사 당시 검색은 데이터 projection SELECT 1개·Entity 로딩 0개였다. 이후 DB 예산 적용으로 설정 SELECT 1개가 추가됐으므로 과거 수치를 현재 전체 왕복 수로 사용하지 않는다. 추가 부분 인덱스의 일관된 이점은 확인하지 못해 기존 인덱스를 유지했다. batching은 실험에서 UPDATE 준비 수를 줄였지만 IDENTITY INSERT는 개별 실행되어 일괄 도입하지 않았다.

timeout 취소의 상대 연결 종료는 확인했지만 고객 연결 해제가 즉시 외부 호출 취소로 전파되지는 않았다. 합성 데이터의 스레드 할당량·객체 그래프 수치는 peak heap이나 운영 처리량이 아니다. 별도 전역 호출 한도와 업무 지표를 우선하고 전체 WebFlux 전환·분산 제한·캐시·circuit breaker는 보류했다.

Supplier C 확장 실험은 기존 기록의 메인 파일 4개 변경·테스트 89개 성공을 보존했다. 전체 재현 자료가 남아 있지 않아 문서 재검토에서 다시 검증한 결과로 표현하지 않았다. 컴파일·시작 시점의 client 안전장치는 유지하고 범용 registry·설정 Map 전환은 필수 개선으로 올리지 않았다.

감사·후속 우선순위는 [커밋 `f3f895e`](https://github.com/JJun-He/Supplier-Hub/commit/f3f895e)에 정리했다. 감사 자료의 과거 전문은 제거하고 직접 작성한 측정·재현 근거와 정정 이력만 남겼다.

## 2026-09-16 — 외부 입력과 내부 오류의 경계 보완

실제 JSON 타입과 정수 범위를 검사해 소수 잘림·숫자 문자열 변환을 막았다. 검색에서는 잘못된 항목만 격리하고, 전체 상태를 반영하는 카탈로그는 snapshot 전체를 거부한다. 정규화된 완전 중복은 제거하되 금액·재고·조식·통화가 다른 Offer는 유지한다. 같은 객실의 반환 가능한 Offer 간 수용 인원이 충돌하면 해당 객실의 Offer를 모두 제외한다.

특수문자 코드는 URI 템플릿 값으로 전달했다. catalog client 중복·활성 client 누락은 시작 시 거부하고 다른 Supplier의 snapshot은 저장하지 않는다. 내부 오류는 `INTERNAL_ERROR`·ERROR 로그로 식별하면서 정상 Supplier 결과를 보존한다.

리뷰에서는 일반 `IllegalArgumentException`까지 외부 입력 오류로 포괄 변환하던 부분을 다시 좁혔다. 명시적인 외부 값 검증은 `InvalidValueException`으로 표시하고, 내부 ID·계산 불변식 오류는 숨기지 않는다.

**검증:** 실제 Boot 주입 어댑터로 정수·날짜·null·중복·인원 충돌·특수 코드·본문 오류와 정상 형제 보존을 검증했다. 일반 내부 예외를 주입하는 회귀 테스트와 고객 JSON 계약도 확인했다. 최종 전체 실행은 메인 214개·Mock 6개 성공, 실패·오류·건너뜀 0개다.

관련 커밋: [입력·실패 계약 `f4963de`](https://github.com/JJun-He/Supplier-Hub/commit/f4963de), [오류 경계 리뷰 반영 `33611ab`](https://github.com/JJun-He/Supplier-Hub/commit/33611ab).

## 2026-09-16 — JVM 호출 한도와 업무 지표

고객 요청별 병렬 제한과 별도로 Supplier·작업별 JVM 허용량을 검색 8개·카탈로그 1개로 두었다. 구독 시 즉시 수용·거부하고 성공·실패·취소에 반환한다. 검색과 카탈로그는 별도 연결 풀을 사용하며 연결 수·pending 수·획득 대기를 제한한다. 응답 버퍼는 검색 2 MiB·카탈로그 8 MiB로 설정했다.

용량 부족·응답 한도 초과를 `CAPACITY_EXCEEDED`·`RESPONSE_TOO_LARGE`로 분류하고 즉시 재시도하지 않는다. transport 자동 재전송을 끄고 카탈로그의 제한 재시도만 유지했다. 호출 결과, 검색 결과·구간 시간, 중복·거부 Offer, 저장 성공 시각·연속 실패를 업무 의미로 기록한다.

검색 허용량 8개는 요청당 4개를 사용하는 두 요청을 기준으로 정한 초기값이다. 처리량 실측치, 고객 진입 제한, DB 저장 직렬화나 전체 heap 상한을 뜻하지 않는다. 단순 버퍼 합계 48 MiB도 peak heap 측정치가 아니다. 신선도 0은 최초 성공 없음과 기동 유예를 함께 해석해야 한다.

**검증:** 실제 HTTP의 동시 고객 제한·정상 Supplier 보존, 별도 카탈로그 자원, timeout 소켓 종료·회복, 큰 응답·한도 초과·회복, 풀 대기, 허용량 반환과 업무 지표를 확인했다. 최종 `test`에서 메인 232개는 실제 실행, 변경 없는 Mock 6개는 앞선 성공 결과를 재사용했다. 실패·오류·건너뜀은 0개다.

관련 커밋: [구현 `1176296`](https://github.com/JJun-He/Supplier-Hub/commit/1176296), [초기값·관측 해석 `5fb6e75`](https://github.com/JJun-He/Supplier-Hub/commit/5fb6e75).

## 2026-09-16 — 동기화 중복 보호와 DB 시간 예산

지원 범위를 하나의 동기화 인스턴스로 정하고 Supplier별 guard를 fetch 이전부터 retry backoff·저장 commit/rollback 이후까지 유지했다. 중복은 추가 fetch 없이 건너뛰며 다른 Supplier는 진행한다. 현재 운영 경로는 이미 단일 `fixedDelay`로 직렬 실행된다. guard는 추가 서비스 호출의 중복 방어이며 다중 인스턴스 DB 경쟁을 해결하지 않는다. 현재 수동 HTTP·CLI 트리거는 없다.

동기화는 외부 트랜잭션 합류를 거부하고 HTTP 중에는 저장 트랜잭션을 열지 않는다. 검색 reader에는 `System.nanoTime()` 기준 종료 시각을 전달하고, 연결 획득 후 남은 예산으로 transaction-local statement/lock timeout을 설정한다. 늦은 결과는 거부한다. 연결 획득·SQL·잠금·쓰기·socket 제한은 각각 다른 경계이며 엄격한 HTTP 완료 상한이 아니다.

예상된 DB 읽기 실패만 `TIMEOUT`·`UNAVAILABLE` 내부 지표로 구분하고 고객에게는 `CATALOG_UNAVAILABLE`/HTTP 503을 반환한다. 이때 Supplier 검색은 시작하지 않는다. 문법·무결성·미분류 내부 오류는 전파한다.

리뷰에서 설정 SELECT 1개와 데이터 projection SELECT 1개를 구분하고 추가 왕복의 성능 효과는 미측정으로 남겼다. 공유 DataSource의 session 제한은 운영 Flyway에도 적용된다. 외부 트랜잭션이 있을 때 `REQUIRES_NEW`가 연결을 추가 점유하는 조건도 명시했으며 현재 고객 검색에는 외부 트랜잭션이 없다.

**검증:** 실제 PostgreSQL에서 commit·중간 실패 rollback·Supplier별 독립 commit·미commit 비노출, 행 잠금 후 회복, 읽기 잠금·`pg_sleep` 취소·풀 고갈·연결 대기 예산 차감·session 설정 복구·늦은 결과 거부를 확인했다. 메인 270개는 실제 실행, Mock 6개는 성공 결과 재사용으로 실패·오류·건너뜀 0개다. 문서·Javadoc만 보완한 후속 커밋에서는 동작 변경이 없음을 확인하고 전체 테스트를 반복하지 않았다.

쓰기 잠금 검증은 PostgreSQL `lock_timeout`의 효과이며 Spring 쓰기 timeout만의 효과를 분리한 검증은 아니다. 외부 트랜잭션 안에서 reader를 중첩 호출하는 통합 검증과 운영 Flyway 설정의 별도 재현은 수행하지 않았다.

관련 커밋: [DB 보호 구현 `86ca690`](https://github.com/JJun-He/Supplier-Hub/commit/86ca690), [비용·지원 범위 보완 `6231c8a`](https://github.com/JJun-He/Supplier-Hub/commit/6231c8a).

## 2026-09-16 — 실제 DB·메인·Mock 전체 연결 검증

`e2eTest`를 별도 소스 집합으로 추가하고 `check`에 연결했다. PostgreSQL 17 Testcontainers, 메인·Mock의 별도 JVM, 격리한 환경·작업 디렉터리와 자동 배정 포트를 사용한다. 실제 기동 스케줄러의 카탈로그 commit을 기다린 후 DB 코드·ID와 고객 HTTP 응답을 대조한다.

정상 1박·3박, DB 이름 반영, A HTTP 오류·B 본문 오류, 전체 실패, 지연·복구, 인원 초과, 입력 오류와 DB 잠금·복구를 9개 테스트에 담았다. 첫 성공 이후에도 리뷰로 지표 기록 시점, 카탈로그 행 중복 단언, 테스트 JVM 종료 시 자식 프로세스 정리를 보강했다. `supplier.calls`는 논리 호출 수이므로 E2E가 실제 HTTP 재전송 횟수를 증명한다는 설명은 삭제했다.

**검증:** 주석 정리 후 메인 270개를 실제 실행하고 Java 토큰 비교로 실행 코드가 바뀌지 않았음을 확인했다. 최종 `./gradlew check --offline --console=plain`에서는 보강한 E2E 9개를 실제 재실행했고 메인 270개·Mock 6개는 up-to-date 성공 결과를 재사용했다. 합계 285개, 실패·오류·건너뜀 0개다.

당시 A 30초 지연은 2,021ms 후 `TIMEOUT`/`PARTIAL`, DB 잠금은 325ms 후 HTTP 503으로 처리됐고 해제 뒤 정상 응답을 관측했다. 단일 실행의 기록이며 5초 완료 보장이나 처리 용량 측정이 아니다. 로그·응답·자식 PID 종료를 확인했으며 생성물은 추적하지 않는다. 구성·시나리오·미검증 범위는 [전체 연결 검증 문서](docs/e2e-verification.md)에 정리했다.

관련 커밋: [주석 정리 `076256c`](https://github.com/JJun-He/Supplier-Hub/commit/076256c), [E2E `2478723`](https://github.com/JJun-He/Supplier-Hub/commit/2478723), [AI 기록 분리 `3d8e873`](https://github.com/JJun-He/Supplier-Hub/commit/3d8e873).

## 2026-09-17 — 현재 설계와 개발 기록 정리

구현·전체 연결 검증 이후의 작업 기준을 [AGENTS.md](AGENTS.md)에 추가했다([`c7da450`](https://github.com/JJun-He/Supplier-Hub/commit/c7da450)). 과거부터 이 지침으로 개발한 것으로 기록하지 않는다.

현재 설계를 주제별로 정리하고 과거 계획·감사는 `docs/archive`로 분리했다. 개발 기록은 날짜·결정·근거·검증·커밋 중심으로 축약하고, AI 활용 기록은 설계부터 자원·DB·E2E 검토까지 수용·수정·보류 사례를 연결했다. 단순 작업 실수와 반복 진행 보고는 제거하되 당시 관측과 미검증 한계는 유지했다. 제품 코드·테스트는 변경하지 않았다.

**실행 검증:** 제품 코드가 같은 `c7da450`의 추적 파일을 `git archive`로 빈 임시 디렉터리에 풀고 Java 21.0.12.1·Docker 28.3.2에서 `./gradlew check --offline --console=plain`을 실행했다. 메인 270개·Mock 6개·E2E 9개를 모두 실제 재실행했으며 합계 285개, 실패·오류·건너뜀 0개다. Gradle 작업 14개가 모두 실행됐고 두 실행 JAR도 빌드됐다. 의존성·컨테이너 이미지는 로컬 캐시를 사용했다.

**실행 안내 재현:** 기존 DB를 보존하고 새 Compose DB를 55432에 구성했다. 메인 8080·Mock 18080의 `bootRun`과 README의 `curl`을 재현해 Flyway·기동 동기화, 정상 3박 총액 A 429,000원·B 452,000원, 부분·전체 실패, 본문 오류, 지연·복구, 인원·입력 검증을 확인했다. A 지연 2,026ms·복구 15ms는 단일 관측이며 성능 보장이 아니다. 직접 시작한 프로세스와 임시 DB·비밀 파일을 정리했다. 자세한 환경·결과·한계는 [최종 재현 기록](docs/e2e-verification.md#2026-09-17-최종-재현)에 있다.

**문서·이력 검토:** 개편한 설계를 코드와 독립 대조했고 남은 중요한 불일치는 발견하지 못했다. 파일·이력에서 공개 제외 문구, 개인 홈 경로·알려진 키 형식을 검사했으며 해당 항목은 발견하지 못했다. `.env`·생성물·외부 원문 PDF는 추적하지 않고, 이전 감사 근거 JSON 4개의 내용은 그대로 보존했다.
