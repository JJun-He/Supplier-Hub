package com.supplierhub.supplier.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.IntStream;

import tools.jackson.databind.JsonNode;

import com.supplierhub.shared.InvalidValueException;

/** Reads external values without scalar coercion. Supplier field names stay in adapters. */
public final class SupplierJson {

	private SupplierJson() {
	}

	public static JsonNode field(JsonNode object, String name) {
		if (object == null || !object.isObject()) {
			throw new InvalidValueException("Supplier item must be an object");
		}
		JsonNode value = object.get(name);
		if (value == null || value.isNull()) {
			throw new InvalidValueException(name + " must not be null");
		}
		return value;
	}

	public static String text(JsonNode object, String name) {
		JsonNode value = field(object, name);
		if (!value.isString() || value.asString().isBlank()) {
			throw new InvalidValueException(name + " must be a non-blank string");
		}
		return value.asString();
	}

	public static int integer(JsonNode object, String name) {
		JsonNode value = field(object, name);
		if (!value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new InvalidValueException(name + " must be a 32-bit JSON integer");
		}
		return value.intValue();
	}

	public static long longInteger(JsonNode object, String name) {
		JsonNode value = field(object, name);
		if (!value.isIntegralNumber() || !value.canConvertToLong()) {
			throw new InvalidValueException(name + " must be a 64-bit JSON integer");
		}
		return value.longValue();
	}

	public static boolean bool(JsonNode object, String name) {
		JsonNode value = field(object, name);
		if (!value.isBoolean()) {
			throw new InvalidValueException(name + " must be a JSON boolean");
		}
		return value.booleanValue();
	}

	public static LocalDate date(JsonNode object, String name) {
		try {
			return LocalDate.parse(text(object, name));
		} catch (DateTimeParseException exception) {
			throw new InvalidValueException(name + " must be an ISO local date", exception);
		}
	}

	public static List<JsonNode> array(JsonNode object, String name) {
		JsonNode value = field(object, name);
		if (!value.isArray()) {
			throw new InvalidValueException(name + " must be an array");
		}
		return IntStream.range(0, value.size()).mapToObj(value::get).toList();
	}
}
