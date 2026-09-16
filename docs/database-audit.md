# 중간 기술 감사: DB 접근과 동시성 정합성

> 후속 상태(2026-09-16): 8-C에서 단일 갱신 인스턴스 지원 정책과 검색 DB/pool 시간 예산을 명시·보완했다. 현재 운영 동기화는 기존 fixedDelay가 직렬화하며 Supplier별 guard는 추가 서비스 호출에 대한 보조 방어다. 검색은 설정 SELECT 1개가 추가되어 정상 조회 시 데이터 projection과 합쳐 SQL 2개를 실행한다. 실제 PostgreSQL commit·rollback·잠금·쿼리 timeout·풀 고갈 회귀 검증을 정식 테스트에 남겼다. [설계 §18](architecture-decisions.md#18-8-c-동기화-중복-실행과-db-시간-예산)을 참고한다. 아래 재현 수치와 미구현 표현은 감사 당시 기록이며, 다중 동기화 인스턴스와 엄격한 HTTP 완료 상한은 지원 범위가 아니다.

## 1. 결론과 범위

- 작성일: 2026-09-15
- 기준 커밋: `eff5bb83a940d8d29a778b9de0d041d888771388`
- 범위: 7.5-B. 실제 SQL, 트랜잭션, 제약, 동시 동기화, 검색 실행 계획, DB 관련 성능 개선 후보
- 환경: Java 21, Hibernate 7.2.24.Final, PostgreSQL 17.11, 별도 Testcontainers DB
- 결과: 임시 검증 12개 완료. 실패·오류·건너뜀 0개. 문제 재현 테스트의 통과는 문제 해결을 뜻하지 않는다.

**검색 N+1은 확인되지 않았고, 단일 동기화의 원자성과 Supplier 간 rollback 격리는 정상이다. 동일 Supplier의 동기화가 겹치면 갱신 유실·과거 상태 복원이 발생한다. 검색의 전체 시간 제한도 DB 잠금 대기를 중단하지 않는다.**

제품 코드, migration, 실제 애플리케이션 DB는 변경하지 않았다. 테스트 DB에서만 합성 데이터와 비교용 인덱스를 만들었다. 기존 95개 테스트는 직전 정확성 감사에서 재실행한 결과를 기준으로 사용했고, 이번에는 DB 감사에 필요한 12개 임시 검증을 추가로 실행했다.

실제 SQL, 실행 계획, 측정값과 테스트 이름은 [DB 측정 근거](audit-evidence/db-query-plans.json)에 보존했다. 이전 단계의 정합성 문제와는 [정확성 감사](correctness-audit.md)를 함께 참조한다.

## 2. 확인 결과 요약

| 항목 | 결과 | 판단 |
| --- | --- | --- |
| 검색 SQL 수 | 숙소 1/100/1,000개 모두 SELECT 1개, Entity 로딩 0개 | N+1 수정 불필요. 8-C 이후 개수는 아래 후속 안내 참조 |
| 변경 없는 동기화 | SELECT 2개, UPDATE 0개 | 불필요한 매회 갱신 없음 |
| 독립 commit 이후 ID 유지 | 누락·비활성화·재등장 후 숙소·객실 ID 유지 | 확인 완료 |
| DB 쓰기 중 실패 | 앞서 INSERT한 행과 기존 이름 변경 모두 rollback | 확인 완료 |
| A 저장 실패 후 B 저장 | A rollback, B commit | 확인 완료 |
| 검색과 미완료 갱신 중첩 | commit 전 기존 숙소·객실 이름, 이후 새 이름 조회 | 미commit 데이터 비노출 확인 |
| 검색 조회 종료 | 트랜잭션 종료, Hikari 활성 연결 0개 | 정상 종료 경계 확인 |
| 같은 Supplier 최초 동기화 중첩 | 한쪽 commit, 다른 쪽 unique 위반 | DB 중복은 방지하지만 작업 충돌 처리 없음 |
| 같은 Supplier 누락 반영 중첩 | 두 번 commit됐지만 누락 횟수 1 | 조건부 정합성 결함 |
| 과거 snapshot 지연 반영 | 최신 이름 덮어쓰기, 비활성 숙소·객실 재활성화 | 조건부 정합성 결함 |
| DB 잠금 대기와 검색 시간 제한 | 테스트 예산 100ms에서 384ms 후 응답 | 8단계 보완 필요 |
| 대량 INSERT/UPDATE | 행 수에 비례하는 개별 쓰기 | 최적화 후보. 아직 운영 병목으로 확정하지 않음 |
| 추가 부분 인덱스 | 비교에서 일관된 실행 시간 개선 없음 | 즉시 migration 추가 근거 없음 |

> 8-C 이후 SQL 개수: 정상 조회는 timeout 설정 SELECT 1개와 데이터 projection SELECT 1개, 합계 2개다(BEGIN/COMMIT 등 제어문 제외). 직접 JDBC로 실행하는 설정문은 Hibernate 통계만으로 세지 않는다. 위 표는 감사 당시 측정값이며, 고정 SQL 1개 추가와 숙소 수에 비례하는 N+1은 구분한다. 추가 왕복의 성능 비용은 [설계 §18.2](architecture-decisions.md#182-검색과-쓰기의-서로-다른-시간-예산)에 설명한다.

## 3. 트랜잭션과 제약 검증

### 3.1 독립 트랜잭션에서도 ID가 유지된다

`CatalogSnapshotStore`의 실제 Spring proxy를 사용하고, 테스트 메서드 전체를 감싸는 트랜잭션은 두지 않았다. 각 `replace`가 끝난 뒤 별도의 JDBC 조회로 commit 결과를 확인했다.

```text
숙소 2개와 객실 등록·commit
→ 한 숙소 누락·commit
→ 다시 누락·commit: 숙소와 객실 비활성
→ 재등장·commit: 이전 숙소·객실 ID로 활성화
```

누락 횟수 초기화, 이름 갱신, 활성 검색 복귀도 확인했다. 이전 writer 테스트가 하나의 테스트 트랜잭션 안에서 수행하던 검증을 실제 commit 경계로 보완한 결과다.

### 3.2 저장 중간 실패가 부분 데이터를 남기지 않는다

기존 P1의 이름을 바꾸고 새 P2와 객실을 INSERT한 뒤, 이름 길이가 DB의 255자 제한을 초과하는 P3을 저장하게 했다. Hibernate 통계로 앞선 INSERT가 실제 실행됐음을 확인했다. 이후 DB 제약 오류가 발생했고 다음 별도 조회에서:

- P1 이름은 원래 값이었다.
- P2와 그 객실은 남지 않았다.
- 기존 행 수가 보존됐다.

`CatalogSnapshotWriter.replace`의 트랜잭션 경계가 전체 snapshot 반영을 보호한다. 단순히 사전 가드에서 거부되는 사례만 확인한 것이 아니다.

같은 실패를 `CatalogSynchronizationService.synchronizeAll()` 흐름에 넣었을 때 A는 rollback되고 다음 B는 정상 commit됐다. 목록 client 호출 시 DB 트랜잭션이 열려 있지 않은 것도 확인했다.

이름 길이 초과는 현재 snapshot 모델에서 허용되다가 DB에서 거부된다. 데이터 손상은 없지만 동기화 로그는 일반 `UNKNOWN` 실패로 분류한다. 외부 필드 길이 검증과 예외 분류 개선 여부는 구조·예외 감사에서 판단한다.

### 3.3 검색 projection은 commit된 한 시점의 데이터를 읽는다

다른 스레드의 트랜잭션에서 숙소·객실 이름을 변경하고 `flush()`까지 수행한 채 commit을 대기시켰다. 그 사이 실제 `ActiveCatalogMappingReader`는 기존 이름 쌍을 반환했고, commit 후에는 새 이름 쌍을 반환했다.

이 결과는 현재 단일 SELECT projection과 맞는다. PostgreSQL의 Read Committed에서 일반 SELECT는 문장이 시작될 때까지 commit된 데이터를 본다. 다만 여러 SELECT가 하나의 동일한 시점을 보장하는 것은 아니다. [PostgreSQL 17 트랜잭션 격리](https://www.postgresql.org/docs/17/transaction-iso.html)

이 검증은 검색 시점의 DB 메타데이터 일관성을 확인한다. 이후 외부 Supplier에서 받은 실시간 재고·가격과 DB 이름이 같은 시각의 snapshot이라는 뜻은 아니다. 여러 Supplier의 카탈로그 갱신도 각각 commit되므로 전체 Supplier의 공통 갱신 시점은 보장하지 않는다.

### 3.4 제약과 기존 인덱스

실제 DB에서 FK 위반, 수용 인원 0, 음수 누락 횟수를 거부했다. 같은 코드 중복은 기존 제약 테스트에 더해 최초 동기화 경쟁에서도 방지됐다.

중요한 인덱스 구성:

| 테이블 | 키 | 의미 |
| --- | --- | --- |
| 숙소 | PK `(id)` | 내부 ID 조회 |
| 숙소 | UNIQUE `(supplier, supplier_property_code)` | 공급사 안의 코드 중복 방지 |
| 숙소 | `(active, supplier)` | 활성 공급사 매핑 조회 |
| 객실 | PK `(id)` | 내부 객실 ID 조회 |
| 객실 | UNIQUE `(property_id, supplier_room_type_code)` | 숙소별 객실 코드 중복 방지, `property_id` 선두 조회 지원 |
| 객실 | `(active, property_id)` | 활성 객실 조인 |

따라서 **`property_id` 선두 인덱스가 없다는 진단은 현재 스키마에 맞지 않는다.** 이미 unique 인덱스의 첫 열이다. FK 자체가 참조하는 쪽의 인덱스를 자동 생성하는 것은 아니지만 이 스키마에서는 unique 제약으로 확보했다. 별도의 동일 목적 인덱스를 추가하기 전에 기존 것을 확인해야 한다. [PostgreSQL 17 제약과 인덱스](https://www.postgresql.org/docs/17/ddl-constraints.html)

부모가 비활성이면 자식도 반드시 비활성이어야 한다는 교차 행 CHECK는 두지 않았다. 검색이 부모·자식 활성 상태를 모두 검사하므로 이것만으로 현재 결함으로 판단하지 않는다.

## 4. D-01: 동일 Supplier의 동기화 중첩

### 4.1 재현한 세 가지 현상

실제 repository 호출 결과를 바꾸지 않고, 조회가 끝난 지점에 테스트 전용 대기 장치를 넣었다. 두 스레드가 DB 상태를 읽는 순서를 고정한 후 실제 writer와 실제 트랜잭션을 실행했다.

1. **최초 INSERT 충돌**: 빈 매핑을 두 작업이 읽은 뒤 같은 코드를 생성한다. 하나는 commit, 다른 하나는 unique 위반으로 rollback된다. 중복 행은 없지만 단순 조회 후 삽입이 동시 upsert를 보장하지 않는다.
2. **누락 횟수 갱신 유실**: 두 작업이 모두 `missing_count=0`을 읽고 같은 숙소가 빠진 목록을 반영한다. 두 작업 모두 commit되지만 최종 횟수는 1이고 숙소가 활성 상태로 남는다.
3. **과거 상태 복원**: 이전 snapshot 처리 작업을 조회 직후 대기시킨다. 새 snapshot을 commit해 이름을 바꾸고 숙소·객실을 비활성화한다. 이전 작업이 이어서 commit하면 중간 이름과 활성 상태로 돌아간다.

관련 위치:

- `catalog/application/CatalogSnapshotWriter.java`: 기존 집합 조회와 개별 `save`·`refresh`·`recordMissing`
- `catalog/domain/Property.java`, `RoomType.java`: 버전 필드 없이 읽은 값을 기준으로 갱신
- `catalog/application/CatalogSynchronizationService.java`: snapshot 취득 후 저장, 실행 세대·순서 검사 없음

### 4.2 발생 조건과 우선순위

현재 기본 단일 애플리케이션의 하나의 `fixedDelay` 스케줄 실행만으로 위 중첩이 발생한다고 주장하지 않는다. 여러 애플리케이션 인스턴스, 추가 수동 실행 경로, 중복 스케줄 호출이 같은 Supplier를 동시에 처리할 때의 결함이다. 재현은 한 JVM의 두 스레드로 이 DB 경쟁을 만들었으며 실제 다중 JVM 배포 테스트는 아니다.

다중 실행을 지원할 경우 정합성 우선 수정 대상이다. 단일 인스턴스만 지원하는 1차 범위라면 그 제약을 명시하고, 동시 실행을 지원한다고 설명하지 않아야 한다. 독립 트랜잭션 회귀 테스트는 운영 범위와 무관하게 남길 가치가 있다.

### 4.3 해결 후보와 주의할 경계

| 후보 | 해결하는 것 | 별도로 고려할 것 |
| --- | --- | --- |
| Supplier별 실행 조정 | 동일 Supplier의 목록 취득·반영 중첩 방지 | 단일 JVM lock은 다른 인스턴스를 막지 못함 |
| PostgreSQL transaction advisory lock | 같은 key의 DB 반영 직렬화 | fetch 이후 획득만으로 오래된 snapshot의 후행 반영을 막지는 못함 |
| Supplier 상태 행 + 실행 세대 검사 | 어떤 실행 결과까지 반영했는지 판단 | 스키마와 원자적 비교·갱신이 필요; 로컬 수신 시각만으로 원본 최신성을 증명할 수 없음 |
| JPA `@Version` | 같은 행의 낡은 버전 덮어쓰기 감지 | 최초 INSERT·새 행 포함 snapshot 전체와 재시도 정책은 별도 |
| `INSERT ... ON CONFLICT` | 동일 고유 키의 INSERT 경쟁 처리 | 전체 snapshot의 누락 판정·최신 순서·ID 보존은 별도 |

transaction advisory lock은 트랜잭션 종료 시 해제된다. 적용한다면 Supplier별 고정 key, 획득 실패·대기 정책을 정한다. 외부 HTTP 호출까지 긴 DB 트랜잭션 안에 넣는 방식으로 해결하지 않는다. [PostgreSQL advisory lock](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS)

upsert 하나를 도입하거나 격리 수준만 올리는 것으로 세 현상이 모두 해결됐다고 판단하지 않는다. 원본 버전이 없다면 '최신'의 정의와 허용할 실행 순서를 먼저 정해야 한다.

## 5. D-02: 검색 시간 제한이 DB 대기를 중단하지 않는다

테스트 DB 설정은 `statement_timeout=0`, `lock_timeout=0`이었다. 검색 서비스의 `overallTimeout`을 테스트에서만 100ms로 설정하고, 다른 트랜잭션이 숙소 테이블을 `ACCESS EXCLUSIVE`로 잠근 상태에서 검색했다.

- `pg_stat_activity`에서 검색 SELECT가 lock을 기다리는 것을 확인했다.
- 대기 확인 후 350ms 동안 검색이 끝나지 않았다.
- 잠금을 해제한 뒤 총 384ms에 `FAILED`로 반환됐다.
- Supplier HTTP 호출은 0회였다.

운영 기본값 5초를 384ms 초과했다는 결과가 아니다. 작은 테스트 예산으로 **blocking JPA 조회에 전체 제한 시간이 실제 취소를 걸지 않는 구조**를 확인한 것이다. 일반적인 행 UPDATE가 검색 SELECT를 막는다는 주장도 아니다. `ACCESS EXCLUSIVE`는 DDL 또는 명시적 테이블 잠금 같은 상황을 모델링한다.

관련 위치는 `IntegratedSearchService.search`의 `mappingReader.findAllActive()` 이후 남은 시간을 계산하는 흐름과, timeout을 지정하지 않은 `JpaActiveCatalogMappingReader`다. 설계 문서에도 DB timeout이 8단계 보완 대상임이 명시돼 있다.

**8단계 제안**

- 검색 DB 조회의 statement timeout과 필요 시 더 짧은 lock timeout을 정한다.
- DB 연결 획득 대기, SQL 실행, 외부 호출, 응답 조립의 예산을 나눠 전체 목표와 맞춘다.
- 실패 시 DB 예외를 API와 지표에서 어떻게 표현할지 정의하고 취소 뒤 연결 반환을 검증한다.
- 검색의 짧은 예산을 카탈로그 쓰기에 무조건 적용하지 않는다.

PostgreSQL의 statement timeout은 SQL 실행을, lock timeout은 잠금 획득 대기를 제한한다. 이 값만으로 풀 연결 획득이나 응답 직렬화 시간까지 제한되지는 않는다. 적용 범위를 구분해야 한다. [PostgreSQL timeout 설정](https://www.postgresql.org/docs/17/runtime-config-client.html)

## 6. SQL 수와 대량 쓰기

각 숙소에 객실 타입 3개를 두고 Hibernate 통계와 실제 SQL을 확인했다. 아래는 **JDBC statement 준비 수**이며, batching 적용 후의 행 수나 네트워크 왕복 수와 혼동하면 안 된다.

| 숙소 / 객실 | 최초 등록 | 검색 | 변경 없는 재동기화 | 전부 이름 변경 |
| --- | ---: | ---: | ---: | ---: |
| 1 / 3 | 6 | 1 | 2 | 6 |
| 100 / 300 | 402 | 1 | 2 | 402 |
| 1,000 / 3,000 | 4,002 | 1 | 2 | 4,002 |

- 최초 등록은 조회 2개와 각 숙소·객실 INSERT다.
- 검색은 projection SELECT 1개이며 Entity를 로딩하지 않았다. 이 값은 감사 당시 기준이며, 8-C 이후 추가된 설정 SELECT 1개는 §2의 후속 안내를 참고한다.
- 변경 없는 재동기화는 조회 2개, UPDATE 0개다. 다만 1,000개 숙소 사례에서 Entity 4,000개는 로딩한다.
- 이름을 전부 바꾸면 해당 숙소·객실 행마다 UPDATE한다.

따라서 읽기의 N+1과 쓰기의 많은 statement를 구분해야 한다. 이 경로에 `@EntityGraph`나 batch fetching을 추가하는 것은 관측된 쓰기 비용의 해결책이 아니다. `saveAll()`로 메서드 이름만 바꾸는 것도 batching을 증명하지 않는다.

### 6.1 JDBC batching 비교 실험

임시 테스트 컨텍스트에서만 `hibernate.jdbc.batch_size=50`, `hibernate.order_updates=true`를 적용했다. 제품 설정은 그대로다.

| 100개 숙소·300개 객실 | 기존 | 임시 batching |
| --- | ---: | ---: |
| 최초 INSERT 행 수 | 400 | 400 |
| 최초 statement 준비 수 | 402 | 402 |
| 전체 이름 UPDATE 행 수 | 400 | 400 |
| 갱신 statement 준비 수 | 402 | 4 |
| JDBC UPDATE batch 실행 | 미사용 | 50행씩 8회 |

갱신 준비 수 4는 조회 2개와 두 Entity UPDATE statement 준비를 의미한다. DB가 단 4행만 갱신하거나 전체 왕복이 정확히 4회라는 뜻이 아니다. 실제 batch 실행은 Hibernate TRACE 로그로 확인했다. 이 실험으로 운영 지연이 몇 배 개선된다는 결론은 내리지 않는다.

현재 `IDENTITY` ID 전략에서는 batch 크기만 설정해도 INSERT가 묶이지 않았다. 이 동작은 Hibernate 문서와도 일치한다. [Hibernate 7.2 JDBC batching](https://docs.hibernate.org/orm/7.2/userguide/html_single/#batch)

**판단:** UPDATE batching은 비교적 작은 개선 후보다. 신규 대량 등록이 실제 병목이면 sequence 기반 ID 할당과 batching, JDBC upsert 등을 비교한다. 기존 내부 ID·고유 제약·재등장·원자성을 유지해야 하므로 성능 근거 없이 ID 전략과 저장 계층을 동시에 바꾸지 않는다. 현재는 쓰기 수를 확인했으며 CPU·메모리·쓰기 처리량의 목표 초과까지 측정하지 않았다.

## 7. 인덱스와 실행 계획

### 7.1 측정 조건

- 총 숙소 6,000개, Supplier별 3,000개, 숙소당 객실 타입 5개: 총 30,000행의 객실 타입.
- 활성 비율 100%, 10%, 1%. 객실 활성 여부는 부모와 같게 설정했다.
- 각 분포 변경 뒤 `VACUUM ANALYZE`, 비교 인덱스 생성 뒤 `ANALYZE`.
- Hibernate가 생성한 검색 SQL과 카탈로그 객실 fetch join SQL에 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 실행.
- 각 조건 4회 중 첫 회를 제외하고 나머지 3회의 중앙값 사용.
- 로컬 컨테이너·warm cache의 SQL 실행 시간이다. JPA 객체 생성, 네트워크 전송, 전체 고객 응답, 동시 부하는 포함하지 않는다.

비교용으로 다음 두 인덱스만 임시 추가했다.

```sql
CREATE INDEX audit_property_active_order
    ON supplier_property (supplier, id) WHERE active;
CREATE INDEX audit_room_active_order
    ON supplier_room_type (property_id, id) WHERE active;
```

### 7.2 결과

| 활성 비율 / 검색 반환 행 | 기존 인덱스 중앙값 | 비교 인덱스 추가 후 | 계획 요약 |
| --- | ---: | ---: | --- |
| 100% / 30,000 | 19.442ms | 39.685ms | 양쪽 모두 Seq Scan + Hash Join + Sort |
| 10% / 3,000 | 2.210ms | 2.429ms | Bitmap Scan + Hash Join + Sort |
| 1% / 300 | 0.196ms | 0.176ms | Index Scan + Nested Loop + Incremental Sort |

카탈로그 객실 fetch join은 한 Supplier의 객실 15,000행을 반환했고 중앙값 8.714ms였다. 조회 2개 중 객실 fetch join을 측정한 수치이며 동기화 전체 시간은 아니다.

대표 계획에서 shared read와 임시 파일 read/write가 모두 0이었다. 전부 활성인 검색의 sort 메모리는 3,112kB였으며 disk spill은 없었다.

**해석**

- 현재 인덱스도 활성 행이 적어지면 사용된다. 대부분의 행을 반환하는 쿼리의 Seq Scan을 인덱스 결함으로 취급하지 않는다.
- 전부 활성인 비교는 실행 계획과 buffer 수가 같으면서 시간이 크게 흔들렸다. 따라서 이 수치로 인덱스 추가가 두 배 느려지는 원인이라고 단정할 수 없다. 무작위 교차 실행과 장시간 부하 검증을 수행하지 않았다.
- 부분 인덱스가 일관되게 개선됐다는 근거는 없다. 1% 조건의 미세한 차이를 운영 효과로 일반화하지 않는다.
- 데이터 분포·이름 길이·객실 수·캐시 조건이 달라지면 다시 측정해야 한다. 현재는 추가 인덱스를 8단계 필수 수정으로 선정하지 않는다.

부분 인덱스는 조회 조건과 데이터 분포에 맞춰 선택하는 기능이다. 존재 자체가 최적화를 보장하지 않는다. [PostgreSQL 부분 인덱스](https://www.postgresql.org/docs/17/indexes-partial.html)

## 8. 8단계 반영과 후속 개선 구분

| 작업 | 제안 | 완료 증거 |
| --- | --- | --- |
| DB 검색 timeout과 오류 표현 | 8단계 자원 제어에 반영 | lock·느린 SQL·연결 대기에서 예산과 연결 반환 확인 |
| commit·rollback·Supplier 격리 회귀 테스트 | 8단계 테스트에 반영 | 실제 DB의 별도 트랜잭션 검증을 정식 테스트로 보존 |
| 동일 Supplier 중복 실행 정책 | 8단계에서 지원 범위 확정 | 단일 인스턴스 제한 명시 또는 실행 조정·세대 검증과 경쟁 테스트 |
| UPDATE batching | 변경 규모·복잡도에 따라 8단계 후보 | batch 로그·정합성 회귀·변경 전후 측정 |
| ID 전략 변경·대량 upsert·staging | 후속 개선 우선 | 신규 등록의 실제 병목이 확인된 경우 재검토 |
| 추가 부분 인덱스·별도 property_id 인덱스 | 현재 추가하지 않음 | 재측정에서 분명한 개선이 확인될 때만 변경 |
| 카탈로그 전체 Entity 로딩 감소 | 메모리 감사로 전달 | 실제 유지 메모리·GC·객실 수에 따른 비용 측정 |
| 다중 SELECT snapshot 일관성·교착 상태·다중 JVM 장애 복구 | 후속 심화 검증 | 실행 조정 방식이 정해진 뒤 해당 실패 조건 재현 |

다음 단계는 **7.5-C 네트워크·메모리·전역 동시성 감사**다. 이번에 측정한 DB 대기와 카탈로그 Entity 수를 입력으로 사용한다. 앞선 정확성 감사의 C-01~C-04는 여전히 미수정 상태다.
