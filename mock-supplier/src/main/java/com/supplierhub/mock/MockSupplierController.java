package com.supplierhub.mock;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MockSupplierController {

	private static final String SCENARIO_HEADER = "X-Mock-Scenario";
	private static final String NORMAL = "normal";
	private static final long NO_RESPONSE_DELAY_MILLIS = 30_000L;
	private static final Set<String> SUPPLIERS = Set.of("a", "b");
	private static final Set<String> MODES = Set.of(
		NORMAL,
		"error",
		"no-response"
	);

	private final Map<String, String> modes = new ConcurrentHashMap<>(Map.of(
		"a", NORMAL,
		"b", NORMAL
	));

	@PostMapping("/control/{supplier}/mode")
	ResponseEntity<Map<String, String>> setMode(
		@PathVariable String supplier,
		@RequestParam String value
	) {
		String normalizedSupplier = normalize(supplier);
		String normalizedMode = normalize(value);
		if (!SUPPLIERS.contains(normalizedSupplier)
			|| !MODES.contains(normalizedMode)) {
			return ResponseEntity.badRequest().body(Map.of(
				"error", "supplier must be a or b and value must be normal, error, or no-response"
			));
		}
		modes.put(normalizedSupplier, normalizedMode);
		return ResponseEntity.ok(Map.of(normalizedSupplier, normalizedMode));
	}

	@GetMapping(
		value = "/a/v1/hotels",
		produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<String> getHotelsA(
		@RequestHeader(
			name = SCENARIO_HEADER,
			required = false
		) String scenarioOverride
	) {
		return respondAsSupplierA(
			resolveCatalogScenario(scenarioOverride),
			MockSupplierResponses.A_HOTELS
		);
	}

	@GetMapping(
		value = "/a/v1/availability",
		produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<String> getAvailabilityA(
		@RequestParam String hotelCodes,
		@RequestParam LocalDate checkIn,
		@RequestParam LocalDate checkOut,
		@RequestParam int adults,
		@RequestParam int children,
		@RequestHeader(
			name = SCENARIO_HEADER,
			required = false
		) String scenarioOverride
	) {
		return respondAsSupplierA(
			resolveSearchScenario("a", scenarioOverride),
			MockSupplierResponses.aAvailability(
				hotelCodes,
				checkIn,
				checkOut
			)
		);
	}

	@GetMapping(
		value = "/b/api/properties",
		produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<String> getPropertiesB(
		@RequestHeader(
			name = SCENARIO_HEADER,
			required = false
		) String scenarioOverride
	) {
		return respondAsSupplierB(
			resolveCatalogScenario(scenarioOverride),
			MockSupplierResponses.B_PROPERTIES
		);
	}

	@GetMapping(
		value = "/b/api/search",
		produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<String> searchB(
		@RequestParam String propertyIds,
		@RequestParam LocalDate checkIn,
		@RequestParam LocalDate checkOut,
		@RequestParam int adults,
		@RequestParam int children,
		@RequestHeader(
			name = SCENARIO_HEADER,
			required = false
		) String scenarioOverride
	) {
		return respondAsSupplierB(
			resolveSearchScenario("b", scenarioOverride),
			MockSupplierResponses.bSearch(
				propertyIds,
				checkIn,
				checkOut
			)
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

	private String resolveCatalogScenario(String scenarioOverride) {
		return hasText(scenarioOverride)
			? normalize(scenarioOverride)
			: NORMAL;
	}

	private String resolveSearchScenario(
		String supplier,
		String scenarioOverride
	) {
		return hasText(scenarioOverride)
			? normalize(scenarioOverride)
			: modes.get(supplier);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private String normalize(String value) {
		return value
			.trim()
			.toLowerCase(Locale.ROOT);
	}

}
