package com.supplierhub.search.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.Offer;

public record IntegratedSearchResult(
	SearchStatus status,
	List<SupplierSearchOutcome> supplierResults
) {

	public IntegratedSearchResult {
		Objects.requireNonNull(status, "status must not be null");
		supplierResults = List.copyOf(Objects.requireNonNull(
			supplierResults,
			"supplierResults must not be null"
		));
		Set<Supplier> suppliers = new HashSet<>();
		for (SupplierSearchOutcome result : supplierResults) {
			Objects.requireNonNull(result, "supplier result must not be null");
			if (!suppliers.add(result.supplier())) {
				throw new IllegalArgumentException(
					"supplierResults must contain each supplier at most once"
				);
			}
		}
	}

	public List<Offer> offers() {
		return supplierResults.stream()
			.flatMap(result -> result.offers().stream())
			.toList();
	}

}
