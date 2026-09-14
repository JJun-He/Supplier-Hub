package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.common.SupplierSearchRequest.PropertyMapping;
import com.supplierhub.supplier.common.SupplierSearchRequest.RoomTypeMapping;
import com.supplierhub.supplier.suppliera.SupplierASearchClient;
import com.supplierhub.supplier.supplierb.SupplierBSearchClient;

class SupplierSearchClientTests {

	private static final SearchCriteria CRITERIA = new SearchCriteria(
		LocalDate.of(2026, 9, 1),
		LocalDate.of(2026, 9, 4),
		2,
		0
	);

	private HttpServer server;
	private int responseStatus;
	private String responseBody;
	private long responseDelayMillis;
	private String requestPath;
	private String requestApiKey;
	private Map<String, String> requestQuery;
	private final AtomicInteger requestCount = new AtomicInteger();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::respond);
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void supplierARequestsExactContractAndNormalizesNightlyPrices() {
		respondWith(200, supplierAResponse("A-10023", "DLX-TWN"));
		SupplierASearchClient client = supplierAClient(Duration.ofSeconds(2));

		SupplierSearchResult result = client.search(request(
			Supplier.SUPPLIER_A,
			101,
			"A-10023",
			201,
			"DLX-TWN"
		)).block(Duration.ofSeconds(2));

		assertThat(requestPath).isEqualTo("/a/v1/availability");
		assertThat(requestApiKey).isEqualTo("a-test-key");
		assertThat(requestQuery).containsExactlyInAnyOrderEntriesOf(Map.of(
			"hotelCodes", "A-10023",
			"checkIn", "2026-09-01",
			"checkOut", "2026-09-04",
			"adults", "2",
			"children", "0"
		));
		assertThat(result).isNotNull();
		assertThat(result.supplier()).isEqualTo(Supplier.SUPPLIER_A);
		assertThat(result.rejectedOfferCount()).isZero();
		assertThat(result.unavailableOfferCount()).isZero();
		assertThat(result.offers()).singleElement().satisfies(offer -> {
			assertThat(offer.propertyId()).isEqualTo(101);
			assertThat(offer.roomTypeId()).isEqualTo(201);
			assertThat(offer.availableRooms()).isEqualTo(1);
			assertThat(offer.price().totalAmount().amount()).isEqualTo(429_000);
			assertThat(offer.price().hasNightlyBreakdown()).isTrue();
		});
	}

	@Test
	void supplierBRequestsExactContractAndPreservesTaxIncludedTotal() {
		respondWith(200, supplierBResponse(true, "B77120", "R-401"));
		SupplierBSearchClient client = supplierBClient(Duration.ofSeconds(2));

		SupplierSearchResult result = client.search(request(
			Supplier.SUPPLIER_B,
			102,
			"B77120",
			202,
			"R-401"
		)).block(Duration.ofSeconds(2));

		assertThat(requestPath).isEqualTo("/b/api/search");
		assertThat(requestApiKey).isEqualTo("b-test-key");
		assertThat(requestQuery).containsEntry("propertyIds", "B77120");
		assertThat(result).isNotNull();
		assertThat(result.offers()).singleElement().satisfies(offer -> {
			assertThat(offer.propertyId()).isEqualTo(102);
			assertThat(offer.roomTypeId()).isEqualTo(202);
			assertThat(offer.breakfastIncluded()).isTrue();
			assertThat(offer.availableRooms()).isEqualTo(1);
			assertThat(offer.price().totalAmount().amount()).isEqualTo(452_000);
			assertThat(offer.price().hasNightlyBreakdown()).isFalse();
		});
	}

	@Test
	void supplierAExcludesUnknownMappingWithoutLosingKnownSibling() {
		respondWith(200, """
			{
			  "items": [
			    %s,
			    %s
			  ]
			}
			""".formatted(
				supplierAItem("A-10023", "DLX-TWN"),
				supplierAItem("UNKNOWN", "ROOM-X")
			));
		SupplierASearchClient client = supplierAClient(Duration.ofSeconds(2));

		SupplierSearchResult result = client.search(request(
			Supplier.SUPPLIER_A,
			101,
			"A-10023",
			201,
			"DLX-TWN"
		)).block(Duration.ofSeconds(2));

		assertThat(result).isNotNull();
		assertThat(result.offers()).hasSize(1);
		assertThat(result.rejectedOfferCount()).isEqualTo(1);
	}

	@Test
	void supplierBExcludesItemWhoseTotalDoesNotIncludeTax() {
		respondWith(200, supplierBResponse(false, "B77120", "R-401"));
		SupplierBSearchClient client = supplierBClient(Duration.ofSeconds(2));

		SupplierSearchResult result = client.search(request(
			Supplier.SUPPLIER_B,
			102,
			"B77120",
			202,
			"R-401"
		)).block(Duration.ofSeconds(2));

		assertThat(result).isNotNull();
		assertThat(result.offers()).isEmpty();
		assertThat(result.rejectedOfferCount()).isEqualTo(1);
	}

	@Test
	void supplierBMapsBodyFailureToCommonFailure() {
		respondWith(200, """
			{
			  "resultCode": "E503",
			  "resultMessage": "TEMPORARILY_UNAVAILABLE",
			  "data": null
			}
			""");
		SupplierBSearchClient client = supplierBClient(Duration.ofSeconds(2));

		assertThatThrownBy(() -> client.search(request(
			Supplier.SUPPLIER_B,
			102,
			"B77120",
			202,
			"R-401"
		)).block(Duration.ofSeconds(2)))
			.isInstanceOfSatisfying(
				SupplierIntegrationException.class,
				exception -> {
					assertThat(exception.getFailureType())
						.isEqualTo(SupplierFailureType.UNAVAILABLE);
					assertThat(exception.isRetryable()).isTrue();
				}
			);
	}

	@Test
	void supplierAStopsNoResponseAtCallTimeoutWithoutRetry() {
		respondWith(200, supplierAResponse("A-10023", "DLX-TWN"));
		responseDelayMillis = 1_000;
		SupplierASearchClient client = supplierAClient(Duration.ofMillis(100));

		assertThatThrownBy(() -> client.search(request(
			Supplier.SUPPLIER_A,
			101,
			"A-10023",
			201,
			"DLX-TWN"
		)).block(Duration.ofSeconds(2)))
			.isInstanceOfSatisfying(
				SupplierIntegrationException.class,
				exception -> assertThat(exception.getFailureType())
					.isEqualTo(SupplierFailureType.TIMEOUT)
			);
		assertThat(requestCount).hasValue(1);
	}

	@Test
	void supplierAClassifiesRateLimitWithoutRetrying() {
		respondWith(429, "{}");
		SupplierASearchClient client = supplierAClient(Duration.ofSeconds(2));

		assertThatThrownBy(() -> client.search(request(
			Supplier.SUPPLIER_A,
			101,
			"A-10023",
			201,
			"DLX-TWN"
		)).block(Duration.ofSeconds(2)))
			.isInstanceOfSatisfying(
				SupplierIntegrationException.class,
				exception -> {
					assertThat(exception.getFailureType())
						.isEqualTo(SupplierFailureType.RATE_LIMITED);
					assertThat(exception.isRetryable()).isFalse();
				}
			);
		assertThat(requestCount).hasValue(1);
	}

	private SupplierASearchClient supplierAClient(Duration callTimeout) {
		SupplierIntegrationProperties properties = properties(callTimeout);
		SupplierClientConfiguration configuration = new SupplierClientConfiguration();
		return new SupplierASearchClient(
			configuration.supplierASearchWebClient(properties),
			properties
		);
	}

	private SupplierBSearchClient supplierBClient(Duration callTimeout) {
		SupplierIntegrationProperties properties = properties(callTimeout);
		SupplierClientConfiguration configuration = new SupplierClientConfiguration();
		return new SupplierBSearchClient(
			configuration.supplierBSearchWebClient(properties),
			properties
		);
	}

	private SupplierIntegrationProperties properties(Duration callTimeout) {
		URI baseUrl = URI.create(
			"http://127.0.0.1:" + server.getAddress().getPort()
		);
		return new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(baseUrl, "a-test-key"),
			new SupplierIntegrationProperties.Endpoint(baseUrl, "b-test-key"),
			new SupplierIntegrationProperties.Catalog(
				true,
				Duration.ofMillis(500),
				Duration.ofSeconds(1),
				2,
				Duration.ofMillis(10),
				Duration.ZERO,
				Duration.ofMinutes(10)
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(1),
				callTimeout
			)
		);
	}

	private SupplierSearchRequest request(
		Supplier supplier,
		long propertyId,
		String propertyCode,
		long roomTypeId,
		String roomTypeCode
	) {
		return new SupplierSearchRequest(
			supplier,
			CRITERIA,
			java.util.List.of(new PropertyMapping(
				propertyId,
				propertyCode,
				java.util.List.of(new RoomTypeMapping(roomTypeId, roomTypeCode))
			))
		);
	}

	private String supplierAResponse(String hotelCode, String roomTypeCode) {
		return """
			{
			  "items": [%s]
			}
			""".formatted(supplierAItem(hotelCode, roomTypeCode));
	}

	private String supplierAItem(String hotelCode, String roomTypeCode) {
		return """
			{
			  "hotelCode": "%s",
			  "roomTypeCode": "%s",
			  "maxOccupancy": 2,
			  "breakfastIncluded": false,
			  "currency": "KRW",
			  "dailyRates": [
			    {"date":"2026-09-01","remainingRooms":3,"nightlyRate":120000,"taxAmount":12000},
			    {"date":"2026-09-02","remainingRooms":1,"nightlyRate":150000,"taxAmount":15000},
			    {"date":"2026-09-03","remainingRooms":5,"nightlyRate":120000,"taxAmount":12000}
			  ]
			}
			""".formatted(hotelCode, roomTypeCode);
	}

	private String supplierBResponse(
		boolean taxIncluded,
		String propertyCode,
		String roomTypeCode
	) {
		return """
			{
			  "resultCode": "0000",
			  "resultMessage": "SUCCESS",
			  "data": {
			    "items": [{
			      "propertyId": "%s",
			      "roomId": "%s",
			      "maxOccupancy": 2,
			      "breakfastIncluded": true,
			      "currency": "KRW",
			      "totalPrice": 452000,
			      "taxIncluded": %s,
			      "inventory": [
			        {"date":"2026-09-01","remainingRooms":3},
			        {"date":"2026-09-02","remainingRooms":1},
			        {"date":"2026-09-03","remainingRooms":5}
			      ]
			    }]
			  }
			}
			""".formatted(propertyCode, roomTypeCode, taxIncluded);
	}

	private void respondWith(int status, String body) {
		responseStatus = status;
		responseBody = body;
	}

	private void respond(HttpExchange exchange) throws IOException {
		requestCount.incrementAndGet();
		requestPath = exchange.getRequestURI().getPath();
		requestApiKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
		requestQuery = parseQuery(exchange.getRequestURI().getRawQuery());
		if (responseDelayMillis > 0) {
			try {
				Thread.sleep(responseDelayMillis);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(responseStatus, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private Map<String, String> parseQuery(String rawQuery) {
		return Arrays.stream(rawQuery.split("&"))
			.map(parameter -> parameter.split("=", 2))
			.collect(Collectors.toMap(
				parts -> decode(parts[0]),
				parts -> decode(parts[1]),
				(first, second) -> second,
				ConcurrentHashMap::new
			));
	}

	private String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

}
