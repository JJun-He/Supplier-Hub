package com.supplierhub.search.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Price(
	Money totalAmount,
	List<NightlyPrice> nightlyBreakdown
) {

	public Price {
		Objects.requireNonNull(totalAmount, "totalAmount must not be null");
		nightlyBreakdown = List.copyOf(Objects.requireNonNull(
			nightlyBreakdown,
			"nightlyBreakdown must not be null"
		));
		if (!nightlyBreakdown.isEmpty()) {
			Money calculatedTotal = calculateTotal(nightlyBreakdown);
			if (!totalAmount.equals(calculatedTotal)) {
				throw new IllegalArgumentException(
					"totalAmount must equal the nightly breakdown total"
				);
			}
		}
	}

	public static Price totalOnly(Money totalAmount) {
		return new Price(totalAmount, List.of());
	}

	public static Price fromNightlyPrices(List<NightlyPrice> nightlyPrices) {
		List<NightlyPrice> copiedPrices = List.copyOf(Objects.requireNonNull(
			nightlyPrices,
			"nightlyPrices must not be null"
		));
		if (copiedPrices.isEmpty()) {
			throw new IllegalArgumentException("nightlyPrices must not be empty");
		}
		return new Price(calculateTotal(copiedPrices), copiedPrices);
	}

	public void requireCoverage(SearchCriteria criteria) {
		Objects.requireNonNull(criteria, "criteria must not be null");
		if (nightlyBreakdown.isEmpty()) {
			return;
		}
		StayDateCoverage.requireExact(
			criteria,
			nightlyBreakdown.stream().map(NightlyPrice::date).toList(),
			"nightly price"
		);
	}

	public boolean hasNightlyBreakdown() {
		return !nightlyBreakdown.isEmpty();
	}

	private static Money calculateTotal(List<NightlyPrice> nightlyPrices) {
		Set<LocalDate> dates = new HashSet<>();
		Money total = null;
		for (NightlyPrice nightlyPrice : nightlyPrices) {
			if (!dates.add(nightlyPrice.date())) {
				throw new IllegalArgumentException("nightly price dates must be unique");
			}
			total = total == null
				? nightlyPrice.totalAmount()
				: total.add(nightlyPrice.totalAmount());
		}
		return total;
	}

}
