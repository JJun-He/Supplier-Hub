package com.supplierhub.search.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record StayInventory(int availableRooms) {

	public StayInventory {
		if (availableRooms < 0) {
			throw new IllegalArgumentException(
				"availableRooms must not be negative"
			);
		}
	}

	public static StayInventory from(
		SearchCriteria criteria,
		List<DailyInventory> dailyInventory
	) {
		Objects.requireNonNull(criteria, "criteria must not be null");
		List<DailyInventory> copiedInventory = List.copyOf(Objects.requireNonNull(
			dailyInventory,
			"dailyInventory must not be null"
		));
		List<LocalDate> dates = copiedInventory.stream()
			.map(DailyInventory::date)
			.toList();
		StayDateCoverage.requireExact(criteria, dates, "inventory");

		int availableRooms = copiedInventory.stream()
			.mapToInt(DailyInventory::remainingRooms)
			.min()
			.orElseThrow();
		return new StayInventory(availableRooms);
	}

	public boolean isAvailable() {
		return availableRooms > 0;
	}

}
