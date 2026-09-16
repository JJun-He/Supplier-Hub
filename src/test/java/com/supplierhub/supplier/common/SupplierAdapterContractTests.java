package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.common.SupplierSearchRequest.PropertyMapping;
import com.supplierhub.supplier.common.SupplierSearchRequest.RoomTypeMapping;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Exercises the actual Boot WebClient codecs, not a test-specific ObjectMapper. */
@SpringBootTest(properties = "supplier.catalog.enabled=false")
class SupplierAdapterContractTests {

	@Container @ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	static final SearchCriteria CRITERIA =
			new SearchCriteria(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 2, 0);
	static volatile String body;
	static volatile Map<String, String> query;
	static final HttpServer server = startServer();

	@Autowired List<SupplierSearchClient> searchClients;
	@Autowired List<SupplierCatalogClient> catalogClients;

	static HttpServer startServer() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext(
					"/",
					exchange -> {
						String raw = exchange.getRequestURI().getRawQuery();
						query =
								raw == null
										? Map.of()
										: Arrays.stream(raw.split("&"))
												.map(part -> part.split("=", 2))
												.collect(
														Collectors.toMap(
																part ->
																		URLDecoder.decode(
																				part[0],
																				StandardCharsets
																						.UTF_8),
																part ->
																		URLDecoder.decode(
																				part[1],
																				StandardCharsets
																						.UTF_8)));
						byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
						exchange.getResponseHeaders().set("Content-Type", "application/json");
						exchange.sendResponseHeaders(200, bytes.length);
						try (var output = exchange.getResponseBody()) {
							output.write(bytes);
						}
					});
			server.start();
			return server;
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@DynamicPropertySource
	static void endpoints(DynamicPropertyRegistry registry) {
		registry.add(
				"supplier.a.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
		registry.add(
				"supplier.b.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
	}

	@BeforeEach
	void reset() {
		body = "{}";
		query = Map.of();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	static String item(Supplier supplier) {
		return supplier == Supplier.SUPPLIER_A
				? """
{"hotelCode":"P1","roomTypeCode":"R1","maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW","dailyRates":[{"date":"2026-10-01","remainingRooms":2,"nightlyRate":1000,"taxAmount":100}]}
"""
						.strip()
				: """
{"propertyId":"P1","roomId":"R1","maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW","totalPrice":1100,"taxIncluded":true,"inventory":[{"date":"2026-10-01","remainingRooms":2}]}
"""
						.strip();
	}

	static String envelope(Supplier supplier, String items) {
		String data = "{\"items\":[" + items + "]}";
		return supplier == Supplier.SUPPLIER_A
				? data
				: "{\"resultCode\":\"0000\",\"data\":" + data + "}";
	}

	static String replace(String item, String field, String literal) {
		return item.replaceAll(
				"\\\"" + field + "\\\":(?:\\\"[^\\\"]*\\\"|[^,}]+)",
				java.util.regex.Matcher.quoteReplacement("\"" + field + "\":" + literal));
	}

	static Stream<Arguments> invalidItems() {
		return Arrays.stream(Supplier.values())
				.flatMap(
						supplier -> {
							String valid = item(supplier);
							String money =
									supplier == Supplier.SUPPLIER_A ? "nightlyRate" : "totalPrice";
							Stream<Arguments> numbers =
									Stream.of(
													"-0.75",
													"1.0",
													"1e0",
													"\"1100\"",
													"null",
													"true",
													"9223372036854775808",
													"-1")
											.map(
													value ->
															Arguments.of(
																	supplier,
																	money + "=" + value,
																	replace(valid, money, value)));
							Stream<Arguments> integers =
									Stream.of("remainingRooms", "maxOccupancy")
											.flatMap(
													field ->
															Stream.of(
																			"0.75",
																			"2.0",
																			"\"2\"",
																			"2147483648",
																			"-1",
																			"null",
																			"false")
																	.map(
																			value ->
																					Arguments.of(
																							supplier,
																							field
																									+ "="
																									+ value,
																							replace(
																									valid,
																									field,
																									value))));
							Stream<Arguments> shapes =
									Stream.of(
											Arguments.of(
													supplier,
													"invalid date",
													replace(valid, "date", "\"2026-02-30\"")),
											Arguments.of(
													supplier,
													"string boolean",
													replace(
															valid,
															"breakfastIncluded",
															"\"false\"")),
											Arguments.of(
													supplier,
													"numeric currency",
													replace(valid, "currency", "123")),
											Arguments.of(supplier, "null item", "null"),
											Arguments.of(supplier, "array item", "[]"),
											Arguments.of(
													supplier,
													"missing money",
													valid.replaceAll(
																	"\\\"" + money + "\\\":\\d+,?",
																	"")
															.replace(",}", "}")));
							Stream<Arguments> tax =
									supplier == Supplier.SUPPLIER_A
											? Stream.of(
													Arguments.of(
															supplier,
															"fractional tax",
															replace(valid, "taxAmount", "0.25")),
													Arguments.of(
															supplier,
															"total overflow",
															replace(
																	valid,
																	"nightlyRate",
																	"9223372036854775807")))
											: Stream.of(
													Arguments.of(
															supplier,
															"numeric tax flag",
															replace(valid, "taxIncluded", "1")));
							return Stream.of(numbers, integers, shapes, tax).flatMap(s -> s);
						});
	}

	static Stream<Arguments> invalidEnvelopes() {
		return Arrays.stream(Supplier.values())
				.flatMap(
						s ->
								Stream.of(
												"{",
												"null",
												"[]",
												"{}",
												s == Supplier.SUPPLIER_A
														? "{\"items\":{}}"
														: "{\"resultCode\":\"0000\",\"data\":{\"items\":null}}")
										.map(value -> Arguments.of(s, value)));
	}

	static Stream<Arguments> specialCodes() {
		return Arrays.stream(Supplier.values())
				.flatMap(
						s ->
								Stream.of("A+1", "A{1}", "A%2B", "A&x=1", "A B", "숙소-1")
										.map(code -> Arguments.of(s, code)));
	}

	static Stream<Arguments> invalidCatalogOccupancy() {
		return Arrays.stream(Supplier.values())
				.flatMap(
						s ->
								Stream.of("-0.75", "2.0", "\"2\"", "2147483648", "null")
										.map(value -> Arguments.of(s, value)));
	}

	SupplierSearchResult search(Supplier supplier, String code) {
		return search(supplier, code, CRITERIA);
	}

	SupplierSearchResult search(Supplier supplier, String code, SearchCriteria criteria) {
		return searchClients.stream()
				.filter(c -> c.supplier() == supplier)
				.findFirst()
				.orElseThrow()
				.search(
						new SupplierSearchRequest(
								supplier,
								criteria,
								List.of(
										new PropertyMapping(
												1, code, List.of(new RoomTypeMapping(11, "R1"))))))
				.block(Duration.ofSeconds(3));
	}

	void assertInvalidResponse(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action)
				.isInstanceOfSatisfying(
						SupplierIntegrationException.class,
						error ->
								assertThat(error.getFailureType())
										.isEqualTo(SupplierFailureType.INVALID_RESPONSE));
	}

	@Nested
	class InputValues {

		@ParameterizedTest(name = "{0}: {1}")
		@MethodSource("com.supplierhub.supplier.common.SupplierAdapterContractTests#invalidItems")
		void rejectsOnlyTheInvalidItem(Supplier supplier, String scenario, String invalid) {
			body = envelope(supplier, invalid + "," + item(supplier));
			var result = search(supplier, "P1");
			assertThat(result.offers())
					.singleElement()
					.satisfies(
							offer ->
									assertThat(offer.price().totalAmount().amount())
											.isEqualTo(1100));
			assertThat(result.rejectedOfferCount()).isEqualTo(1);
		}

		@ParameterizedTest
		@MethodSource(
				"com.supplierhub.supplier.common.SupplierAdapterContractTests#invalidEnvelopes")
		void rejectsWholeMalformedOrMissingEnvelope(Supplier supplier, String value) {
			body = value;
			assertInvalidResponse(() -> search(supplier, "P1"));
			assertInvalidResponse(
					() ->
							catalogClients.stream()
									.filter(c -> c.supplier() == supplier)
									.findFirst()
									.orElseThrow()
									.fetchCatalog()
									.block(Duration.ofSeconds(3)));
		}

		@ParameterizedTest
		@MethodSource(
				"com.supplierhub.supplier.common.SupplierAdapterContractTests#invalidCatalogOccupancy")
		void rejectsWholeCatalogInsteadOfSavingATruncatedSnapshot(
				Supplier supplier, String occupancy) {
			String property =
					supplier == Supplier.SUPPLIER_A
							? "{\"hotelCode\":\"P1\",\"hotelName\":\"One\",\"roomTypes\":[{\"roomTypeCode\":\"R1\",\"roomTypeName\":\"Room\",\"maxOccupancy\":2}]}"
							: "{\"propertyId\":\"P1\",\"propertyName\":\"One\",\"rooms\":[{\"roomId\":\"R1\",\"roomName\":\"Room\",\"maxOccupancy\":2}]}";
			body =
					envelope(
							supplier,
							property
									+ ","
									+ replace(
											property.replace("P1", "P2"),
											"maxOccupancy",
											occupancy));
			assertInvalidResponse(
					() ->
							catalogClients.stream()
									.filter(c -> c.supplier() == supplier)
									.findFirst()
									.orElseThrow()
									.fetchCatalog()
									.block(Duration.ofSeconds(3)));
		}

		@Test
		void acceptsLongMaxWithoutRoundingForSupplierB() {
			body =
					envelope(
							Supplier.SUPPLIER_B,
							replace(
									item(Supplier.SUPPLIER_B),
									"totalPrice",
									Long.toString(Long.MAX_VALUE)));
			assertThat(search(Supplier.SUPPLIER_B, "P1").offers())
					.singleElement()
					.satisfies(
							offer ->
									assertThat(offer.price().totalAmount().amount())
											.isEqualTo(Long.MAX_VALUE));
		}
	}

	@Nested
	class OfferPolicy {

		@ParameterizedTest
		@EnumSource(Supplier.class)
		void removesExactDuplicatesButKeepsDifferentSaleConditions(Supplier supplier) {
			String valid = item(supplier);
			String money = supplier == Supplier.SUPPLIER_A ? "nightlyRate" : "totalPrice";
			body =
					envelope(
							supplier,
							String.join(
									",",
									valid,
									valid,
									replace(valid, money, "1200"),
									replace(valid, "breakfastIncluded", "true"),
									replace(valid, "currency", "\"USD\""),
									replace(valid, "remainingRooms", "3")));
			var result = search(supplier, "P1");
			assertThat(result.offers()).hasSize(5);
			assertThat(result.rejectedOfferCount()).isZero();
		}

		@Test
		void removesDuplicateSupplierAOfferWhenOnlyNightlyOrderDiffers() {
			String first =
					"""
					{"date":"2026-10-01","remainingRooms":2,"nightlyRate":1000,"taxAmount":100}
					"""
							.strip();
			String second = first.replace("2026-10-01", "2026-10-02");
			String valid = item(Supplier.SUPPLIER_A);
			body =
					envelope(
							Supplier.SUPPLIER_A,
							valid.replace(first, first + "," + second)
									+ ","
									+ valid.replace(first, second + "," + first));
			SearchCriteria twoNights =
					new SearchCriteria(CRITERIA.checkIn(), CRITERIA.checkOut().plusDays(1), 2, 0);
			var result = search(Supplier.SUPPLIER_A, "P1", twoNights);
			assertThat(result.offers())
					.singleElement()
					.satisfies(
							offer -> {
								assertThat(offer.price().totalAmount().amount()).isEqualTo(2200);
								assertThat(offer.price().nightlyBreakdown())
										.extracting(day -> day.date())
										.containsExactly(CRITERIA.checkIn(), CRITERIA.checkOut());
							});
			assertThat(result.rejectedOfferCount()).isZero();
		}

		@ParameterizedTest
		@EnumSource(Supplier.class)
		void rejectsConflictingRoomCapacitiesAndPreservesAnotherPropertyWithTheSameRoomCode(
				Supplier supplier) {
			String valid = item(supplier);
			String conflict = replace(valid, "maxOccupancy", "3");
			String healthy = valid.replace("P1", "P2");
			SupplierSearchRequest request =
					new SupplierSearchRequest(
							supplier,
							CRITERIA,
							List.of(
									new PropertyMapping(
											1, "P1", List.of(new RoomTypeMapping(11, "R1"))),
									new PropertyMapping(
											2, "P2", List.of(new RoomTypeMapping(22, "R1")))));
			for (String items :
					List.of(
							valid + "," + conflict + "," + healthy,
							healthy + "," + conflict + "," + valid)) {
				body = envelope(supplier, items);
				var result =
						searchClients.stream()
								.filter(c -> c.supplier() == supplier)
								.findFirst()
								.orElseThrow()
								.search(request)
								.block(Duration.ofSeconds(3));
				assertThat(result.offers())
						.singleElement()
						.satisfies(
								offer -> {
									assertThat(offer.propertyId()).isEqualTo(2);
									assertThat(offer.roomTypeId()).isEqualTo(22);
								});
				assertThat(result.rejectedOfferCount()).isEqualTo(2);
			}
		}
	}

	@Nested
	class RequestEncoding {

		@ParameterizedTest
		@MethodSource("com.supplierhub.supplier.common.SupplierAdapterContractTests#specialCodes")
		void preservesSupplierCodeAsUriValue(Supplier supplier, String code) {
			body = envelope(supplier, item(supplier).replace("P1", code));
			assertThat(search(supplier, code).offers()).hasSize(1);
			assertThat(query.get(supplier == Supplier.SUPPLIER_A ? "hotelCodes" : "propertyIds"))
					.isEqualTo(code);
		}
	}

	@Nested
	class BodyFailures {

		@ParameterizedTest
		@CsvSource({
			"E400, INVALID_REQUEST, false",
			"E401, AUTHENTICATION_FAILED, false",
			"E429, RATE_LIMITED, false",
			"E500, UNAVAILABLE, true",
			"E503, UNAVAILABLE, true",
			"unexpected, INVALID_RESPONSE, false"
		})
		void classifiesSupplierBBodyFailuresForBothOperations(
				String code, SupplierFailureType expected, boolean retryable) {
			body = "{\"resultCode\":\"" + code + "\",\"data\":null}";
			List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> operations =
					List.of(
							() -> search(Supplier.SUPPLIER_B, "P1"),
							() ->
									catalogClients.stream()
											.filter(c -> c.supplier() == Supplier.SUPPLIER_B)
											.findFirst()
											.orElseThrow()
											.fetchCatalog()
											.block(Duration.ofSeconds(3)));
			for (var operation : operations) {
				assertThatThrownBy(operation)
						.isInstanceOfSatisfying(
								SupplierIntegrationException.class,
								error -> {
									assertThat(error.getFailureType()).isEqualTo(expected);
									assertThat(error.isRetryable()).isEqualTo(retryable);
								});
			}
		}
	}

	@Nested
	class ResponseSizes {

		@ParameterizedTest
		@EnumSource(Supplier.class)
		void actualBootCodecRejectsOversizedBodiesForBothOperations(Supplier supplier) {
			body = "{\"padding\":\"" + "x".repeat(8 * 1024 * 1024) + "\",\"items\":[]}";
			for (var operation :
					List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
							() -> search(supplier, "P1"),
							() ->
									catalogClients.stream()
											.filter(client -> client.supplier() == supplier)
											.findFirst()
											.orElseThrow()
											.fetchCatalog()
											.block(Duration.ofSeconds(5)))) {
				assertThatThrownBy(operation)
						.isInstanceOfSatisfying(
								SupplierIntegrationException.class,
								error ->
										assertThat(error.getFailureType())
												.isEqualTo(SupplierFailureType.RESPONSE_TOO_LARGE));
			}
		}

		@Test
		void actualBootCodecAcceptsLargeCatalogAndSearchFixtures() {
			body = SupplierResponseFixtures.catalogBody(3000);
			var snapshot =
					catalogClients.stream()
							.filter(client -> client.supplier() == Supplier.SUPPLIER_A)
							.findFirst()
							.orElseThrow()
							.fetchCatalog()
							.block(Duration.ofSeconds(5));
			assertThat(snapshot.properties()).hasSize(3000);
			body = SupplierResponseFixtures.searchBody(50, 5, 30);
			var result =
					searchClients.stream()
							.filter(client -> client.supplier() == Supplier.SUPPLIER_A)
							.findFirst()
							.orElseThrow()
							.search(SupplierResponseFixtures.request(50, 5, 30))
							.block(Duration.ofSeconds(5));
			assertThat(result.offers()).hasSize(250);
			assertThat(result.rejectedOfferCount()).isZero();
		}
	}
}
