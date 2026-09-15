package com.supplierhub.catalog.application;

import com.supplierhub.catalog.domain.Supplier;

public record ActiveCatalogMapping(
	long propertyId,
	Supplier supplier,
	String supplierPropertyCode,
	String propertyName,
	long roomTypeId,
	String supplierRoomTypeCode,
	String roomTypeName
) {
}
