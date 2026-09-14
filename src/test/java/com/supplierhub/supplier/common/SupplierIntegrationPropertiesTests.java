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
				URI.create("http://localhost"),
				" "
			),
			new SupplierIntegrationProperties.Endpoint(
				URI.create("http://localhost"),
				"key"
			),
			validCatalog(),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(2),
				Duration.ZERO
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
				URI.create("http://localhost"),
				"a-key"
			),
			new SupplierIntegrationProperties.Endpoint(
				URI.create("http://localhost"),
				"b-key"
			),
			validCatalog(),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(2),
				Duration.ofSeconds(3)
			)
		);

		assertThat(validator.validate(properties)).isEmpty();
	}

	private SupplierIntegrationProperties.Catalog validCatalog() {
		return new SupplierIntegrationProperties.Catalog(
			true,
			Duration.ofMillis(500),
			Duration.ofSeconds(3),
			2,
			Duration.ofMillis(300),
			Duration.ZERO,
			Duration.ofMinutes(10)
		);
	}

}
