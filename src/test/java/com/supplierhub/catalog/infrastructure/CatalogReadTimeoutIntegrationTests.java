package com.supplierhub.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.supplierhub.catalog.application.ActiveCatalogMappingReader;
import com.supplierhub.catalog.application.CatalogReadException;
import com.supplierhub.catalog.application.CatalogReadException.Reason;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.api.StaySearchController;
import com.supplierhub.search.application.IntegratedSearchService;
import com.supplierhub.search.application.SearchStatus;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;
import com.supplierhub.supplier.common.SupplierResourceFixture;
import com.supplierhub.supplier.common.SupplierSearchClient;

/** 실제 PostgreSQL 쿼리 취소와 풀 연결 하나의 재사용을 검증한다. */
@SpringBootTest(properties = {
	"supplier.catalog.enabled=false",
	"spring.datasource.hikari.maximum-pool-size=1",
	"spring.datasource.hikari.minimum-idle=1",
	"spring.datasource.hikari.connection-timeout=500",
	"spring.datasource.hikari.validation-timeout=250"
})
class CatalogReadTimeoutIntegrationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private ActiveCatalogMappingReader mappingReader;

	@Autowired
	private RoomTypeRepository roomTypeRepository;

	@Autowired
	private CatalogSyncStateRepository syncStateRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private HikariDataSource dataSource;

	@Autowired
	private SupplierIntegrationProperties supplierProperties;

	@Test
	void lockedCatalogReturns503WithoutCallingSuppliersAndThenRecovers() throws Exception {
		List<String> baseline = settings();
		SupplierSearchClient supplierA = mock(SupplierSearchClient.class);
		SupplierSearchClient supplierB = mock(SupplierSearchClient.class);
		when(supplierA.supplier()).thenReturn(Supplier.SUPPLIER_A);
		when(supplierB.supplier()).thenReturn(Supplier.SUPPLIER_B);
		try (SupplierResourceFixture fixture = new SupplierResourceFixture();
			Connection blocker = independentConnection()) {
			blocker.setAutoCommit(false);
			try (var statement = blocker.createStatement()) {
				statement.execute("lock table supplier_property in access exclusive mode");
			}
			StaySearchController controller = new StaySearchController(new IntegratedSearchService(
				mappingReader, List.of(supplierA, supplierB), supplierProperties,
				fixture.resources, fixture.metrics
			));
			long started = System.nanoTime();

			var response = controller.search(
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), 2, 0
			);

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(response.getBody()).isNotNull();
			assertThat(response.getBody().status()).isEqualTo(SearchStatus.FAILED);
			assertThat(response.getBody().supplierResults()).hasSize(2).allSatisfy(result ->
				assertThat(result.failureTypes()).containsExactly(SupplierFailureType.CATALOG_UNAVAILABLE)
			);
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
			verify(supplierA, never()).search(any());
			verify(supplierB, never()).search(any());
			assertThat(fixture.registry.get("search.catalog.read.failures")
				.tag("reason", "TIMEOUT").counter().count()).isEqualTo(1);
			assertPoolIdle();
			blocker.rollback();
		}

		assertThat(settings()).isEqualTo(baseline);
		assertThat(mappingReader.findAllActive(deadline(Duration.ofSeconds(2))).mappings()).isEmpty();
		assertPoolIdle();
	}

	@Test
	void statementTimeoutCancelsSlowSqlAndRestoresConnectionSettings() {
		List<String> baseline = settings();
		RoomTypeRepository slowRepository = mock(RoomTypeRepository.class);
		when(slowRepository.findAllActiveMappingsForSearch()).thenAnswer(invocation -> {
			jdbcTemplate.execute("select pg_sleep(3)");
			return List.of();
		});
		JpaActiveCatalogMappingReader reader = reader(
			slowRepository, Duration.ofMillis(150), Duration.ofMillis(100)
		);
		long started = System.nanoTime();

		assertThatThrownBy(() -> reader.findAllActive(deadline(Duration.ofSeconds(2))))
			.isInstanceOfSatisfying(CatalogReadException.class, exception ->
				assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT)
			);

		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
		assertPoolIdle();
		assertThat(settings()).isEqualTo(baseline);
		assertThat(mappingReader.findAllActive(deadline(Duration.ofSeconds(2))).mappings()).isEmpty();
		assertPoolIdle();
	}

	@Test
	void poolExhaustionIsUnavailableAndRecoversAfterConnectionIsReturned() throws Exception {
		try (Connection held = dataSource.getConnection()) {
			long started = System.nanoTime();

			assertThatThrownBy(() -> mappingReader.findAllActive(deadline(Duration.ofSeconds(2))))
				.isInstanceOfSatisfying(CatalogReadException.class, exception ->
					assertThat(exception.reason()).isEqualTo(Reason.UNAVAILABLE)
				);

			assertThat(Duration.ofNanos(System.nanoTime() - started))
				.isBetween(Duration.ofMillis(300), Duration.ofSeconds(2));
			assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
			assertThat(dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
		}

		assertPoolIdle();
		assertThat(mappingReader.findAllActive(deadline(Duration.ofSeconds(2))).mappings()).isEmpty();
		assertPoolIdle();
	}

	@Test
	void deductsConnectionWaitBeforeSettingSqlBudget() throws Exception {
		AtomicLong configuredStatementMillis = new AtomicLong();
		RoomTypeRepository observingRepository = mock(RoomTypeRepository.class);
		when(observingRepository.findAllActiveMappingsForSearch()).thenAnswer(invocation -> {
			configuredStatementMillis.set(jdbcTemplate.queryForObject(
				"select setting::bigint from pg_settings where name = 'statement_timeout'",
				Long.class
			));
			return roomTypeRepository.findAllActiveMappingsForSearch();
		});
		JpaActiveCatalogMappingReader reader = reader(
			observingRepository, Duration.ofSeconds(1), Duration.ofMillis(300)
		);
		try (var executor = Executors.newSingleThreadExecutor()) {
			java.util.concurrent.Future<?> read;
			try (Connection held = dataSource.getConnection()) {
				long deadline = deadline(Duration.ofMillis(700));
				read = executor.submit(() -> reader.findAllActive(deadline));
				await(() -> dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection() == 1);
				Thread.sleep(200);
			}
			read.get(3, TimeUnit.SECONDS);
		}

		assertThat(configuredStatementMillis.get()).isBetween(1L, 550L);
		assertPoolIdle();
	}

	@Test
	void readSettingsAreRestoredAfterSuccessfulQuery() {
		List<String> baseline = settings();
		RoomTypeRepository observingRepository = mock(RoomTypeRepository.class);
		when(observingRepository.findAllActiveMappingsForSearch()).thenAnswer(invocation -> {
			assertThat(settings()).containsExactly("150ms", "100ms");
			return roomTypeRepository.findAllActiveMappingsForSearch();
		});
		JpaActiveCatalogMappingReader reader = reader(
			observingRepository, Duration.ofMillis(150), Duration.ofMillis(100)
		);

		assertThat(reader.findAllActive(deadline(Duration.ofSeconds(2))).mappings()).isEmpty();

		assertThat(settings()).isEqualTo(baseline);
		assertPoolIdle();
	}

	@Test
	void reappliesRemainingSqlBudgetBeforeReadingReadiness() {
		AtomicLong mappingBudget = new AtomicLong();
		AtomicLong readinessBudget = new AtomicLong();
		RoomTypeRepository mappingStage = mock(RoomTypeRepository.class);
		CatalogSyncStateRepository readinessStage = mock(CatalogSyncStateRepository.class);
		when(mappingStage.findAllActiveMappingsForSearch()).thenAnswer(invocation -> {
			mappingBudget.set(statementBudgetMillis());
			Thread.sleep(180);
			return roomTypeRepository.findAllActiveMappingsForSearch();
		});
		when(readinessStage.findInitializedSuppliers()).thenAnswer(invocation -> {
			readinessBudget.set(statementBudgetMillis());
			return syncStateRepository.findInitializedSuppliers();
		});
		var reader = new JpaActiveCatalogMappingReader(mappingStage, readinessStage, jdbcTemplate,
			transactionManager, new CatalogDatabaseProperties(Duration.ofSeconds(2), Duration.ofMillis(300)));

		reader.findAllActive(deadline(Duration.ofSeconds(1)));

		assertThat(readinessBudget.get()).isPositive().isLessThan(mappingBudget.get() - 100);
		assertPoolIdle();
	}

	@Test
	void readinessTableLockTimesOutAndRecoversWithoutLeakingSettings() throws Exception {
		List<String> baseline = settings();
		try (Connection blocker = independentConnection()) {
			blocker.setAutoCommit(false);
			try (var statement = blocker.createStatement()) {
				statement.execute("lock table supplier_catalog_state in access exclusive mode");
			}
			assertThatThrownBy(() -> mappingReader.findAllActive(deadline(Duration.ofSeconds(2))))
				.isInstanceOfSatisfying(CatalogReadException.class,
					failure -> assertThat(failure.reason()).isEqualTo(Reason.TIMEOUT));
			assertPoolIdle();
			blocker.rollback();
		}
		assertThat(mappingReader.findAllActive(deadline(Duration.ofSeconds(2))).mappings()).isEmpty();
		assertThat(settings()).isEqualTo(baseline);
		assertPoolIdle();
	}

	private long statementBudgetMillis() {
		return jdbcTemplate.queryForObject(
			"select setting::bigint from pg_settings where name = 'statement_timeout'", Long.class);
	}

	@Test
	void rejectsResultThatFinishesMaterializingAfterTheDeadline() {
		List<String> baseline = settings();
		RoomTypeRepository slowMaterialization = mock(RoomTypeRepository.class);
		when(slowMaterialization.findAllActiveMappingsForSearch()).thenAnswer(invocation -> {
			var rows = roomTypeRepository.findAllActiveMappingsForSearch();
			// SQL이 끝나면 이후 Java 객체 변환을 SQL 시간 제한으로 중단할 수 없다.
			Thread.sleep(150);
			return rows;
		});
		JpaActiveCatalogMappingReader reader = reader(
			slowMaterialization, Duration.ofSeconds(1), Duration.ofMillis(300)
		);

		assertThatThrownBy(() -> reader.findAllActive(deadline(Duration.ofMillis(100))))
			.isInstanceOfSatisfying(CatalogReadException.class, exception ->
				assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT)
			);

		assertThat(settings()).isEqualTo(baseline);
		assertPoolIdle();
	}

	private JpaActiveCatalogMappingReader reader(
		RoomTypeRepository repository, Duration statementTimeout, Duration lockTimeout
	) {
		return new JpaActiveCatalogMappingReader(
			repository, syncStateRepository, jdbcTemplate, transactionManager,
			new CatalogDatabaseProperties(statementTimeout, lockTimeout)
		);
	}

	private List<String> settings() {
		return jdbcTemplate.queryForObject("""
			select current_setting('statement_timeout'), current_setting('lock_timeout')
			""", (rows, rowNumber) -> List.of(rows.getString(1), rows.getString(2)));
	}

	private Connection independentConnection() throws SQLException {
		return DriverManager.getConnection(
			postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
		);
	}

	private static long deadline(Duration duration) {
		return System.nanoTime() + duration.toNanos();
	}

	private void assertPoolIdle() {
		assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
		assertThat(dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = deadline(Duration.ofSeconds(2));
		while (!condition.getAsBoolean() && deadline - System.nanoTime() > 0) {
			Thread.sleep(5);
		}
		assertThat(condition.getAsBoolean()).as("reader waits for the held pool connection").isTrue();
	}

}
