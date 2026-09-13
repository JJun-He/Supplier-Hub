package com.supplierhub.search.domain;

import java.time.LocalDate;
import java.util.Objects;

public record DailyInventory(LocalDate date, int remainingRooms) {

	public DailyInventory {
		Objects.requireNonNull(date, "date must not be null");
		if (remainingRooms < 0) {
			throw new IllegalArgumentException(
				"remainingRooms must not be negative"
			);
		}
	}

}
