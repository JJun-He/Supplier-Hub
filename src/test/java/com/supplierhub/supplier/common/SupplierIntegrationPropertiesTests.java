package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class SupplierIntegrationPropertiesTests {

	private final Validator validator = Validation
		.buildDefaultValidatorFactory()
		.getValidator();

	@Test
	void rejectsBlankApiKeyAndNonPositiveSearchTimeout() {
		SupplierIntegrationProperties properties = new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				" "
			),
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"key"
			),
			validCatalog(),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(2),
				Duration.ZERO,
				Duration.ofSeconds(5),
				4
			)
		);

		assertThat(validator.validate(properties))
			.extracting(violation -> violation.getPropertyPath().toString())
			.contains(
				"a.apiKey",
				"search.timeoutConfigurationValid"
			);
	}

	@Test
	void acceptsConfiguredDefaults() {
		SupplierIntegrationProperties properties = new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"a-key"
			),
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"b-key"
			),
			validCatalog(),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(2),
				Duration.ofSeconds(3),
				Duration.ofSeconds(5),
				4
			)
		);

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void rejectsNonPositiveOverallTimeoutAndSearchConcurrency() {
		SupplierIntegrationProperties properties = new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"a-key"
			),
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"b-key"
			),
			validCatalog(),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(2),
				Duration.ofSeconds(3),
				Duration.ZERO,
				0
			)
		);

		assertThat(validator.validate(properties))
			.extracting(violation -> violation.getPropertyPath().toString())
			.contains(
				"search.timeoutConfigurationValid",
				"search.maxConcurrency"
			);
	}

	@Test
	void rejectsNonPositiveCatalogCallTimeout() {
		SupplierIntegrationProperties properties = new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"a-key"
			),
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"b-key"
			),
			new SupplierIntegrationProperties.Catalog(
				true,
				Duration.ofMillis(500),
				Duration.ofSeconds(3),
				Duration.ZERO,
				2,
				Duration.ofMillis(300),
				Duration.ZERO,
				Duration.ofMinutes(10),
				10,
				0.5
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(2),
				Duration.ofSeconds(3),
				Duration.ofSeconds(5),
				4
			)
		);

		assertThat(validator.validate(properties))
			.extracting(violation -> violation.getPropertyPath().toString())
			.contains("catalog.durationConfigurationValid");
	}

	@Test
	void rejectsInvalidBulkMissingThresholds() {
		SupplierIntegrationProperties properties = new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"a-key"
			),
			new SupplierIntegrationProperties.Endpoint(
				true,
				URI.create("http://localhost"),
				"b-key"
			),
			new SupplierIntegrationProperties.Catalog(
				true,
				Duration.ofMillis(500),
				Duration.ofSeconds(3),
				Duration.ofSeconds(4),
				2,
				Duration.ofMillis(300),
				Duration.ZERO,
				Duration.ofMinutes(10),
				0,
				1.1
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(2),
				Duration.ofSeconds(3),
				Duration.ofSeconds(5),
				4
			)
		);

		assertThat(validator.validate(properties))
			.extracting(violation -> violation.getPropertyPath().toString())
			.contains(
				"catalog.bulkMissingMinimumCount",
				"catalog.maximumMissingRatio"
			);
	}

	private SupplierIntegrationProperties.Catalog validCatalog() {
		return new SupplierIntegrationProperties.Catalog(
			true,
			Duration.ofMillis(500),
			Duration.ofSeconds(3),
			Duration.ofSeconds(4),
			2,
			Duration.ofMillis(300),
			Duration.ZERO,
			Duration.ofMinutes(10),
			10,
			0.5
		);
	}

}
