package com.supplierhub.search.application;

/** 파싱 성공 여부와 무관하게 기록할 수 있는, 길이가 제한된 외부 상품 식별 정보다. */
public record OfferSourceReference(String supplierPropertyCode, String supplierRoomTypeCode) {

	public OfferSourceReference {
		supplierPropertyCode = safeText(supplierPropertyCode, 120);
		supplierRoomTypeCode = safeText(supplierRoomTypeCode, 120);
	}

	public static OfferSourceReference unknown() {
		return new OfferSourceReference(null, null);
	}

	static String safeText(String value, int maxLength) {
		if (value == null || value.isBlank()) {
			return "UNKNOWN";
		}
		int end = Math.min(value.length(), maxLength);
		StringBuilder safe = new StringBuilder(end);
		for (int index = 0; index < end; index++) {
			char character = value.charAt(index);
			safe.append(Character.isISOControl(character)
				|| Character.getType(character) == Character.FORMAT
				|| character == '\u2028' || character == '\u2029' ? ' ' : character);
		}
		if (value.length() > maxLength) {
			safe.replace(maxLength - 3, maxLength, "...");
		}
		return safe.toString();
	}
}
