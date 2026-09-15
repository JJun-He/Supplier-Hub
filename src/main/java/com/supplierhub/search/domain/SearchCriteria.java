package com.supplierhub.search.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.stream.Stream;

public record SearchCriteria(
	LocalDate checkIn,
	LocalDate checkOut,
	int adults,
	int children
) {
	public static final long MAX_NIGHTS = 30;

	public SearchCriteria {
		Objects.requireNonNull(checkIn, "checkIn must not be null");
		Objects.requireNonNull(checkOut, "checkOut must not be null");
		if (!checkOut.isAfter(checkIn)) {
			throw new IllegalArgumentException("checkOut must be after checkIn");
		}
		if (ChronoUnit.DAYS.between(checkIn, checkOut) > MAX_NIGHTS) {
			throw new IllegalArgumentException(
				"stay must not exceed " + MAX_NIGHTS + " nights"
			);
		}
		if (adults < 0) {
			throw new IllegalArgumentException("adults must not be negative");
		}
		if (children < 0) {
			throw new IllegalArgumentException("children must not be negative");
		}
		long guestCount = (long) adults + children;
		if (guestCount < 1 || guestCount > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("guest count is out of range");
		}
	}

	public int guestCount() {
		return adults + children;
	}

	public long nightCount() {
		return ChronoUnit.DAYS.between(checkIn, checkOut);
	}

	public Stream<LocalDate> stayDates() {
		return checkIn.datesUntil(checkOut);
	}

	public boolean containsStayDate(LocalDate date) {
		return date != null
			&& !date.isBefore(checkIn)
			&& date.isBefore(checkOut);
	}

}
