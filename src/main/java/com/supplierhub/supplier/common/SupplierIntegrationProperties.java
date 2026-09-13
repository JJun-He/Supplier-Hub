package com.supplierhub.supplier.common;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplier")
public record SupplierIntegrationProperties(
	Endpoint a,
	Endpoint b,
	Catalog catalog,
	Search search
) {

	public record Endpoint(
		URI baseUrl,
		String apiKey
	) {
	}

	public record Catalog(
		boolean enabled,
		Duration connectTimeout,
		Duration responseTimeout,
		int maxRetries,
		Duration retryBackoff,
		Duration fixedDelay
	) {
	}

	public record Search(
		Duration connectTimeout,
		Duration responseTimeout,
		Duration callTimeout
	) {
	}

}
