package com.supplierhub.catalog.application;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.util.retry.Retry;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.common.SupplierCatalogClient;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;

@Service
public class CatalogSynchronizationService {

	private static final Logger log = LoggerFactory.getLogger(
		CatalogSynchronizationService.class
	);

	private final List<SupplierCatalogClient> clients;
	private final CatalogSnapshotStore snapshotStore;
	private final int maxRetries;
	private final Duration retryBackoff;

	public CatalogSynchronizationService(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore snapshotStore,
		SupplierIntegrationProperties properties
	) {
		this.clients = clients.stream()
			.sorted(Comparator.comparing(SupplierCatalogClient::supplier))
			.toList();
		this.snapshotStore = snapshotStore;
		this.maxRetries = properties.catalog().maxRetries();
		this.retryBackoff = properties.catalog().retryBackoff();
	}

	public void synchronizeAll() {
		for (SupplierCatalogClient client : clients) {
			synchronize(client);
		}
	}

	private void synchronize(SupplierCatalogClient client) {
		try {
			CatalogSnapshot snapshot = client.fetchCatalog()
				.retryWhen(Retry.backoff(maxRetries, retryBackoff)
					.filter(this::isRetryable)
					.onRetryExhaustedThrow((spec, signal) -> signal.failure()))
				.block();

			if (snapshot == null) {
				throw new SupplierIntegrationException(
					client.supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier catalog completed without a snapshot"
				);
			}
			snapshotStore.replace(snapshot);
			log.info(
				"Supplier catalog synchronization succeeded: supplier={}, properties={}",
				client.supplier(),
				snapshot.properties().size()
			);
		} catch (SupplierIntegrationException exception) {
			log.warn(
				"Supplier catalog synchronization failed: supplier={}, failureType={}",
				client.supplier(),
				exception.getFailureType(),
				exception
			);
		} catch (CatalogSnapshotRejectedException exception) {
			log.warn(
				"Supplier catalog synchronization failed: supplier={}, failureType={}",
				client.supplier(),
				SupplierFailureType.INVALID_RESPONSE,
				exception
			);
		} catch (RuntimeException exception) {
			log.warn(
				"Supplier catalog synchronization failed: supplier={}, failureType={}",
				client.supplier(),
				SupplierFailureType.UNKNOWN,
				exception
			);
		}
	}

	private boolean isRetryable(Throwable throwable) {
		return throwable instanceof SupplierIntegrationException exception
			&& exception.isRetryable();
	}

}
