package com.supplierhub.search.application;

import java.util.List;
import java.util.Objects;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.Offer;
import com.supplierhub.supplier.common.SupplierFailureType;

public record SupplierSearchOutcome(
	Supplier supplier,
	SupplierSearchStatus status,
	List<Offer> offers,
	int rejectedOfferCount,
	int unavailableOfferCount,
	List<SupplierFailureType> failureTypes
) {

	public SupplierSearchOutcome {
		Objects.requireNonNull(supplier, "supplier must not be null");
		Objects.requireNonNull(status, "status must not be null");
		offers = List.copyOf(Objects.requireNonNull(
			offers,
			"offers must not be null"
		));
		failureTypes = List.copyOf(Objects.requireNonNull(
			failureTypes,
			"failureTypes must not be null"
		));
		if (rejectedOfferCount < 0 || unavailableOfferCount < 0) {
			throw new IllegalArgumentException("offer counts must not be negative");
		}
	}

	public int acceptedOfferCount() {
		return offers.size();
	}

}
