package com.supplierhub.search.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.supplierhub.catalog.domain.Supplier;

public final class Offer {

	private final long propertyId;
	private final long roomTypeId;
	private final Supplier supplier;
	private final int maxOccupancy;
	private final int availableRooms;
	private final boolean breakfastIncluded;
	private final Price price;

	private Offer(
		long propertyId,
		long roomTypeId,
		Supplier supplier,
		int maxOccupancy,
		int availableRooms,
		boolean breakfastIncluded,
		Price price
	) {
		this.propertyId = propertyId;
		this.roomTypeId = roomTypeId;
		this.supplier = supplier;
		this.maxOccupancy = maxOccupancy;
		this.availableRooms = availableRooms;
		this.breakfastIncluded = breakfastIncluded;
		this.price = price;
	}

	public static Optional<Offer> createAvailable(
		long propertyId,
		long roomTypeId,
		Supplier supplier,
		int maxOccupancy,
		boolean breakfastIncluded,
		Price price,
		SearchCriteria criteria,
		List<DailyInventory> dailyInventory
	) {
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
		Objects.requireNonNull(criteria, "criteria must not be null");
		Objects.requireNonNull(price, "price must not be null");
		if (maxOccupancy < criteria.guestCount()) {
			return Optional.empty();
		}

		price.requireCoverage(criteria);
		StayInventory inventory = StayInventory.from(criteria, dailyInventory);
		if (!inventory.isAvailable()) {
			return Optional.empty();
		}

		return Optional.of(new Offer(
			propertyId,
			roomTypeId,
			supplier,
			maxOccupancy,
			inventory.availableRooms(),
			breakfastIncluded,
			price
		));
	}

	public long propertyId() {
		return propertyId;
	}

	public long roomTypeId() {
		return roomTypeId;
	}

	public Supplier supplier() {
		return supplier;
	}

	public int maxOccupancy() {
		return maxOccupancy;
	}

	public int availableRooms() {
		return availableRooms;
	}

	public boolean breakfastIncluded() {
		return breakfastIncluded;
	}

	public Price price() {
		return price;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof Offer offer)) {
			return false;
		}
		return propertyId == offer.propertyId
			&& roomTypeId == offer.roomTypeId
			&& maxOccupancy == offer.maxOccupancy
			&& availableRooms == offer.availableRooms
			&& breakfastIncluded == offer.breakfastIncluded
			&& supplier == offer.supplier
			&& price.equals(offer.price);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
			propertyId,
			roomTypeId,
			supplier,
			maxOccupancy,
			availableRooms,
			breakfastIncluded,
			price
		);
	}

	@Override
	public String toString() {
		return "Offer["
			+ "propertyId=" + propertyId
			+ ", roomTypeId=" + roomTypeId
			+ ", supplier=" + supplier
			+ ", maxOccupancy=" + maxOccupancy
			+ ", availableRooms=" + availableRooms
			+ ", breakfastIncluded=" + breakfastIncluded
			+ ", price=" + price
			+ "]";
	}

}
