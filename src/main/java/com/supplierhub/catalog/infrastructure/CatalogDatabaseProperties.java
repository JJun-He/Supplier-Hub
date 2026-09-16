package com.supplierhub.catalog.infrastructure;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "catalog.database")
public record CatalogDatabaseProperties(
	@NotNull @DefaultValue("1s") Duration readStatementTimeout,
	@NotNull @DefaultValue("300ms") Duration readLockTimeout
) {

	@AssertTrue(message = "database read timeouts must be between one millisecond and 2147483647 milliseconds, with lock timeout no greater than statement timeout")
	public boolean isReadTimeoutConfigurationValid() {
		return isSupported(readStatementTimeout)
			&& isSupported(readLockTimeout)
			&& readLockTimeout.compareTo(readStatementTimeout) <= 0;
	}

	private static boolean isSupported(Duration timeout) {
		return timeout != null
			&& timeout.compareTo(Duration.ofMillis(1)) >= 0
			&& timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0;
	}

}
