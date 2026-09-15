package com.supplierhub.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.application.ActiveCatalogMappingReader;
import com.supplierhub.search.domain.DailyInventory;
import com.supplierhub.search.domain.Money;
import com.supplierhub.search.domain.Offer;
import com.supplierhub.search.domain.Price;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;
import com.supplierhub.supplier.common.SupplierSearchClient;
import com.supplierhub.supplier.common.SupplierSearchRequest;
import com.supplierhub.supplier.common.SupplierSearchResult;

class IntegratedSearchServiceTests {

	private static final SearchCriteria CRITERIA = new SearchCriteria(
		LocalDate.of(2026, 10, 1),
		LocalDate.of(2026, 10, 2),
		2,
		0
	);

	private final ActiveCatalogMappingReader mappingReader = mock(
		ActiveCatalogMappingReader.class
	);

	@Test
	void splitsFiftyOnePropertiesIntoFiftyAndOne() {
		when(mappingReader.findAllActive())
			.thenReturn(mappings(Supplier.SUPPLIER_A, 51, 1));
		List<Integer> batchSizes = new CopyOnWriteArrayList<>();
		SupplierSearchClient client = client(Supplier.SUPPLIER_A, request -> {
			batchSizes.add(request.properties().size());
			return Mono.just(success(request.supplier()));
		});
		IntegratedSearchService service = service(
			List.of(client),
			Duration.ofSeconds(1),
			4
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(batchSizes).containsExactlyInAnyOrder(50, 1);
		assertThat(result.status()).isEqualTo(SearchStatus.COMPLETE);
		assertThat(result.searchOffers()).isEmpty();
		assertThat(result.supplierResults()).singleElement().satisfies(outcome -> {
			assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.SUCCESS);
			assertThat(outcome.failureTypes()).isEmpty();
		});
	}

	@Test
	void startsDifferentSuppliersBeforeEitherOneCompletes() {
		List<ActiveCatalogMapping> rows = new ArrayList<>();
		rows.addAll(mappings(Supplier.SUPPLIER_A, 1, 1));
		rows.addAll(mappings(Supplier.SUPPLIER_B, 1, 100));
		when(mappingReader.findAllActive()).thenReturn(rows);
		AtomicInteger started = new AtomicInteger();
		AtomicInteger completed = new AtomicInteger();
		AtomicBoolean bothStartedBeforeCompletion = new AtomicBoolean();
		Function<SupplierSearchRequest, Mono<SupplierSearchResult>> behavior = request ->
			Mono.defer(() -> {
				if (started.incrementAndGet() == 2 && completed.get() == 0) {
					bothStartedBeforeCompletion.set(true);
				}
				return Mono.delay(Duration.ofMillis(50))
					.map(ignored -> success(request.supplier()))
					.doOnNext(ignored -> completed.incrementAndGet());
			});
		IntegratedSearchService service = service(
			List.of(
				client(Supplier.SUPPLIER_A, behavior),
				client(Supplier.SUPPLIER_B, behavior)
			),
			Duration.ofSeconds(1),
			4
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(bothStartedBeforeCompletion).isTrue();
		assertThat(result.status()).isEqualTo(SearchStatus.COMPLETE);
	}

	@Test
	void limitsConcurrentCallsInsideOneSupplier() {
		when(mappingReader.findAllActive())
			.thenReturn(mappings(Supplier.SUPPLIER_A, 201, 1));
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger maximumInFlight = new AtomicInteger();
		AtomicInteger calls = new AtomicInteger();
		SupplierSearchClient client = client(
			Supplier.SUPPLIER_A,
			request -> Mono.defer(() -> {
				calls.incrementAndGet();
				int current = inFlight.incrementAndGet();
				maximumInFlight.accumulateAndGet(current, Math::max);
				return Mono.delay(Duration.ofMillis(30))
					.map(ignored -> success(request.supplier()))
					.doOnNext(ignored -> inFlight.decrementAndGet());
			})
		);
		IntegratedSearchService service = service(
			List.of(client),
			Duration.ofSeconds(1),
			4
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(calls).hasValue(5);
		assertThat(maximumInFlight).hasValue(4);
		assertThat(result.status()).isEqualTo(SearchStatus.COMPLETE);
	}

	@Test
	void preservesCompletedOffersWhenAnotherBatchFails() {
		when(mappingReader.findAllActive())
			.thenReturn(mappings(Supplier.SUPPLIER_A, 51, 1));
		Offer offer = offer(Supplier.SUPPLIER_A, 1, 10);
		SupplierSearchClient client = client(Supplier.SUPPLIER_A, request ->
			request.properties().size() == 50
				? Mono.just(success(request.supplier(), offer))
				: Mono.error(failure(
					request.supplier(),
					SupplierFailureType.UNAVAILABLE
				))
		);
		IntegratedSearchService service = service(
			List.of(client),
			Duration.ofSeconds(1),
			4
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(result.status()).isEqualTo(SearchStatus.PARTIAL);
		assertThat(result.offers()).containsExactly(offer);
		assertThat(result.searchOffers()).singleElement().satisfies(searchOffer -> {
			assertThat(searchOffer.offer()).isEqualTo(offer);
			assertThat(searchOffer.propertyName()).isEqualTo("Property 1");
			assertThat(searchOffer.roomTypeName()).isEqualTo("Room 1");
		});
		assertThat(result.supplierResults()).singleElement().satisfies(outcome -> {
			assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.PARTIAL);
			assertThat(outcome.acceptedOfferCount()).isEqualTo(1);
			assertThat(outcome.failureTypes())
				.containsExactly(SupplierFailureType.UNAVAILABLE);
		});
	}

	@Test
	void cancelsPendingWorkAtOverallTimeoutAndKeepsCompletedBatch() {
		when(mappingReader.findAllActive())
			.thenReturn(mappings(Supplier.SUPPLIER_A, 101, 1));
		Offer offer = offer(Supplier.SUPPLIER_A, 1, 10);
		AtomicInteger started = new AtomicInteger();
		SupplierSearchClient client = client(Supplier.SUPPLIER_A, request -> {
			int call = started.getAndIncrement();
			return call == 0
				? Mono.delay(Duration.ofMillis(10))
					.map(ignored -> success(request.supplier(), offer))
				: Mono.never();
		});
		IntegratedSearchService service = service(
			List.of(client),
			Duration.ofMillis(100),
			1
		);

		long startedAt = System.nanoTime();
		IntegratedSearchResult result = service.search(CRITERIA);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

		assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
		assertThat(started).hasValue(2);
		assertThat(result.status()).isEqualTo(SearchStatus.PARTIAL);
		assertThat(result.offers()).containsExactly(offer);
		assertThat(result.supplierResults()).singleElement().satisfies(outcome -> {
			assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.PARTIAL);
			assertThat(outcome.failureTypes())
				.containsExactly(SupplierFailureType.TIMEOUT);
		});
	}

	@Test
	void reportsCatalogUnavailableWithoutCallingSupplier() {
		when(mappingReader.findAllActive())
			.thenReturn(List.of());
		SupplierSearchClient client = mock(SupplierSearchClient.class);
		when(client.supplier()).thenReturn(Supplier.SUPPLIER_A);
		IntegratedSearchService service = service(
			List.of(client),
			Duration.ofSeconds(1),
			4
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(result.status()).isEqualTo(SearchStatus.FAILED);
		assertThat(result.supplierResults()).singleElement().satisfies(outcome -> {
			assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.FAILED);
			assertThat(outcome.failureTypes())
				.containsExactly(SupplierFailureType.CATALOG_UNAVAILABLE);
		});
		verify(client, never()).search(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void marksRejectedSupplierItemsAsPartialWithoutTreatingSoldOutAsFailure() {
		when(mappingReader.findAllActive())
			.thenReturn(mappings(Supplier.SUPPLIER_A, 1, 1));
		SupplierSearchClient client = client(
			Supplier.SUPPLIER_A,
			request -> Mono.just(new SupplierSearchResult(
				request.supplier(),
				List.of(),
				2,
				3
			))
		);
		IntegratedSearchService service = service(
			List.of(client),
			Duration.ofSeconds(1),
			4
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(result.status()).isEqualTo(SearchStatus.PARTIAL);
		assertThat(result.supplierResults()).singleElement().satisfies(outcome -> {
			assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.PARTIAL);
			assertThat(outcome.rejectedOfferCount()).isEqualTo(2);
			assertThat(outcome.unavailableOfferCount()).isEqualTo(3);
			assertThat(outcome.failureTypes())
				.containsExactly(SupplierFailureType.INVALID_RESPONSE);
		});
	}

	@Test
	void treatsNormalEmptyResultAsSuccessBesideFailedSupplier() {
		List<ActiveCatalogMapping> rows = new ArrayList<>();
		rows.addAll(mappings(Supplier.SUPPLIER_A, 1, 1));
		rows.addAll(mappings(Supplier.SUPPLIER_B, 1, 100));
		when(mappingReader.findAllActive()).thenReturn(rows);
		IntegratedSearchService service = service(
			List.of(
				client(
					Supplier.SUPPLIER_A,
					request -> Mono.just(success(request.supplier()))
				),
				client(
					Supplier.SUPPLIER_B,
					request -> Mono.error(failure(
						request.supplier(),
						SupplierFailureType.TIMEOUT
					))
				)
			),
			Duration.ofSeconds(1),
			4
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(result.status()).isEqualTo(SearchStatus.PARTIAL);
		assertThat(result.offers()).isEmpty();
		assertThat(result.supplierResults())
			.extracting(SupplierSearchOutcome::status)
			.containsExactly(
				SupplierSearchStatus.SUCCESS,
				SupplierSearchStatus.FAILED
			);
	}

	@Test
	void reportsFailedWhenEverySupplierCallFails() {
		List<ActiveCatalogMapping> rows = new ArrayList<>();
		rows.addAll(mappings(Supplier.SUPPLIER_A, 1, 1));
		rows.addAll(mappings(Supplier.SUPPLIER_B, 1, 100));
		when(mappingReader.findAllActive()).thenReturn(rows);
		Function<SupplierSearchRequest, Mono<SupplierSearchResult>> failure = request ->
			Mono.error(failure(
				request.supplier(),
				SupplierFailureType.UNAVAILABLE
			));
		IntegratedSearchService service = service(
			List.of(
				client(Supplier.SUPPLIER_A, failure),
				client(Supplier.SUPPLIER_B, failure)
			),
			Duration.ofSeconds(1),
			4
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(result.status()).isEqualTo(SearchStatus.FAILED);
		assertThat(result.supplierResults())
			.extracting(SupplierSearchOutcome::status)
			.containsOnly(SupplierSearchStatus.FAILED);
	}

	@Test
	void rejectsEnabledSupplierWithoutMatchingClientAtStartup() {
		SupplierSearchClient supplierAClient = client(
			Supplier.SUPPLIER_A,
			request -> Mono.just(success(request.supplier()))
		);

		assertThatThrownBy(() -> new IntegratedSearchService(
			mappingReader,
			List.of(supplierAClient),
			properties(Duration.ofSeconds(1), 4, true, true)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SUPPLIER_B");
	}

	@Test
	void ignoresDisabledSupplierMappingsAndDoesNotReportPartialResult() {
		List<ActiveCatalogMapping> rows = new ArrayList<>();
		rows.addAll(mappings(Supplier.SUPPLIER_A, 1, 1));
		rows.addAll(mappings(Supplier.SUPPLIER_B, 1, 100));
		when(mappingReader.findAllActive()).thenReturn(rows);
		AtomicBoolean supplierBCalled = new AtomicBoolean();
		SupplierSearchClient supplierAClient = client(
			Supplier.SUPPLIER_A,
			request -> Mono.just(success(request.supplier()))
		);
		SupplierSearchClient supplierBClient = client(
			Supplier.SUPPLIER_B,
			request -> {
				supplierBCalled.set(true);
				return Mono.just(success(request.supplier()));
			}
		);
		IntegratedSearchService service = new IntegratedSearchService(
			mappingReader,
			List.of(supplierAClient, supplierBClient),
			properties(Duration.ofSeconds(1), 4, true, false)
		);

		IntegratedSearchResult result = service.search(CRITERIA);

		assertThat(result.status()).isEqualTo(SearchStatus.COMPLETE);
		assertThat(result.supplierResults())
			.extracting(SupplierSearchOutcome::supplier)
			.containsExactly(Supplier.SUPPLIER_A);
		assertThat(supplierBCalled).isFalse();
	}

	private IntegratedSearchService service(
		List<SupplierSearchClient> clients,
		Duration overallTimeout,
		int maxConcurrency
	) {
		boolean supplierAEnabled = clients.stream().anyMatch(
			client -> client.supplier() == Supplier.SUPPLIER_A
		);
		boolean supplierBEnabled = clients.stream().anyMatch(
			client -> client.supplier() == Supplier.SUPPLIER_B
		);
		return new IntegratedSearchService(
			mappingReader,
			clients,
			properties(
				overallTimeout,
				maxConcurrency,
				supplierAEnabled,
				supplierBEnabled
			)
		);
	}

	private SupplierSearchClient client(
		Supplier supplier,
		Function<SupplierSearchRequest, Mono<SupplierSearchResult>> behavior
	) {
		return new SupplierSearchClient() {
			@Override
			public Supplier supplier() {
				return supplier;
			}

			@Override
			public Mono<SupplierSearchResult> search(SupplierSearchRequest request) {
				return behavior.apply(request);
			}
		};
	}

	private SupplierSearchResult success(Supplier supplier, Offer... offers) {
		return new SupplierSearchResult(
			supplier,
			List.of(offers),
			0,
			0
		);
	}

	private SupplierIntegrationException failure(
		Supplier supplier,
		SupplierFailureType failureType
	) {
		return new SupplierIntegrationException(
			supplier,
			failureType,
			false,
			"test failure"
		);
	}

	private Offer offer(Supplier supplier, long propertyId, long roomTypeId) {
		return Offer.createAvailable(
			propertyId,
			roomTypeId,
			supplier,
			2,
			false,
			Price.totalOnly(Money.of("KRW", 100_000)),
			CRITERIA,
			List.of(new DailyInventory(CRITERIA.checkIn(), 1))
		).orElseThrow();
	}

	private List<ActiveCatalogMapping> mappings(
		Supplier supplier,
		int count,
		long firstPropertyId
	) {
		return IntStream.range(0, count)
			.mapToObj(index -> {
				long propertyId = firstPropertyId + index;
				return new ActiveCatalogMapping(
					propertyId,
					supplier,
					"PROPERTY-" + propertyId,
					"Property " + propertyId,
					propertyId * 10,
					"ROOM-1",
					"Room " + propertyId
				);
			})
			.toList();
	}

	private SupplierIntegrationProperties properties(
		Duration overallTimeout,
		int maxConcurrency,
		boolean supplierAEnabled,
		boolean supplierBEnabled
	) {
		return new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(
				supplierAEnabled,
				URI.create("http://localhost"),
				"a-key"
			),
			new SupplierIntegrationProperties.Endpoint(
				supplierBEnabled,
				URI.create("http://localhost"),
				"b-key"
			),
			new SupplierIntegrationProperties.Catalog(
				true,
				Duration.ofMillis(500),
				Duration.ofSeconds(3),
				Duration.ofSeconds(4),
				2,
				Duration.ofMillis(300),
				Duration.ZERO,
				Duration.ofMinutes(10),
				10,
				0.5
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(2),
				Duration.ofSeconds(3),
				overallTimeout,
				maxConcurrency
			)
		);
	}

}
