package com.supplierhub.search.application;

public record SearchCatalogItem(
	long propertyId,
	String propertyName,
	long roomTypeId,
	String roomTypeName
) {

	public SearchCatalogItem {
		if (propertyId <= 0) {
			throw new IllegalArgumentException("propertyId must be positive");
		}
		if (roomTypeId <= 0) {
			throw new IllegalArgumentException("roomTypeId must be positive");
		}
		propertyName = requireText(propertyName, "propertyName");
		roomTypeName = requireText(roomTypeName, "roomTypeName");
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}

}
