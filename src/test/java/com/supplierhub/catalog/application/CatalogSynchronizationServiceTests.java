package com.supplierhub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import reactor.core.publisher.Mono;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.common.SupplierResourceFixture;
import com.supplierhub.supplier.common.SupplierCatalogClient;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;

@ExtendWith(OutputCaptureExtension.class)
class CatalogSynchronizationServiceTests {

	private final SupplierResourceFixture resourceFixture =
		new SupplierResourceFixture();

	@AfterEach
	void closeCallResources() {
		resourceFixture.close();
	}

	@Test
	void retriesTransientFailureAndStoresSuccessfulSnapshot() {
		AtomicInteger attempts = new AtomicInteger();
		CatalogSnapshot snapshot = emptySnapshot(Supplier.SUPPLIER_A);
		SupplierCatalogClient client = client(
			Supplier.SUPPLIER_A,
			Mono.defer(() -> attempts.incrementAndGet() < 3
				? Mono.error(failure(Supplier.SUPPLIER_A, true))
				: Mono.just(snapshot))
		);
		RecordingSnapshotStore store = new RecordingSnapshotStore();
		CatalogSynchronizationService service = service(List.of(client), store);

		service.synchronizeAll();

		assertThat(attempts).hasValue(3);
		assertThat(store.snapshots).containsExactly(snapshot);
	}

	@Test
	void doesNotRetryPermanentFailureAndContinuesWithOtherSupplier() {
		AtomicInteger failedAttempts = new AtomicInteger();
		CatalogSnapshot successfulSnapshot = emptySnapshot(Supplier.SUPPLIER_B);
		SupplierCatalogClient failedClient = client(
			Supplier.SUPPLIER_A,
			Mono.defer(() -> {
				failedAttempts.incrementAndGet();
				return Mono.error(failure(Supplier.SUPPLIER_A, false));
			})
		);
		SupplierCatalogClient successfulClient = client(
			Supplier.SUPPLIER_B,
			Mono.just(successfulSnapshot)
		);
		RecordingSnapshotStore store = new RecordingSnapshotStore();
		CatalogSynchronizationService service = service(
			List.of(successfulClient, failedClient),
			store
		);

		service.synchronizeAll();

		assertThat(failedAttempts).hasValue(1);
		assertThat(store.snapshots).containsExactly(successfulSnapshot);
	}

	@Test
	void logsUnexpectedFailureWithItsStackTrace(CapturedOutput output) {
		CatalogSnapshot snapshot = emptySnapshot(Supplier.SUPPLIER_A);
		SupplierCatalogClient client = client(
			Supplier.SUPPLIER_A,
			Mono.just(snapshot)
		);
		CatalogSnapshotStore failingStore = ignored -> {
			throw new IllegalStateException("database write failed");
		};
		CatalogSynchronizationService service = service(
			List.of(client),
			failingStore
		);

		service.synchronizeAll();

		assertThat(output)
			.contains("failureType=INTERNAL_ERROR")
			.contains("IllegalStateException: database write failed");
	}

	@Test
	void timesOutEachCatalogAttemptAndRetriesIt(CapturedOutput output) {
		AtomicInteger attempts = new AtomicInteger();
		SupplierCatalogClient client = client(
			Supplier.SUPPLIER_A,
			Mono.defer(() -> {
				attempts.incrementAndGet();
				return Mono.never();
			})
		);
		RecordingSnapshotStore store = new RecordingSnapshotStore();
		CatalogSynchronizationService service = service(
			List.of(client),
			store,
			Duration.ofMillis(20)
		);

		service.synchronizeAll();

		assertThat(attempts).hasValue(3);
		assertThat(store.snapshots).isEmpty();
		assertThat(output).contains("failureType=TIMEOUT");
	}

	@Test
	void doesNotSynchronizeDisabledSupplier() {
		AtomicInteger attempts = new AtomicInteger();
		SupplierCatalogClient client = client(
			Supplier.SUPPLIER_A,
			Mono.defer(() -> {
				attempts.incrementAndGet();
				return Mono.just(emptySnapshot(Supplier.SUPPLIER_A));
			})
		);
		RecordingSnapshotStore store = new RecordingSnapshotStore();
		CatalogSynchronizationService service = service(
			List.of(client),
			store,
			Duration.ofSeconds(2),
			false,
			false
		);

		service.synchronizeAll();

		assertThat(attempts).hasValue(0);
		assertThat(store.snapshots).isEmpty();
	}

	@Test
	void rejectsDuplicateClientsAndMissingEnabledClientsAtStartup() {
		var client = client(Supplier.SUPPLIER_A, Mono.just(emptySnapshot(Supplier.SUPPLIER_A)));
		var store = new RecordingSnapshotStore();
		assertThatThrownBy(() -> service(List.of(client, client), store))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most once");
		assertThatThrownBy(() -> service(List.of(client), store, Duration.ofSeconds(2), true, true))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SUPPLIER_B");
		assertThat(store.snapshots).isEmpty();
	}

	@Test
	void rejectsForeignSnapshotAndContinuesWithHealthySupplier(CapturedOutput output) {
		var wrong = client(Supplier.SUPPLIER_A, Mono.just(emptySnapshot(Supplier.SUPPLIER_B)));
		var healthy = emptySnapshot(Supplier.SUPPLIER_B);
		var store = new RecordingSnapshotStore();
		service(List.of(wrong, client(Supplier.SUPPLIER_B, Mono.just(healthy))), store).synchronizeAll();
		assertThat(store.snapshots).containsExactly(healthy);
		assertThat(output).contains("failureType=INTERNAL_ERROR");
	}

	@Test
	void rejectsEmptyCatalogPublisherWithoutSaving(CapturedOutput output) {
		var store = new RecordingSnapshotStore();
		service(List.of(client(Supplier.SUPPLIER_A, Mono.empty())), store).synchronizeAll();
		assertThat(store.snapshots).isEmpty();
		assertThat(output).contains("failureType=INVALID_RESPONSE");
	}

	@Test
	void retriesRetryableFailureThrownBeforePublisherCreation() {
		AtomicInteger attempts = new AtomicInteger();
		SupplierCatalogClient client = new SupplierCatalogClient() {
			public Supplier supplier() { return Supplier.SUPPLIER_A; }
			public Mono<CatalogSnapshot> fetchCatalog() {
				if (attempts.incrementAndGet() < 3) {
					throw failure(supplier(), true);
				}
				return Mono.just(emptySnapshot(supplier()));
			}
		};
		var store = new RecordingSnapshotStore();
		service(List.of(client), store).synchronizeAll();
		assertThat(attempts).hasValue(3);
		assertThat(store.snapshots).hasSize(1);
	}

	@Test
	void updatesFreshnessOnlyAfterSuccessfulStoreAndRetainsItOnFailure() {
		var snapshot = emptySnapshot(Supplier.SUPPLIER_A);
		var source = client(Supplier.SUPPLIER_A, Mono.just(snapshot));
		var registry = resourceFixture.registry;
		var lastSuccess = registry.get("supplier.catalog.last.success")
			.tag("supplier", "SUPPLIER_A").gauge();
		var failures = registry.get("supplier.catalog.consecutive.failures")
			.tag("supplier", "SUPPLIER_A").gauge();
		CatalogSnapshotStore failingStore = ignored -> {
			throw new CatalogSnapshotRejectedException("rejected snapshot");
		};
		service(List.of(source), failingStore).synchronizeAll();
		assertThat(lastSuccess.value()).isZero();
		assertThat(failures.value()).isEqualTo(1);
		service(List.of(source), new RecordingSnapshotStore()).synchronizeAll();
		double successfulTime = lastSuccess.value();
		assertThat(successfulTime).isPositive();
		assertThat(failures.value()).isZero();
		service(List.of(source), failingStore).synchronizeAll();
		assertThat(lastSuccess.value()).isEqualTo(successfulTime);
		assertThat(failures.value()).isEqualTo(1);
	}

	private CatalogSynchronizationService service(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore store
	) {
		return service(clients, store, Duration.ofSeconds(2));
	}

	private CatalogSynchronizationService service(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore store,
		Duration callTimeout
	) {
		return service(clients, store, callTimeout,
			clients.stream().anyMatch(client -> client.supplier() == Supplier.SUPPLIER_A),
			clients.stream().anyMatch(client -> client.supplier() == Supplier.SUPPLIER_B));
	}

	private CatalogSynchronizationService service(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore store,
		Duration callTimeout,
		boolean supplierAEnabled,
		boolean supplierBEnabled
	) {
		SupplierIntegrationProperties properties = new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(
				supplierAEnabled,
				URI.create("http://localhost"),
				"key"
			),
			new SupplierIntegrationProperties.Endpoint(
				supplierBEnabled,
				URI.create("http://localhost"),
				"key"
			),
			new SupplierIntegrationProperties.Catalog(
				true,
				Duration.ofMillis(100),
				Duration.ofSeconds(1),
				callTimeout,
				2,
				Duration.ofMillis(1),
				Duration.ZERO,
				Duration.ofMinutes(10),
				10,
				0.5
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(100),
				Duration.ofSeconds(1),
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				4
			)
		);
		return new CatalogSynchronizationService(clients, store, properties, resourceFixture.resources, resourceFixture.metrics);
	}

	private SupplierCatalogClient client(
		Supplier supplier,
		Mono<CatalogSnapshot> result
	) {
		return new SupplierCatalogClient() {
			@Override
			public Supplier supplier() {
				return supplier;
			}

			@Override
			public Mono<CatalogSnapshot> fetchCatalog() {
				return result;
			}
		};
	}

	private CatalogSnapshot emptySnapshot(Supplier supplier) {
		return new CatalogSnapshot(supplier, List.of());
	}

	private SupplierIntegrationException failure(
		Supplier supplier,
		boolean retryable
	) {
		return new SupplierIntegrationException(
			supplier,
			SupplierFailureType.UNAVAILABLE,
			retryable,
			"test failure"
		);
	}

	private static final class RecordingSnapshotStore
		implements CatalogSnapshotStore {

		private final List<CatalogSnapshot> snapshots = new ArrayList<>();

		@Override
		public CatalogSnapshotUpdate replace(CatalogSnapshot snapshot) {
			snapshots.add(snapshot);
			return new CatalogSnapshotUpdate(
				snapshot.properties().size(),
				0,
				0,
				0,
				0,
				0,
				0,
				0,
				0,
				0
			);
		}

	}

}
