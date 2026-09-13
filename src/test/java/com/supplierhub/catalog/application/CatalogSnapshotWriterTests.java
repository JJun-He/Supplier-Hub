package com.supplierhub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
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

@DataJpaTest
@Import(CatalogSnapshotWriter.class)
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
		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			"Initial Property",
			"Initial Room",
			2
		));
		Property originalProperty = property(Supplier.SUPPLIER_A);
		RoomType originalRoomType = roomType(originalProperty);
		Long propertyId = originalProperty.getId();
		Long roomTypeId = originalRoomType.getId();

		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			"Renamed Property",
			"Renamed Room",
			4
		));

		Property refreshedProperty = property(Supplier.SUPPLIER_A);
		RoomType refreshedRoomType = roomType(refreshedProperty);
		assertThat(refreshedProperty.getId()).isEqualTo(propertyId);
		assertThat(refreshedProperty.getName()).isEqualTo("Renamed Property");
		assertThat(refreshedRoomType.getId()).isEqualTo(roomTypeId);
		assertThat(refreshedRoomType.getName()).isEqualTo("Renamed Room");
		assertThat(refreshedRoomType.getMaxOccupancy()).isEqualTo(4);
	}

	@Test
	void deactivatesMissingMappingsAndReactivatesThemWithSameIds() {
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

		snapshotStore.replace(snapshot(
			Supplier.SUPPLIER_A,
			retainedProperty
		));

		assertThat(property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		).isActive()).isFalse();
		assertThat(roomType(originalProperty).isActive()).isFalse();
		assertThat(roomTypeRepository.findAllActiveForSearch()).hasSize(1);

		snapshotStore.replace(original);

		Property reactivatedProperty = property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		);
		RoomType reactivatedRoomType = roomType(reactivatedProperty);
		assertThat(reactivatedProperty.getId()).isEqualTo(propertyId);
		assertThat(reactivatedProperty.isActive()).isTrue();
		assertThat(reactivatedRoomType.getId()).isEqualTo(roomTypeId);
		assertThat(reactivatedRoomType.isActive()).isTrue();
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

		assertThat(property(
			Supplier.SUPPLIER_A,
			"PROPERTY-2"
		).isActive()).isFalse();
		assertThat(property(Supplier.SUPPLIER_A).isActive()).isTrue();
		assertThat(property(Supplier.SUPPLIER_B).isActive()).isTrue();
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
		return roomTypeRepository.findByPropertyIdAndSupplierRoomTypeCode(
			property.getId(),
			"ROOM-1"
		).orElseThrow();
	}

}
