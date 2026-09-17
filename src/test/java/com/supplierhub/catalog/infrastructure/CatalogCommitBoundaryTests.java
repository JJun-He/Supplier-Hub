package com.supplierhub.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.application.ActiveCatalogMappingReader;
import com.supplierhub.catalog.application.CatalogSnapshotStore;
import com.supplierhub.catalog.application.CatalogSynchronizationService;
import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogProperty;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogRoomType;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.suppliera.SupplierACatalogClient;
import com.supplierhub.supplier.supplierb.SupplierBCatalogClient;
import com.zaxxer.hikari.HikariDataSource;

/** 테스트 트랜잭션 없이 실제 commit된 DB 상태를 검증한다. */
@SpringBootTest(properties = {
	"supplier.catalog.enabled=false",
	"spring.jpa.properties.hibernate.generate_statistics=true",
	"catalog.database.write-transaction-timeout-seconds=1",
	"spring.datasource.hikari.data-source-properties.options="
		+ "-c statement_timeout=10000 -c lock_timeout=500"
})
class CatalogCommitBoundaryTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(
		"postgres:17-alpine"
	);

	@Autowired
	private CatalogSnapshotStore snapshotStore;

	@Autowired
	private RoomTypeRepository roomTypeRepository;

	@Autowired
	private CatalogSyncStateRepository syncStateRepository;

	@Autowired
	private ActiveCatalogMappingReader mappingReader;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private CatalogSynchronizationService synchronizationService;

	@Autowired
	private HikariDataSource dataSource;

	@MockitoSpyBean
	private SupplierACatalogClient supplierAClient;

	@MockitoSpyBean
	private SupplierBCatalogClient supplierBClient;

	@BeforeEach
	void clearCommittedCatalog() {
		jdbcTemplate.update("delete from supplier_room_type");
		jdbcTemplate.update("delete from supplier_property");
		jdbcTemplate.update("delete from supplier_catalog_state");
	}

	@Test
	void commitsEmptyCatalogReadinessWithoutCreatingMappings() {
		assertThat(mappingReader.findAllActive(deadline()).initializedSuppliers()).isEmpty();

		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A));
		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A));

		var result = mappingReader.findAllActive(deadline());
		assertThat(result.initializedSuppliers()).containsExactly(Supplier.SUPPLIER_A);
		assertThat(result.mappings()).isEmpty();
		assertThat(syncStateRepository.count()).isEqualTo(1);
	}

	@Test
	void rollsBackReadinessTogetherWithFirstCatalogMappings() {
		transaction().executeWithoutResult(status -> {
			snapshotStore.replace(snapshot(Supplier.SUPPLIER_A, catalogProperty("P1", "New", "Room")));
			assertThat(syncStateRepository.findInitializedSuppliers()).containsExactly(Supplier.SUPPLIER_A);
			status.setRollbackOnly();
		});

		var result = mappingReader.findAllActive(deadline());
		assertThat(result.initializedSuppliers()).isEmpty();
		assertThat(result.mappings()).isEmpty();
	}

	@Test
	void readsMappingsAndReadinessFromSameSnapshotDuringFirstCommit() throws Exception {
		RoomTypeRepository observingRepository = mock(RoomTypeRepository.class);
		try (var executor = Executors.newSingleThreadExecutor()) {
			when(observingRepository.findAllActiveMappingsForSearch()).thenAnswer(invocation -> {
				var rows = roomTypeRepository.findAllActiveMappingsForSearch();
				executor.submit(() -> snapshotStore.replace(snapshot(Supplier.SUPPLIER_A,
					catalogProperty("P1", "Committed during read", "Room")))).get(5, TimeUnit.SECONDS);
				return rows;
			});
			var reader = new JpaActiveCatalogMappingReader(observingRepository, syncStateRepository,
				jdbcTemplate, transactionManager,
				new CatalogDatabaseProperties(Duration.ofSeconds(2), Duration.ofMillis(300)));

			var beforeCommit = reader.findAllActive(deadline());
			assertThat(beforeCommit.mappings()).isEmpty();
			assertThat(beforeCommit.initializedSuppliers()).isEmpty();
		}

		var afterCommit = mappingReader.findAllActive(deadline());
		assertThat(afterCommit.mappings()).hasSize(1);
		assertThat(afterCommit.initializedSuppliers()).containsExactly(Supplier.SUPPLIER_A);
	}

	@Test
	void migrationBackfillsExistingSupplierWithoutChangingItsIds() {
		String schema = "catalog_v2_upgrade";
		try {
			Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.schemas(schema).defaultSchema(schema).target("2").load().migrate();
			jdbcTemplate.update("insert into " + schema + ".supplier_property"
				+ " (id, supplier, supplier_property_code, name, active) values (42, 'SUPPLIER_A', 'P1', 'Legacy', false)");
			Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.schemas(schema).defaultSchema(schema).load().migrate();

			assertThat(jdbcTemplate.queryForList("select supplier from " + schema + ".supplier_catalog_state", String.class))
				.containsExactly("SUPPLIER_A");
			assertThat(jdbcTemplate.queryForObject("select id from " + schema + ".supplier_property", Long.class))
				.isEqualTo(42L);
		} finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
		}
	}

	@Test
	void preservesIdsAcrossCommittedOmissionDeactivationAndReappearance() {
		CatalogProperty retained = catalogProperty("P1", "Retained", "Room 1");
		CatalogProperty missing = catalogProperty("P2", "Original", "Room 2");
		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A, retained, missing));
		StoredMapping original = storedMapping(Supplier.SUPPLIER_A, "P2");

		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A, retained));

		StoredMapping suspected = storedMapping(Supplier.SUPPLIER_A, "P2");
		assertThat(suspected.propertyId()).isEqualTo(original.propertyId());
		assertThat(suspected.roomTypeId()).isEqualTo(original.roomTypeId());
		assertThat(suspected.propertyActive()).isTrue();
		assertThat(suspected.roomTypeActive()).isTrue();
		assertThat(suspected.propertyMissingCount()).isEqualTo(1);
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(2);

		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A, retained));

		StoredMapping deactivated = storedMapping(Supplier.SUPPLIER_A, "P2");
		assertThat(deactivated.propertyId()).isEqualTo(original.propertyId());
		assertThat(deactivated.roomTypeId()).isEqualTo(original.roomTypeId());
		assertThat(deactivated.propertyActive()).isFalse();
		assertThat(deactivated.roomTypeActive()).isFalse();
		assertThat(deactivated.propertyMissingCount()).isEqualTo(2);
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(1);

		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			retained,
			catalogProperty("P2", "Reappeared", "Reappeared Room")
		));

		StoredMapping reappeared = storedMapping(Supplier.SUPPLIER_A, "P2");
		assertThat(reappeared.propertyId()).isEqualTo(original.propertyId());
		assertThat(reappeared.roomTypeId()).isEqualTo(original.roomTypeId());
		assertThat(reappeared.propertyName()).isEqualTo("Reappeared");
		assertThat(reappeared.roomTypeName()).isEqualTo("Reappeared Room");
		assertThat(reappeared.propertyActive()).isTrue();
		assertThat(reappeared.roomTypeActive()).isTrue();
		assertThat(reappeared.propertyMissingCount()).isZero();
		assertThat(reappeared.roomTypeMissingCount()).isZero();
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(2);
	}

	@Test
	void rollsBackEarlierInsertsAndChangesWhenLaterInsertViolatesDatabaseLimit() {
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			catalogProperty("P1", "Original", "Original Room")
		));
		StoredMapping original = storedMapping(Supplier.SUPPLIER_A, "P1");
		CatalogSnapshot invalid = snapshot(
			Supplier.SUPPLIER_A,
			catalogProperty("P1", "Changed", "Changed Room"),
			catalogProperty("P2", "Inserted Before Failure", "Inserted Room"),
			catalogProperty("P3", "x".repeat(256), "Invalid Property Room")
		);
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class)
			.getStatistics();
		statistics.clear();

		assertThatThrownBy(() -> snapshotStore.replace(invalid))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasRootCauseMessage("ERROR: value too long for type character varying(255)");

		assertThat(statistics.getEntityInsertCount())
			.as("property P2 and its room were inserted before P3 failed")
			.isGreaterThanOrEqualTo(2L);
		assertThat(storedMapping(Supplier.SUPPLIER_A, "P1")).isEqualTo(original);
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from supplier_property",
			Long.class
		)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from supplier_room_type",
			Long.class
		)).isEqualTo(1L);
	}

	@Test
	void commitsSupplierBWhileSupplierATransactionIsPendingAndThenRollsBack()
		throws Exception {
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			catalogProperty("P1", "Original A", "Original A Room")
		));
		StoredMapping originalA = storedMapping(Supplier.SUPPLIER_A, "P1");
		CountDownLatch supplierAFlushed = new CountDownLatch(1);
		CountDownLatch allowSupplierARollback = new CountDownLatch(1);

		try (var executor = Executors.newSingleThreadExecutor()) {
			var supplierAWrite = executor.submit(() -> transaction().executeWithoutResult(
				status -> {
					snapshotStore.replace(snapshot(
						Supplier.SUPPLIER_A,
						catalogProperty("P1", "Uncommitted A", "Uncommitted A Room"),
						catalogProperty("P2", "New A", "New A Room")
					));
					roomTypeRepository.flush();
					supplierAFlushed.countDown();
					await(allowSupplierARollback);
					status.setRollbackOnly();
				}
			));
			try {
				await(supplierAFlushed);

				snapshotStore.replace(snapshot(
					Supplier.SUPPLIER_B,
					catalogProperty("P1", "Committed B", "Committed B Room")
				));

				assertThat(storedMapping(Supplier.SUPPLIER_B, "P1").propertyName())
					.isEqualTo("Committed B");
				assertThat(storedMapping(Supplier.SUPPLIER_A, "P1"))
					.isEqualTo(originalA);
			} finally {
				allowSupplierARollback.countDown();
			}
			supplierAWrite.get(10, TimeUnit.SECONDS);
		}

		assertThat(storedMapping(Supplier.SUPPLIER_A, "P1")).isEqualTo(originalA);
		assertThat(storedMapping(Supplier.SUPPLIER_B, "P1").roomTypeName())
			.isEqualTo("Committed B Room");
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from supplier_property where supplier = ?",
			Long.class,
			Supplier.SUPPLIER_A.name()
		)).isEqualTo(1L);
	}

	@Test
	void exposesUpdatedProjectionOnlyAfterWriterTransactionCommits() throws Exception {
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			catalogProperty("P1", "Original", "Original Room")
		));
		List<ActiveCatalogMapping> original =
			roomTypeRepository.findAllActiveMappingsForSearch();
		CountDownLatch writerFlushed = new CountDownLatch(1);
		CountDownLatch allowCommit = new CountDownLatch(1);

		try (var executor = Executors.newSingleThreadExecutor()) {
			var writer = executor.submit(() -> transaction().executeWithoutResult(
				status -> {
					snapshotStore.replace(snapshot(
						Supplier.SUPPLIER_A,
						catalogProperty("P1", "Committed", "Committed Room")
					));
					roomTypeRepository.flush();
					writerFlushed.countDown();
					await(allowCommit);
				}
			));
			try {
				await(writerFlushed);

				assertThat(roomTypeRepository.findAllActiveMappingsForSearch())
					.containsExactlyElementsOf(original);
			} finally {
				allowCommit.countDown();
			}
			writer.get(10, TimeUnit.SECONDS);
		}

		ActiveCatalogMapping previous = original.getFirst();
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch())
			.containsExactly(new ActiveCatalogMapping(
				previous.propertyId(),
				Supplier.SUPPLIER_A,
				"P1",
				"Committed",
				previous.roomTypeId(),
				"R1",
				"Committed Room"
			));
	}

	@Test
	void refusesSynchronizationInsideExistingTransactionBeforeCallingSuppliers() {
		assertThatThrownBy(() -> transaction().executeWithoutResult(
			status -> synchronizationService.synchronizeAll()
		))
			.isInstanceOf(IllegalTransactionStateException.class);

		verify(supplierAClient, never()).fetchCatalog();
		verify(supplierBClient, never()).fetchCatalog();
		assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
	}

	@Test
	void rollsBackBlockedWriteWithinDatabaseBudgetAndRecoversAfterLockRelease()
		throws Exception {
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			catalogProperty("P1", "Original", "Original Room")
		));
		StoredMapping original = storedMapping(Supplier.SUPPLIER_A, "P1");
		CatalogSnapshot replacement = snapshot(
			Supplier.SUPPLIER_A,
			catalogProperty("P1", "Changed", "Changed Room"),
			catalogProperty("P2", "New", "New Room")
		);

		try (var lockConnection = dataSource.getConnection()) {
			lockConnection.setAutoCommit(false);
			try (var statement = lockConnection.prepareStatement(
				"select id from supplier_property where id = ? for update"
			)) {
				statement.setLong(1, original.propertyId());
				try (var rows = statement.executeQuery()) {
					assertThat(rows.next()).isTrue();
				}
			}
			try {
				long started = System.nanoTime();
				Throwable failure = catchThrowable(() -> snapshotStore.replace(replacement));
				Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

				assertThat(failure).isNotNull();
				Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
				assertThat(cause).isInstanceOf(SQLException.class);
				assertThat(((SQLException) cause).getSQLState()).isEqualTo("55P03");
				assertThat(elapsed).isBetween(Duration.ofMillis(200), Duration.ofSeconds(2));
				assertThat(dataSource.getHikariPoolMXBean().getActiveConnections())
					.as("only the deliberate lock holder still owns a connection")
					.isEqualTo(1);
				assertThat(storedMapping(Supplier.SUPPLIER_A, "P1")).isEqualTo(original);
				assertThat(jdbcTemplate.queryForObject(
					"select count(*) from supplier_property",
					Long.class
				)).isEqualTo(1L);
				assertThat(jdbcTemplate.queryForObject(
					"select count(*) from supplier_room_type",
					Long.class
				)).isEqualTo(1L);
			} finally {
				lockConnection.rollback();
			}
		}
		assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();

		snapshotStore.replace(replacement);

		StoredMapping refreshed = storedMapping(Supplier.SUPPLIER_A, "P1");
		assertThat(refreshed.propertyId()).isEqualTo(original.propertyId());
		assertThat(refreshed.roomTypeId()).isEqualTo(original.roomTypeId());
		assertThat(refreshed.propertyName()).isEqualTo("Changed");
		assertThat(refreshed.roomTypeName()).isEqualTo("Changed Room");
		assertThat(storedMapping(Supplier.SUPPLIER_A, "P2").propertyName())
			.isEqualTo("New");
		assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
	}

	private long deadline() {
		return System.nanoTime() + Duration.ofSeconds(5).toNanos();
	}

	private TransactionTemplate transaction() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(10);
		return template;
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS))
				.as("concurrent catalog transaction reached its checkpoint")
				.isTrue();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(
				"Interrupted while coordinating catalog test",
				exception
			);
		}
	}

	private CatalogSnapshot snapshot(
		Supplier supplier,
		CatalogProperty... properties
	) {
		return new CatalogSnapshot(supplier, List.of(properties));
	}

	private CatalogProperty catalogProperty(
		String propertyCode,
		String propertyName,
		String roomName
	) {
		return new CatalogProperty(
			propertyCode,
			propertyName,
			List.of(new CatalogRoomType("R1", roomName, 2))
		);
	}

	private StoredMapping storedMapping(Supplier supplier, String propertyCode) {
		return jdbcTemplate.queryForObject("""
			select property.id as property_id, room_type.id as room_type_id,
			       property.name as property_name, room_type.name as room_type_name,
			       property.active as property_active, room_type.active as room_type_active,
			       property.consecutive_missing_count as property_missing_count,
			       room_type.consecutive_missing_count as room_type_missing_count
			from supplier_property property
			join supplier_room_type room_type on room_type.property_id = property.id
			where property.supplier = ? and property.supplier_property_code = ?
			  and room_type.supplier_room_type_code = 'R1'
			""", (resultSet, rowNumber) -> new StoredMapping(
				resultSet.getLong("property_id"),
				resultSet.getLong("room_type_id"),
				resultSet.getString("property_name"),
				resultSet.getString("room_type_name"),
				resultSet.getBoolean("property_active"),
				resultSet.getBoolean("room_type_active"),
				resultSet.getInt("property_missing_count"),
				resultSet.getInt("room_type_missing_count")
			), supplier.name(), propertyCode);
	}

	private record StoredMapping(
		long propertyId,
		long roomTypeId,
		String propertyName,
		String roomTypeName,
		boolean propertyActive,
		boolean roomTypeActive,
		int propertyMissingCount,
		int roomTypeMissingCount
	) {
	}

}
