package com.supplierhub.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.supplierhub.catalog.domain.Supplier;

class OfferCandidateTests {

	@Test
	void offerCanOnlyBeCreatedThroughItsValidationFactory() {
		assertThat(Offer.class.getDeclaredConstructors())
			.allSatisfy(constructor -> assertThat(
				Modifier.isPrivate(constructor.getModifiers())
			).isTrue());
	}

	@Test
	void requiresCompleteValidatedCandidateValues() {
		Price price = Price.totalOnly(Money.of("KRW", 100_000));
		List<DailyInventory> inventory = List.of(
			new DailyInventory(LocalDate.of(2026, 9, 1), 1)
		);

		assertThatThrownBy(() -> new OfferCandidate(
			1,
			2,
			null,
			2,
			false,
			price,
			inventory
		)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new OfferCandidate(
			1,
			2,
			Supplier.SUPPLIER_A,
			2,
			false,
			null,
			inventory
		)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new OfferCandidate(
			1,
			2,
			Supplier.SUPPLIER_A,
			2,
			false,
			price,
			null
		)).isInstanceOf(NullPointerException.class);
	}

}
