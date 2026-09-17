package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.application.OfferNormalizer;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.suppliera.SupplierASearchClient;
import com.supplierhub.supplier.supplierb.SupplierBSearchClient;

/** 실제 WebClient JSON decoder와 A/B 어댑터를 통과한 항목 실패 로그를 검증한다. */
class SupplierOfferLoggingTests {

	private final Logger logger = (Logger) LoggerFactory.getLogger(OfferNormalizer.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void captureLogs() {
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void releaseLogs() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@ParameterizedTest
	@EnumSource(Supplier.class)
	void samplesFiveFailuresWithSafeReferencesAndSummarizesAllRejections(Supplier supplier) {
		String valid = item(supplier);
		String invalidCurrency = valid.replace("KRW", "INVALID");
		List<String> items = new ArrayList<>(List.of(
			invalidCurrency,
			valid.replace("P1", "BAD\\nCODE" + "x".repeat(200))
				.replace("R1", "ROOM\\r\\n" + "r".repeat(200)),
			"{}",
			"null",
			valid.replace("2026-10-01", "2026-10-02")
		));
		IntStream.range(0, 5).forEach(ignored -> items.add(invalidCurrency));
		items.add(valid);
		SupplierSearchClient client = client(supplier, envelope(supplier, items));

		SupplierSearchResult result = client.search(request(supplier))
			.contextWrite(context -> context.put(SearchCallContext.class, new SearchCallContext("search-123", 7)))
			.block();

		assertThat(result).isNotNull();
		assertThat(result.offers()).hasSize(1);
		assertThat(result.rejectedOfferCount()).isEqualTo(10);
		assertThat(result.unavailableOfferCount()).isZero();
		assertThat(result.duplicateOfferCount()).isZero();
		assertThat(appender.list).hasSize(6).allSatisfy(event -> {
			assertThat(event.getThrowableProxy()).isNull();
			assertThat(event.getFormattedMessage())
				.contains("sourceSupplier=" + supplier, "searchId=search-123", "batchIndex=7")
				.doesNotContain("\n", "\r");
		});
		List<String> messages = messages();
		for (int index = 0; index < 5; index++) {
			assertThat(messages.get(index)).contains("itemIndex=" + index);
		}
		assertThat(messages.get(0)).contains("supplierPropertyCode=P1", "supplierRoomTypeCode=R1",
			"reasonType=InvalidValueException", "currencyCode must be ISO 4217");
		assertThat(field(messages.get(1), "supplierPropertyCode")).hasSize(120).startsWith("BAD CODE").endsWith("...");
		assertThat(field(messages.get(1), "supplierRoomTypeCode")).hasSize(120).startsWith("ROOM  ").endsWith("...");
		assertThat(messages.get(2)).contains("supplierPropertyCode=UNKNOWN", "supplierRoomTypeCode=UNKNOWN");
		assertThat(messages.get(3)).contains("supplierPropertyCode=UNKNOWN", "Supplier item must be an object");
		assertThat(messages.get(4)).contains("candidateSupplier=" + supplier, "propertyId=1", "roomTypeId=11");
		assertThat(messages.get(5)).contains("Supplier offer rejection summary", "rejectedOfferCount=10",
			"sampledCount=5", "omittedCount=5");
	}

	@ParameterizedTest
	@EnumSource(Supplier.class)
	void readsContextForEverySubscriptionAndResetsItsSampleBudget(Supplier supplier) {
		SupplierSearchClient client = client(supplier, envelope(supplier, java.util.Collections.nCopies(8, "{}")));
		Mono<SupplierSearchResult> call = client.search(request(supplier));

		for (int batch = 0; batch < 2; batch++) {
			SearchCallContext callContext = new SearchCallContext("search-" + batch, batch);
			assertThat(call.contextWrite(context -> context.put(SearchCallContext.class, callContext))
				.block().rejectedOfferCount()).isEqualTo(8);
		}

		assertThat(messages()).hasSize(12);
		for (int batch = 0; batch < 2; batch++) {
			int expectedBatch = batch;
			assertThat(messages().subList(batch * 6, batch * 6 + 6))
				.allSatisfy(message -> assertThat(message).contains("searchId=search-" + expectedBatch,
					"batchIndex=" + expectedBatch));
			assertThat(messages().get(batch * 6 + 5)).contains("searchId=search-" + batch,
				"batchIndex=" + batch, "rejectedOfferCount=8", "omittedCount=3");
		}
	}

	@ParameterizedTest
	@EnumSource(Supplier.class)
	void directAdapterCallsGetDistinctFallbackContextsEvenWithoutProductKeys(Supplier supplier) {
		Mono<SupplierSearchResult> call = client(supplier, envelope(supplier, List.of("{}")))
			.search(request(supplier));

		assertThat(call.block().rejectedOfferCount()).isEqualTo(1);
		assertThat(call.block().rejectedOfferCount()).isEqualTo(1);

		assertThat(messages()).hasSize(4);
		String firstId = field(messages().get(0), "searchId");
		String secondId = field(messages().get(2), "searchId");
		assertThat(UUID.fromString(firstId)).isNotEqualTo(UUID.fromString(secondId));
		assertThat(messages().get(1)).contains("searchId=" + firstId, "batchIndex=0", "omittedCount=0");
		assertThat(messages().get(3)).contains("searchId=" + secondId, "batchIndex=0", "omittedCount=0");
	}

	private List<String> messages() {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private static String field(String message, String field) {
		var matcher = Pattern.compile("(?:^|[ ,])" + field + "=([^,]+)").matcher(message);
		assertThat(matcher.find()).as("log contains %s", field).isTrue();
		return matcher.group(1);
	}

	private SupplierSearchClient client(Supplier supplier, String body) {
		WebClient webClient = WebClient.builder()
			.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", "application/json")
				.body(body).build()))
			.build();
		SupplierIntegrationProperties properties = mock(SupplierIntegrationProperties.class, RETURNS_DEEP_STUBS);
		when(properties.search().callTimeout()).thenReturn(Duration.ofSeconds(3));
		return supplier == Supplier.SUPPLIER_A
			? new SupplierASearchClient(webClient, properties) : new SupplierBSearchClient(webClient, properties);
	}

	private SupplierSearchRequest request(Supplier supplier) {
		return new SupplierSearchRequest(supplier,
			new SearchCriteria(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 2, 0),
			List.of(new SupplierSearchRequest.PropertyMapping(1, "P1",
				List.of(new SupplierSearchRequest.RoomTypeMapping(11, "R1")))));
	}

	private String envelope(Supplier supplier, List<String> items) {
		String data = "{\"items\":[" + String.join(",", items) + "]}";
		return supplier == Supplier.SUPPLIER_A ? data : "{\"resultCode\":\"0000\",\"data\":" + data + "}";
	}

	private String item(Supplier supplier) {
		return supplier == Supplier.SUPPLIER_A
			? """
				{"hotelCode":"P1","roomTypeCode":"R1","maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW","dailyRates":[{"date":"2026-10-01","remainingRooms":2,"nightlyRate":1000,"taxAmount":100}]}
				""".strip()
			: """
				{"propertyId":"P1","roomId":"R1","maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW","totalPrice":1100,"taxIncluded":true,"inventory":[{"date":"2026-10-01","remainingRooms":2}]}
				""".strip();
	}
}
