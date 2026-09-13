package com.supplierhub.supplier.suppliera;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.application.OfferNormalizationResult;
import com.supplierhub.search.application.OfferNormalizer;
import com.supplierhub.search.domain.DailyInventory;
import com.supplierhub.search.domain.Money;
import com.supplierhub.search.domain.NightlyPrice;
import com.supplierhub.search.domain.OfferCandidate;
import com.supplierhub.search.domain.Price;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;
import com.supplierhub.supplier.common.SupplierSearchClient;
import com.supplierhub.supplier.common.SupplierSearchRequest;
import com.supplierhub.supplier.common.SupplierSearchRequest.PropertyMapping;
import com.supplierhub.supplier.common.SupplierSearchResult;
import com.supplierhub.supplier.common.SupplierTransportFailureMapper;

@Component
public class SupplierASearchClient implements SupplierSearchClient {

	private final WebClient webClient;
	private final SupplierIntegrationProperties properties;

	public SupplierASearchClient(
		@Qualifier("supplierASearchWebClient") WebClient webClient,
		SupplierIntegrationProperties properties
	) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public Supplier supplier() {
		return Supplier.SUPPLIER_A;
	}

	@Override
	public Mono<SupplierSearchResult> search(SupplierSearchRequest request) {
		requireMatchingSupplier(request);
		Map<SupplierItemKey, InternalMapping> mappings = mappings(request);
		String hotelCodes = request.properties().stream()
			.map(PropertyMapping::supplierPropertyCode)
			.collect(Collectors.joining(","));

		return webClient.get()
			.uri(uriBuilder -> uriBuilder
				.path("/a/v1/availability")
				.queryParam("hotelCodes", hotelCodes)
				.queryParam("checkIn", request.criteria().checkIn())
				.queryParam("checkOut", request.criteria().checkOut())
				.queryParam("adults", request.criteria().adults())
				.queryParam("children", request.criteria().children())
				.build())
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::httpFailure)
			.bodyToMono(AvailabilityResponse.class)
			.switchIfEmpty(Mono.error(invalidResponse()))
			.map(response -> normalize(response, request, mappings))
			.timeout(properties.search().callTimeout())
			.onErrorMap(
				cause -> !(cause instanceof SupplierIntegrationException)
					&& SupplierTransportFailureMapper.isTimeout(cause),
				cause -> SupplierTransportFailureMapper.timeoutFailure(
					supplier(),
					"Supplier A search request",
					cause
				)
			)
			.onErrorMap(
				WebClientRequestException.class,
				cause -> SupplierTransportFailureMapper.requestFailure(
					supplier(),
					"Supplier A search request",
					cause
				)
			)
			.onErrorMap(
				DecodingException.class,
				cause -> new SupplierIntegrationException(
					supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier A search response was invalid",
					cause
				)
			);
	}

	private SupplierSearchResult normalize(
		AvailabilityResponse response,
		SupplierSearchRequest request,
		Map<SupplierItemKey, InternalMapping> mappings
	) {
		if (response.items() == null) {
			throw invalidResponse();
		}
		OfferNormalizationResult normalized = OfferNormalizer.normalize(
			request.criteria(),
			supplier(),
			response.items(),
			item -> toCandidate(item, mappings)
		);
		return new SupplierSearchResult(
			supplier(),
			normalized.offers(),
			normalized.rejectedOfferCount(),
			normalized.unavailableOfferCount()
		);
	}

	private OfferCandidate toCandidate(
		AvailabilityItem item,
		Map<SupplierItemKey, InternalMapping> mappings
	) {
		Objects.requireNonNull(item, "availability item must not be null");
		InternalMapping mapping = mappings.get(new SupplierItemKey(
			item.hotelCode(),
			item.roomTypeCode()
		));
		if (mapping == null) {
			throw new IllegalArgumentException(
				"availability item must have an active internal mapping"
			);
		}
		List<DailyRate> dailyRates = required(
			item.dailyRates(),
			"dailyRates"
		);
		List<NightlyPrice> nightlyPrices = dailyRates.stream()
			.map(rate -> toNightlyPrice(rate, item.currency()))
			.toList();
		List<DailyInventory> dailyInventory = dailyRates.stream()
			.map(this::toDailyInventory)
			.toList();

		return new OfferCandidate(
			mapping.propertyId(),
			mapping.roomTypeId(),
			supplier(),
			required(item.maxOccupancy(), "maxOccupancy"),
			required(item.breakfastIncluded(), "breakfastIncluded"),
			Price.fromNightlyPrices(nightlyPrices),
			dailyInventory
		);
	}

	private NightlyPrice toNightlyPrice(DailyRate rate, String currency) {
		Objects.requireNonNull(rate, "daily rate must not be null");
		return new NightlyPrice(
			required(rate.date(), "daily rate date"),
			Money.of(currency, required(rate.nightlyRate(), "nightlyRate")),
			Money.of(currency, required(rate.taxAmount(), "taxAmount"))
		);
	}

	private DailyInventory toDailyInventory(DailyRate rate) {
		Objects.requireNonNull(rate, "daily rate must not be null");
		return new DailyInventory(
			required(rate.date(), "daily rate date"),
			required(rate.remainingRooms(), "remainingRooms")
		);
	}

	private Map<SupplierItemKey, InternalMapping> mappings(
		SupplierSearchRequest request
	) {
		Map<SupplierItemKey, InternalMapping> mappings = new HashMap<>();
		for (PropertyMapping property : request.properties()) {
			for (SupplierSearchRequest.RoomTypeMapping roomType : property.roomTypes()) {
				mappings.put(
					new SupplierItemKey(
						property.supplierPropertyCode(),
						roomType.supplierRoomTypeCode()
					),
					new InternalMapping(property.propertyId(), roomType.roomTypeId())
				);
			}
		}
		return Map.copyOf(mappings);
	}

	private void requireMatchingSupplier(SupplierSearchRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		if (request.supplier() != supplier()) {
			throw new IllegalArgumentException(
				"search request supplier must match the client supplier"
			);
		}
	}

	private Mono<? extends Throwable> httpFailure(ClientResponse response) {
		int status = response.statusCode().value();
		SupplierFailureType failureType = switch (status) {
			case 400 -> SupplierFailureType.INVALID_REQUEST;
			case 401 -> SupplierFailureType.AUTHENTICATION_FAILED;
			case 429 -> SupplierFailureType.RATE_LIMITED;
			default -> status >= 500
				? SupplierFailureType.UNAVAILABLE
				: SupplierFailureType.UNKNOWN;
		};
		return Mono.error(new SupplierIntegrationException(
			supplier(),
			failureType,
			status >= 500,
			"Supplier A search returned an error status"
		));
	}

	private SupplierIntegrationException invalidResponse() {
		return new SupplierIntegrationException(
			supplier(),
			SupplierFailureType.INVALID_RESPONSE,
			false,
			"Supplier A search response was incomplete"
		);
	}

	private static <T> T required(T value, String fieldName) {
		if (value == null) {
			throw new IllegalArgumentException(fieldName + " must not be null");
		}
		return value;
	}

	private record AvailabilityResponse(List<AvailabilityItem> items) {
	}

	private record AvailabilityItem(
		String hotelCode,
		String hotelName,
		String roomTypeCode,
		String roomTypeName,
		Integer maxOccupancy,
		Boolean breakfastIncluded,
		String currency,
		List<DailyRate> dailyRates
	) {
	}

	private record DailyRate(
		LocalDate date,
		Integer remainingRooms,
		Long nightlyRate,
		Long taxAmount
	) {
	}

	private record SupplierItemKey(
		String supplierPropertyCode,
		String supplierRoomTypeCode
	) {
	}

	private record InternalMapping(long propertyId, long roomTypeId) {
	}

}
