package com.supplierhub.search.application;

import java.util.List;
import java.util.Objects;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.common.SupplierFailureType;

public record SupplierSearchOutcome(
	Supplier supplier,
	SupplierSearchStatus status,
	List<SearchOffer> searchOffers,
	int rejectedOfferCount,
	int unavailableOfferCount,
	List<SupplierFailureType> failureTypes
) {

	public SupplierSearchOutcome {
		Objects.requireNonNull(supplier, "supplier must not be null");
		Objects.requireNonNull(status, "status must not be null");
		searchOffers = List.copyOf(Objects.requireNonNull(
			searchOffers,
			"searchOffers must not be null"
		));
		for (SearchOffer searchOffer : searchOffers) {
			if (searchOffer.offer().supplier() != supplier) {
				throw new IllegalArgumentException(
					"searchOffers must belong to the outcome supplier"
				);
			}
		}
		failureTypes = List.copyOf(Objects.requireNonNull(
			failureTypes,
			"failureTypes must not be null"
		));
		if (rejectedOfferCount < 0 || unavailableOfferCount < 0) {
			throw new IllegalArgumentException("offer counts must not be negative");
		}
	}

	public int acceptedOfferCount() {
		return searchOffers.size();
	}

}
