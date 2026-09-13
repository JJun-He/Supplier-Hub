package com.supplierhub.search.domain;

import java.util.List;
import java.util.Objects;

import com.supplierhub.catalog.domain.Supplier;

public record OfferCandidate(
	long propertyId,
	long roomTypeId,
	Supplier supplier,
	int maxOccupancy,
	boolean breakfastIncluded,
	Price price,
	List<DailyInventory> dailyInventory
) {

	public OfferCandidate {
		if (propertyId <= 0) {
			throw new IllegalArgumentException("propertyId must be positive");
		}
		if (roomTypeId <= 0) {
			throw new IllegalArgumentException("roomTypeId must be positive");
		}
		Objects.requireNonNull(supplier, "supplier must not be null");
		if (maxOccupancy <= 0) {
			throw new IllegalArgumentException("maxOccupancy must be positive");
		}
		Objects.requireNonNull(price, "price must not be null");
		dailyInventory = List.copyOf(Objects.requireNonNull(
			dailyInventory,
			"dailyInventory must not be null"
		));
	}

}
