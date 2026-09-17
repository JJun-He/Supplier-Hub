# 실행과 전체 연결 검증

## 실행 방법

Java 21과 실행 중인 Docker가 필요하다. 저장소 루트에서 다음을 실행한다.

```sh
# 두 실행 JAR을 빌드하고 전체 연결 테스트 실행
./gradlew e2eTest

# 메인·Mock의 기존 테스트와 전체 연결 테스트
./gradlew check
```

`test`는 기존 메인·Mock 테스트를 실행하고, `e2eTest`는 별도 소스 경로의 전체 연결 테스트를 실행한다. `check`에는 두 검증이 포함된다. 변경이 없으면 Gradle이 성공 결과를 재사용할 수 있으며, 다시 실제 실행하려면 `./gradlew e2eTest --rerun`을 사용한다.

## 연결 구성

`SupplierHubEndToEndTests`는 PostgreSQL 17 Testcontainers와 메인·Mock의 실행 JAR을 사용한다. 메인과 Mock은 각각 별도 JVM에서 실제 HTTP 서버로 실행한다. 테스트가 application 서비스나 controller를 직접 호출하거나 Supplier 응답을 테스트용 HTTP 서버로 대체하지 않는다.

임시 작업 디렉터리와 테스트 전용 연결 설정을 사용하여 저장소의 `.env`나 기존 로컬 DB를 사용하지 않는다. HTTP 포트는 각 서버가 사용 가능한 포트를 배정받는다. 기동 시 실제 스케줄러가 A/B 카탈로그를 HTTP로 가져와 저장하고, 준비 완료를 확인한 뒤 고객 검색을 실행한다.

로그와 HTTP 응답·경과 시간은 `build/reports/e2e/` 아래 실행별 디렉터리에 남는다. JUnit HTML 보고서는 `build/reports/tests/e2eTest/index.html`, 원본 결과는 `build/test-results/e2eTest/`에 생성된다. 정상 완료·기동 실패·테스트 실패 시 프로세스와 테스트 DB를 정리한다. 테스트 JVM의 종료 훅에도 자식 서버 정리를 등록한다. OS 강제 종료로 종료 훅 자체가 실행되지 않는 경우까지 보장하지는 않는다.

## 데이터 기대값

정상 검색 기준은 2026-10-01부터 2026-10-04까지 3박, 성인 2명이다. 기대값은 Mock 데이터와 도메인 규칙에서 독립적으로 계산한다.

| 항목 | 기대값 |
| --- | --- |
| 동기화된 매핑 | 숙소 3개·객실 3개. A 숙소 2개, B 숙소 1개 |
| A Riverside | 세금 포함 KRW 429,000, 연박 재고 1개, 조식 미포함 |
| B Riverside | 세금 포함 KRW 452,000, 연박 재고 1개, 조식 포함 |
| A Namsan | 둘째 날 재고 0으로 검색 결과 제외 |
| 고객 응답 | 같은 숙소명이어도 Supplier별 내부 ID를 사용하여 stays 2개 |
| A 결과 건수 | accepted 1 / rejected 0 / unavailable 1 |
| B 결과 건수 | accepted 1 / rejected 0 / unavailable 0 |
| 성인 2명 + 아동 1명 | 최대 수용 인원 2명 초과로 HTTP 200 / COMPLETE, 빈 stays |

활성 매핑의 Supplier·숙소 코드·객실 코드를 대조하고 fixture 숙소마다 객실이 정확히 하나인지 확인한다. 고객 내부 ID는 고정 숫자 대신 이 DB 매핑과 대조한다. 고객 응답에는 Supplier 원본 코드와 내부 일별 가격 근거를 노출하지 않는다.

## 정식 시나리오

| 사례 | 확인하는 고객 응답과 상태 |
| --- | --- |
| 정상 3박·1박 | HTTP 200 / COMPLETE, 내부 ID·이름, 세금 포함 가격·조식·연박 재고·품절 제외 |
| DB 이름 변경 후 검색 | 저장된 숙소·객실 이름 반영, 검사 후 원래 값 복원 |
| A HTTP 503 | HTTP 200 / PARTIAL, A UNAVAILABLE, B Offer 유지 |
| B HTTP 200 + E503 본문 | HTTP 200 / PARTIAL, B UNAVAILABLE, A Offer 유지 |
| A/B 모두 오류 | HTTP 503 / FAILED, 빈 stays |
| A 30초 지연 | HTTP 200 / PARTIAL, A TIMEOUT, B 유지, 정상 모드 복구 후 COMPLETE |
| 성인·아동 합산 수용 인원 초과 | HTTP 200 / COMPLETE, 빈 stays, unavailable 건수 반영 |
| 잘못된 날짜·음수 인원·날짜 형식·필수값 누락 | HTTP 400, 입력 오류 코드, Supplier 논리 호출 증가 없음 |
| DB 숙소 테이블 잠금 | HTTP 503 / FAILED, CATALOG_UNAVAILABLE, 읽기 TIMEOUT 지표 +1, Supplier 논리 호출 증가 없음, 잠금 해제 후 COMPLETE |

실제 검색이 진행된 요청마다 `supplier.calls`의 SEARCH COUNT가 A/B 각각 1 증가할 때까지 확인한다. 다음 요청의 기준값과 이전 완료 기록이 겹치지 않게 하기 위한 처리다. 이 값은 계측 경계의 **논리 호출 수**이며, Mock의 HTTP 수신 횟수나 전송 계층 내부 재시도를 직접 측정한 값이 아니다.

## 2026-09-16 실행 결과

Java 21에서 두 실행 JAR 빌드와 메인 270개 회귀 테스트가 성공했다. 첫 E2E 실행 9개가 통과한 뒤 카탈로그 매핑 검사·지표 완료 대기·자식 프로세스 종료 처리를 보강하고 `./gradlew check --offline --console=plain`으로 E2E 9개를 다시 실행했다. 최종 `check`에서는 변경 없는 메인 270개·Mock 6개의 성공 결과를 재사용했다. XML 합계는 **285개, 실패·오류·건너뜀 0개**다.

최종 로그 디렉터리는 `build/reports/e2e/run-1789569269164/`다. 고객 HTTP 요청부터 본문 수신까지의 단일 관측값은 다음과 같다. 성능 목표나 부하 시험 결과는 아니다.

| 요청 | HTTP / 결과 | 경과 시간 |
| --- | --- | ---: |
| 정상 3박 | 200 / COMPLETE | 8ms |
| A 지연 | 200 / PARTIAL, A TIMEOUT | 2,021ms |
| A 정상 복구 | 200 / COMPLETE | 12ms |
| DB 잠금 | 503 / FAILED, CATALOG_UNAVAILABLE | 325ms |
| DB 잠금 해제 후 | 200 / COMPLETE | 12ms |

스케줄러가 A의 숙소·객실 2개씩과 B의 1개씩을 저장한 성공 로그, DB의 활성 매핑 3개, 고객 응답을 함께 확인했다. 테스트 종료 후 메인·Mock PID가 종료된 것도 확인했다. 원시 로그에는 로컬 경로·포트가 포함되어 Git에 추가하지 않으며 재실행 시 새 보고서를 생성한다.

## 검증 범위와 한계

- Mock의 A 오류는 HTTP 503이고 B 오류는 HTTP 200 안의 E503 본문이다. 고객 응답과 업무 실패 분류를 함께 확인한다.
- Mock의 `no-response`는 30초 응답 지연이다. 기본 response 2초 / call 3초 / overall 5초 설정에서는 응답 시간 제한이 먼저 작동할 수 있다. 이 사례를 전체 HTTP 완료 5초 상한의 증명으로 해석하지 않는다.
- Mock 제어 모드는 검색에만 적용된다. 카탈로그 장애 시나리오와 인증 키의 유효성 검증은 이번 연결 검증의 범위가 아니다.
- 50개 분할·전역 동시성·codec 크기·입력 항목 격리 등의 상세 경계는 기존 단위·통합 테스트와 함께 확인한다. 작은 정상 Mock 데이터로 수천 개 숙소의 처리 용량을 검증한 것으로 표현하지 않는다.
- README의 고정 포트 `bootRun`·Compose 실행 재현은 아래 별도 기록으로 확인한다. 자동 E2E는 자동 배정 포트와 Testcontainers를 사용한다.

## 2026-09-17 최종 재현

### 깨끗한 소스에서 전체 테스트

제품 코드가 같은 `c7da450`의 추적 파일을 `git archive`로 빈 임시 디렉터리에 풀었다. 기존 `.env`, 빌드 결과와 테스트 결과를 복사하지 않았다. macOS arm64, Temurin Java 21.0.12.1, Docker 28.3.2, PostgreSQL 17 이미지에서 다음을 실행했다.

```sh
./gradlew check --offline --console=plain
```

메인 **270개**, Mock **6개**, E2E **9개**를 모두 새로 실행했다. XML 합계 **285개, 실패·오류·건너뜀 0개**, Gradle **14개 작업 모두 실행**, `BUILD SUCCESSFUL`을 확인했다. 두 실행 JAR 빌드도 포함한다. 의존성과 컨테이너 이미지는 로컬 캐시를 사용했으므로 네트워크에서 처음 다운로드하는 환경의 검증은 아니다.

### README의 Compose·bootRun·curl 재현

같은 디렉터리에서 `.env.example`을 복사하고 검증용 임시 비밀번호를 설정했다. 기존 로컬 DB의 5432 포트가 사용 중이어서 README 안내대로 `DB_PORT=55432`와 `DB_URL`의 포트를 함께 변경했다. 메인 `8080`과 Mock `18080`은 기본 포트를 유지했다. 새 Compose 프로젝트·새 데이터 볼륨을 사용했으며 기존 DB는 사용하지 않았다.

README 순서대로 DB → Mock → 메인을 시작했다. Gradle에는 출력 정리용 `--console=plain`과 캐시 사용을 명시하는 `--offline`만 추가했다. Flyway의 두 migration과 A/B 기동 카탈로그 저장 성공을 로그로 확인한 뒤 README의 날짜·인원과 Mock 모드 제어 요청을 `curl`로 실행했다.

| 요청 | HTTP / 결과 | 응답 대조 | 단일 관측 시간 |
| --- | --- | --- | ---: |
| 정상 3박 | 200 / COMPLETE | 숙소 2개, A 429,000원·B 452,000원, 각각 재고 1 | 51ms |
| 인원 3명 | 200 / COMPLETE | 빈 stays, 수용 인원 초과 제외 | 12ms |
| A 오류 | 200 / PARTIAL | A UNAVAILABLE, B 결과 보존 | 22ms |
| A/B 모두 오류 | 503 / FAILED | 빈 stays, 양쪽 UNAVAILABLE | 12ms |
| B 본문 오류 | 200 / PARTIAL | B UNAVAILABLE, A 결과 보존 | 8ms |
| A 30초 지연 | 200 / PARTIAL | A TIMEOUT, B 결과 보존 | 2,026ms |
| 정상 복구 | 200 / COMPLETE | 정상 가격·재고 복원 | 15ms |
| 역전된 숙박 날짜 | 400 | INVALID_SEARCH_CRITERIA | — |

반환 내부 ID, 최대 수용 인원, A/B 조식 차이, 통화, 원본 코드·일별 가격 비노출도 대조했다. `supplier.calls`, 카탈로그 마지막 성공 시각·연속 실패 지표 조회는 HTTP 200이었다. 시간은 단일 실행 관측값이며 처리량·지연 보장이나 성능 개선 수치가 아니다.

검증 로그에는 macOS Netty 네이티브 DNS 로더의 시스템 resolver 대체 메시지가 있었고, 이 로컬 호스트 환경의 동기화·검색은 정상 완료했다. 별도 운영 DNS 환경의 검증으로 확대 해석하지 않는다.

검증 후 모드를 정상화하고, 직접 시작한 두 JVM을 정상 종료한 뒤 `docker compose down`을 실행했다. 검증용 볼륨은 종료 명령에서 유지되는 것을 확인한 뒤, 이번에 만든 볼륨만 별도로 제거했다. 생성한 임시 비밀번호 파일도 제거했다. 원시 응답·명령 로그는 작업 저장소의 `build/reports/final-verification/`, JUnit XML과 E2E 로그도 같은 검증 보고서 디렉터리에 복사해 보존했다. 생성물과 임시 비밀 값은 Git에 포함하지 않는다. 재현 절차와 기대값은 이 문서에 보존한다.

## 2026-09-17 미사용 의존성 제거 후 검증

Lombok 컴파일·테스트 의존성 4개를 제거한 뒤 같은 Java 21·Docker 환경에서 다음을 실행했다. 제품 코드·테스트·실행 설정값은 변경하지 않았다.

```sh
./gradlew check --rerun-tasks --offline --console=plain
```

메인 270개·Mock 6개·E2E 9개를 모두 재실행했고 JUnit XML 합계 285개, 실패·오류·건너뜀 0개를 확인했다. 두 실행 JAR 빌드를 포함해 14개 작업이 모두 실행됐으며 `BUILD SUCCESSFUL`이었다. 이번에는 작업 저장소에서 실행했으며 의존성과 컨테이너 이미지는 로컬 캐시를 사용했다. 명령 로그·집계는 추적하지 않는 `build/reports/review-followup/`에 남겼다.

같이 보완한 타임아웃 근거는 설정값 변경이 아니며, 서킷 브레이커는 미구현 제안 설계다. 이 테스트 성공이 서킷 동작을 검증했다는 뜻은 아니다. Compose·고정 포트 `bootRun` 수동 재현은 앞 절의 결과이며 이번에 반복하지 않았다.
