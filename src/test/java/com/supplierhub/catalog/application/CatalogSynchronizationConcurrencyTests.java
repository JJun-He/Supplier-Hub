package com.supplierhub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.common.SupplierCatalogClient;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;
import com.supplierhub.supplier.common.SupplierResourceFixture;

class CatalogSynchronizationConcurrencyTests {

	private final SupplierResourceFixture fixture = new SupplierResourceFixture();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	@AfterEach
	void closeResources() throws InterruptedException {
		executor.shutdownNow();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		fixture.close();
	}

	@Test
	void skipsConcurrentFetchWithoutChangingPreviousFreshnessOrFailures() throws Exception {
		CatalogSnapshot snapshot = snapshot(Supplier.SUPPLIER_A);
		AtomicInteger fetches = new AtomicInteger();
		Sinks.One<CatalogSnapshot> pending = Sinks.one();
		CountDownLatch fetching = new CountDownLatch(1);
		SupplierCatalogClient client = client(Supplier.SUPPLIER_A, () -> {
			int attempt = fetches.incrementAndGet();
			if (attempt == 2) {
				return Mono.error(failure(false));
			}
			if (attempt == 3) {
				return pending.asMono().doOnSubscribe(ignored -> fetching.countDown());
			}
			return Mono.just(snapshot);
		});
		List<CatalogSnapshot> saved = new CopyOnWriteArrayList<>();
		CatalogSynchronizationService service = service(List.of(client), value -> {
			saved.add(value);
			return emptyUpdate();
		});
		service.synchronizeAll();
		double previousSuccess = lastSuccess(Supplier.SUPPLIER_A);
		service.synchronizeAll();
		assertThat(previousSuccess).isPositive();
		assertThat(failures(Supplier.SUPPLIER_A)).isEqualTo(1);

		var running = executor.submit(service::synchronizeAll);
		try {
			assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(activeCalls(Supplier.SUPPLIER_A)).isEqualTo(1);

			service.synchronizeAll();

			assertThat(fetches).hasValue(3);
			assertThat(saved).containsExactly(snapshot);
			assertThat(skipped(Supplier.SUPPLIER_A)).isEqualTo(1);
			assertThat(lastSuccess(Supplier.SUPPLIER_A)).isEqualTo(previousSuccess);
			assertThat(failures(Supplier.SUPPLIER_A)).isEqualTo(1);
			assertThat(activeCalls(Supplier.SUPPLIER_A)).isEqualTo(1);
		} finally {
			pending.tryEmitValue(snapshot);
		}
		running.get(5, TimeUnit.SECONDS);
		assertThat(saved).containsExactly(snapshot, snapshot);
		assertThat(failures(Supplier.SUPPLIER_A)).isZero();
		assertThat(activeCalls(Supplier.SUPPLIER_A)).isZero();

		service.synchronizeAll();
		assertThat(fetches).hasValue(4);
		assertThat(saved).hasSize(3);
	}

	@Test
	void protectsBlockedStoreAfterHttpPermitReturnAndContinuesOtherSupplier() throws Exception {
		AtomicInteger aFetches = new AtomicInteger();
		AtomicInteger bFetches = new AtomicInteger();
		AtomicInteger aWrites = new AtomicInteger();
		CountDownLatch storingA = new CountDownLatch(1);
		CountDownLatch releaseStore = new CountDownLatch(1);
		List<CatalogSnapshot> saved = new CopyOnWriteArrayList<>();
		SupplierCatalogClient a = client(Supplier.SUPPLIER_A, () -> {
			aFetches.incrementAndGet();
			return Mono.just(snapshot(Supplier.SUPPLIER_A));
		});
		SupplierCatalogClient b = client(Supplier.SUPPLIER_B, () -> {
			bFetches.incrementAndGet();
			return Mono.just(snapshot(Supplier.SUPPLIER_B));
		});
		CatalogSynchronizationService service = service(List.of(a, b), value -> {
			if (value.supplier() == Supplier.SUPPLIER_A && aWrites.incrementAndGet() == 1) {
				storingA.countDown();
				waitFor(releaseStore);
			}
			saved.add(value);
			return emptyUpdate();
		});

		var running = executor.submit(service::synchronizeAll);
		try {
			assertThat(storingA.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(activeCalls(Supplier.SUPPLIER_A)).isZero();

			service.synchronizeAll();

			assertThat(aFetches).hasValue(1);
			assertThat(aWrites).hasValue(1);
			assertThat(bFetches).hasValue(1);
			assertThat(saved).containsExactly(snapshot(Supplier.SUPPLIER_B));
			assertThat(skipped(Supplier.SUPPLIER_A)).isEqualTo(1);
			assertThat(lastSuccess(Supplier.SUPPLIER_A)).isZero();
			assertThat(failures(Supplier.SUPPLIER_A)).isZero();
			assertThat(lastSuccess(Supplier.SUPPLIER_B)).isPositive();
		} finally {
			releaseStore.countDown();
		}
		running.get(5, TimeUnit.SECONDS);
		assertThat(saved).containsExactly(
			snapshot(Supplier.SUPPLIER_B),
			snapshot(Supplier.SUPPLIER_A),
			snapshot(Supplier.SUPPLIER_B)
		);
		assertThat(lastSuccess(Supplier.SUPPLIER_A)).isPositive();

		service.synchronizeAll();
		assertThat(aFetches).hasValue(2);
		assertThat(aWrites).hasValue(2);
		assertThat(activeCalls(Supplier.SUPPLIER_A)).isZero();
	}

	@Test
	void releasesSynchronizationGuardAfterSynchronousFetchAndStoreFailures() {
		AtomicInteger fetches = new AtomicInteger();
		AtomicInteger writes = new AtomicInteger();
		SupplierCatalogClient client = client(Supplier.SUPPLIER_A, () -> {
			if (fetches.incrementAndGet() == 1) {
				throw new IllegalStateException("synchronous fetch failure");
			}
			return Mono.just(snapshot(Supplier.SUPPLIER_A));
		});
		CatalogSynchronizationService service = service(List.of(client), value -> {
			if (writes.incrementAndGet() == 1) {
				throw new CatalogSnapshotRejectedException("rejected snapshot");
			}
			return emptyUpdate();
		});

		service.synchronizeAll();
		assertThat(fetches).hasValue(1);
		assertThat(writes).hasValue(0);
		assertThat(failures(Supplier.SUPPLIER_A)).isEqualTo(1);
		assertThat(lastSuccess(Supplier.SUPPLIER_A)).isZero();
		assertThat(activeCalls(Supplier.SUPPLIER_A)).isZero();

		service.synchronizeAll();
		assertThat(fetches).hasValue(2);
		assertThat(writes).hasValue(1);
		assertThat(failures(Supplier.SUPPLIER_A)).isEqualTo(2);
		assertThat(lastSuccess(Supplier.SUPPLIER_A)).isZero();
		assertThat(activeCalls(Supplier.SUPPLIER_A)).isZero();

		service.synchronizeAll();
		assertThat(fetches).hasValue(3);
		assertThat(writes).hasValue(2);
		assertThat(failures(Supplier.SUPPLIER_A)).isZero();
		assertThat(lastSuccess(Supplier.SUPPLIER_A)).isPositive();
		assertThat(activeCalls(Supplier.SUPPLIER_A)).isZero();
		assertThat(skipped(Supplier.SUPPLIER_A)).isZero();
	}

	@Test
	void keepsSynchronizationGuardWhileRetryBackoffReleasesHttpPermit() throws Exception {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		VirtualTimeScheduler.set(scheduler);
		try {
			AtomicInteger fetches = new AtomicInteger();
			List<CatalogSnapshot> saved = new CopyOnWriteArrayList<>();
			SupplierCatalogClient client = client(Supplier.SUPPLIER_A, () ->
				fetches.incrementAndGet() == 1
					? Mono.error(failure(true))
					: Mono.just(snapshot(Supplier.SUPPLIER_A))
			);
			CatalogSynchronizationService service = service(List.of(client), value -> {
				saved.add(value);
				return emptyUpdate();
			}, Duration.ofHours(1));
			var running = executor.submit(service::synchronizeAll);
			try {
				// The first timeout task and retry delay have both been scheduled.
				// Virtual time stays still, so the retry cannot race this assertion.
				await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
					assertThat(scheduler.getScheduledTaskCount()).isGreaterThanOrEqualTo(2);
					assertThat(fetches).hasValue(1);
					assertThat(activeCalls(Supplier.SUPPLIER_A)).isZero();
					assertThat(fixture.registry.find("supplier.calls")
						.tags("supplier", "SUPPLIER_A", "operation", "CATALOG", "outcome", "FAILED")
						.timer()).isNotNull();
				});

				service.synchronizeAll();

				assertThat(fetches).hasValue(1);
				assertThat(saved).isEmpty();
				assertThat(skipped(Supplier.SUPPLIER_A)).isEqualTo(1);
				assertThat(lastSuccess(Supplier.SUPPLIER_A)).isZero();
				assertThat(failures(Supplier.SUPPLIER_A)).isZero();

				// Covers the default retry jitter without any wall-clock delay.
				scheduler.advanceTimeBy(Duration.ofHours(2));
				running.get(5, TimeUnit.SECONDS);
				assertThat(fetches).hasValue(2);
				assertThat(saved).containsExactly(snapshot(Supplier.SUPPLIER_A));
				assertThat(lastSuccess(Supplier.SUPPLIER_A)).isPositive();
				assertThat(activeCalls(Supplier.SUPPLIER_A)).isZero();

				service.synchronizeAll();
				assertThat(fetches).hasValue(3);
				assertThat(saved).hasSize(2);
			} finally {
				running.cancel(true);
			}
		} finally {
			VirtualTimeScheduler.reset();
			scheduler.dispose();
		}
	}

	private CatalogSynchronizationService service(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore store
	) {
		return service(clients, store, Duration.ofMillis(1));
	}

	private CatalogSynchronizationService service(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore store,
		Duration retryBackoff
	) {
		var properties = new SupplierIntegrationProperties(
			endpoint(clients, Supplier.SUPPLIER_A),
			endpoint(clients, Supplier.SUPPLIER_B),
			new SupplierIntegrationProperties.Catalog(
				true, Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(30),
				1, retryBackoff, Duration.ZERO, Duration.ofMinutes(10), 10, 0.5
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(2),
				Duration.ofSeconds(5), 4
			)
		);
		return new CatalogSynchronizationService(
			clients, store, properties, fixture.resources, fixture.metrics
		);
	}

	private SupplierIntegrationProperties.Endpoint endpoint(
		List<SupplierCatalogClient> clients,
		Supplier supplier
	) {
		return new SupplierIntegrationProperties.Endpoint(
			clients.stream().anyMatch(client -> client.supplier() == supplier),
			URI.create("http://localhost"), "key"
		);
	}

	private SupplierCatalogClient client(
		Supplier supplier,
		java.util.function.Supplier<Mono<CatalogSnapshot>> fetch
	) {
		return new SupplierCatalogClient() {
			@Override
			public Supplier supplier() {
				return supplier;
			}

			@Override
			public Mono<CatalogSnapshot> fetchCatalog() {
				return fetch.get();
			}
		};
	}

	private CatalogSnapshot snapshot(Supplier supplier) {
		return new CatalogSnapshot(supplier, List.of());
	}

	private SupplierIntegrationException failure(boolean retryable) {
		return new SupplierIntegrationException(
			Supplier.SUPPLIER_A, SupplierFailureType.UNAVAILABLE, retryable, "catalog unavailable"
		);
	}

	private CatalogSnapshotUpdate emptyUpdate() {
		return new CatalogSnapshotUpdate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}

	private double activeCalls(Supplier supplier) {
		return fixture.registry.get("supplier.calls.active")
			.tags("supplier", supplier.name(), "operation", "CATALOG").gauge().value();
	}

	private double lastSuccess(Supplier supplier) {
		return fixture.registry.get("supplier.catalog.last.success")
			.tag("supplier", supplier.name()).gauge().value();
	}

	private double failures(Supplier supplier) {
		return fixture.registry.get("supplier.catalog.consecutive.failures")
			.tag("supplier", supplier.name()).gauge().value();
	}

	private double skipped(Supplier supplier) {
		var counter = fixture.registry.find("supplier.catalog.skipped")
			.tag("supplier", supplier.name()).counter();
		return counter == null ? 0 : counter.count();
	}

	private void waitFor(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("test store interrupted", exception);
		}
	}
}
