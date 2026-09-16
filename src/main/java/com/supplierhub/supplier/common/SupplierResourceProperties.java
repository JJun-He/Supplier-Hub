package com.supplierhub.supplier.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "supplier.resources")
public record SupplierResourceProperties(
		@Valid @NotNull Limits search, @Valid @NotNull Limits catalog) {

	public record Limits(
			@Positive int maxConcurrentCalls,
			@Positive int maxResponseBytes,
			@Positive int pendingAcquireMaxCount,
			@NotNull Duration pendingAcquireTimeout) {

		@AssertTrue(message = "pool acquire timeout must be at least one millisecond")
		public boolean isAcquireTimeoutValid() {
			return pendingAcquireTimeout != null && pendingAcquireTimeout.toMillis() > 0;
		}
	}

	public Limits forOperation(SupplierOperation operation) {
		return switch (operation) {
			case SEARCH -> search;
			case CATALOG -> catalog;
		};
	}
}
