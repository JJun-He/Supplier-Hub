package com.supplierhub.catalog.application;

import com.supplierhub.catalog.domain.Supplier;

public record ActiveCatalogMapping(
	long propertyId,
	Supplier supplier,
	String supplierPropertyCode,
	long roomTypeId,
	String supplierRoomTypeCode
) {
}
