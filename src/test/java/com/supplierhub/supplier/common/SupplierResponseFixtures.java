package com.supplierhub.supplier.common;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.SearchCriteria;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class SupplierResponseFixtures {
	private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 1);

	private SupplierResponseFixtures() {}

	static SupplierSearchRequest request(int propertyCount, int rooms, int nights) {
		List<SupplierSearchRequest.PropertyMapping> properties = new ArrayList<>();
		for (int p = 0; p < propertyCount; p++) {
			List<SupplierSearchRequest.RoomTypeMapping> mappings = new ArrayList<>();
			for (int r = 0; r < rooms; r++)
				mappings.add(new SupplierSearchRequest.RoomTypeMapping(p * rooms + r + 1, "R" + r));
			properties.add(new SupplierSearchRequest.PropertyMapping(p + 1, "P" + p, mappings));
		}
		return new SupplierSearchRequest(
				Supplier.SUPPLIER_A,
				new SearchCriteria(CHECK_IN, CHECK_IN.plusDays(nights), 2, 0),
				properties);
	}

	static String catalogBody(int properties) {
		List<String> items = new ArrayList<>();
		for (int p = 0; p < properties; p++) {
			items.add(
					"{\"hotelCode\":\"P"
							+ p
							+ "\",\"hotelName\":\"Property"
							+ " name\",\"roomTypes\":[{\"roomTypeCode\":\"R0\",\"roomTypeName\":\"Room\",\"maxOccupancy\":2}]}");
		}
		return "{\"items\":[" + String.join(",", items) + "]}";
	}

	static String searchBody(int properties, int rooms, int nights) {
		List<String> days = new ArrayList<>();
		for (int day = 0; day < nights; day++) {
			days.add(
					"{\"date\":\""
							+ CHECK_IN.plusDays(day)
							+ "\",\"remainingRooms\":2,\"nightlyRate\":1000,\"taxAmount\":100}");
		}
		List<String> items = new ArrayList<>();
		for (int p = 0; p < properties; p++) {
			for (int r = 0; r < rooms; r++) {
				items.add(
						"{\"hotelCode\":\"P"
								+ p
								+ "\",\"roomTypeCode\":\"R"
								+ r
								+ "\",\"maxOccupancy\":2,\"breakfastIncluded\":false,\"currency\":\"KRW\",\"dailyRates\":["
								+ String.join(",", days)
								+ "]}");
			}
		}
		return "{\"items\":[" + String.join(",", items) + "]}";
	}
}
