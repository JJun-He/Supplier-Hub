package com.supplierhub.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

import com.supplierhub.catalog.application.CatalogReadException;
import com.supplierhub.catalog.application.CatalogReadException.Reason;

class JpaActiveCatalogMappingReaderTests {

	@ParameterizedTest
	@ValueSource(strings = {"57014", "55P03"})
	void recognizesDatabaseCancellationAndLockTimeoutThroughWrapper(String sqlState) {
		SQLException sqlException = new SQLException("private SQL detail", sqlState);

		assertThat(JpaActiveCatalogMappingReader.failureReason(
			new IllegalStateException("wrapped", sqlException)
		)).isEqualTo(Reason.TIMEOUT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"08001", "08006", "53300", "57P01", "57P02", "57P03"})
	void recognizesConnectionAndDatabaseAvailabilityFailures(String sqlState) {
		assertThat(JpaActiveCatalogMappingReader.failureReason(
			new SQLException("private SQL detail", sqlState)
		)).isEqualTo(Reason.UNAVAILABLE);
	}

	@Test
	void recognizesPoolAcquisitionFailureEvenWithoutSqlState() {
		assertThat(JpaActiveCatalogMappingReader.failureReason(
			new CannotCreateTransactionException(
				"Could not open EntityManager",
				new SQLTransientConnectionException("Connection not available")
			)
		)).isEqualTo(Reason.UNAVAILABLE);
	}

	@Test
	void inspectsChainedSqlExceptions() {
		SQLException first = new SQLException("wrapper");
		first.setNextException(new SQLException("timeout", "57014"));

		assertThat(JpaActiveCatalogMappingReader.failureReason(first))
			.isEqualTo(Reason.TIMEOUT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"23505", "23503", "42601", "42P01"})
	void doesNotHideConstraintAndProgrammingErrors(String sqlState) {
		assertThat(JpaActiveCatalogMappingReader.failureReason(
			new SQLException("internal defect", sqlState)
		)).isNull();
		assertThat(JpaActiveCatalogMappingReader.failureReason(
			new IllegalArgumentException("internal defect")
		)).isNull();
	}

	@Test
	void rejectsExpiredDeadlineBeforeOpeningTransaction() {
		RoomTypeRepository repository = mock(RoomTypeRepository.class);
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
		JpaActiveCatalogMappingReader reader = new JpaActiveCatalogMappingReader(
			repository, mock(CatalogSyncStateRepository.class), jdbcTemplate, transactions,
			new CatalogDatabaseProperties(Duration.ofSeconds(1), Duration.ofMillis(300))
		);

		assertThatThrownBy(() -> reader.findAllActive(System.nanoTime() - 1))
			.isInstanceOfSatisfying(CatalogReadException.class, exception ->
				assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT)
			);
		verifyNoInteractions(repository, jdbcTemplate, transactions);
	}

	@Test
	void exposesFixedMessageWithoutDatabaseDetails() {
		CatalogReadException failure = new CatalogReadException(
			Reason.TIMEOUT,
			new SQLException("secret SQL or connection information")
		);

		assertThat(failure.getMessage()).isEqualTo("Catalog mappings could not be read");
		assertThat(failure.getCause()).isInstanceOf(SQLException.class);
	}

	@Test
	void propagatesUnclassifiedRepositoryDefectWithoutChangingItsType() {
		RoomTypeRepository repository = mock(RoomTypeRepository.class);
		IllegalStateException defect = new IllegalStateException("broken mapping implementation");
		when(repository.findAllActiveMappingsForSearch()).thenThrow(defect);
		JpaActiveCatalogMappingReader reader = new JpaActiveCatalogMappingReader(
			repository, mock(CatalogSyncStateRepository.class), mock(JdbcTemplate.class), mock(PlatformTransactionManager.class),
			new CatalogDatabaseProperties(Duration.ofSeconds(1), Duration.ofMillis(300))
		);

		assertThatThrownBy(() -> reader.findAllActive(
			System.nanoTime() + Duration.ofSeconds(2).toNanos()
		)).isSameAs(defect);
	}

}
