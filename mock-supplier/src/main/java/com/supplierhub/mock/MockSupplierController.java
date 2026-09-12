package com.supplierhub.mock;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MockSupplierController {

	private static final String SCENARIO_HEADER = "X-Mock-Scenario";
	private static final long NO_RESPONSE_DELAY_MILLIS = 600_000L;

	@GetMapping(
		value = "/a/v1/hotels",
		produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<String> getHotelsA(
		@RequestHeader(
			name = SCENARIO_HEADER,
			defaultValue = "normal"
		) String scenario
	) {
		return respondAsSupplierA(
			scenario,
			MockSupplierResponses.A_HOTELS
		);
	}

	@GetMapping(
		value = "/a/v1/availability",
		produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<String> getAvailabilityA(
		@RequestParam String hotelCodes,
		@RequestParam String checkIn,
		@RequestParam String checkOut,
		@RequestParam int adults,
		@RequestParam int children,
		@RequestHeader(
			name = SCENARIO_HEADER,
			defaultValue = "normal"
		) String scenario
	) {
		return respondAsSupplierA(
			scenario,
			MockSupplierResponses.A_AVAILABILITY
		);
	}

	@GetMapping(
		value = "/b/api/properties",
		produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<String> getPropertiesB(
		@RequestHeader(
			name = SCENARIO_HEADER,
			defaultValue = "normal"
		) String scenario
	) {
		return respondAsSupplierB(
			scenario,
			MockSupplierResponses.B_PROPERTIES
		);
	}

	@GetMapping(
		value = "/b/api/search",
		produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<String> searchB(
		@RequestParam String propertyIds,
		@RequestParam String checkIn,
		@RequestParam String checkOut,
		@RequestParam int adults,
		@RequestParam int children,
		@RequestHeader(
			name = SCENARIO_HEADER,
			defaultValue = "normal"
		) String scenario
	) {
		return respondAsSupplierB(
			scenario,
			MockSupplierResponses.B_SEARCH
		);
	}

	private ResponseEntity<String> respondAsSupplierA(
		String scenario,
		String normalBody
	) {
		return switch (normalize(scenario)) {
			case "normal" -> ResponseEntity.ok(normalBody);
			case "error" -> ResponseEntity
				.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(MockSupplierResponses.A_SERVICE_UNAVAILABLE);
			case "no-response" -> delayed(normalBody);
			default -> ResponseEntity
				.badRequest()
				.body(MockSupplierResponses.A_INVALID_SCENARIO);
		};
	}

	private ResponseEntity<String> respondAsSupplierB(
		String scenario,
		String normalBody
	) {
		return switch (normalize(scenario)) {
			case "normal" -> ResponseEntity.ok(normalBody);
			case "error" -> ResponseEntity.ok(
				MockSupplierResponses.B_SERVICE_UNAVAILABLE
			);
			case "no-response" -> delayed(normalBody);
			default -> ResponseEntity.ok(
				MockSupplierResponses.B_INVALID_SCENARIO
			);
		};
	}

	private ResponseEntity<String> delayed(String body) {
		try {
			Thread.sleep(NO_RESPONSE_DELAY_MILLIS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}

		return ResponseEntity.ok(body);
	}

	private String normalize(String scenario) {
		return scenario
			.trim()
			.toLowerCase(Locale.ROOT);
	}

}
