package com.supplierhub.catalog.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.supplierhub.shared.InvalidValueException;

public record CatalogSnapshot(
	Supplier supplier,
	List<CatalogProperty> properties
) {

	public CatalogSnapshot {
		Objects.requireNonNull(supplier, "supplier must not be null");
		properties = List.copyOf(Objects.requireNonNull(
			properties,
			"properties must not be null"
		));
		ensureUniquePropertyCodes(properties);
	}

	private static void ensureUniquePropertyCodes(
		List<CatalogProperty> properties
	) {
		Set<String> propertyCodes = new HashSet<>();
		for (CatalogProperty property : properties) {
			if (!propertyCodes.add(property.supplierPropertyCode())) {
				throw new InvalidValueException(
					"duplicate supplier property code"
				);
			}
		}
	}

	public record CatalogProperty(
		String supplierPropertyCode,
		String name,
		List<CatalogRoomType> roomTypes
	) {

		public CatalogProperty {
			supplierPropertyCode = requireText(
				supplierPropertyCode,
				"supplierPropertyCode"
			);
			if (supplierPropertyCode.contains(",")) {
				throw new InvalidValueException(
					"supplierPropertyCode must not contain a comma"
				);
			}
			name = requireText(name, "name");
			roomTypes = List.copyOf(Objects.requireNonNull(
				roomTypes,
				"roomTypes must not be null"
			));
			ensureUniqueRoomTypeCodes(roomTypes);
		}

		private static void ensureUniqueRoomTypeCodes(
			List<CatalogRoomType> roomTypes
		) {
			Set<String> roomTypeCodes = new HashSet<>();
			for (CatalogRoomType roomType : roomTypes) {
				if (!roomTypeCodes.add(roomType.supplierRoomTypeCode())) {
					throw new InvalidValueException(
						"duplicate supplier room type code"
					);
				}
			}
		}

	}

	public record CatalogRoomType(
		String supplierRoomTypeCode,
		String name,
		int maxOccupancy
	) {

		public CatalogRoomType {
			supplierRoomTypeCode = requireText(
				supplierRoomTypeCode,
				"supplierRoomTypeCode"
			);
			name = requireText(name, "name");
			if (maxOccupancy <= 0) {
				throw new InvalidValueException(
					"maxOccupancy must be positive"
				);
			}
		}

	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new InvalidValueException(fieldName + " must not be blank");
		}
		return value;
	}

}
