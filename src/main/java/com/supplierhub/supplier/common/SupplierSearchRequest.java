package com.supplierhub.supplier.common;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.SearchCriteria;

public record SupplierSearchRequest(
	Supplier supplier,
	SearchCriteria criteria,
	List<PropertyMapping> properties
) {

	public static final int MAX_PROPERTY_COUNT = 50;

	public SupplierSearchRequest {
		Objects.requireNonNull(supplier, "supplier must not be null");
		Objects.requireNonNull(criteria, "criteria must not be null");
		properties = List.copyOf(Objects.requireNonNull(
			properties,
			"properties must not be null"
		));
		if (properties.isEmpty() || properties.size() > MAX_PROPERTY_COUNT) {
			throw new IllegalArgumentException(
				"properties must contain between 1 and 50 mappings"
			);
		}
		requireUniquePropertyMappings(properties);
	}

	private static void requireUniquePropertyMappings(
		List<PropertyMapping> properties
	) {
		Set<Long> propertyIds = new HashSet<>();
		Set<String> supplierPropertyCodes = new HashSet<>();
		for (PropertyMapping property : properties) {
			Objects.requireNonNull(property, "property mapping must not be null");
			if (!propertyIds.add(property.propertyId())) {
				throw new IllegalArgumentException("property IDs must be unique");
			}
			if (!supplierPropertyCodes.add(property.supplierPropertyCode())) {
				throw new IllegalArgumentException(
					"Supplier property codes must be unique"
				);
			}
		}
	}

	public record PropertyMapping(
		long propertyId,
		String supplierPropertyCode,
		List<RoomTypeMapping> roomTypes
	) {

		public PropertyMapping {
			if (propertyId <= 0) {
				throw new IllegalArgumentException("propertyId must be positive");
			}
			supplierPropertyCode = requireText(
				supplierPropertyCode,
				"supplierPropertyCode"
			);
			roomTypes = List.copyOf(Objects.requireNonNull(
				roomTypes,
				"roomTypes must not be null"
			));
			if (roomTypes.isEmpty()) {
				throw new IllegalArgumentException("roomTypes must not be empty");
			}
			requireUniqueRoomTypeMappings(roomTypes);
		}

		private static void requireUniqueRoomTypeMappings(
			List<RoomTypeMapping> roomTypes
		) {
			Set<Long> roomTypeIds = new HashSet<>();
			Set<String> supplierRoomTypeCodes = new HashSet<>();
			for (RoomTypeMapping roomType : roomTypes) {
				Objects.requireNonNull(roomType, "roomType mapping must not be null");
				if (!roomTypeIds.add(roomType.roomTypeId())) {
					throw new IllegalArgumentException("room type IDs must be unique");
				}
				if (!supplierRoomTypeCodes.add(roomType.supplierRoomTypeCode())) {
					throw new IllegalArgumentException(
						"Supplier room type codes must be unique inside a property"
					);
				}
			}
		}
	}

	public record RoomTypeMapping(
		long roomTypeId,
		String supplierRoomTypeCode
	) {

		public RoomTypeMapping {
			if (roomTypeId <= 0) {
				throw new IllegalArgumentException("roomTypeId must be positive");
			}
			supplierRoomTypeCode = requireText(
				supplierRoomTypeCode,
				"supplierRoomTypeCode"
			);
		}
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}

}
