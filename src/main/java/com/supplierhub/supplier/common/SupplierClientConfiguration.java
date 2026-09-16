package com.supplierhub.supplier.common;

import java.time.Duration;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import com.supplierhub.catalog.domain.Supplier;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
	SupplierIntegrationProperties.class, SupplierResourceProperties.class
})
public class SupplierClientConfiguration {

	private static final String API_KEY_HEADER = "X-Api-Key";

	@Bean
	@Qualifier("supplierAWebClient")
	WebClient supplierAWebClient(
		WebClient.Builder builder,
		SupplierIntegrationProperties properties,
		SupplierCallResources resources
	) {
		return createWebClient(
			builder,
			properties.a(),
			properties.catalog().connectTimeout(),
			properties.catalog().responseTimeout(),
			resources, Supplier.SUPPLIER_A, SupplierOperation.CATALOG
		);
	}

	@Bean
	@Qualifier("supplierBWebClient")
	WebClient supplierBWebClient(
		WebClient.Builder builder,
		SupplierIntegrationProperties properties,
		SupplierCallResources resources
	) {
		return createWebClient(
			builder,
			properties.b(),
			properties.catalog().connectTimeout(),
			properties.catalog().responseTimeout(),
			resources, Supplier.SUPPLIER_B, SupplierOperation.CATALOG
		);
	}

	@Bean
	@Qualifier("supplierASearchWebClient")
	WebClient supplierASearchWebClient(
		WebClient.Builder builder,
		SupplierIntegrationProperties properties,
		SupplierCallResources resources
	) {
		return createWebClient(
			builder,
			properties.a(),
			properties.search().connectTimeout(),
			properties.search().responseTimeout(),
			resources, Supplier.SUPPLIER_A, SupplierOperation.SEARCH
		);
	}

	@Bean
	@Qualifier("supplierBSearchWebClient")
	WebClient supplierBSearchWebClient(
		WebClient.Builder builder,
		SupplierIntegrationProperties properties,
		SupplierCallResources resources
	) {
		return createWebClient(
			builder,
			properties.b(),
			properties.search().connectTimeout(),
			properties.search().responseTimeout(),
			resources, Supplier.SUPPLIER_B, SupplierOperation.SEARCH
		);
	}

	private WebClient createWebClient(
		WebClient.Builder builder,
		SupplierIntegrationProperties.Endpoint endpoint,
		Duration connectTimeout,
		Duration responseTimeout,
		SupplierCallResources resources,
		Supplier supplier,
		SupplierOperation operation
	) {
		HttpClient httpClient = HttpClient.create(resources.pool(supplier, operation))
			.disableRetry(true)
			.option(
				ChannelOption.CONNECT_TIMEOUT_MILLIS,
				Math.toIntExact(connectTimeout.toMillis())
			)
			.responseTimeout(responseTimeout);

		return builder.clone()
			.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(
				resources.maxResponseBytes(supplier, operation)
			))
			.baseUrl(endpoint.baseUrl().toString())
			.defaultHeader(HttpHeaders.ACCEPT, "application/json")
			.defaultHeader(API_KEY_HEADER, endpoint.apiKey())
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.build();
	}

}
