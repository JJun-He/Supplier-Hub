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
import com.supplierhub.supplier.common.SupplierCallResources;
import com.supplierhub.supplier.common.SupplierMetrics;
import com.supplierhub.supplier.common.SupplierOperation;
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
	private final SupplierCallResources resources;
	private final SupplierMetrics metrics;

	public CatalogSynchronizationService(
		List<SupplierCatalogClient> clients,
		CatalogSnapshotStore snapshotStore,
		SupplierIntegrationProperties properties,
		SupplierCallResources resources,
		SupplierMetrics metrics
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
		this.resources = resources;
		this.metrics = metrics;
	}

	public void synchronizeAll() {
		for (SupplierCatalogClient client : clients) {
			synchronize(client);
		}
	}

	private void synchronize(SupplierCatalogClient client) {
		long started = System.nanoTime();
		SupplierFailureType failure = SupplierFailureType.INTERNAL_ERROR;
		try {
			CatalogSnapshot snapshot = resources.execute(
				client.supplier(), SupplierOperation.CATALOG, () -> Mono.defer(client::fetchCatalog)
				.switchIfEmpty(Mono.error(new SupplierIntegrationException(
					client.supplier(), SupplierFailureType.INVALID_RESPONSE, false,
					"Supplier catalog completed without a snapshot"
				)))
				.doOnNext(value -> {
					if (value.supplier() != client.supplier()) {
						throw new IllegalStateException("Catalog snapshot supplier must match the client supplier");
					}
				})
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
				)
				.retryWhen(Retry.backoff(maxRetries, retryBackoff)
					.filter(this::isRetryable)
					.onRetryExhaustedThrow((spec, signal) -> signal.failure()))
				.block();

			CatalogSnapshotUpdate update = snapshotStore.replace(snapshot);
			failure = null;
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
			failure = exception.getFailureType();
			log.warn(
				"Supplier catalog synchronization failed: supplier={}, failureType={}",
				client.supplier(),
				exception.getFailureType(),
				exception
			);
		} catch (CatalogSnapshotRejectedException exception) {
			failure = SupplierFailureType.INVALID_RESPONSE;
			log.warn(
				"Supplier catalog synchronization failed: supplier={}, failureType={}",
				client.supplier(),
				SupplierFailureType.INVALID_RESPONSE,
				exception
			);
		} catch (RuntimeException exception) {
			failure = SupplierFailureType.INTERNAL_ERROR;
			log.error(
				"Supplier catalog synchronization failed: supplier={}, failureType={}",
				client.supplier(),
				SupplierFailureType.INTERNAL_ERROR,
				exception
			);
		} finally {
			metrics.catalogCompleted(client.supplier(), failure, started);
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
