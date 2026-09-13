package com.supplierhub.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.DailyInventory;
import com.supplierhub.search.domain.Money;
import com.supplierhub.search.domain.NightlyPrice;
import com.supplierhub.search.domain.OfferCandidate;
import com.supplierhub.search.domain.Price;
import com.supplierhub.search.domain.SearchCriteria;

@ExtendWith(OutputCaptureExtension.class)
class OfferNormalizerTests {

	private static final SearchCriteria CRITERIA = new SearchCriteria(
		LocalDate.of(2026, 9, 1),
		LocalDate.of(2026, 9, 4),
		2,
		0
	);

	@Test
	void createsAvailableOfferWithWholeStayInventory() {
		OfferNormalizationResult result = OfferNormalizer.normalize(
			CRITERIA,
			Supplier.SUPPLIER_B,
			List.of(candidate(2, totalPrice(), 3, 1, 5))
		);

		assertThat(result.rejectedOfferCount()).isZero();
		assertThat(result.unavailableOfferCount()).isZero();
		assertThat(result.offers()).singleElement().satisfies(offer -> {
			assertThat(offer.propertyId()).isEqualTo(10);
			assertThat(offer.roomTypeId()).isEqualTo(20);
			assertThat(offer.availableRooms()).isEqualTo(1);
			assertThat(offer.price().totalAmount().amount()).isEqualTo(452_000);
		});
	}

	@Test
	void excludesSoldOutOfferWithoutTreatingItAsInvalid() {
		OfferNormalizationResult result = OfferNormalizer.normalize(
			CRITERIA,
			Supplier.SUPPLIER_B,
			List.of(candidate(2, totalPrice(), 2, 0, 4))
		);

		assertThat(result.offers()).isEmpty();
		assertThat(result.rejectedOfferCount()).isZero();
		assertThat(result.unavailableOfferCount()).isEqualTo(1);
	}

	@Test
	void keepsValidSiblingAndLogsWhenAnotherOfferIsInvalid(
		CapturedOutput output
	) {
		OfferCandidate valid = candidate(2, totalPrice(), 3, 1, 5);
		OfferCandidate invalid = candidate(1, totalPrice(), 3, 1, 5);

		OfferNormalizationResult result = OfferNormalizer.normalize(
			CRITERIA,
			Supplier.SUPPLIER_B,
			List.of(valid, invalid)
		);

		assertThat(result.offers()).hasSize(1);
		assertThat(result.rejectedOfferCount()).isEqualTo(1);
		assertThat(result.hasRejectedOffers()).isTrue();
		assertThat(result.unavailableOfferCount()).isZero();
		assertThat(output)
			.contains("itemIndex=1")
			.contains("sourceSupplier=SUPPLIER_B")
			.contains("candidateSupplier=SUPPLIER_B")
			.contains("maxOccupancy must cover the requested guests");
	}

	@Test
	void isolatesFailureWhileMappingRawSupplierItem(CapturedOutput output) {
		List<RawOffer> rawOffers = List.of(
			new RawOffer("KRW", 452_000),
			new RawOffer("INVALID", 100_000)
		);

		OfferNormalizationResult result = OfferNormalizer.normalize(
			CRITERIA,
			Supplier.SUPPLIER_B,
			rawOffers,
			rawOffer -> candidate(
				2,
				Price.totalOnly(Money.of(
					rawOffer.currency(),
					rawOffer.totalAmount()
				)),
				3,
				1,
				5
			)
		);

		assertThat(result.offers()).hasSize(1);
		assertThat(result.rejectedOfferCount()).isEqualTo(1);
		assertThat(output)
			.contains("itemIndex=1")
			.contains("sourceSupplier=SUPPLIER_B")
			.contains("candidateSupplier=UNKNOWN")
			.contains("currencyCode must be ISO 4217");
	}

	@Test
	void rejectsAndLogsCandidateFromDifferentSupplier(CapturedOutput output) {
		OfferCandidate supplierACandidate = candidate(
			Supplier.SUPPLIER_A,
			2,
			totalPrice(),
			3,
			1,
			5
		);

		OfferNormalizationResult result = OfferNormalizer.normalize(
			CRITERIA,
			Supplier.SUPPLIER_B,
			List.of(supplierACandidate)
		);

		assertThat(result.offers()).isEmpty();
		assertThat(result.rejectedOfferCount()).isEqualTo(1);
		assertThat(output)
			.contains("sourceSupplier=SUPPLIER_B")
			.contains("candidateSupplier=SUPPLIER_A")
			.contains("candidate supplier must match the source supplier");
	}

	@Test
	void rejectsOfferWhoseNightlyPricesDoNotCoverTheStay() {
		Price incompletePrice = Price.fromNightlyPrices(List.of(
			new NightlyPrice(
				LocalDate.of(2026, 9, 1),
				Money.of("KRW", 100_000),
				Money.of("KRW", 10_000)
			),
			new NightlyPrice(
				LocalDate.of(2026, 9, 2),
				Money.of("KRW", 100_000),
				Money.of("KRW", 10_000)
			)
		));

		OfferNormalizationResult result = OfferNormalizer.normalize(
			CRITERIA,
			Supplier.SUPPLIER_B,
			List.of(candidate(2, incompletePrice, 3, 1, 5))
		);

		assertThat(result.offers()).isEmpty();
		assertThat(result.rejectedOfferCount()).isEqualTo(1);
	}

	private OfferCandidate candidate(
		int maxOccupancy,
		Price price,
		int firstNight,
		int secondNight,
		int thirdNight
	) {
		return candidate(
			Supplier.SUPPLIER_B,
			maxOccupancy,
			price,
			firstNight,
			secondNight,
			thirdNight
		);
	}

	private OfferCandidate candidate(
		Supplier supplier,
		int maxOccupancy,
		Price price,
		int firstNight,
		int secondNight,
		int thirdNight
	) {
		return new OfferCandidate(
			10,
			20,
			supplier,
			maxOccupancy,
			true,
			price,
			List.of(
				inventory(1, firstNight),
				inventory(2, secondNight),
				inventory(3, thirdNight)
			)
		);
	}

	private Price totalPrice() {
		return Price.totalOnly(Money.of("KRW", 452_000));
	}

	private DailyInventory inventory(int day, int remainingRooms) {
		return new DailyInventory(
			LocalDate.of(2026, 9, day),
			remainingRooms
		);
	}

	private record RawOffer(String currency, long totalAmount) {
	}

}
