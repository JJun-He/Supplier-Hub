package com.supplierhub.supplier.common;

import java.util.List;
import java.util.Objects;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.Offer;

public record SupplierSearchResult(
	Supplier supplier,
	List<Offer> offers,
	int rejectedOfferCount,
	int unavailableOfferCount
) {

	public SupplierSearchResult {
		Objects.requireNonNull(supplier, "supplier must not be null");
		offers = List.copyOf(Objects.requireNonNull(
			offers,
			"offers must not be null"
		));
		if (rejectedOfferCount < 0 || unavailableOfferCount < 0) {
			throw new IllegalArgumentException("offer counts must not be negative");
		}
	}

	public boolean hasRejectedOffers() {
		return rejectedOfferCount > 0;
	}

}
