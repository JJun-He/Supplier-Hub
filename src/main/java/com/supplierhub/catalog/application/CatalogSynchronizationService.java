package com.supplierhub.catalog.application;

import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import reactor.core.publisher.Mono;

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
import com.supplierhub.supplier.common.SupplierTransportFailureMapper;

@Service
public class CatalogSynchronizationService {

	private static final Logger log = LoggerFactory.getLogger(
		CatalogSynchronizationService.class
	);

	private final List<SupplierCatalogClient> clients;
	private final CatalogSnapshotStore snapshotStore;
	private final Duration callTimeout;
	private final int maxRetries;
	private final Duration retryBackoff;

	public CatalogSynchronizationService(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore snapshotStore,
		SupplierIntegrationProperties properties
	) {
		requireClientContracts(clients, properties);
		this.clients = clients.stream()
			.filter(client -> properties.isEnabled(client.supplier()))
			.sorted(Comparator.comparing(SupplierCatalogClient::supplier))
			.toList();
		this.snapshotStore = snapshotStore;
		this.callTimeout = properties.catalog().callTimeout();
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
			CatalogSnapshot snapshot = Mono.defer(client::fetchCatalog)
				.timeout(callTimeout)
				.onErrorMap(
					cause -> !(cause instanceof SupplierIntegrationException)
						&& SupplierTransportFailureMapper.isTimeout(cause),
					cause -> SupplierTransportFailureMapper.timeoutFailure(
						client.supplier(),
						"Supplier catalog request",
						cause
					)
				)
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
			if (snapshot.supplier() != client.supplier()) {
				throw new IllegalStateException("Catalog snapshot supplier must match the client supplier");
			}
			CatalogSnapshotUpdate update = snapshotStore.replace(snapshot);
			log.info(
				"Supplier catalog synchronization succeeded: supplier={}, properties={}, roomTypes={}, createdProperties={}, createdRoomTypes={}, reactivatedProperties={}, reactivatedRoomTypes={}, suspectedMissingProperties={}, suspectedMissingRoomTypes={}, deactivatedProperties={}, deactivatedRoomTypes={}",
				client.supplier(),
				update.propertyCount(),
				update.roomTypeCount(),
				update.createdProperties(),
				update.createdRoomTypes(),
				update.reactivatedProperties(),
				update.reactivatedRoomTypes(),
				update.suspectedMissingProperties(),
				update.suspectedMissingRoomTypes(),
				update.deactivatedProperties(),
				update.deactivatedRoomTypes()
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
			log.error(
				"Supplier catalog synchronization failed: supplier={}, failureType={}",
				client.supplier(),
				SupplierFailureType.INTERNAL_ERROR,
				exception
			);
		}
	}

	private void requireClientContracts(
		List<SupplierCatalogClient> clients, SupplierIntegrationProperties properties
	) {
		Objects.requireNonNull(clients, "clients must not be null");
		Set<Supplier> registered = EnumSet.noneOf(Supplier.class);
		for (SupplierCatalogClient client : clients) {
			Objects.requireNonNull(client, "catalog client must not be null");
			Supplier supplier = Objects.requireNonNull(client.supplier(), "client supplier must not be null");
			if (!registered.add(supplier)) {
				throw new IllegalArgumentException("Catalog clients must contain each supplier at most once");
			}
		}
		Set<Supplier> missing = EnumSet.noneOf(Supplier.class);
		for (Supplier supplier : Supplier.values()) {
			if (properties.isEnabled(supplier) && !registered.contains(supplier)) {
				missing.add(supplier);
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Enabled Suppliers have no catalog client: " + missing);
		}
	}

	private boolean isRetryable(Throwable throwable) {
		return throwable instanceof SupplierIntegrationException exception
			&& exception.isRetryable();
	}

}
