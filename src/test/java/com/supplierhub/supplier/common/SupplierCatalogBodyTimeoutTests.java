package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.application.ActiveCatalogSnapshot;
import com.supplierhub.catalog.application.CatalogSnapshotUpdate;
import com.supplierhub.catalog.application.CatalogSynchronizationService;
import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.application.IntegratedSearchService;
import com.supplierhub.search.application.SearchStatus;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.suppliera.SupplierACatalogClient;
import com.supplierhub.supplier.suppliera.SupplierASearchClient;
import com.supplierhub.supplier.supplierb.SupplierBCatalogClient;
import com.supplierhub.supplier.supplierb.SupplierBSearchClient;

import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class SupplierCatalogBodyTimeoutTests {

	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofMillis(250);
	private final SupplierResourceFixture fixture = new SupplierResourceFixture();
	private final AtomicInteger attempts = new AtomicInteger();
	private final AtomicInteger partialBodiesSent = new AtomicInteger();
	private final CountDownLatch releaseResponses = new CountDownLatch(1);
	private final List<CatalogSnapshot> storedSnapshots = new ArrayList<>();
	private volatile int stalledAttempts = Integer.MAX_VALUE;
	private ExecutorService executor;
	private HttpServer server;

	@BeforeEach
	void start() throws IOException {
		executor = Executors.newVirtualThreadPerTaskExecutor();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(executor);
		server.createContext("/", this::respond);
		server.start();
	}

	@AfterEach
	void stop() {
		releaseResponses.countDown();
		if (server != null) server.stop(0);
		if (executor != null) executor.close();
		fixture.close();
	}

	@ParameterizedTest
	@EnumSource(Supplier.class)
	void classifiesReadTimeoutAfterHeadersAsRetryable(Supplier supplier) {
		var client = catalogClient(supplier);
		assertThatThrownBy(() -> fixture.resources.execute(
			supplier, SupplierOperation.CATALOG, client::fetchCatalog
		).block(WAIT)).isInstanceOfSatisfying(SupplierIntegrationException.class, failure -> {
			assertThat(failure.getFailureType()).isEqualTo(SupplierFailureType.TIMEOUT);
			assertThat(failure.isRetryable()).isTrue();
			assertThat(failure.getCause()).isInstanceOf(WebClientResponseException.class);
			assertThat(failure).hasRootCauseInstanceOf(ReadTimeoutException.class);
		});
		assertThat(attempts).hasValue(1);
		assertThat(partialBodiesSent).hasValue(1);
		assertCallMetrics(supplier, SupplierOperation.CATALOG, 1, 0);
	}

	@ParameterizedTest
	@EnumSource(Supplier.class)
	void retriesTwiceThenReleasesCapacityAndRecoversOnNextSynchronization(Supplier supplier) {
		var service = catalogService(supplier);
		service.synchronizeAll();

		assertThat(attempts).hasValue(3);
		assertThat(partialBodiesSent).hasValue(3);
		assertThat(storedSnapshots).isEmpty();
		assertCallMetrics(supplier, SupplierOperation.CATALOG, 3, 0);
		assertThat(timerCount("supplier.catalog.sync", supplier, "FAILED", "TIMEOUT"))
			.isEqualTo(1);
		assertThat(gauge("supplier.catalog.consecutive.failures", supplier)).isEqualTo(1);
		assertThat(gauge("supplier.catalog.last.success", supplier)).isZero();

		stalledAttempts = 0;
		service.synchronizeAll();

		assertThat(attempts).hasValue(4);
		assertThat(storedSnapshots).singleElement()
			.satisfies(snapshot -> assertThat(snapshot.supplier()).isEqualTo(supplier));
		assertCallMetrics(supplier, SupplierOperation.CATALOG, 3, 1);
		assertThat(timerCount("supplier.catalog.sync", supplier, "SUCCESS", "NONE"))
			.isEqualTo(1);
		assertThat(gauge("supplier.catalog.consecutive.failures", supplier)).isZero();
		assertThat(gauge("supplier.catalog.last.success", supplier)).isPositive();
	}

	@ParameterizedTest
	@EnumSource(Supplier.class)
	void storesSnapshotWhenTheFirstRetryRecovers(Supplier supplier) {
		stalledAttempts = 1;
		catalogService(supplier).synchronizeAll();

		assertThat(attempts).hasValue(2);
		assertThat(partialBodiesSent).hasValue(1);
		assertThat(storedSnapshots).singleElement()
			.satisfies(snapshot -> assertThat(snapshot.supplier()).isEqualTo(supplier));
		assertCallMetrics(supplier, SupplierOperation.CATALOG, 1, 1);
		assertThat(timerCount("supplier.catalog.sync", supplier, "SUCCESS", "NONE"))
			.isEqualTo(1);
		assertThat(timerCount("supplier.catalog.sync", supplier, "FAILED", "TIMEOUT"))
			.isZero();
		assertThat(gauge("supplier.catalog.consecutive.failures", supplier)).isZero();
		assertThat(gauge("supplier.catalog.last.success", supplier)).isPositive();
	}

	@ParameterizedTest
	@EnumSource(Supplier.class)
	void searchStillDoesNotRetryBodyReadTimeout(Supplier supplier) {
		var properties = properties(supplier);
		var configuration = new SupplierClientConfiguration();
		SupplierSearchClient client = switch (supplier) {
			case SUPPLIER_A -> new SupplierASearchClient(configuration.supplierASearchWebClient(
				WebClient.builder(), properties, fixture.resources
			), properties);
			case SUPPLIER_B -> new SupplierBSearchClient(configuration.supplierBSearchWebClient(
				WebClient.builder(), properties, fixture.resources
			), properties);
		};
		var service = new IntegratedSearchService(
			deadline -> new ActiveCatalogSnapshot(
				List.of(new ActiveCatalogMapping(1, supplier, "P1", "Hotel", 1, "R1", "Room")),
				Set.of(supplier)
			),
			List.of(client), properties, fixture.resources, fixture.metrics
		);
		var result = service.search(new SearchCriteria(
			LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 2, 0
		));

		assertThat(result.status()).isEqualTo(SearchStatus.FAILED);
		assertThat(result.supplierResults()).singleElement().satisfies(supplierResult ->
			assertThat(supplierResult.failureTypes()).containsExactly(SupplierFailureType.TIMEOUT)
		);
		assertThat(attempts).hasValue(1);
		assertThat(partialBodiesSent).hasValue(1);
		assertCallMetrics(supplier, SupplierOperation.SEARCH, 1, 0);
	}

	private void respond(HttpExchange exchange) throws IOException {
		int attempt = attempts.incrementAndGet();
		boolean supplierA = exchange.getRequestURI().getPath().startsWith("/a/");
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		try {
			if (attempt <= stalledAttempts) {
				exchange.sendResponseHeaders(200, 0);
				String prefix = supplierA ? "{\"items\":[" : "{\"resultCode\":\"0000\",\"data\":{\"items\":[";
				exchange.getResponseBody().write(prefix.getBytes(StandardCharsets.UTF_8));
				exchange.getResponseBody().flush();
				partialBodiesSent.incrementAndGet();
				// 헤더와 본문 일부를 실제 전송한 뒤, 클라이언트의 읽기 timeout을 기다린다.
				releaseResponses.await();
			} else {
				String body = supplierA ? "{\"items\":[]}" : "{\"resultCode\":\"0000\",\"data\":{\"items\":[]}}";
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} finally {
			exchange.close();
		}
	}

	private CatalogSynchronizationService catalogService(Supplier supplier) {
		return new CatalogSynchronizationService(
			List.of(catalogClient(supplier)), snapshot -> {
				storedSnapshots.add(snapshot);
				return new CatalogSnapshotUpdate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
			}, properties(supplier), fixture.resources, fixture.metrics
		);
	}

	private SupplierCatalogClient catalogClient(Supplier supplier) {
		var configuration = new SupplierClientConfiguration();
		return switch (supplier) {
			case SUPPLIER_A -> new SupplierACatalogClient(configuration.supplierAWebClient(
				WebClient.builder(), properties(supplier), fixture.resources
			));
			case SUPPLIER_B -> new SupplierBCatalogClient(configuration.supplierBWebClient(
				WebClient.builder(), properties(supplier), fixture.resources
			));
		};
	}

	private SupplierIntegrationProperties properties(Supplier supplier) {
		URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		return new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(supplier == Supplier.SUPPLIER_A, endpoint, "a-test"),
			new SupplierIntegrationProperties.Endpoint(supplier == Supplier.SUPPLIER_B, endpoint, "b-test"),
			new SupplierIntegrationProperties.Catalog(
				true, Duration.ofMillis(500), READ_TIMEOUT, Duration.ofSeconds(3), 2,
				Duration.ofMillis(10), Duration.ZERO, Duration.ofMinutes(10), 10, 0.5
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500), READ_TIMEOUT, Duration.ofSeconds(3), WAIT, 1
			)
		);
	}

	private void assertCallMetrics(Supplier supplier, SupplierOperation operation, long failed, long succeeded) {
		await().atMost(WAIT).untilAsserted(() -> {
			assertThat(callCount(supplier, operation, "FAILED", "TIMEOUT")).isEqualTo(failed);
			assertThat(callCount(supplier, operation, "SUCCESS", "NONE")).isEqualTo(succeeded);
			assertThat(callCount(supplier, operation, "FAILED", "UNKNOWN")).isZero();
			assertThat(fixture.registry.get("supplier.calls.active")
				.tags("supplier", supplier.name(), "operation", operation.name()).gauge().value()).isZero();
		});
	}

	private long callCount(Supplier supplier, SupplierOperation operation, String outcome, String failure) {
		var timer = fixture.registry.find("supplier.calls")
			.tags("supplier", supplier.name(), "operation", operation.name(), "outcome", outcome, "failure", failure)
			.timer();
		return timer == null ? 0 : timer.count();
	}

	private long timerCount(String name, Supplier supplier, String outcome, String failure) {
		var timer = fixture.registry.find(name)
			.tags("supplier", supplier.name(), "outcome", outcome, "failure", failure).timer();
		return timer == null ? 0 : timer.count();
	}

	private double gauge(String name, Supplier supplier) {
		return fixture.registry.get(name).tag("supplier", supplier.name()).gauge().value();
	}

}
