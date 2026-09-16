package com.supplierhub.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SupplierHubEndToEndTests {

	private static final String A = "SUPPLIER_A";
	private static final String B = "SUPPLIER_B";
	private static final String THREE_NIGHTS =
		"checkIn=2026-10-01&checkOut=2026-10-04&adults=2&children=0";
	private static final String SEARCH = "/api/v1/stays/search?";
	private final JsonMapper json = JsonMapper.builder().build();
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(2))
		.build();
	private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
		.withDatabaseName("supplier_hub_e2e")
		.withUsername("supplier_hub_e2e")
		.withPassword("supplier_hub_e2e");
	private JarProcess mock;
	private JarProcess application;
	private URI mockAddress;
	private URI applicationAddress;
	private Path reports;
	private Path workingDirectory;
	private Map<String, CatalogMapping> catalog;
	private String scenario = "startup";

	@BeforeAll
	void startSeparateProcessesAndWaitForScheduledCatalogCommit() throws Exception {
		reports = Path.of(System.getProperty("e2e.logs", "build/reports/e2e"))
			.toAbsolutePath().resolve("run-" + System.currentTimeMillis());
		Files.createDirectories(reports);
		workingDirectory = Files.createTempDirectory("supplier-hub-e2e-");
		System.out.println("E2E 실행 로그: " + reports);
		try {
			postgres.start();
			mock = new JarProcess(
				Path.of(System.getProperty("e2e.mock.jar")),
				workingDirectory,
				reports.resolve("mock.log")
			);
			mockAddress = mock.awaitAddress();
			assertThat(request(mockAddress, "/a/v1/hotels", false).status()).isEqualTo(200);
			application = new JarProcess(
				Path.of(System.getProperty("e2e.app.jar")),
				workingDirectory,
				reports.resolve("application.log"),
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--supplier.a.base-url=" + mockAddress,
				"--supplier.b.base-url=" + mockAddress,
				"--supplier.catalog.enabled=true",
				"--supplier.catalog.initial-delay=0",
				"--supplier.catalog.fixed-delay=1h"
			);
			applicationAddress = application.awaitAddress();
			assertThat(request(applicationAddress, "/actuator/health", false).status())
				.isEqualTo(200);
			awaitCatalogCommit();
		} catch (Exception | AssertionError failure) {
			try {
				stopStack();
			} catch (Exception cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	@BeforeEach
	void resetSupplierModes(TestInfo testInfo) throws Exception {
		scenario = testInfo.getDisplayName();
		application.ensureAlive();
		mock.ensureAlive();
		mode("a", "normal");
		mode("b", "normal");
	}

	@AfterAll
	void stopStack() throws IOException {
		try {
			if (application != null) {
				application.close();
				application = null;
			}
		} finally {
			try {
				if (mock != null) {
					mock.close();
					mock = null;
				}
			} finally {
				try {
					if (postgres.isRunning()) {
						Files.writeString(reports.resolve("postgres.log"), postgres.getLogs());
					}
				} finally {
					postgres.close();
					http.close();
					if (workingDirectory != null) {
						Files.deleteIfExists(workingDirectory);
					}
				}
			}
		}
	}

	@Test
	void normalSearchUsesCommittedIdsAndNormalizesTaxesInventoryAndSoldOutRooms()
		throws Exception {
		HttpResult result = search(THREE_NIGHTS);

		assertSearch(result, 200, "COMPLETE", 2);
		assertOffer(result, catalog.get("A-10023"), 429_000, 1, false);
		assertOffer(result, catalog.get("B77120"), 452_000, 1, true);
		assertSupplier(result, A, "SUCCESS", 1, 1);
		assertSupplier(result, B, "SUCCESS", 1, 0);
		assertThat(elements(result.body().get("stays")))
			.extracting(stay -> stay.get("stayId").longValue())
			.doesNotContain(catalog.get("A-10044").propertyId());
		assertThat(result.body().get("searchCriteria").get("checkOut").asString())
			.isEqualTo("2026-10-04");

		// 같은 객실도 품절 날짜를 포함하지 않는 1박 검색에는 반환된다.
		HttpResult oneNight = search(THREE_NIGHTS.replace("2026-10-04", "2026-10-02"));
		assertSearch(oneNight, 200, "COMPLETE", 3);
		assertOffer(oneNight, catalog.get("A-10023"), 132_000, 3, false);
		assertOffer(oneNight, catalog.get("A-10044"), 96_800, 2, false);
		assertOffer(oneNight, catalog.get("B77120"), 152_000, 3, true);
	}

	@Test
	void returnsNamesFromCommittedDatabaseMetadata() throws Exception {
		CatalogMapping original = catalog.get("A-10023");
		CatalogMapping renamed = new CatalogMapping(
			original.supplier(), original.supplierRoomTypeCode(),
			original.propertyId(), original.roomTypeId(),
			"DB Catalog Hotel", "DB Catalog Room"
		);
		try {
			writeNames(renamed);
			HttpResult result = search(THREE_NIGHTS);
			assertSearch(result, 200, "COMPLETE", 2);
			assertOffer(result, renamed, 429_000, 1, false);
		} finally {
			writeNames(original);
		}
	}

	@Test
	void retainsSupplierBWhenSupplierAReturnsHttp503() throws Exception {
		mode("a", "error");
		assertThat(request(mockAddress,
			"/a/v1/availability?hotelCodes=A-10023&" + THREE_NIGHTS, false).status())
			.isEqualTo(503);
		HttpResult result = search(THREE_NIGHTS);

		assertSearch(result, 200, "PARTIAL", 1);
		assertOffer(result, catalog.get("B77120"), 452_000, 1, true);
		assertSupplier(result, A, "FAILED", 0, 0, "UNAVAILABLE");
		assertSupplier(result, B, "SUCCESS", 1, 0);
	}

	@Test
	void retainsSupplierAWhenSupplierBReturnsHttp200WithBodyError()
		throws Exception {
		mode("b", "error");
		HttpResult upstream = request(mockAddress,
			"/b/api/search?propertyIds=B77120&" + THREE_NIGHTS, false);
		assertThat(upstream.status()).isEqualTo(200);
		assertThat(upstream.body().get("resultCode").asString()).isEqualTo("E503");
		HttpResult result = search(THREE_NIGHTS);

		assertSearch(result, 200, "PARTIAL", 1);
		assertOffer(result, catalog.get("A-10023"), 429_000, 1, false);
		assertSupplier(result, A, "SUCCESS", 1, 1);
		assertSupplier(result, B, "FAILED", 0, 0, "UNAVAILABLE");
	}

	@Test
	void returns503WhenBothSuppliersFail() throws Exception {
		mode("a", "error");
		mode("b", "error");
		HttpResult result = search(THREE_NIGHTS);

		assertSearch(result, 503, "FAILED", 0);
		assertSupplier(result, A, "FAILED", 0, 0, "UNAVAILABLE");
		assertSupplier(result, B, "FAILED", 0, 0, "UNAVAILABLE");
	}

	@Test
	void timesOutSupplierAAndRecoversOnNextNormalSearch() throws Exception {
		mode("a", "no-response");
		HttpResult result = search(THREE_NIGHTS);

		assertSearch(result, 200, "PARTIAL", 1);
		assertOffer(result, catalog.get("B77120"), 452_000, 1, true);
		assertSupplier(result, A, "FAILED", 0, 0, "TIMEOUT");
		assertSupplier(result, B, "SUCCESS", 1, 0);
		// 기본 응답 제한시간의 작동을 확인하며 전체 5초 보장을 주장하지 않는다.
		assertThat(result.elapsed()).isLessThan(Duration.ofSeconds(8));

		mode("a", "normal");
		HttpResult recovered = search(THREE_NIGHTS);
		assertSearch(recovered, 200, "COMPLETE", 2);
		assertOffer(recovered, catalog.get("A-10023"), 429_000, 1, false);
		assertOffer(recovered, catalog.get("B77120"), 452_000, 1, true);
	}

	@Test
	void excludesRoomsThatCannotAccommodateAdultsAndChildrenTogether() throws Exception {
		HttpResult result = search(THREE_NIGHTS.replace("children=0", "children=1"));

		assertSearch(result, 200, "COMPLETE", 0);
		assertSupplier(result, A, "SUCCESS", 0, 2);
		assertSupplier(result, B, "SUCCESS", 0, 1);
	}

	@Test
	void rejectsInvalidCustomerInputsBeforeCallingSuppliers() throws Exception {
		Map<String, String> invalidQueries = Map.of(
			THREE_NIGHTS.replace("2026-10-04", "2026-10-01"), "INVALID_SEARCH_CRITERIA",
			THREE_NIGHTS.replace("adults=2", "adults=-1"), "INVALID_SEARCH_CRITERIA",
			THREE_NIGHTS.replace("2026-10-01", "not-a-date"), "INVALID_REQUEST_PARAMETER",
			THREE_NIGHTS.replace("&adults=2", ""), "INVALID_REQUEST_PARAMETER"
		);
		for (var invalid : invalidQueries.entrySet()) {
			HttpResult result = searchWithoutSupplierCalls(invalid.getKey());
			assertThat(result.status()).isEqualTo(400);
			assertThat(result.body().get("code").asString()).isEqualTo(invalid.getValue());
		}
	}

	@Test
	void returnsCatalogUnavailableDuringDatabaseLockAndRecoversAfterRelease()
		throws Exception {
		long failuresBefore = metricCount("search.catalog.read.failures", "reason:TIMEOUT");
		try (Connection connection = database()) {
			connection.setAutoCommit(false);
			try {
				try (var statement = connection.createStatement()) {
					statement.execute("lock table supplier_property in access exclusive mode");
				}

				HttpResult result = searchWithoutSupplierCalls(THREE_NIGHTS);

				assertSearch(result, 503, "FAILED", 0);
				assertSupplier(result, A, "FAILED", 0, 0, "CATALOG_UNAVAILABLE");
				assertSupplier(result, B, "FAILED", 0, 0, "CATALOG_UNAVAILABLE");
				assertThat(result.elapsed()).isLessThan(Duration.ofSeconds(4));
				assertThat(metricCount("search.catalog.read.failures", "reason:TIMEOUT"))
					.isEqualTo(failuresBefore + 1);
			} finally {
				connection.rollback();
			}
		}
		HttpResult recovered = search(THREE_NIGHTS);
		assertSearch(recovered, 200, "COMPLETE", 2);
		assertOffer(recovered, catalog.get("A-10023"), 429_000, 1, false);
		assertOffer(recovered, catalog.get("B77120"), 452_000, 1, true);
	}

	private HttpResult search(String query) throws Exception {
		Map<String, Long> before = searchCallCounts();
		HttpResult result = request(applicationAddress, SEARCH + query, false);
		assertOneRecordedSearchCallPerSupplier(before);
		return result;
	}

	private HttpResult searchWithoutSupplierCalls(String query) throws Exception {
		Map<String, Long> before = searchCallCounts();
		HttpResult result = request(applicationAddress, SEARCH + query, false);
		assertThat(searchCallCounts()).isEqualTo(before);
		return result;
	}

	private void mode(String supplier, String mode) throws Exception {
		HttpResult result = request(mockAddress,
			"/control/" + supplier + "/mode?value=" + mode, true);
		assertThat(result.status()).isEqualTo(200);
		assertThat(result.body().get(supplier).asString()).isEqualTo(mode);
	}

	private HttpResult request(URI address, String path, boolean post) throws Exception {
		URI target = address.resolve(path);
		HttpRequest.Builder request = HttpRequest.newBuilder(target)
			.timeout(Duration.ofSeconds(12));
		if (post) {
			request.POST(HttpRequest.BodyPublishers.noBody());
		}
		long started = System.nanoTime();
		try {
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
			Files.writeString(reports.resolve("http.log"),
				"scenario=" + scenario + " " + (post ? "POST " : "GET ") + target
					+ " status=" + response.statusCode() + " elapsedMs=" + elapsed.toMillis()
					+ "\n" + response.body() + "\n\n",
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			return new HttpResult(response.statusCode(),
				json.readTree(response.body().isBlank() ? "null" : response.body()),
				response.body(), elapsed);
		} catch (Exception exception) {
			Files.writeString(reports.resolve("http.log"),
				"scenario=" + scenario + " " + target + " failed=" + exception + "\n",
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			throw exception;
		}
	}

	private void assertSearch(HttpResult result, int httpStatus, String status, int stays) {
		assertThat(result.status()).as(result.raw()).isEqualTo(httpStatus);
		assertThat(result.body().get("status").asString()).isEqualTo(status);
		assertThat(elements(result.body().get("stays"))).hasSize(stays);
		assertThat(elements(result.body().get("supplierResults"))).hasSize(2);
		assertThat(result.raw()).doesNotContain(
			"A-10023", "A-10044", "B77120", "DLX-TWN", "STD-DBL", "R-401",
			"supplierPropertyCode", "supplierRoomTypeCode", "hotelCode", "propertyId", "roomId",
			"nightlyBreakdown", "baseAmount", "taxAmount", "dailyRates", "nightlyRate"
		);
	}

	private void assertOffer(
		HttpResult result, CatalogMapping mapping, long amount, int inventory, boolean breakfast
	) {
		JsonNode stay = elements(result.body().get("stays")).stream()
			.filter(value -> value.get("stayId").longValue() == mapping.propertyId())
			.findFirst().orElseThrow();
		assertThat(stay.get("stayName").asString()).isEqualTo(mapping.propertyName());
		assertThat(elements(stay.get("roomTypes"))).hasSize(1);
		JsonNode room = stay.get("roomTypes").get(0);
		assertThat(room.get("roomTypeId").longValue()).isEqualTo(mapping.roomTypeId());
		assertThat(room.get("roomTypeName").asString()).isEqualTo(mapping.roomTypeName());
		assertThat(room.get("maxOccupancy").intValue()).isEqualTo(2);
		assertThat(elements(room.get("offers"))).hasSize(1);
		JsonNode offer = room.get("offers").get(0);
		assertThat(offer.get("supplier").asString()).isEqualTo(mapping.supplier());
		assertThat(offer.get("availableRooms").intValue()).isEqualTo(inventory);
		assertThat(offer.get("breakfastIncluded").booleanValue()).isEqualTo(breakfast);
		assertThat(offer.get("price").get("currency").asString()).isEqualTo("KRW");
		assertThat(offer.get("price").get("totalAmountIncludingTax").longValue()).isEqualTo(amount);
	}

	private void assertSupplier(
		HttpResult result, String supplier, String status, int accepted, int unavailable,
		String... failures
	) {
		JsonNode outcome = elements(result.body().get("supplierResults")).stream()
			.filter(value -> value.get("supplier").asString().equals(supplier))
			.findFirst().orElseThrow();
		assertThat(outcome.get("status").asString()).isEqualTo(status);
		assertThat(outcome.get("acceptedOfferCount").intValue()).isEqualTo(accepted);
		assertThat(outcome.get("rejectedOfferCount").intValue()).isZero();
		assertThat(outcome.get("unavailableOfferCount").intValue()).isEqualTo(unavailable);
		assertThat(elements(outcome.get("failureTypes"))).extracting(JsonNode::asString)
			.containsExactly(failures);
	}

	private List<JsonNode> elements(JsonNode array) {
		assertThat(array.isArray()).isTrue();
		return IntStream.range(0, array.size()).mapToObj(array::get).toList();
	}

	private Map<String, Long> searchCallCounts() throws Exception {
		return Map.of(
			A, metricCount("supplier.calls", "operation:SEARCH", "supplier:" + A),
			B, metricCount("supplier.calls", "operation:SEARCH", "supplier:" + B)
		);
	}

	private long metricCount(String name, String... tags) throws Exception {
		String path = "/actuator/metrics/" + name;
		for (int index = 0; index < tags.length; index++) {
			path += (index == 0 ? "?" : "&") + "tag=" + tags[index].replace(":", "%3A");
		}
		HttpResult result = request(applicationAddress, path, false);
		if (result.status() == 404) {
			return 0;
		}
		assertThat(result.status()).isEqualTo(200);
		return elements(result.body().get("measurements")).stream()
			.filter(value -> value.get("statistic").asString().equals("COUNT"))
			.mapToLong(value -> (long) value.get("value").doubleValue())
			.sum();
	}

	private void assertOneRecordedSearchCallPerSupplier(Map<String, Long> before) throws Exception {
		// 논리 호출의 완료 기록을 확인하며 실제 HTTP 재전송 횟수를 증명하지 않는다.
		Map<String, Long> expected = Map.of(A, before.get(A) + 1, B, before.get(B) + 1);
		long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		Map<String, Long> actual;
		do {
			actual = searchCallCounts();
			if (actual.equals(expected)) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(50);
		} while (System.nanoTime() < deadline);
		assertThat(actual).isEqualTo(expected);
	}

	private void awaitCatalogCommit() throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		do {
			application.ensureAlive();
			catalog = readCatalog();
			if (catalog.size() == 3) {
				assertThat(catalog.keySet()).containsExactlyInAnyOrder("A-10023", "A-10044", "B77120");
				assertThat(catalog.get("A-10023"))
					.extracting(CatalogMapping::supplier, CatalogMapping::supplierRoomTypeCode)
					.containsExactly(A, "DLX-TWN");
				assertThat(catalog.get("A-10044"))
					.extracting(CatalogMapping::supplier, CatalogMapping::supplierRoomTypeCode)
					.containsExactly(A, "STD-DBL");
				assertThat(catalog.get("B77120"))
					.extracting(CatalogMapping::supplier, CatalogMapping::supplierRoomTypeCode)
					.containsExactly(B, "R-401");
				return;
			}
			TimeUnit.MILLISECONDS.sleep(100);
		} while (System.nanoTime() < deadline);
		throw new IllegalStateException("스케줄 카탈로그 commit 대기 실패. 로그: " + reports);
	}

	private Map<String, CatalogMapping> readCatalog() throws SQLException {
		Map<String, CatalogMapping> rows = new LinkedHashMap<>();
		try (Connection connection = database(); var statement = connection.createStatement();
			var result = statement.executeQuery("""
				select property.supplier, property.supplier_property_code,
				       property.id, room.id as room_id, room.supplier_room_type_code,
				       property.name, room.name as room_name
				from supplier_property property
				join supplier_room_type room on room.property_id = property.id
				where property.active and room.active
				""")) {
			while (result.next()) {
				String propertyCode = result.getString("supplier_property_code");
				assertThat(rows).as("fixture 숙소별 객실이 정확히 한 개여야 한다")
					.doesNotContainKey(propertyCode);
				rows.put(propertyCode, new CatalogMapping(
					result.getString("supplier"), result.getString("supplier_room_type_code"),
					result.getLong("id"), result.getLong("room_id"),
					result.getString("name"), result.getString("room_name")
				));
			}
		}
		return rows;
	}

	private void writeNames(CatalogMapping mapping) throws SQLException {
		try (Connection connection = database()) {
			connection.setAutoCommit(false);
			try (var property = connection.prepareStatement("update supplier_property set name = ? where id = ?");
				var room = connection.prepareStatement("update supplier_room_type set name = ? where id = ?")) {
				property.setString(1, mapping.propertyName());
				property.setLong(2, mapping.propertyId());
				assertThat(property.executeUpdate()).isEqualTo(1);
				room.setString(1, mapping.roomTypeName());
				room.setLong(2, mapping.roomTypeId());
				assertThat(room.executeUpdate()).isEqualTo(1);
				connection.commit();
			}
		}
	}

	private Connection database() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private record HttpResult(int status, JsonNode body, String raw, Duration elapsed) {
	}

	private record CatalogMapping(
		String supplier, String supplierRoomTypeCode,
		long propertyId, long roomTypeId, String propertyName, String roomTypeName
	) {
	}

}
