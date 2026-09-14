package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.supplier.suppliera.SupplierACatalogClient;
import com.supplierhub.supplier.supplierb.SupplierBCatalogClient;

class SupplierCatalogClientTests {

	private HttpServer server;
	private int responseStatus;
	private String responseBody;
	private String requestPath;
	private String requestApiKey;

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
	void supplierARequestsAndMapsCatalog() {
		respondWith(200, """
			{
			  "items": [
			    {
			      "hotelCode": "A-1",
			      "hotelName": "First Property",
			      "roomTypes": [
			        {
			          "roomTypeCode": "ROOM-1",
			          "roomTypeName": "First Room",
			          "maxOccupancy": 2
			        }
			      ]
			    }
			  ]
			}
			""");
		SupplierACatalogClient client = new SupplierACatalogClient(
			webClient(true, "a-test-key")
		);

		CatalogSnapshot snapshot = client.fetchCatalog()
			.block(Duration.ofSeconds(2));

		assertThat(requestPath).isEqualTo("/a/v1/hotels");
		assertThat(requestApiKey).isEqualTo("a-test-key");
		assertThat(snapshot).isNotNull();
		assertThat(snapshot.properties()).singleElement().satisfies(property -> {
			assertThat(property.supplierPropertyCode()).isEqualTo("A-1");
			assertThat(property.roomTypes()).singleElement().satisfies(roomType -> {
				assertThat(roomType.supplierRoomTypeCode()).isEqualTo("ROOM-1");
				assertThat(roomType.maxOccupancy()).isEqualTo(2);
			});
		});
	}

	@Test
	void supplierAClassifiesServerFailureAsRetryable() {
		respondWith(503, "{}");
		SupplierACatalogClient client = new SupplierACatalogClient(
			webClient(true, "a-test-key")
		);

		assertThatThrownBy(() -> client.fetchCatalog().block(Duration.ofSeconds(2)))
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
	void supplierBRequestsAndMapsSuccessfulBody() {
		respondWith(200, """
			{
			  "resultCode": "0000",
			  "resultMessage": "SUCCESS",
			  "data": {
			    "items": [
			      {
			        "propertyId": "B-1",
			        "propertyName": "First Property",
			        "rooms": [
			          {
			            "roomId": "ROOM-1",
			            "roomName": "First Room",
			            "maxOccupancy": 3
			          }
			        ]
			      }
			    ]
			  }
			}
			""");
		SupplierBCatalogClient client = new SupplierBCatalogClient(
			webClient(false, "b-test-key")
		);

		CatalogSnapshot snapshot = client.fetchCatalog()
			.block(Duration.ofSeconds(2));

		assertThat(requestPath).isEqualTo("/b/api/properties");
		assertThat(requestApiKey).isEqualTo("b-test-key");
		assertThat(snapshot).isNotNull();
		assertThat(snapshot.properties()).singleElement().satisfies(property -> {
			assertThat(property.supplierPropertyCode()).isEqualTo("B-1");
			assertThat(property.roomTypes()).singleElement().satisfies(roomType ->
				assertThat(roomType.maxOccupancy()).isEqualTo(3)
			);
		});
	}

	@Test
	void supplierBClassifiesBodyFailureAsRetryable() {
		respondWith(200, """
			{
			  "resultCode": "E503",
			  "resultMessage": "TEMPORARILY_UNAVAILABLE",
			  "data": null
			}
			""");
		SupplierBCatalogClient client = new SupplierBCatalogClient(
			webClient(false, "b-test-key")
		);

		assertThatThrownBy(() -> client.fetchCatalog().block(Duration.ofSeconds(2)))
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
	void supplierBClassifiesHttpAuthenticationFailure() {
		respondWith(401, "{}");
		SupplierBCatalogClient client = new SupplierBCatalogClient(
			webClient(false, "b-test-key")
		);

		assertThatThrownBy(() -> client.fetchCatalog().block(Duration.ofSeconds(2)))
			.isInstanceOfSatisfying(
				SupplierIntegrationException.class,
				exception -> {
					assertThat(exception.getFailureType())
						.isEqualTo(SupplierFailureType.AUTHENTICATION_FAILED);
					assertThat(exception.isRetryable()).isFalse();
				}
			);
	}

	private WebClient webClient(boolean supplierA, String apiKey) {
		URI baseUrl = URI.create(
			"http://127.0.0.1:" + server.getAddress().getPort()
		);
		SupplierIntegrationProperties properties = new SupplierIntegrationProperties(
			new SupplierIntegrationProperties.Endpoint(true, baseUrl, apiKey),
			new SupplierIntegrationProperties.Endpoint(true, baseUrl, apiKey),
			new SupplierIntegrationProperties.Catalog(
				true,
				Duration.ofMillis(500),
				Duration.ofSeconds(1),
				Duration.ofSeconds(2),
				2,
				Duration.ofMillis(10),
				Duration.ZERO,
				Duration.ofMinutes(10),
				10,
				0.5
			),
			new SupplierIntegrationProperties.Search(
				Duration.ofMillis(500),
				Duration.ofSeconds(1),
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				4
			)
		);
		SupplierClientConfiguration configuration = new SupplierClientConfiguration();
		return supplierA
			? configuration.supplierAWebClient(WebClient.builder(), properties)
			: configuration.supplierBWebClient(WebClient.builder(), properties);
	}

	private void respondWith(int status, String body) {
		this.responseStatus = status;
		this.responseBody = body;
	}

	private void respond(HttpExchange exchange) throws IOException {
		requestPath = exchange.getRequestURI().getPath();
		requestApiKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
		byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(responseStatus, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

}
