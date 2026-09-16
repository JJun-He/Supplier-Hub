package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.suppliera.SupplierACatalogClient;
import com.supplierhub.supplier.suppliera.SupplierASearchClient;
import com.supplierhub.supplier.supplierb.SupplierBCatalogClient;
import com.supplierhub.supplier.supplierb.SupplierBSearchClient;

class SupplierMappingFailureTests {

	@ParameterizedTest
	@EnumSource(Supplier.class)
	@SuppressWarnings("unchecked")
	void propagatesUnexpectedArgumentErrorsFromBothAdapters(Supplier supplier) {
		IllegalArgumentException bug = new IllegalArgumentException("mapper bug");
		JsonNode item = mock(JsonNode.class);
		when(item.isObject()).thenThrow(bug);
		JsonNode items = mock(JsonNode.class);
		when(items.isArray()).thenReturn(true);
		when(items.size()).thenReturn(1);
		when(items.get(0)).thenReturn(item);
		JsonNode response = mock(JsonNode.class);
		when(response.isObject()).thenReturn(true);
		when(response.get("items")).thenReturn(items);
		when(response.get("data")).thenReturn(response);
		JsonNode code = mock(JsonNode.class);
		when(code.isString()).thenReturn(true);
		when(code.asString()).thenReturn("0000");
		when(response.get("resultCode")).thenReturn(code);

		WebClient webClient = mock(WebClient.class, RETURNS_DEEP_STUBS);
		when(webClient.get().uri(anyString()).retrieve()
			.onStatus(any(), any()).bodyToMono(JsonNode.class))
			.thenReturn(Mono.just(response));
		when(webClient.get().uri(any(Function.class)).retrieve()
			.onStatus(any(), any()).bodyToMono(JsonNode.class))
			.thenReturn(Mono.just(response));
		SupplierIntegrationProperties properties = mock(
			SupplierIntegrationProperties.class, RETURNS_DEEP_STUBS
		);
		when(properties.search().callTimeout()).thenReturn(Duration.ofSeconds(3));
		SupplierCatalogClient catalog = supplier == Supplier.SUPPLIER_A
			? new SupplierACatalogClient(webClient)
			: new SupplierBCatalogClient(webClient);
		SupplierSearchClient search = supplier == Supplier.SUPPLIER_A
			? new SupplierASearchClient(webClient, properties)
			: new SupplierBSearchClient(webClient, properties);
		SupplierSearchRequest request = new SupplierSearchRequest(
			supplier,
			new SearchCriteria(
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 2, 0
			),
			List.of(new SupplierSearchRequest.PropertyMapping(
				1, "P1", List.of(new SupplierSearchRequest.RoomTypeMapping(11, "R1"))
			))
		);

		assertThatThrownBy(() -> catalog.fetchCatalog().block()).isSameAs(bug);
		assertThatThrownBy(() -> search.search(request).block()).isSameAs(bug);
	}

}
