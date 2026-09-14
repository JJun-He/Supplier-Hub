package com.supplierhub.supplier.common;

import java.net.URI;
import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.supplierhub.catalog.domain.Supplier;

@Validated
@ConfigurationProperties(prefix = "supplier")
public record SupplierIntegrationProperties(
	@Valid @NotNull Endpoint a,
	@Valid @NotNull Endpoint b,
	@Valid @NotNull Catalog catalog,
	@Valid @NotNull Search search
) {

	public record Endpoint(
		boolean enabled,
		@NotNull URI baseUrl,
		@NotBlank String apiKey
	) {
	}

	public record Catalog(
		boolean enabled,
		@NotNull Duration connectTimeout,
		@NotNull Duration responseTimeout,
		@NotNull Duration callTimeout,
		@PositiveOrZero int maxRetries,
		@NotNull Duration retryBackoff,
		@NotNull Duration initialDelay,
		@NotNull Duration fixedDelay,
		@Positive int bulkMissingMinimumCount,
		@DecimalMin("0.0") @DecimalMax("1.0") double maximumMissingRatio
	) {

		@AssertTrue(message = "catalog durations must be valid")
		public boolean isDurationConfigurationValid() {
			return isPositive(connectTimeout)
				&& isPositive(responseTimeout)
				&& isPositive(callTimeout)
				&& isPositive(retryBackoff)
				&& isNonNegative(initialDelay)
				&& isPositive(fixedDelay);
		}
	}

	public boolean isEnabled(Supplier supplier) {
		return switch (supplier) {
			case SUPPLIER_A -> a.enabled();
			case SUPPLIER_B -> b.enabled();
		};
	}

	public record Search(
		@NotNull Duration connectTimeout,
		@NotNull Duration responseTimeout,
		@NotNull Duration callTimeout,
		@NotNull Duration overallTimeout,
		@Positive int maxConcurrency
	) {

		@AssertTrue(message = "search timeouts must be positive")
		public boolean isTimeoutConfigurationValid() {
			return isPositive(connectTimeout)
				&& isPositive(responseTimeout)
				&& isPositive(callTimeout)
				&& isPositive(overallTimeout);
		}
	}

	private static boolean isPositive(Duration duration) {
		return duration != null && !duration.isZero() && !duration.isNegative();
	}

	private static boolean isNonNegative(Duration duration) {
		return duration != null && !duration.isNegative();
	}

}
