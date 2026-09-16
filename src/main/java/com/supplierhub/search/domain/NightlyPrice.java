package com.supplierhub.search.domain;

import java.time.LocalDate;
import java.util.Objects;

import com.supplierhub.shared.InvalidValueException;

public record NightlyPrice(
	LocalDate date,
	Money baseAmount,
	Money taxAmount
) {

	public NightlyPrice {
		Objects.requireNonNull(date, "date must not be null");
		Objects.requireNonNull(baseAmount, "baseAmount must not be null");
		Objects.requireNonNull(taxAmount, "taxAmount must not be null");
		if (!baseAmount.currency().equals(taxAmount.currency())) {
			throw new InvalidValueException(
				"base and tax currencies must match"
			);
		}
	}

	public Money totalAmount() {
		return baseAmount.add(taxAmount);
	}

}
