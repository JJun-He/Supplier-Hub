package com.supplierhub.search.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.supplierhub.shared.InvalidValueException;
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
		Set<Offer> offers = new LinkedHashSet<>();
		int rejectedOfferCount = 0;
		int unavailableOfferCount = 0;
		int duplicateOfferCount = 0;

		for (int index = 0; index < sourceItems.size(); index++) {
			OfferCandidate candidate;
			try {
				candidate = Objects.requireNonNull(
					candidateMapper.apply(sourceItems.get(index)), "candidate mapper must return a value"
				);
			} catch (OfferMappingException exception) {
				rejectedOfferCount++;
				logRejection(index, sourceSupplier, null, exception);
				continue;
			}
			try {
				if (candidate.supplier() != sourceSupplier) {
					throw new InvalidValueException(
						"candidate supplier must match the source supplier"
					);
				}
				Optional<Offer> offer = normalize(criteria, candidate);
				if (offer.isPresent()) {
					if (!offers.add(offer.orElseThrow())) {
						duplicateOfferCount++;
					}
				} else {
					unavailableOfferCount++;
				}
			} catch (InvalidValueException exception) {
				rejectedOfferCount++;
				logRejection(index, sourceSupplier, candidate, exception);
			}
		}

		rejectedOfferCount += rejectConflictingRoomCapacities(offers, sourceSupplier);
		return new OfferNormalizationResult(
			List.copyOf(offers),
			rejectedOfferCount,
			unavailableOfferCount,
			duplicateOfferCount
		);
	}

	private static int rejectConflictingRoomCapacities(Set<Offer> offers, Supplier supplier) {
		Map<Long, Integer> capacities = new HashMap<>();
		Set<Long> conflictingRoomIds = new HashSet<>();
		for (Offer offer : offers) {
			Integer previous = capacities.putIfAbsent(offer.roomTypeId(), offer.maxOccupancy());
			if (previous != null && previous != offer.maxOccupancy()) {
				conflictingRoomIds.add(offer.roomTypeId());
			}
		}
		int before = offers.size();
		offers.removeIf(offer -> conflictingRoomIds.contains(offer.roomTypeId()));
		int rejected = before - offers.size();
		if (rejected > 0) {
			log.warn(
				"Supplier offers rejected due to conflicting room capacities: sourceSupplier={}, roomCount={}, offerCount={}",
				supplier, conflictingRoomIds.size(), rejected
			);
		}
		return rejected;
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
