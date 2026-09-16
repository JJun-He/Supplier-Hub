package com.supplierhub.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CatalogDatabasePropertiesTests {

	@Test
	void bindsFiniteDefaultsWithoutExplicitProperties() {
		new ApplicationContextRunner()
			.withUserConfiguration(CatalogDatabaseConfiguration.class)
			.run(context -> {
				CatalogDatabaseProperties properties = context.getBean(
					CatalogDatabaseProperties.class
				);
				assertThat(properties.readStatementTimeout()).isEqualTo(Duration.ofSeconds(1));
				assertThat(properties.readLockTimeout()).isEqualTo(Duration.ofMillis(300));
			});
	}

	@Test
	void rejectsDisabledSubmillisecondOversizedAndInvertedTimeouts() {
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			var validator = factory.getValidator();
			for (CatalogDatabaseProperties properties : new CatalogDatabaseProperties[] {
				new CatalogDatabaseProperties(Duration.ZERO, Duration.ofMillis(1)),
				new CatalogDatabaseProperties(Duration.ofSeconds(1), Duration.ZERO),
				new CatalogDatabaseProperties(Duration.ofSeconds(1), Duration.ofNanos(1)),
				new CatalogDatabaseProperties(Duration.ofDays(30), Duration.ofMillis(1)),
				new CatalogDatabaseProperties(Duration.ofMillis(100), Duration.ofMillis(200))
			}) {
				assertThat(validator.validate(properties)).isNotEmpty();
			}
			assertThat(validator.validate(new CatalogDatabaseProperties(
				Duration.ofSeconds(1), Duration.ofMillis(300)
			))).isEmpty();
		}
	}

}
