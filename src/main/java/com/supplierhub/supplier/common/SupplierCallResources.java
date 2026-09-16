package com.supplierhub.supplier.common;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import jakarta.annotation.PreDestroy;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;

import org.springframework.stereotype.Component;

import com.supplierhub.catalog.domain.Supplier;

@Component
public class SupplierCallResources implements AutoCloseable {

	private final Map<Supplier, Map<SupplierOperation, Resources>> resources;
	private final SupplierMetrics metrics;

	public SupplierCallResources(
		SupplierResourceProperties properties,
		SupplierMetrics metrics,
		MeterRegistry registry
	) {
		this.metrics = metrics;
		resources = new EnumMap<>(Supplier.class);
		for (Supplier supplier : Supplier.values()) {
			Map<SupplierOperation, Resources> operations = new EnumMap<>(SupplierOperation.class);
			for (SupplierOperation operation : SupplierOperation.values()) {
				var limits = properties.forOperation(operation);
				Semaphore permits = new Semaphore(limits.maxConcurrentCalls());
				ConnectionProvider pool = ConnectionProvider.builder(
					"supplier-" + supplier.name() + "-" + operation.name()
				)
					.maxConnections(limits.maxConcurrentCalls())
					.pendingAcquireMaxCount(limits.pendingAcquireMaxCount())
					.pendingAcquireTimeout(limits.pendingAcquireTimeout())
					.metrics(true)
					.build();
				operations.put(operation, new Resources(permits, pool, limits));
				registry.gauge(
					"supplier.calls.active", metrics.tags(supplier, operation),
					permits, value -> limits.maxConcurrentCalls() - value.availablePermits()
				);
			}
			resources.put(supplier, operations);
		}
	}

	public ConnectionProvider pool(Supplier supplier, SupplierOperation operation) {
		return resource(supplier, operation).pool();
	}

	public int maxResponseBytes(Supplier supplier, SupplierOperation operation) {
		return resource(supplier, operation).limits().maxResponseBytes();
	}

	public <T> Mono<T> execute(
		Supplier supplier,
		SupplierOperation operation,
		java.util.function.Supplier<Mono<T>> action
	) {
		Resources resource = resource(supplier, operation);
		Mono<T> guarded = Mono.using(
			() -> {
				// 이벤트 루프를 기다리게 하지 않고 구독 시점에 즉시 수용 또는 거부한다.
				if (!resource.permits().tryAcquire()) {
					throw new SupplierIntegrationException(
						supplier, SupplierFailureType.CAPACITY_EXCEEDED, false,
						"Supplier local call capacity exceeded"
					);
				}
				return resource.permits();
			},
			permit -> Mono.defer(action),
			Semaphore::release,
			true
		);
		return metrics.observeCall(supplier, operation, guarded);
	}

	private Resources resource(Supplier supplier, SupplierOperation operation) {
		return resources.get(supplier).get(operation);
	}

	@Override
	@PreDestroy
	public void close() {
		for (Map<SupplierOperation, Resources> operations : resources.values()) {
			operations.values().forEach(resource -> resource.pool().dispose());
		}
	}

	private record Resources(
		Semaphore permits,
		ConnectionProvider pool,
		SupplierResourceProperties.Limits limits
	) {
	}

}
