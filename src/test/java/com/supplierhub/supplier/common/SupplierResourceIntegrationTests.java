package com.supplierhub.supplier.common;

import static com.supplierhub.supplier.common.SupplierResponseFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.application.ActiveCatalogSnapshot;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.application.IntegratedSearchService;
import com.supplierhub.search.application.SearchStatus;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.suppliera.SupplierACatalogClient;
import com.supplierhub.supplier.suppliera.SupplierASearchClient;
import com.supplierhub.supplier.supplierb.SupplierBSearchClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierResourceIntegrationTests {

	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final SearchCriteria CRITERIA =
			new SearchCriteria(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 2, 0);
	private final SupplierResourceFixture fixture =
			new SupplierResourceFixture(2, 2 * 1024 * 1024, 8 * 1024 * 1024);
	private final Map<String, String> bodies = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
	private final AtomicInteger closedConnections = new AtomicInteger();
	private volatile Sinks.One<Void> gate;
	private DisposableServer server;
	private SupplierIntegrationProperties properties;
	private SupplierASearchClient a;
	private SupplierBSearchClient b;
	private SupplierACatalogClient catalog;

	@BeforeEach
	void start() {
		bodies.put("/a/v1/availability", "{\"items\":[]}");
		bodies.put("/b/api/search", "{\"resultCode\":\"0000\",\"data\":{\"items\":[]}}");
		bodies.put("/a/v1/hotels", "{\"items\":[]}");
		server =
				HttpServer.create()
						.host("127.0.0.1")
						.port(0)
						.handle(
								(request, response) -> {
									String path = URI.create(request.uri()).getPath();
									calls.computeIfAbsent(path, ignored -> new AtomicInteger())
											.incrementAndGet();
									response.header("Content-Type", "application/json");
									Sinks.One<Void> currentGate = gate;
									if (path.equals("/a/v1/availability") && currentGate != null) {
										response.withConnection(
												connection ->
														connection.onDispose(
																closedConnections
																		::incrementAndGet));
										return response.sendString(
												currentGate.asMono().thenReturn(bodies.get(path)));
									}
									return response.sendString(Mono.just(bodies.get(path)));
								})
						.bindNow();
		properties = properties(Duration.ofSeconds(3));
		var configuration = new SupplierClientConfiguration();
		a =
				new SupplierASearchClient(
						configuration.supplierASearchWebClient(
								WebClient.builder(), properties, fixture.resources),
						properties);
		b =
				new SupplierBSearchClient(
						configuration.supplierBSearchWebClient(
								WebClient.builder(), properties, fixture.resources),
						properties);
		catalog =
				new SupplierACatalogClient(
						configuration.supplierAWebClient(
								WebClient.builder(), properties, fixture.resources));
	}

	@AfterEach
	void stop() {
		if (gate != null) {
			gate.tryEmitEmpty();
		}
		fixture.close();
		server.disposeNow();
	}

	@Test
	void limitsConcurrentCustomersAndPreservesHealthySupplierOnTheSameHost() {
		gate = Sinks.one();
		IntegratedSearchService service = service(Duration.ofSeconds(3));
		var first = CompletableFuture.supplyAsync(() -> service.search(CRITERIA));
		var second = CompletableFuture.supplyAsync(() -> service.search(CRITERIA));
		try {
			await().atMost(WAIT)
					.untilAsserted(() -> assertThat(count("/a/v1/availability")).isEqualTo(2));
			assertThat(active(Supplier.SUPPLIER_A)).isEqualTo(2);
			// 고객 2명이 A의 허용량을 모두 점유한 동안 6명이 추가 검색한다.
			for (int i = 0; i < 6; i++) {
				var result = service.search(CRITERIA);
				assertThat(result.status()).isEqualTo(SearchStatus.PARTIAL);
				assertThat(result.supplierResults().getFirst().failureTypes())
						.containsExactly(SupplierFailureType.CAPACITY_EXCEEDED);
				assertThat(result.supplierResults().getLast().failureTypes()).isEmpty();
			}
			assertThat(count("/a/v1/availability")).isEqualTo(2);
			// 같은 Supplier라도 카탈로그 연결 풀과 허용량은 독립적이다.
			assertThat(
							fixture.resources
									.execute(
											Supplier.SUPPLIER_A,
											SupplierOperation.CATALOG,
											catalog::fetchCatalog)
									.block(WAIT))
					.isNotNull();
		} finally {
			gate.tryEmitEmpty();
		}
		assertThat(first.join().status()).isEqualTo(SearchStatus.COMPLETE);
		assertThat(second.join().status()).isEqualTo(SearchStatus.COMPLETE);
		assertThat(active(Supplier.SUPPLIER_A)).isZero();
		await().atMost(WAIT)
				.untilAsserted(
						() ->
								assertThat(
												fixture.registry
														.get("supplier.calls")
														.tags(
																"supplier",
																"SUPPLIER_A",
																"operation",
																"SEARCH",
																"failure",
																"CAPACITY_EXCEEDED")
														.timer()
														.count())
										.isEqualTo(6));
	}

	@Test
	void overallTimeoutClosesHttpRequestsReturnsCapacityAndAllowsRecovery() {
		gate = Sinks.one();
		IntegratedSearchService service = service(Duration.ofMillis(300));
		var result = service.search(CRITERIA);
		assertThat(result.status()).isEqualTo(SearchStatus.PARTIAL);
		assertThat(result.supplierResults().getFirst().failureTypes())
				.containsExactly(SupplierFailureType.TIMEOUT);
		await().atMost(WAIT)
				.untilAsserted(
						() -> {
							assertThat(closedConnections).hasValue(1);
							assertThat(active(Supplier.SUPPLIER_A)).isZero();
						});
		assertThat(count("/a/v1/availability")).isEqualTo(1);
		gate = null;
		assertThat(service(Duration.ofSeconds(3)).search(CRITERIA).status())
				.isEqualTo(SearchStatus.COMPLETE);
		await().atMost(WAIT)
				.untilAsserted(
						() ->
								assertThat(
												fixture.registry
														.get("supplier.calls")
														.tags(
																"supplier",
																"SUPPLIER_A",
																"operation",
																"SEARCH",
																"outcome",
																"CANCELLED")
														.timer()
														.count())
										.isEqualTo(1));
		assertThat(
						fixture.registry
								.get("supplier.search.failures")
								.tags("supplier", "SUPPLIER_A", "failure", "TIMEOUT")
								.counter()
								.count())
				.isEqualTo(1);
	}

	@Test
	void measuresHttp200BodyFailureAsBusinessFailureWithoutRetry() {
		bodies.put("/b/api/search", "{\"resultCode\":\"E503\",\"data\":null}");
		var result = service(Duration.ofSeconds(3)).search(CRITERIA);
		assertThat(result.status()).isEqualTo(SearchStatus.PARTIAL);
		assertThat(count("/b/api/search")).isEqualTo(1);
		await().atMost(WAIT)
				.untilAsserted(
						() ->
								assertThat(
												fixture.registry
														.get("supplier.calls")
														.tags(
																"supplier",
																"SUPPLIER_B",
																"operation",
																"SEARCH",
																"failure",
																"UNAVAILABLE",
																"outcome",
																"FAILED")
														.timer()
														.count())
										.isEqualTo(1));
		assertThat(
						fixture.registry
								.get("search.requests")
								.tag("outcome", "PARTIAL")
								.timer()
								.count())
				.isEqualTo(1);
		assertThat(fixture.registry.find("search.stages").timers()).hasSize(4);
	}

	@Test
	void acceptsLargeCatalogAndFiftyPropertiesWithThirtyNightPrices() {
		String catalogBody = catalogBody(3000);
		assertThat(catalogBody.length()).isGreaterThan(262144);
		bodies.put("/a/v1/hotels", catalogBody);
		assertThat(
						fixture.resources
								.execute(
										Supplier.SUPPLIER_A,
										SupplierOperation.CATALOG,
										catalog::fetchCatalog)
								.block(WAIT)
								.properties())
				.hasSize(3000);
		String searchBody = searchBody(50, 5, 30);
		assertThat(searchBody.length()).isGreaterThan(262144);
		bodies.put("/a/v1/availability", searchBody);
		var result =
				fixture.resources
						.execute(
								Supplier.SUPPLIER_A,
								SupplierOperation.SEARCH,
								() -> a.search(request(50, 5, 30)))
						.block(WAIT);
		assertThat(result.offers()).hasSize(250);
		assertThat(result.rejectedOfferCount()).isZero();
		assertThat(result.offers())
				.allSatisfy(
						offer -> assertThat(offer.price().totalAmount().amount()).isEqualTo(33000));
	}

	@Test
	void classifiesOversizedResponsesAndRecoversAfterBothOperations() {
		for (SupplierOperation operation : SupplierOperation.values()) {
			int limit = fixture.resources.maxResponseBytes(Supplier.SUPPLIER_A, operation);
			String path =
					operation == SupplierOperation.SEARCH ? "/a/v1/availability" : "/a/v1/hotels";
			bodies.put(path, "{\"padding\":\"" + "x".repeat(limit) + "\",\"items\":[]}");
			assertThatThrownBy(
							() ->
									fixture.resources
											.execute(
													Supplier.SUPPLIER_A,
													operation,
													() ->
															operation == SupplierOperation.SEARCH
																	? a.search(request(1, 1, 1))
																			.then()
																	: catalog.fetchCatalog().then())
											.block(WAIT))
					.isInstanceOfSatisfying(
							SupplierIntegrationException.class,
							error -> {
								assertThat(error.getFailureType())
										.isEqualTo(SupplierFailureType.RESPONSE_TOO_LARGE);
								assertThat(error.isRetryable()).isFalse();
							});
			assertThat(count(path)).isEqualTo(1);
			bodies.put(path, "{\"items\":[]}");
			fixture.resources
					.execute(
							Supplier.SUPPLIER_A,
							operation,
							() ->
									operation == SupplierOperation.SEARCH
											? a.search(request(1, 1, 1)).then()
											: catalog.fetchCatalog().then())
					.block(WAIT);
			assertThat(count(path)).isEqualTo(2);
		}
		assertThat(active(Supplier.SUPPLIER_A)).isZero();
	}

	@Test
	void observesDuplicatesSeparatelyFromRejectedOffers() {
		String single = searchBody(1, 1, 1);
		String item = single.substring("{\"items\":[".length(), single.length() - 2);
		bodies.put("/a/v1/availability", "{\"items\":[" + item + "," + item + "]}");
		var result =
				fixture.resources
						.execute(
								Supplier.SUPPLIER_A,
								SupplierOperation.SEARCH,
								() -> a.search(request(1, 1, 1)))
						.block(WAIT);
		assertThat(result.offers()).hasSize(1);
		assertThat(result.duplicateOfferCount()).isEqualTo(1);
		assertThat(result.rejectedOfferCount()).isZero();
		assertThat(
						fixture.registry
								.get("supplier.offers")
								.tags("supplier", "SUPPLIER_A", "outcome", "DUPLICATE")
								.counter()
								.count())
				.isEqualTo(1);
	}

	@Test
	void poolPendingOverflowAndTimeoutAreCapacityFailuresAndCancellationRecovers() {
		// 호출 수 제한을 우회해 실제 연결 풀의 한도를 검증한다.
		gate = Sinks.one();
		Disposable first = a.search(request(1, 1, 1)).subscribe();
		Disposable second = a.search(request(1, 1, 1)).subscribe();
		List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
		Disposable pendingOne = null;
		Disposable pendingTwo = null;
		try {
			await().atMost(WAIT)
					.untilAsserted(() -> assertThat(count("/a/v1/availability")).isEqualTo(2));
			pendingOne = a.search(request(1, 1, 1)).subscribe(ignored -> {}, failures::add);
			pendingTwo = a.search(request(1, 1, 1)).subscribe(ignored -> {}, failures::add);
			assertThatThrownBy(() -> a.search(request(1, 1, 1)).block(WAIT))
					.isInstanceOfSatisfying(
							SupplierIntegrationException.class,
							error ->
									assertThat(error.getFailureType())
											.isEqualTo(SupplierFailureType.CAPACITY_EXCEEDED));
			await().atMost(WAIT).untilAsserted(() -> assertThat(failures).hasSize(2));
			assertThat(failures)
					.allSatisfy(
							failure ->
									assertThat(failure)
											.isInstanceOfSatisfying(
													SupplierIntegrationException.class,
													error ->
															assertThat(error.getFailureType())
																	.isEqualTo(
																			SupplierFailureType
																					.CAPACITY_EXCEEDED)));
			assertThat(count("/a/v1/availability")).isEqualTo(2);
		} finally {
			first.dispose();
			second.dispose();
			if (pendingOne != null) pendingOne.dispose();
			if (pendingTwo != null) pendingTwo.dispose();
		}
		await().atMost(WAIT).untilAsserted(() -> assertThat(closedConnections).hasValue(2));
		gate = null;
		assertThat(a.search(request(1, 1, 1)).block(WAIT)).isNotNull();
	}

	private IntegratedSearchService service(Duration timeout) {
		return new IntegratedSearchService(
				deadlineNanos -> new ActiveCatalogSnapshot(
						List.of(
								new ActiveCatalogMapping(
										1, Supplier.SUPPLIER_A, "P0", "One", 1, "R0", "Room"),
								new ActiveCatalogMapping(
										2, Supplier.SUPPLIER_B, "P0", "Two", 2, "R0", "Room")),
						java.util.Set.of(Supplier.SUPPLIER_A, Supplier.SUPPLIER_B)),
				List.of(a, b),
				properties(timeout),
				fixture.resources,
				fixture.metrics);
	}

	private SupplierIntegrationProperties properties(Duration timeout) {
		URI url = URI.create("http://127.0.0.1:" + server.port());
		return new SupplierIntegrationProperties(
				new SupplierIntegrationProperties.Endpoint(true, url, "a-test"),
				new SupplierIntegrationProperties.Endpoint(true, url, "b-test"),
				new SupplierIntegrationProperties.Catalog(
						false,
						Duration.ofMillis(500),
						Duration.ofSeconds(3),
						Duration.ofSeconds(4),
						0,
						Duration.ofMillis(10),
						Duration.ZERO,
						Duration.ofMinutes(10),
						10,
						0.5),
				new SupplierIntegrationProperties.Search(
						Duration.ofMillis(500),
						Duration.ofSeconds(3),
						Duration.ofSeconds(4),
						timeout,
						4));
	}

	private int count(String path) {
		return calls.getOrDefault(path, new AtomicInteger()).get();
	}

	private double active(Supplier supplier) {
		return fixture.registry
				.get("supplier.calls.active")
				.tags("supplier", supplier.name(), "operation", "SEARCH")
				.gauge()
				.value();
	}
}
