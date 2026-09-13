package com.supplierhub.search.domain;

import java.util.Currency;
import java.util.Objects;

public record Money(Currency currency, long amount) {

	public Money {
		Objects.requireNonNull(currency, "currency must not be null");
		if (amount < 0) {
			throw new IllegalArgumentException("amount must not be negative");
		}
	}

	public static Money of(String currencyCode, long amount) {
		if (currencyCode == null || currencyCode.isBlank()) {
			throw new IllegalArgumentException("currencyCode must not be blank");
		}
		Currency currency;
		try {
			currency = Currency.getInstance(currencyCode);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("currencyCode must be ISO 4217", exception);
		}
		return new Money(currency, amount);
	}

	public Money add(Money other) {
		Objects.requireNonNull(other, "other must not be null");
		if (!currency.equals(other.currency)) {
			throw new IllegalArgumentException("currencies must match");
		}
		return new Money(currency, Math.addExact(amount, other.amount));
	}

	public String currencyCode() {
		return currency.getCurrencyCode();
	}

}
