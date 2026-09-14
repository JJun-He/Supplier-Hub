package com.supplierhub.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.domain.Property;
import com.supplierhub.catalog.domain.RoomType;
import com.supplierhub.catalog.domain.Supplier;

@DataJpaTest
class CatalogRepositoryTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private PropertyRepository propertyRepository;

	@Autowired
	private RoomTypeRepository roomTypeRepository;

	@Test
	void rejectsDuplicatePropertyCodeWithinSameSupplier() {
		propertyRepository.saveAndFlush(Property.create(
			Supplier.SUPPLIER_A,
			"PROPERTY-1",
			"First Property"
		));

		assertThatThrownBy(() -> propertyRepository.saveAndFlush(Property.create(
			Supplier.SUPPLIER_A,
			"PROPERTY-1",
			"Duplicate Property"
		)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsSamePropertyCodeForDifferentSuppliers() {
		Property firstProperty = propertyRepository.save(Property.create(
			Supplier.SUPPLIER_A,
			"PROPERTY-1",
			"First Property"
		));
		Property secondProperty = propertyRepository.saveAndFlush(Property.create(
			Supplier.SUPPLIER_B,
			"PROPERTY-1",
			"Second Property"
		));

		assertThat(firstProperty.getId()).isNotNull();
		assertThat(secondProperty.getId()).isNotNull();
	}

	@Test
	void rejectsDuplicateRoomTypeCodeWithinSameProperty() {
		Property property = propertyRepository.save(Property.create(
			Supplier.SUPPLIER_A,
			"PROPERTY-1",
			"Property"
		));
		roomTypeRepository.saveAndFlush(RoomType.create(
			property,
			"ROOM-1",
			"First Room",
			2
		));

		assertThatThrownBy(() -> roomTypeRepository.saveAndFlush(RoomType.create(
			property,
			"ROOM-1",
			"Duplicate Room",
			4
		)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsSameRoomTypeCodeInDifferentProperties() {
		Property firstProperty = propertyRepository.save(Property.create(
			Supplier.SUPPLIER_A,
			"PROPERTY-1",
			"First Property"
		));
		Property secondProperty = propertyRepository.save(Property.create(
			Supplier.SUPPLIER_A,
			"PROPERTY-2",
			"Second Property"
		));

		RoomType firstRoomType = roomTypeRepository.save(RoomType.create(
			firstProperty,
			"ROOM-1",
			"First Room",
			2
		));
		RoomType secondRoomType = roomTypeRepository.saveAndFlush(RoomType.create(
			secondProperty,
			"ROOM-1",
			"Second Room",
			4
		));

		assertThat(firstRoomType.getId()).isNotNull();
		assertThat(secondRoomType.getId()).isNotNull();
	}

	@Test
	void findsInactiveMappingsForSynchronizationButExcludesThemFromSearch() {
		Property property = propertyRepository.save(Property.create(
			Supplier.SUPPLIER_B,
			"PROPERTY-1",
			"Property"
		));
		RoomType roomType = roomTypeRepository.save(RoomType.create(
			property,
			"ROOM-1",
			"Room",
			2
		));

		property.deactivate();
		roomTypeRepository.flush();

		assertThat(propertyRepository.findBySupplierAndSupplierPropertyCode(
			Supplier.SUPPLIER_B,
			"PROPERTY-1"
		)).isPresent();
		assertThat(roomTypeRepository.findByPropertyIdAndSupplierRoomTypeCode(
			property.getId(),
			"ROOM-1"
		)).isPresent();
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).isEmpty();
	}

	@Test
	void excludesInactiveRoomTypeFromSearch() {
		Property property = propertyRepository.save(Property.create(
			Supplier.SUPPLIER_A,
			"PROPERTY-1",
			"Property"
		));
		RoomType roomType = roomTypeRepository.save(RoomType.create(
			property,
			"ROOM-1",
			"Room",
			2
		));

		roomType.deactivate();
		roomTypeRepository.flush();

		assertThat(roomTypeRepository.findByPropertyIdAndSupplierRoomTypeCode(
			property.getId(),
			"ROOM-1"
		)).isPresent();
		assertThat(roomTypeRepository.findAllActiveMappingsForSearch()).isEmpty();
	}

	@Test
	void readsOnlyIdentifiersAndSupplierCodesForActiveSearchMappings() {
		Property property = propertyRepository.save(Property.create(
			Supplier.SUPPLIER_A,
			"PROPERTY-1",
			"Property"
		));
		RoomType activeRoomType = roomTypeRepository.save(RoomType.create(
			property,
			"ROOM-1",
			"Active Room",
			2
		));
		RoomType inactiveRoomType = roomTypeRepository.save(RoomType.create(
			property,
			"ROOM-2",
			"Inactive Room",
			4
		));
		inactiveRoomType.deactivate();
		roomTypeRepository.flush();

		assertThat(roomTypeRepository.findAllActiveMappingsForSearch())
			.containsExactly(new ActiveCatalogMapping(
				property.getId(),
				Supplier.SUPPLIER_A,
				"PROPERTY-1",
				activeRoomType.getId(),
				"ROOM-1"
			));
	}

}
