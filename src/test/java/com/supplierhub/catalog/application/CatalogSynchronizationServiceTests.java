package com.supplierhub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import reactor.core.publisher.Mono;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.common.SupplierCatalogClient;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;

@ExtendWith(OutputCaptureExtension.class)
class CatalogSynchronizationServiceTests {

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
			.contains("failureType=UNKNOWN")
			.contains("IllegalStateException: database write failed");
	}

	private CatalogSynchronizationService service(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore store
	) {
		SupplierIntegrationProperties properties = new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(
				URI.create("http://localhost"),
				"key"
			),
			new SupplierIntegrationProperties.Endpoint(
				URI.create("http://localhost"),
				"key"
			),
			new SupplierIntegrationProperties.Catalog(
				true,
				Duration.ofMillis(100),
				Duration.ofSeconds(1),
				2,
				Duration.ofMillis(1),
				Duration.ofMinutes(10)
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(100),
				Duration.ofSeconds(1),
				Duration.ofSeconds(2)
			)
		);
		return new CatalogSynchronizationService(clients, store, properties);
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
		public void replace(CatalogSnapshot snapshot) {
			snapshots.add(snapshot);
		}

	}

}
