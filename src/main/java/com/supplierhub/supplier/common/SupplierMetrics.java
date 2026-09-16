package com.supplierhub.supplier.common;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

import com.supplierhub.catalog.domain.Supplier;

@Component
public class SupplierMetrics {

	private final MeterRegistry registry;
	private final Map<Supplier, AtomicLong> catalogLastSuccess = new EnumMap<>(Supplier.class);
	private final Map<Supplier, AtomicLong> catalogConsecutiveFailures = new EnumMap<>(Supplier.class);

	public SupplierMetrics(MeterRegistry registry) {
		this.registry = registry;
		for (Supplier supplier : Supplier.values()) {
			AtomicLong lastSuccess = new AtomicLong();
			AtomicLong failures = new AtomicLong();
			catalogLastSuccess.put(supplier, lastSuccess);
			catalogConsecutiveFailures.put(supplier, failures);
			Tags tags = Tags.of("supplier", supplier.name());
			registry.gauge("supplier.catalog.last.success", tags, lastSuccess);
			registry.gauge("supplier.catalog.consecutive.failures", tags, failures);
		}
	}

	Tags tags(Supplier supplier, SupplierOperation operation) {
		return Tags.of("supplier", supplier.name(), "operation", operation.name());
	}

	<T> Mono<T> observeCall(Supplier supplier, SupplierOperation operation, Mono<T> call) {
		return Mono.defer(() -> {
			long started = System.nanoTime();
			AtomicReference<String> outcome = new AtomicReference<>("CANCELLED");
			AtomicReference<String> failure = new AtomicReference<>("NONE");
			return call
				.doOnSuccess(value -> {
					outcome.set("SUCCESS");
					if (value instanceof SupplierSearchResult result) {
						recordOffers(result);
						if (result.hasRejectedOffers()) {
							outcome.set("PARTIAL");
							failure.set(SupplierFailureType.INVALID_RESPONSE.name());
						}
					}
				})
				.doOnError(cause -> {
					outcome.set("FAILED");
					failure.set(failureType(cause).name());
				})
				.doFinally(signal -> {
					Tags tags = tags(supplier, operation)
						.and("outcome", outcome.get(), "failure", failure.get());
					registry.timer("supplier.calls", tags)
						.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
				});
		});
	}

	private SupplierFailureType failureType(Throwable cause) {
		return cause instanceof SupplierIntegrationException exception
			? exception.getFailureType() : SupplierFailureType.INTERNAL_ERROR;
	}

	private void recordOffers(SupplierSearchResult result) {
		Tags tags = Tags.of("supplier", result.supplier().name());
		registry.counter("supplier.offers", tags.and("outcome", "ACCEPTED"))
			.increment(result.offers().size());
		registry.counter("supplier.offers", tags.and("outcome", "REJECTED"))
			.increment(result.rejectedOfferCount());
		registry.counter("supplier.offers", tags.and("outcome", "UNAVAILABLE"))
			.increment(result.unavailableOfferCount());
		registry.counter("supplier.offers", tags.and("outcome", "DUPLICATE"))
			.increment(result.duplicateOfferCount());
	}

	public void searchResult(
		Supplier supplier,
		String outcome,
		List<SupplierFailureType> failures
	) {
		Tags tags = Tags.of("supplier", supplier.name());
		registry.counter("supplier.search.results", tags.and("outcome", outcome)).increment();
		for (SupplierFailureType failure : failures) {
			registry.counter("supplier.search.failures", tags.and("failure", failure.name()))
				.increment();
		}
	}

	public void searchDuration(String outcome, long started) {
		registry.timer("search.requests", "outcome", outcome)
			.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
	}

	public <T> T stage(String stage, java.util.function.Supplier<T> action) {
		long started = System.nanoTime();
		try {
			return action.get();
		} finally {
			registry.timer("search.stages", "stage", stage)
				.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
		}
	}

	public void catalogCompleted(
		Supplier supplier,
		SupplierFailureType failure,
		long started
	) {
		Tags tags = Tags.of(
			"supplier", supplier.name(),
			"outcome", failure == null ? "SUCCESS" : "FAILED",
			"failure", failure == null ? "NONE" : failure.name()
		);
		registry.timer("supplier.catalog.sync", tags)
			.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
		if (failure == null) {
			catalogLastSuccess.get(supplier).set(System.currentTimeMillis() / 1000);
			catalogConsecutiveFailures.get(supplier).set(0);
		} else {
			catalogConsecutiveFailures.get(supplier).incrementAndGet();
		}
	}

}
