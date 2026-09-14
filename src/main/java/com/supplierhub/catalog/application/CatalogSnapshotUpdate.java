package com.supplierhub.catalog.application;

public record CatalogSnapshotUpdate(
	int propertyCount,
	int roomTypeCount,
	int createdProperties,
	int createdRoomTypes,
	int reactivatedProperties,
	int reactivatedRoomTypes,
	int suspectedMissingProperties,
	int suspectedMissingRoomTypes,
	int deactivatedProperties,
	int deactivatedRoomTypes
) {
}
