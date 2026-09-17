package com.supplierhub.search.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.supplierhub.supplier.common.SearchCallContext;

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
			Function.identity(),
			SearchCallContext.direct(),
			ignored -> OfferSourceReference.unknown()
		);
	}

	public static <T> OfferNormalizationResult normalize(
		SearchCriteria criteria,
		Supplier sourceSupplier,
		List<T> sourceItems,
		Function<? super T, OfferCandidate> candidateMapper
	) {
		return normalizeItems(
			criteria,
			sourceSupplier,
			sourceItems,
			candidateMapper,
			SearchCallContext.direct(),
			ignored -> OfferSourceReference.unknown()
		);
	}

	public static <T> OfferNormalizationResult normalize(
		SearchCriteria criteria,
		Supplier sourceSupplier,
		List<T> sourceItems,
		Function<? super T, OfferCandidate> candidateMapper,
		SearchCallContext context,
		Function<? super T, OfferSourceReference> referenceMapper
	) {
		return normalizeItems(criteria, sourceSupplier, sourceItems, candidateMapper, context, referenceMapper);
	}

	private static <T> OfferNormalizationResult normalizeItems(
		SearchCriteria criteria,
		Supplier sourceSupplier,
		List<T> sourceItems,
		Function<? super T, OfferCandidate> candidateMapper,
		SearchCallContext context,
		Function<? super T, OfferSourceReference> referenceMapper
	) {
		Objects.requireNonNull(criteria, "criteria must not be null");
		Objects.requireNonNull(sourceSupplier, "sourceSupplier must not be null");
		Objects.requireNonNull(sourceItems, "sourceItems must not be null");
		Objects.requireNonNull(candidateMapper, "candidateMapper must not be null");
		Objects.requireNonNull(context, "context must not be null");
		Objects.requireNonNull(referenceMapper, "referenceMapper must not be null");
		Map<Offer, Integer> offers = new LinkedHashMap<>();
		RejectionLog<T> rejectionLog = new RejectionLog<>(sourceSupplier, context, sourceItems, referenceMapper);
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
				rejectionLog.rejected(index, null, exception);
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
					if (offers.putIfAbsent(offer.orElseThrow(), index) != null) {
						duplicateOfferCount++;
					}
				} else {
					unavailableOfferCount++;
				}
			} catch (InvalidValueException exception) {
				rejectedOfferCount++;
				rejectionLog.rejected(index, candidate, exception);
			}
		}

		rejectedOfferCount += rejectConflictingRoomCapacities(offers, rejectionLog);
		rejectionLog.summary(rejectedOfferCount);
		return new OfferNormalizationResult(
			List.copyOf(offers.keySet()),
			rejectedOfferCount,
			unavailableOfferCount,
			duplicateOfferCount
		);
	}

	private static int rejectConflictingRoomCapacities(Map<Offer, Integer> offers, RejectionLog<?> rejectionLog) {
		Map<Long, Integer> capacities = new HashMap<>();
		Set<Long> conflictingRoomIds = new HashSet<>();
		for (Offer offer : offers.keySet()) {
			Integer previous = capacities.putIfAbsent(offer.roomTypeId(), offer.maxOccupancy());
			if (previous != null && previous != offer.maxOccupancy()) {
				conflictingRoomIds.add(offer.roomTypeId());
			}
		}
		int before = offers.size();
		offers.entrySet().removeIf(entry -> {
			Offer offer = entry.getKey();
			if (!conflictingRoomIds.contains(offer.roomTypeId())) {
				return false;
			}
			rejectionLog.rejected(entry.getValue(), offer.supplier(), offer.propertyId(), offer.roomTypeId(),
				"InvalidValueException", "conflicting room capacities for the same room type");
			return true;
		});
		return before - offers.size();
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

	private static final class RejectionLog<T> {

		private static final int MAX_SAMPLES = 5;

		private final Supplier supplier;
		private final SearchCallContext context;
		private final List<T> sourceItems;
		private final Function<? super T, OfferSourceReference> referenceMapper;
		private int sampledCount;

		private RejectionLog(Supplier supplier, SearchCallContext context, List<T> sourceItems,
			Function<? super T, OfferSourceReference> referenceMapper) {
			this.supplier = supplier;
			this.context = context;
			this.sourceItems = sourceItems;
			this.referenceMapper = referenceMapper;
		}

		private void rejected(int index, OfferCandidate candidate, RuntimeException exception) {
			Throwable reason = exception instanceof OfferMappingException && exception.getCause() != null
				? exception.getCause() : exception;
			rejected(index, candidate == null ? null : candidate.supplier(),
				candidate == null ? null : candidate.propertyId(),
				candidate == null ? null : candidate.roomTypeId(),
				reason.getClass().getSimpleName(), reason.getMessage());
		}

		private void rejected(int index, Supplier candidateSupplier, Long propertyId, Long roomTypeId,
			String reasonType, String reason) {
			if (sampledCount >= MAX_SAMPLES) {
				return;
			}
			OfferSourceReference reference = referenceMapper.apply(sourceItems.get(index));
			if (reference == null) {
				reference = OfferSourceReference.unknown();
			}
			log.warn(
				"Supplier offer candidate rejected: sourceSupplier={}, searchId={}, batchIndex={}, itemIndex={}, supplierPropertyCode={}, supplierRoomTypeCode={}, candidateSupplier={}, propertyId={}, roomTypeId={}, reasonType={}, reason={}",
				supplier, OfferSourceReference.safeText(context.searchId(), 80), context.batchIndex(), index,
				reference.supplierPropertyCode(), reference.supplierRoomTypeCode(),
				candidateSupplier == null ? "UNKNOWN" : candidateSupplier, propertyId, roomTypeId,
				reasonType, OfferSourceReference.safeText(reason, 240)
			);
			sampledCount++;
		}

		private void summary(int rejectedCount) {
			if (rejectedCount > 0) {
				log.warn(
					"Supplier offer rejection summary: sourceSupplier={}, searchId={}, batchIndex={}, rejectedOfferCount={}, sampledCount={}, omittedCount={}",
					supplier, OfferSourceReference.safeText(context.searchId(), 80), context.batchIndex(),
					rejectedCount, sampledCount, rejectedCount - sampledCount
				);
			}
		}
	}

}
