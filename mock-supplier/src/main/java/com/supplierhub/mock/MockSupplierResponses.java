package com.supplierhub.mock;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class MockSupplierResponses {

	static final String A_HOTELS = """
			{
			  "items": [
			    {
			      "hotelCode": "A-10023",
			      "hotelName": "Riverside Hotel Seoul",
			      "roomTypes": [
			        {
			          "roomTypeCode": "DLX-TWN",
			          "roomTypeName": "Deluxe Twin",
			          "maxOccupancy": 2
			        }
			      ]
			    },
			    {
			      "hotelCode": "A-10044",
			      "hotelName": "Namsan Garden Stay",
			      "roomTypes": [
			        {
			          "roomTypeCode": "STD-DBL",
			          "roomTypeName": "Standard Double",
			          "maxOccupancy": 2
			        }
			      ]
			    }
			  ]
			}
			""";

	static final String B_PROPERTIES = """
			{
			  "resultCode": "0000",
			  "resultMessage": "SUCCESS",
			  "data": {
			    "items": [
			      {
			        "propertyId": "B77120",
			        "propertyName": "Riverside Hotel Seoul",
			        "rooms": [
			          {
			            "roomId": "R-401",
			            "roomName": "Deluxe Twin Room",
			            "maxOccupancy": 2
			          }
			        ]
			      }
			    ]
			  }
			}
			""";

	static final String A_SERVICE_UNAVAILABLE = """
			{"error":"SERVICE_UNAVAILABLE","message":"temporarily unavailable"}
			""";

	static final String A_INVALID_SCENARIO = """
			{"error":"INVALID_PARAMETER","message":"unsupported X-Mock-Scenario"}
			""";

	static final String B_SERVICE_UNAVAILABLE = """
			{"resultCode":"E503","resultMessage":"TEMPORARILY_UNAVAILABLE","data":null}
			""";

	static final String B_INVALID_SCENARIO = """
			{"resultCode":"E400","resultMessage":"INVALID_PARAMETER","data":null}
			""";

	static String aAvailability(
		String hotelCodes,
		LocalDate checkIn,
		LocalDate checkOut
	) {
		Set<String> requestedCodes = requestedCodes(hotelCodes);
		List<LocalDate> dates = stayDates(checkIn, checkOut);
		List<String> items = new java.util.ArrayList<>();
		if (requestedCodes.contains("A-10023")) {
			items.add(aItem(
				"A-10023",
				"Riverside Hotel Seoul",
				"DLX-TWN",
				"Deluxe Twin",
				dates,
				new int[] {3, 1, 5},
				new long[] {120_000, 150_000, 120_000}
			));
		}
		if (requestedCodes.contains("A-10044")) {
			items.add(aItem(
				"A-10044",
				"Namsan Garden Stay",
				"STD-DBL",
				"Standard Double",
				dates,
				new int[] {2, 0, 4},
				new long[] {88_000, 99_000, 88_000}
			));
		}
		return "{\"items\":[" + String.join(",", items) + "]}";
	}

	static String bSearch(
		String propertyIds,
		LocalDate checkIn,
		LocalDate checkOut
	) {
		Set<String> requestedCodes = requestedCodes(propertyIds);
		List<LocalDate> dates = stayDates(checkIn, checkOut);
		String items = requestedCodes.contains("B77120")
			? bItem(dates)
			: "";
		return """
			{"resultCode":"0000","resultMessage":"SUCCESS","data":{"items":[%s]}}
			""".formatted(items);
	}

	private static String aItem(
		String hotelCode,
		String hotelName,
		String roomTypeCode,
		String roomTypeName,
		List<LocalDate> dates,
		int[] inventories,
		long[] nightlyRates
	) {
		String dailyRates = IntStream.range(0, dates.size())
			.mapToObj(index -> """
				{"date":"%s","remainingRooms":%d,"nightlyRate":%d,"taxAmount":%d}
				""".formatted(
					dates.get(index),
					inventories[index % inventories.length],
					nightlyRates[index % nightlyRates.length],
					nightlyRates[index % nightlyRates.length] / 10
				).trim())
			.collect(Collectors.joining(","));
		return """
			{"hotelCode":"%s","hotelName":"%s","roomTypeCode":"%s","roomTypeName":"%s","maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW","dailyRates":[%s]}
			""".formatted(
				hotelCode,
				hotelName,
				roomTypeCode,
				roomTypeName,
				dailyRates
			).trim();
	}

	private static String bItem(List<LocalDate> dates) {
		int[] inventories = {3, 1, 5};
		String inventory = IntStream.range(0, dates.size())
			.mapToObj(index -> """
				{"date":"%s","remainingRooms":%d}
				""".formatted(
					dates.get(index),
					inventories[index % inventories.length]
				).trim())
			.collect(Collectors.joining(","));
		long totalPrice = Math.addExact(
			Math.multiplyExact(150_000L, dates.size()),
			2_000L
		);
		return """
			{"propertyId":"B77120","propertyName":"Riverside Hotel Seoul","roomId":"R-401","roomName":"Deluxe Twin Room","maxOccupancy":2,"breakfastIncluded":true,"currency":"KRW","totalPrice":%d,"taxIncluded":true,"inventory":[%s]}
			""".formatted(totalPrice, inventory).trim();
	}

	private static Set<String> requestedCodes(String codes) {
		if (codes == null || codes.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(codes.split(","))
			.map(String::trim)
			.filter(code -> !code.isEmpty())
			.collect(Collectors.toUnmodifiableSet());
	}

	private static List<LocalDate> stayDates(
		LocalDate checkIn,
		LocalDate checkOut
	) {
		if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
			return List.of();
		}
		return checkIn.datesUntil(checkOut).toList();
	}

	private MockSupplierResponses() {
	}

}
