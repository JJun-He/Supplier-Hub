package com.supplierhub.search.application;

import java.util.List;
import java.util.Objects;

import com.supplierhub.search.domain.Offer;

public record OfferNormalizationResult(
	List<Offer> offers,
	int rejectedOfferCount,
	int unavailableOfferCount,
	int duplicateOfferCount
) {

	public OfferNormalizationResult {
		offers = List.copyOf(Objects.requireNonNull(
			offers,
			"offers must not be null"
		));
		if (rejectedOfferCount < 0) {
			throw new IllegalArgumentException(
				"rejectedOfferCount must not be negative"
			);
		}
		if (unavailableOfferCount < 0 || duplicateOfferCount < 0) {
			throw new IllegalArgumentException(
				"unavailableOfferCount must not be negative"
			);
		}
	}

	public boolean hasRejectedOffers() {
		return rejectedOfferCount > 0;
	}

}
