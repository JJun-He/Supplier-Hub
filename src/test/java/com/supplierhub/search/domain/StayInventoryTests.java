package com.supplierhub.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class StayInventoryTests {

	private static final SearchCriteria CRITERIA = new SearchCriteria(
		LocalDate.of(2026, 9, 1),
		LocalDate.of(2026, 9, 4),
		2,
		0
	);

	@Test
	void usesMinimumDailyInventoryForTheWholeStay() {
		StayInventory inventory = StayInventory.from(CRITERIA, List.of(
			inventory(1, 3),
			inventory(2, 1),
			inventory(3, 5)
		));

		assertThat(inventory.availableRooms()).isEqualTo(1);
		assertThat(inventory.isAvailable()).isTrue();
	}

	@Test
	void marksTheStayUnavailableWhenAnyNightIsSoldOut() {
		StayInventory inventory = StayInventory.from(CRITERIA, List.of(
			inventory(1, 2),
			inventory(2, 0),
			inventory(3, 4)
		));

		assertThat(inventory.availableRooms()).isZero();
		assertThat(inventory.isAvailable()).isFalse();
	}

	@Test
	void rejectsMissingDuplicateAndOutOfRangeDates() {
		assertThatThrownBy(() -> StayInventory.from(CRITERIA, List.of(
			inventory(1, 3),
			inventory(2, 1)
		))).isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> StayInventory.from(CRITERIA, List.of(
			inventory(1, 3),
			inventory(1, 1),
			inventory(3, 5)
		))).isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> StayInventory.from(CRITERIA, List.of(
			inventory(1, 3),
			inventory(2, 1),
			inventory(4, 5)
		))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNegativeDailyInventory() {
		assertThatThrownBy(() -> inventory(1, -1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private DailyInventory inventory(int day, int remainingRooms) {
		return new DailyInventory(
			LocalDate.of(2026, 9, day),
			remainingRooms
		);
	}

}
