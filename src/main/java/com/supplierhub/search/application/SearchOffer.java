package com.supplierhub.search.application;

import java.util.Objects;

import com.supplierhub.search.domain.Offer;

public record SearchOffer(
	Offer offer,
	String propertyName,
	String roomTypeName
) {

	public SearchOffer {
		Objects.requireNonNull(offer, "offer must not be null");
		propertyName = requireText(propertyName, "propertyName");
		roomTypeName = requireText(roomTypeName, "roomTypeName");
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}

}
