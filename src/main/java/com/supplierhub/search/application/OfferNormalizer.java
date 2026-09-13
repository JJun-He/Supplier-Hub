package com.supplierhub.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.Offer;
import com.supplierhub.search.domain.OfferCandidate;
import com.supplierhub.search.domain.SearchCriteria;

public final class OfferNormalizer {

	private static final Logger log = LoggerFactory.getLogger(
		OfferNormalizer.class
	);

	private OfferNormalizer() {
	}

	public static OfferNormalizationResult normalize(
		SearchCriteria criteria,
		Supplier sourceSupplier,
		List<OfferCandidate> candidates
	) {
		return normalizeItems(
			criteria,
			sourceSupplier,
			candidates,
			Function.identity()
		);
	}

	public static <T> OfferNormalizationResult normalize(
		SearchCriteria criteria,
		Supplier sourceSupplier,
		List<T> sourceItems,
		Function<? super T, OfferCandidate> candidateMapper
	) {
		Objects.requireNonNull(sourceSupplier, "sourceSupplier must not be null");
		return normalizeItems(
			criteria,
			sourceSupplier,
			sourceItems,
			candidateMapper
		);
	}

	private static <T> OfferNormalizationResult normalizeItems(
		SearchCriteria criteria,
		Supplier sourceSupplier,
		List<T> sourceItems,
		Function<? super T, OfferCandidate> candidateMapper
	) {
		Objects.requireNonNull(criteria, "criteria must not be null");
		Objects.requireNonNull(sourceSupplier, "sourceSupplier must not be null");
		Objects.requireNonNull(sourceItems, "sourceItems must not be null");
		Objects.requireNonNull(candidateMapper, "candidateMapper must not be null");
		List<Offer> offers = new ArrayList<>();
		int rejectedOfferCount = 0;
		int unavailableOfferCount = 0;

		for (int index = 0; index < sourceItems.size(); index++) {
			OfferCandidate candidate = null;
			try {
				candidate = candidateMapper.apply(sourceItems.get(index));
				if (candidate.supplier() != sourceSupplier) {
					throw new IllegalArgumentException(
						"candidate supplier must match the source supplier"
					);
				}
				Optional<Offer> offer = normalize(criteria, candidate);
				if (offer.isPresent()) {
					offers.add(offer.orElseThrow());
				} else {
					unavailableOfferCount++;
				}
			} catch (
				IllegalArgumentException
					| ArithmeticException
					| NullPointerException exception
			) {
				rejectedOfferCount++;
				logRejection(index, sourceSupplier, candidate, exception);
			}
		}

		return new OfferNormalizationResult(
			offers,
			rejectedOfferCount,
			unavailableOfferCount
		);
	}

	private static Optional<Offer> normalize(
		SearchCriteria criteria,
		OfferCandidate candidate
	) {
		return Offer.createAvailable(
			candidate.propertyId(),
			candidate.roomTypeId(),
			candidate.supplier(),
			candidate.maxOccupancy(),
			candidate.breakfastIncluded(),
			candidate.price(),
			criteria,
			candidate.dailyInventory()
		);
	}

	private static void logRejection(
		int index,
		Supplier sourceSupplier,
		OfferCandidate candidate,
		RuntimeException exception
	) {
		if (candidate == null) {
			log.warn(
				"Supplier offer candidate rejected: itemIndex={}, sourceSupplier={}, candidateSupplier=UNKNOWN",
				index,
				sourceSupplier,
				exception
			);
			return;
		}
		log.warn(
			"Supplier offer candidate rejected: itemIndex={}, sourceSupplier={}, candidateSupplier={}, propertyId={}, roomTypeId={}",
			index,
			sourceSupplier,
			candidate.supplier(),
			candidate.propertyId(),
			candidate.roomTypeId(),
			exception
		);
	}

}
