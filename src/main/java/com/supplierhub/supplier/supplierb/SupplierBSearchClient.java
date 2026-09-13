package com.supplierhub.supplier.supplierb;

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
public class SupplierBSearchClient implements SupplierSearchClient {

	private static final String SUCCESS_CODE = "0000";

	private final WebClient webClient;
	private final SupplierIntegrationProperties properties;

	public SupplierBSearchClient(
		@Qualifier("supplierBSearchWebClient") WebClient webClient,
		SupplierIntegrationProperties properties
	) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public Supplier supplier() {
		return Supplier.SUPPLIER_B;
	}

	@Override
	public Mono<SupplierSearchResult> search(SupplierSearchRequest request) {
		requireMatchingSupplier(request);
		Map<SupplierItemKey, InternalMapping> mappings = mappings(request);
		String propertyIds = request.properties().stream()
			.map(PropertyMapping::supplierPropertyCode)
			.collect(Collectors.joining(","));

		return webClient.get()
			.uri(uriBuilder -> uriBuilder
				.path("/b/api/search")
				.queryParam("propertyIds", propertyIds)
				.queryParam("checkIn", request.criteria().checkIn())
				.queryParam("checkOut", request.criteria().checkOut())
				.queryParam("adults", request.criteria().adults())
				.queryParam("children", request.criteria().children())
				.build())
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::httpFailure)
			.bodyToMono(SearchResponse.class)
			.switchIfEmpty(Mono.error(invalidResponse()))
			.map(response -> normalize(response, request, mappings))
			.timeout(properties.search().callTimeout())
			.onErrorMap(
				cause -> !(cause instanceof SupplierIntegrationException)
					&& SupplierTransportFailureMapper.isTimeout(cause),
				cause -> SupplierTransportFailureMapper.timeoutFailure(
					supplier(),
					"Supplier B search request",
					cause
				)
			)
			.onErrorMap(
				WebClientRequestException.class,
				cause -> SupplierTransportFailureMapper.requestFailure(
					supplier(),
					"Supplier B search request",
					cause
				)
			)
			.onErrorMap(
				DecodingException.class,
				cause -> new SupplierIntegrationException(
					supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier B search response was invalid",
					cause
				)
			);
	}

	private SupplierSearchResult normalize(
		SearchResponse response,
		SupplierSearchRequest request,
		Map<SupplierItemKey, InternalMapping> mappings
	) {
		if (!SUCCESS_CODE.equals(response.resultCode())) {
			throw bodyFailure(response.resultCode());
		}
		if (response.data() == null || response.data().items() == null) {
			throw invalidResponse();
		}
		OfferNormalizationResult normalized = OfferNormalizer.normalize(
			request.criteria(),
			supplier(),
			response.data().items(),
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
		SearchItem item,
		Map<SupplierItemKey, InternalMapping> mappings
	) {
		Objects.requireNonNull(item, "search item must not be null");
		InternalMapping mapping = mappings.get(new SupplierItemKey(
			item.propertyId(),
			item.roomId()
		));
		if (mapping == null) {
			throw new IllegalArgumentException(
				"search item must have an active internal mapping"
			);
		}
		if (!Boolean.TRUE.equals(item.taxIncluded())) {
			throw new IllegalArgumentException("totalPrice must include tax");
		}
		List<Inventory> inventory = required(item.inventory(), "inventory");

		return new OfferCandidate(
			mapping.propertyId(),
			mapping.roomTypeId(),
			supplier(),
			required(item.maxOccupancy(), "maxOccupancy"),
			required(item.breakfastIncluded(), "breakfastIncluded"),
			Price.totalOnly(Money.of(
				item.currency(),
				required(item.totalPrice(), "totalPrice")
			)),
			inventory.stream().map(this::toDailyInventory).toList()
		);
	}

	private DailyInventory toDailyInventory(Inventory inventory) {
		Objects.requireNonNull(inventory, "inventory item must not be null");
		return new DailyInventory(
			required(inventory.date(), "inventory date"),
			required(inventory.remainingRooms(), "remainingRooms")
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
		return Mono.error(new SupplierIntegrationException(
			supplier(),
			response.statusCode().is5xxServerError()
				? SupplierFailureType.UNAVAILABLE
				: SupplierFailureType.UNKNOWN,
			response.statusCode().is5xxServerError(),
			"Supplier B search returned an unexpected HTTP status"
		));
	}

	private SupplierIntegrationException bodyFailure(String resultCode) {
		if (resultCode == null) {
			return invalidResponse();
		}
		SupplierFailureType failureType = switch (resultCode) {
			case "E400" -> SupplierFailureType.INVALID_REQUEST;
			case "E401" -> SupplierFailureType.AUTHENTICATION_FAILED;
			case "E429" -> SupplierFailureType.RATE_LIMITED;
			case "E500", "E503" -> SupplierFailureType.UNAVAILABLE;
			default -> SupplierFailureType.INVALID_RESPONSE;
		};
		boolean retryable = "E500".equals(resultCode)
			|| "E503".equals(resultCode);
		return new SupplierIntegrationException(
			supplier(),
			failureType,
			retryable,
			"Supplier B search returned a failure result"
		);
	}

	private SupplierIntegrationException invalidResponse() {
		return new SupplierIntegrationException(
			supplier(),
			SupplierFailureType.INVALID_RESPONSE,
			false,
			"Supplier B search response was incomplete"
		);
	}

	private static <T> T required(T value, String fieldName) {
		if (value == null) {
			throw new IllegalArgumentException(fieldName + " must not be null");
		}
		return value;
	}

	private record SearchResponse(
		String resultCode,
		String resultMessage,
		Data data
	) {
	}

	private record Data(List<SearchItem> items) {
	}

	private record SearchItem(
		String propertyId,
		String propertyName,
		String roomId,
		String roomName,
		Integer maxOccupancy,
		Boolean breakfastIncluded,
		String currency,
		Long totalPrice,
		Boolean taxIncluded,
		List<Inventory> inventory
	) {
	}

	private record Inventory(LocalDate date, Integer remainingRooms) {
	}

	private record SupplierItemKey(
		String supplierPropertyCode,
		String supplierRoomTypeCode
	) {
	}

	private record InternalMapping(long propertyId, long roomTypeId) {
	}

}
