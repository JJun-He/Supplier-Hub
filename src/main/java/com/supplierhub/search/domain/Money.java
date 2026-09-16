package com.supplierhub.search.domain;

import java.util.Currency;
import java.util.Objects;

import com.supplierhub.shared.InvalidValueException;

public record Money(Currency currency, long amount) {

	public Money {
		Objects.requireNonNull(currency, "currency must not be null");
		if (amount < 0) {
			throw new InvalidValueException("amount must not be negative");
		}
	}

	public static Money of(String currencyCode, long amount) {
		if (currencyCode == null || currencyCode.isBlank()) {
			throw new InvalidValueException("currencyCode must not be blank");
		}
		Currency currency;
		try {
			currency = Currency.getInstance(currencyCode);
		} catch (IllegalArgumentException exception) {
			throw new InvalidValueException("currencyCode must be ISO 4217", exception);
		}
		return new Money(currency, amount);
	}

	public Money add(Money other) {
		Objects.requireNonNull(other, "other must not be null");
		if (!currency.equals(other.currency)) {
			throw new InvalidValueException("currencies must match");
		}
		long sum;
		try {
			sum = Math.addExact(amount, other.amount);
		} catch (ArithmeticException exception) {
			throw new InvalidValueException("amount exceeds 64-bit range", exception);
		}
		return new Money(currency, sum);
	}

	public String currencyCode() {
		return currency.getCurrencyCode();
	}

}
