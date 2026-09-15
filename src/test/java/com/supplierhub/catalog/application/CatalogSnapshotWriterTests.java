package com.supplierhub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogProperty;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogRoomType;
import com.supplierhub.catalog.domain.Property;
import com.supplierhub.catalog.domain.RoomType;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.catalog.infrastructure.PropertyRepository;
import com.supplierhub.catalog.infrastructure.RoomTypeRepository;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;

@DataJpaTest
@Import(CatalogSnapshotWriter.class)
@EnableConfigurationProperties(SupplierIntegrationProperties.class)
class CatalogSnapshotWriterTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private CatalogSnapshotStore snapshotStore;

	@Autowired
	private PropertyRepository propertyRepository;

	@Autowired
	private RoomTypeRepository roomTypeRepository;

	@Test
	void refreshesCatalogWhileKeepingInternalIds() {
		CatalogSnapshotUpdate createdUpdate = snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			"Initial Property",
			"Initial Room",
			2
		));
		assertThat(createdUpdate.createdProperties()).isEqualTo(1);
		assertThat(createdUpdate.createdRoomTypes()).isEqualTo(1);
		Property originalProperty = property(Supplier.SUPPLIER_A);
		RoomType originalRoomType = roomType(originalProperty);
		Long propertyId = originalProperty.getId();
		Long roomTypeId = originalRoomType.getId();

		CatalogSnapshotUpdate refreshedUpdate = snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			"Renamed Property",
			"Renamed Room",
			4
		));
		assertThat(refreshedUpdate.createdProperties()).isZero();
		assertThat(refreshedUpdate.createdRoomTypes()).isZero();

		Property refreshedProperty = property(Supplier.SUPPLIER_A);
		RoomType refreshedRoomType = roomType(refreshedProperty);
		assertThat(refreshedProperty.getId()).isEqualTo(propertyId);
		assertThat(refreshedProperty.getName()).isEqualTo("Renamed Property");
		assertThat(refreshedRoomType.getId()).isEqualTo(roomTypeId);
		assertThat(refreshedRoomType.getName()).isEqualTo("Renamed Room");
		assertThat(refreshedRoomType.getMaxOccupancy()).isEqualTo(4);
	}

	@Test
	void deactivatesPropertyAfterTwoConsecutiveOmissionsAndReactivatesWithSameIds() {
		CatalogProperty retainedProperty = catalogProperty(
			"PROPERTY-1",
			"Retained Property",
			"ROOM-1",
			"Retained Room",
			2
		);
		CatalogProperty removedProperty = catalogProperty(
			"PROPERTY-2",
			"Removed Property",
			"ROOM-1",
			"Removed Room",
			3
		);
		CatalogSnapshot original = snapshot(
			Supplier.SUPPLIER_A,
			retainedProperty,
			removedProperty
		);
		snapshotStore.replace(original);
		Property originalProperty = property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		);
		RoomType originalRoomType = roomType(originalProperty);
		Long propertyId = originalProperty.getId();
		Long roomTypeId = originalRoomType.getId();

		CatalogSnapshotUpdate suspectedUpdate = snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			retainedProperty
		));

		Property suspectedMissingProperty = property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		);
		assertThat(suspectedMissingProperty.isActive()).isTrue();
		assertThat(suspectedMissingProperty.getConsecutiveMissingCount())
			.isEqualTo(1);
		assertThat(roomType(suspectedMissingProperty).isActive()).isTrue();
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(2);
		assertThat(suspectedUpdate.suspectedMissingProperties()).isEqualTo(1);
		assertThat(suspectedUpdate.deactivatedProperties()).isZero();

		CatalogSnapshotUpdate deactivatedUpdate = snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			retainedProperty
		));

		Property deactivatedProperty = property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		);
		assertThat(deactivatedProperty.isActive()).isFalse();
		assertThat(deactivatedProperty.getConsecutiveMissingCount())
			.isEqualTo(2);
		assertThat(roomType(deactivatedProperty).isActive()).isFalse();
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(1);
		assertThat(deactivatedUpdate.deactivatedProperties()).isEqualTo(1);
		assertThat(deactivatedUpdate.deactivatedRoomTypes()).isEqualTo(1);

		snapshotStore.replace(original);

		Property reactivatedProperty = property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		);
		RoomType reactivatedRoomType = roomType(reactivatedProperty);
		assertThat(reactivatedProperty.getId()).isEqualTo(propertyId);
		assertThat(reactivatedProperty.isActive()).isTrue();
		assertThat(reactivatedProperty.getConsecutiveMissingCount()).isZero();
		assertThat(reactivatedRoomType.getId()).isEqualTo(roomTypeId);
		assertThat(reactivatedRoomType.isActive()).isTrue();
		assertThat(reactivatedRoomType.getConsecutiveMissingCount()).isZero();
	}

	@Test
	void deactivatesRoomTypeAfterTwoConsecutiveOmissionsAndResetsOnReappearance() {
		CatalogProperty originalProperty = new CatalogProperty(
			"PROPERTY-1",
			"Property",
			List.of(
				new CatalogRoomType("ROOM-1", "Retained Room", 2),
				new CatalogRoomType("ROOM-2", "Missing Room", 3)
			)
		);
		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A, originalProperty));
		Property property = property(Supplier.SUPPLIER_A);
		RoomType originalRoomType = roomType(property, "ROOM-2");
		Long roomTypeId = originalRoomType.getId();
		CatalogProperty partialProperty = catalogProperty(
			"PROPERTY-1",
			"Property",
			"ROOM-1",
			"Retained Room",
			2
		);

		CatalogSnapshotUpdate suspectedUpdate = snapshotStore.replace(
			snapshot(Supplier.SUPPLIER_A, partialProperty)
		);

		RoomType suspectedMissingRoomType = roomType(property, "ROOM-2");
		assertThat(suspectedMissingRoomType.isActive()).isTrue();
		assertThat(suspectedMissingRoomType.getConsecutiveMissingCount())
			.isEqualTo(1);
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(2);
		assertThat(suspectedUpdate.suspectedMissingRoomTypes()).isEqualTo(1);
		assertThat(suspectedUpdate.deactivatedRoomTypes()).isZero();

		CatalogSnapshotUpdate deactivatedUpdate = snapshotStore.replace(
			snapshot(Supplier.SUPPLIER_A, partialProperty)
		);

		RoomType deactivatedRoomType = roomType(property, "ROOM-2");
		assertThat(deactivatedRoomType.isActive()).isFalse();
		assertThat(deactivatedRoomType.getConsecutiveMissingCount()).isEqualTo(2);
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(1);
		assertThat(deactivatedUpdate.deactivatedRoomTypes()).isEqualTo(1);

		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A, originalProperty));

		RoomType reactivatedRoomType = roomType(property, "ROOM-2");
		assertThat(reactivatedRoomType.getId()).isEqualTo(roomTypeId);
		assertThat(reactivatedRoomType.isActive()).isTrue();
		assertThat(reactivatedRoomType.getConsecutiveMissingCount()).isZero();
	}

	@Test
	void resetsSuspectedPropertyOmissionWhenPropertyReappears() {
		CatalogProperty retainedProperty = catalogProperty(
			"PROPERTY-1",
			"Retained Property",
			"ROOM-1",
			"Retained Room",
			2
		);
		CatalogProperty intermittentProperty = catalogProperty(
			"PROPERTY-2",
			"Intermittent Property",
			"ROOM-1",
			"Intermittent Room",
			2
		);
		CatalogSnapshot original = snapshot(
			Supplier.SUPPLIER_A,
			retainedProperty,
			intermittentProperty
		);
		snapshotStore.replace(original);

		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A, retainedProperty));
		assertThat(property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		).getConsecutiveMissingCount()).isEqualTo(1);

		snapshotStore.replace(original);

		Property reappearedProperty = property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		);
		assertThat(reappearedProperty.isActive()).isTrue();
		assertThat(reappearedProperty.getConsecutiveMissingCount()).isZero();

		snapshotStore.replace(snapshot(Supplier.SUPPLIER_A, retainedProperty));

		Property missingAgainProperty = property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		);
		assertThat(missingAgainProperty.isActive()).isTrue();
		assertThat(missingAgainProperty.getConsecutiveMissingCount()).isEqualTo(1);
	}

	@Test
	void rejectsEmptyCatalogWhenActivePropertyMappingsExist() {
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			"Property",
			"Room",
			2
		));

		assertThatThrownBy(() -> snapshotStore.replace(new CatalogSnapshot(
			Supplier.SUPPLIER_A,
			List.of()
		))).isInstanceOf(CatalogSnapshotRejectedException.class);

		Property property = property(Supplier.SUPPLIER_A);
		assertThat(property.isActive()).isTrue();
		assertThat(roomType(property).isActive()).isTrue();
	}

	@Test
	void rejectsEmptyRoomCatalogWhenActiveRoomMappingsExist() {
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			"Property",
			"Room",
			2
		));

		CatalogSnapshot emptyRoomCatalog = snapshot(
			Supplier.SUPPLIER_A,
			new CatalogProperty("PROPERTY-1", "Property", List.of())
		);
		assertThatThrownBy(() -> snapshotStore.replace(emptyRoomCatalog))
			.isInstanceOf(CatalogSnapshotRejectedException.class);

		Property property = property(Supplier.SUPPLIER_A);
		assertThat(property.isActive()).isTrue();
		assertThat(roomType(property).isActive()).isTrue();
	}

	@Test
	void replacesEachSupplierCatalogIndependently() {
		CatalogProperty retainedSupplierAProperty = catalogProperty(
			"PROPERTY-1",
			"Retained Supplier A Property",
			"ROOM-1",
			"Retained Supplier A Room",
			2
		);
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			retainedSupplierAProperty,
			catalogProperty(
				"PROPERTY-2",
				"Removed Supplier A Property",
				"ROOM-1",
				"Removed Supplier A Room",
				2
			)
		));
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_B,
			"Supplier B Property",
			"Supplier B Room",
			3
		));

		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			retainedSupplierAProperty
		));
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			retainedSupplierAProperty
		));

		assertThat(property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		).isActive()).isFalse();
		assertThat(property(Supplier.SUPPLIER_A).isActive()).isTrue();
		assertThat(property(Supplier.SUPPLIER_B).isActive()).isTrue();
	}

	@Test
	void rejectsSnapshotThatSuddenlyDropsMostProperties() {
		List<CatalogProperty> originalProperties = IntStream.rangeClosed(1, 20)
			.mapToObj(index -> catalogProperty(
				"PROPERTY-" + index,
				"Property " + index,
				"ROOM-1",
				"Room",
				2
			))
			.toList();
		snapshotStore.replace(new CatalogSnapshot(
			Supplier.SUPPLIER_A,
			originalProperties
		));

		assertThatThrownBy(() -> snapshotStore.replace(new CatalogSnapshot(
			Supplier.SUPPLIER_A,
			List.of(originalProperties.getFirst())
		)))
			.isInstanceOf(CatalogSnapshotRejectedException.class)
			.hasMessageContaining("Bulk missing properties");
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(20);
	}

	@Test
	void rejectsSnapshotThatSuddenlyDropsMostRoomTypes() {
		List<CatalogRoomType> originalRoomTypes = IntStream.rangeClosed(1, 20)
			.mapToObj(index -> new CatalogRoomType(
				"ROOM-" + index,
				"Room " + index,
				2
			))
			.toList();
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			new CatalogProperty(
				"PROPERTY-1",
				"Property",
				originalRoomTypes
			)
		));

		assertThatThrownBy(() -> snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			new CatalogProperty(
				"PROPERTY-1",
				"Property",
				List.of(originalRoomTypes.getFirst())
			)
		)))
			.isInstanceOf(CatalogSnapshotRejectedException.class)
			.hasMessageContaining("Bulk missing room types");
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(20);
	}

	@Test
	void rejectsCompleteReplacementOfSmallPropertyCatalog() {
		List<CatalogProperty> originalProperties = IntStream.rangeClosed(1, 3)
			.mapToObj(index -> catalogProperty(
				"PROPERTY-" + index,
				"Property " + index,
				"ROOM-1",
				"Room",
				2
			))
			.toList();
		snapshotStore.replace(new CatalogSnapshot(
			Supplier.SUPPLIER_A,
			originalProperties
		));

		assertThatThrownBy(() -> snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			catalogProperty(
				"REPLACEMENT-PROPERTY",
				"Replacement Property",
				"ROOM-1",
				"Room",
				2
			)
		)))
			.isInstanceOf(CatalogSnapshotRejectedException.class)
			.hasMessageContaining("Bulk missing properties");
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(3);
	}

	@Test
	void rejectsCompleteReplacementOfSmallRoomTypeCatalog() {
		List<CatalogRoomType> originalRoomTypes = IntStream.rangeClosed(1, 3)
			.mapToObj(index -> new CatalogRoomType(
				"ROOM-" + index,
				"Room " + index,
				2
			))
			.toList();
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			new CatalogProperty(
				"PROPERTY-1",
				"Property",
				originalRoomTypes
			)
		));

		assertThatThrownBy(() -> snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			catalogProperty(
				"PROPERTY-1",
				"Property",
				"REPLACEMENT-ROOM",
				"Replacement Room",
				2
			)
		)))
			.isInstanceOf(CatalogSnapshotRejectedException.class)
			.hasMessageContaining("Bulk missing room types");
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).hasSize(3);
	}

	private CatalogSnapshot snapshot(
		Supplier supplier,
		String propertyName,
		String roomName,
		int maxOccupancy
	) {
		return snapshot(
			supplier,
			catalogProperty(
				"PROPERTY-1",
				propertyName,
				"ROOM-1",
				roomName,
				maxOccupancy
			)
		);
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
		String roomCode,
		String roomName,
		int maxOccupancy
	) {
		return new CatalogProperty(
			propertyCode,
			propertyName,
			List.of(new CatalogRoomType(
				roomCode,
				roomName,
				maxOccupancy
			))
		);
	}

	private Property property(Supplier supplier) {
		return property(supplier, "PROPERTY-1");
	}

	private Property property(Supplier supplier, String propertyCode) {
		return propertyRepository.findBySupplierAndSupplierPropertyCode(
			supplier,
			propertyCode
		).orElseThrow();
	}

	private RoomType roomType(Property property) {
		return roomType(property, "ROOM-1");
	}

	private RoomType roomType(Property property, String roomTypeCode) {
		return roomTypeRepository.findByPropertyIdAndSupplierRoomTypeCode(
			property.getId(),
			roomTypeCode
		).orElseThrow();
	}

}
