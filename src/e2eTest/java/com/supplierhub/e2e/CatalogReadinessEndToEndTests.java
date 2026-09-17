package com.supplierhub.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
class CatalogReadinessEndToEndTests {

	private static final String A = "SUPPLIER_A";
	private static final String B = "SUPPLIER_B";
	private static final String SEARCH =
		"/api/v1/stays/search?checkIn=2026-10-01&checkOut=2026-10-04&adults=2&children=0";
	private final JsonMapper json = JsonMapper.builder().build();
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(2))
		.build();
	private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
		.withDatabaseName("catalog_readiness_e2e")
		.withUsername("catalog_readiness_e2e")
		.withPassword("catalog_readiness_e2e");
	private Path reports;
	private Path workingDirectory;
	private Path scenarioReports;
	private String schema;
	private int scenarioIndex;

	@BeforeAll
	void startDatabase() throws Exception {
		reports = Path.of(System.getProperty("e2e.logs", "build/reports/e2e"))
			.toAbsolutePath().resolve("catalog-readiness-" + System.currentTimeMillis());
		Files.createDirectories(reports);
		workingDirectory = Files.createTempDirectory("catalog-readiness-e2e-");
		System.out.println("카탈로그 준비 상태 E2E 실행 로그: " + reports);
		postgres.start();
	}

	@BeforeEach
	void createIsolatedSchema(TestInfo testInfo) throws Exception {
		schema = "readiness_" + ++scenarioIndex;
		scenarioReports = reports.resolve(testInfo.getTestMethod().orElseThrow().getName());
		Files.createDirectories(scenarioReports);
		try (var connection = DriverManager.getConnection(
			postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var statement = connection.createStatement()) {
			statement.execute("create schema " + schema);
		}
	}

	@AfterAll
	void stopDatabase() throws IOException {
		try {
			if (postgres.isRunning()) {
				Files.writeString(reports.resolve("postgres.log"), postgres.getLogs());
			}
		} finally {
			try {
				postgres.close();
			} finally {
				http.close();
				if (workingDirectory != null) {
					Files.deleteIfExists(workingDirectory);
				}
			}
		}
	}

	@Test
	void freshDatabaseWithoutSynchronizationReturnsCatalogUnavailable() throws Exception {
		try (EmptyCatalogSupplier fixture = new EmptyCatalogSupplier(false);
			JarProcess application = startApplication(fixture, false, "application.log")) {
			HttpResult result = search(application.awaitAddress());

			assertSearch(result, 503, "FAILED");
			assertSupplier(result, A, "FAILED", "CATALOG_UNAVAILABLE");
			assertSupplier(result, B, "FAILED", "CATALOG_UNAVAILABLE");
			assertThat(initializedSuppliers()).isEmpty();
			assertThat(fixture.catalogACalls.get()).isZero();
			assertThat(fixture.catalogBCalls.get()).isZero();
			assertThat(fixture.searchCalls.get()).isZero();
		}
	}

	@Test
	void committedEmptyCatalogsRemainReadyAfterRestartWithoutSynchronization() throws Exception {
		try (EmptyCatalogSupplier fixture = new EmptyCatalogSupplier(false)) {
			try (JarProcess application = startApplication(fixture, true, "initial-application.log")) {
				URI address = application.awaitAddress();
				awaitCatalogCommit(application, List.of(A, B));
				HttpResult result = search(address);

				assertSearch(result, 200, "COMPLETE");
				assertSupplier(result, A, "SUCCESS");
				assertSupplier(result, B, "SUCCESS");
				assertThat(fixture.catalogACalls.get()).isEqualTo(1);
				assertThat(fixture.catalogBCalls.get()).isEqualTo(1);
				assertThat(fixture.searchCalls.get()).isZero();
			}

			try (JarProcess restarted = startApplication(fixture, false, "restarted-application.log")) {
				HttpResult result = search(restarted.awaitAddress());

				assertSearch(result, 200, "COMPLETE");
				assertSupplier(result, A, "SUCCESS");
				assertSupplier(result, B, "SUCCESS");
				assertThat(initializedSuppliers()).containsExactly(A, B);
				// 재동기화나 공급사 검색 없이 DB에 commit된 준비 상태로 판단한다.
				assertThat(fixture.catalogACalls.get()).isEqualTo(1);
				assertThat(fixture.catalogBCalls.get()).isEqualTo(1);
				assertThat(fixture.searchCalls.get()).isZero();
			}
		}
	}

	@Test
	void emptySupplierAAndUninitializedSupplierBReturnPartialSuccess() throws Exception {
		try (EmptyCatalogSupplier fixture = new EmptyCatalogSupplier(true);
			JarProcess application = startApplication(fixture, true, "application.log")) {
			URI address = application.awaitAddress();
			awaitCatalogCommit(application, List.of(A));
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while (fixture.catalogBCalls.get() == 0 && System.nanoTime() < deadline) {
				application.ensureAlive();
				TimeUnit.MILLISECONDS.sleep(50);
			}
			assertThat(fixture.catalogBCalls.get()).isEqualTo(1);

			HttpResult result = search(address);
			assertSearch(result, 200, "PARTIAL");
			assertSupplier(result, A, "SUCCESS");
			assertSupplier(result, B, "FAILED", "CATALOG_UNAVAILABLE");
			assertThat(initializedSuppliers()).containsExactly(A);
			assertThat(fixture.catalogACalls.get()).isEqualTo(1);
			assertThat(fixture.searchCalls.get()).isZero();
		}
	}

	private JarProcess startApplication(EmptyCatalogSupplier fixture, boolean synchronize, String log)
		throws IOException {
		return new JarProcess(
			Path.of(System.getProperty("e2e.app.jar")),
			workingDirectory,
			scenarioReports.resolve(log),
			"--spring.datasource.url=" + jdbcUrl(),
			"--spring.datasource.username=" + postgres.getUsername(),
			"--spring.datasource.password=" + postgres.getPassword(),
			"--spring.flyway.default-schema=" + schema,
			"--supplier.a.base-url=" + fixture.address(),
			"--supplier.b.base-url=" + fixture.address(),
			"--supplier.catalog.enabled=" + synchronize,
			"--supplier.catalog.initial-delay=0",
			"--supplier.catalog.fixed-delay=1h",
			"--supplier.catalog.max-retries=0"
		);
	}

	private String jdbcUrl() {
		String url = postgres.getJdbcUrl();
		return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
	}

	private List<String> initializedSuppliers() throws SQLException {
		List<String> suppliers = new ArrayList<>();
		try (var connection = DriverManager.getConnection(
			jdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var statement = connection.createStatement();
			var rows = statement.executeQuery("select supplier from supplier_catalog_state order by supplier")) {
			while (rows.next()) {
				suppliers.add(rows.getString(1));
			}
		}
		return suppliers;
	}

	private void awaitCatalogCommit(JarProcess application, List<String> expected) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		List<String> actual;
		do {
			application.ensureAlive();
			actual = initializedSuppliers();
			if (actual.equals(expected)) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(100);
		} while (System.nanoTime() < deadline);
		assertThat(actual).as("정상 빈 카탈로그 준비 상태 commit, 로그: %s", scenarioReports)
			.isEqualTo(expected);
	}

	private HttpResult search(URI address) throws Exception {
		HttpResponse<String> response = http.send(
			HttpRequest.newBuilder(address.resolve(SEARCH)).timeout(Duration.ofSeconds(12)).build(),
			HttpResponse.BodyHandlers.ofString()
		);
		Files.writeString(scenarioReports.resolve("http.log"),
			"GET " + address.resolve(SEARCH) + " status=" + response.statusCode()
				+ "\n" + response.body() + "\n\n",
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		return new HttpResult(response.statusCode(), json.readTree(response.body()), response.body());
	}

	private void assertSearch(HttpResult result, int httpStatus, String status) {
		assertThat(result.status()).as(result.raw()).isEqualTo(httpStatus);
		assertThat(result.body().get("status").asString()).isEqualTo(status);
		assertThat(elements(result.body().get("stays"))).isEmpty();
		assertThat(elements(result.body().get("supplierResults"))).hasSize(2);
	}

	private void assertSupplier(HttpResult result, String supplier, String status, String... failures) {
		JsonNode outcome = elements(result.body().get("supplierResults")).stream()
			.filter(value -> value.get("supplier").asString().equals(supplier))
			.findFirst().orElseThrow();
		assertThat(outcome.get("status").asString()).isEqualTo(status);
		assertThat(outcome.get("acceptedOfferCount").intValue()).isZero();
		assertThat(outcome.get("rejectedOfferCount").intValue()).isZero();
		assertThat(outcome.get("unavailableOfferCount").intValue()).isZero();
		assertThat(elements(outcome.get("failureTypes"))).extracting(JsonNode::asString)
			.containsExactly(failures);
	}

	private List<JsonNode> elements(JsonNode array) {
		assertThat(array.isArray()).isTrue();
		return IntStream.range(0, array.size()).mapToObj(array::get).toList();
	}

	private record HttpResult(int status, JsonNode body, String raw) {
	}

	private static final class EmptyCatalogSupplier implements AutoCloseable {
		private final HttpServer server;
		private final boolean failSupplierB;
		private final AtomicInteger catalogACalls = new AtomicInteger();
		private final AtomicInteger catalogBCalls = new AtomicInteger();
		private final AtomicInteger searchCalls = new AtomicInteger();

		private EmptyCatalogSupplier(boolean failSupplierB) throws IOException {
			this.failSupplierB = failSupplierB;
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", this::respond);
			server.start();
		}

		private URI address() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		}

		private void respond(HttpExchange exchange) throws IOException {
			try (exchange) {
				int status = 200;
				String body;
				switch (exchange.getRequestURI().getPath()) {
					case "/a/v1/hotels" -> {
						catalogACalls.incrementAndGet();
						body = "{\"items\":[]}";
					}
					case "/b/api/properties" -> {
						catalogBCalls.incrementAndGet();
						status = failSupplierB ? 503 : 200;
						body = failSupplierB ? "{\"error\":\"catalog unavailable\"}"
							: "{\"resultCode\":\"0000\",\"data\":{\"items\":[]}}";
					}
					default -> {
						searchCalls.incrementAndGet();
						status = 500;
						body = "{\"error\":\"unexpected supplier request\"}";
					}
				}
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
