package com.supplierhub.search.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class StayDateCoverage {

	private StayDateCoverage() {
	}

	static void requireExact(
		SearchCriteria criteria,
		List<LocalDate> dates,
		String valueName
	) {
		if (dates.size() != criteria.nightCount()) {
			throw new IllegalArgumentException(
				valueName + " dates must cover the entire stay"
			);
		}

		Set<LocalDate> uniqueDates = new HashSet<>();
		for (LocalDate date : dates) {
			if (!criteria.containsStayDate(date)) {
				throw new IllegalArgumentException(
					valueName + " date is outside the stay"
				);
			}
			if (!uniqueDates.add(date)) {
				throw new IllegalArgumentException(
					valueName + " dates must not contain duplicates"
				);
			}
		}
	}

}
