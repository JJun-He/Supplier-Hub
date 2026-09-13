package com.supplierhub.supplier.common;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplierIntegrationProperties.class)
public class SupplierClientConfiguration {

	private static final String API_KEY_HEADER = "X-Api-Key";

	@Bean
	@Qualifier("supplierAWebClient")
	WebClient supplierAWebClient(SupplierIntegrationProperties properties) {
		return createWebClient(WebClient.builder(), properties.a(), properties.catalog());
	}

	@Bean
	@Qualifier("supplierBWebClient")
	WebClient supplierBWebClient(SupplierIntegrationProperties properties) {
		return createWebClient(WebClient.builder(), properties.b(), properties.catalog());
	}

	private WebClient createWebClient(
		WebClient.Builder builder,
		SupplierIntegrationProperties.Endpoint endpoint,
		SupplierIntegrationProperties.Catalog catalog
	) {
		HttpClient httpClient = HttpClient.create()
			.option(
				ChannelOption.CONNECT_TIMEOUT_MILLIS,
				Math.toIntExact(catalog.connectTimeout().toMillis())
			)
			.responseTimeout(catalog.responseTimeout());

		return builder.clone()
			.baseUrl(endpoint.baseUrl().toString())
			.defaultHeader(HttpHeaders.ACCEPT, "application/json")
			.defaultHeader(API_KEY_HEADER, endpoint.apiKey())
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.build();
	}

}
