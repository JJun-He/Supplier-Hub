package com.supplierhub.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SearchCriteriaTests {

	@Test
	void exposesStayDatesWithoutCheckoutDate() {
		SearchCriteria criteria = new SearchCriteria(
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 9, 4),
			2,
			0
		);

		assertThat(criteria.nightCount()).isEqualTo(3);
		assertThat(criteria.guestCount()).isEqualTo(2);
		assertThat(criteria.stayDates()).containsExactly(
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 9, 2),
			LocalDate.of(2026, 9, 3)
		);
	}

	@Test
	void requiresCheckoutAfterCheckin() {
		LocalDate checkIn = LocalDate.of(2026, 9, 1);

		assertThatThrownBy(() -> new SearchCriteria(
			checkIn,
			checkIn,
			1,
			0
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SearchCriteria(
			checkIn,
			checkIn.minusDays(1),
			1,
			0
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresNonNegativeGuestCounts() {
		LocalDate checkIn = LocalDate.of(2026, 9, 1);
		LocalDate checkOut = checkIn.plusDays(1);

		assertThatThrownBy(() -> new SearchCriteria(
			checkIn,
			checkOut,
			-1,
			1
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SearchCriteria(
			checkIn,
			checkOut,
			1,
			-1
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresAtLeastOneGuest() {
		LocalDate checkIn = LocalDate.of(2026, 9, 1);

		assertThatThrownBy(() -> new SearchCriteria(
			checkIn,
			checkIn.plusDays(1),
			0,
			0
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void allowsSearchWithChildrenAndNoAdults() {
		LocalDate checkIn = LocalDate.of(2026, 9, 1);

		SearchCriteria criteria = new SearchCriteria(
			checkIn,
			checkIn.plusDays(1),
			0,
			1
		);

		assertThat(criteria.guestCount()).isEqualTo(1);
	}

}
