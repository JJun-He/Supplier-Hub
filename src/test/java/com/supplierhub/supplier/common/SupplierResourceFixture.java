package com.supplierhub.supplier.common;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;

public final class SupplierResourceFixture implements AutoCloseable {

	public final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	public final SupplierMetrics metrics = new SupplierMetrics(registry);
	public final SupplierCallResources resources;

	public SupplierResourceFixture() {
		this(8, 2 * 1024 * 1024, 8 * 1024 * 1024);
	}

	public SupplierResourceFixture(int concurrency, int searchBytes, int catalogBytes) {
		resources =
				new SupplierCallResources(
						new SupplierResourceProperties(
								new SupplierResourceProperties.Limits(
										concurrency,
										searchBytes,
										concurrency,
										Duration.ofMillis(200)),
								new SupplierResourceProperties.Limits(
										1, catalogBytes, 1, Duration.ofMillis(200))),
						metrics,
						registry);
	}

	@Override
	public void close() {
		resources.close();
		registry.close();
	}
}
