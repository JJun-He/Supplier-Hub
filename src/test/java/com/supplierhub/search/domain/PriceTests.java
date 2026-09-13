package com.supplierhub.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class PriceTests {

	@Test
	void sumsNightlyNetRatesAndTaxesAsGrossStayTotal() {
		Price price = Price.fromNightlyPrices(List.of(
			nightlyPrice(1, 120_000, 12_000),
			nightlyPrice(2, 150_000, 15_000),
			nightlyPrice(3, 120_000, 12_000)
		));

		assertThat(price.totalAmount().currencyCode()).isEqualTo("KRW");
		assertThat(price.totalAmount().amount()).isEqualTo(429_000);
		assertThat(price.hasNightlyBreakdown()).isTrue();
	}

	@Test
	void preservesTaxIncludedStayTotalWithoutInventingNightlyPrices() {
		Price price = Price.totalOnly(Money.of("KRW", 452_000));

		assertThat(price.totalAmount().amount()).isEqualTo(452_000);
		assertThat(price.nightlyBreakdown()).isEmpty();
		assertThat(price.hasNightlyBreakdown()).isFalse();
	}

	@Test
	void rejectsDifferentBaseAndTaxCurrencies() {
		assertThatThrownBy(() -> new NightlyPrice(
			LocalDate.of(2026, 9, 1),
			Money.of("KRW", 100_000),
			Money.of("USD", 10_000)
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsDuplicateNightlyPriceDates() {
		NightlyPrice nightlyPrice = nightlyPrice(1, 100_000, 10_000);

		assertThatThrownBy(() -> Price.fromNightlyPrices(List.of(
			nightlyPrice,
			nightlyPrice
		))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNegativeAmountsAndInvalidCurrencyCodes() {
		assertThatThrownBy(() -> Money.of("KRW", -1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Money.of("NOT_A_CURRENCY", 1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private NightlyPrice nightlyPrice(int day, long base, long tax) {
		return new NightlyPrice(
			LocalDate.of(2026, 9, day),
			Money.of("KRW", base),
			Money.of("KRW", tax)
		);
	}

}
