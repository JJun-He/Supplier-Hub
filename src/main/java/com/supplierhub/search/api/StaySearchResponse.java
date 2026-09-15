package com.supplierhub.search.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.application.IntegratedSearchResult;
import com.supplierhub.search.application.SearchOffer;
import com.supplierhub.search.application.SearchStatus;
import com.supplierhub.search.application.SupplierSearchOutcome;
import com.supplierhub.search.application.SupplierSearchStatus;
import com.supplierhub.search.domain.Offer;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.common.SupplierFailureType;

public record StaySearchResponse(
	SearchStatus status,
	SearchCriteriaResponse searchCriteria,
	List<StayResponse> stays,
	List<SupplierResultResponse> supplierResults
) {

	public StaySearchResponse {
		Objects.requireNonNull(status, "status must not be null");
		Objects.requireNonNull(searchCriteria, "searchCriteria must not be null");
		stays = List.copyOf(Objects.requireNonNull(
			stays,
			"stays must not be null"
		));
		supplierResults = List.copyOf(Objects.requireNonNull(
			supplierResults,
			"supplierResults must not be null"
		));
	}

	public static StaySearchResponse from(
		SearchCriteria criteria,
		IntegratedSearchResult result
	) {
		Map<Long, StayBuilder> stays = new LinkedHashMap<>();
		for (SearchOffer searchOffer : result.searchOffers()) {
			Offer offer = searchOffer.offer();
			StayBuilder stay = stays.computeIfAbsent(
				offer.propertyId(),
				ignored -> new StayBuilder(
					offer.propertyId(),
					searchOffer.propertyName()
				)
			);
			stay.add(searchOffer);
		}

		return new StaySearchResponse(
			result.status(),
			SearchCriteriaResponse.from(criteria),
			stays.values().stream().map(StayBuilder::build).toList(),
			result.supplierResults().stream()
				.map(SupplierResultResponse::from)
				.toList()
		);
	}

	public record SearchCriteriaResponse(
		LocalDate checkIn,
		LocalDate checkOut,
		int adults,
		int children
	) {

		private static SearchCriteriaResponse from(SearchCriteria criteria) {
			return new SearchCriteriaResponse(
				criteria.checkIn(),
				criteria.checkOut(),
				criteria.adults(),
				criteria.children()
			);
		}
	}

	public record StayResponse(
		long stayId,
		String stayName,
		List<RoomTypeResponse> roomTypes
	) {

		public StayResponse {
			Objects.requireNonNull(stayName, "stayName must not be null");
			roomTypes = List.copyOf(Objects.requireNonNull(
				roomTypes,
				"roomTypes must not be null"
			));
		}
	}

	public record RoomTypeResponse(
		long roomTypeId,
		String roomTypeName,
		int maxOccupancy,
		List<OfferResponse> offers
	) {

		public RoomTypeResponse {
			Objects.requireNonNull(roomTypeName, "roomTypeName must not be null");
			offers = List.copyOf(Objects.requireNonNull(
				offers,
				"offers must not be null"
			));
		}
	}

	public record OfferResponse(
		Supplier supplier,
		int availableRooms,
		boolean breakfastIncluded,
		PriceResponse price
	) {

		private static OfferResponse from(Offer offer) {
			return new OfferResponse(
				offer.supplier(),
				offer.availableRooms(),
				offer.breakfastIncluded(),
				new PriceResponse(
					offer.price().totalAmount().currencyCode(),
					offer.price().totalAmount().amount()
				)
			);
		}
	}

	public record PriceResponse(
		String currency,
		long totalAmountIncludingTax
	) {

		public PriceResponse {
			Objects.requireNonNull(currency, "currency must not be null");
		}
	}

	public record SupplierResultResponse(
		Supplier supplier,
		SupplierSearchStatus status,
		int acceptedOfferCount,
		int rejectedOfferCount,
		int unavailableOfferCount,
		List<SupplierFailureType> failureTypes
	) {

		public SupplierResultResponse {
			Objects.requireNonNull(supplier, "supplier must not be null");
			Objects.requireNonNull(status, "status must not be null");
			failureTypes = List.copyOf(Objects.requireNonNull(
				failureTypes,
				"failureTypes must not be null"
			));
		}

		private static SupplierResultResponse from(
			SupplierSearchOutcome result
		) {
			return new SupplierResultResponse(
				result.supplier(),
				result.status(),
				result.acceptedOfferCount(),
				result.rejectedOfferCount(),
				result.unavailableOfferCount(),
				result.failureTypes()
			);
		}
	}

	private static final class StayBuilder {

		private final long stayId;
		private final String stayName;
		private final Map<Long, RoomTypeBuilder> roomTypes = new LinkedHashMap<>();

		private StayBuilder(long stayId, String stayName) {
			this.stayId = stayId;
			this.stayName = stayName;
		}

		private void add(SearchOffer searchOffer) {
			Offer offer = searchOffer.offer();
			roomTypes.computeIfAbsent(
				offer.roomTypeId(),
				ignored -> new RoomTypeBuilder(
					offer.roomTypeId(),
					searchOffer.roomTypeName(),
					offer.maxOccupancy()
				)
			).offers.add(OfferResponse.from(offer));
		}

		private StayResponse build() {
			return new StayResponse(
				stayId,
				stayName,
				roomTypes.values().stream().map(RoomTypeBuilder::build).toList()
			);
		}
	}

	private static final class RoomTypeBuilder {

		private final long roomTypeId;
		private final String roomTypeName;
		private final int maxOccupancy;
		private final List<OfferResponse> offers = new ArrayList<>();

		private RoomTypeBuilder(
			long roomTypeId,
			String roomTypeName,
			int maxOccupancy
		) {
			this.roomTypeId = roomTypeId;
			this.roomTypeName = roomTypeName;
			this.maxOccupancy = maxOccupancy;
		}

		private RoomTypeResponse build() {
			return new RoomTypeResponse(
				roomTypeId,
				roomTypeName,
				maxOccupancy,
				offers
			);
		}
	}

}
