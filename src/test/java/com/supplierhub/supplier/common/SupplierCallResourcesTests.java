package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.supplierhub.catalog.domain.Supplier;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierCallResourcesTests {

	@Test
	void acquiresOnSubscriptionAndReleasesBeforeImmediateResubscription() {
		try (var fixture = new SupplierResourceFixture(1, 1024, 1024)) {
			AtomicInteger actions = new AtomicInteger();
			Mono<Integer> call =
					fixture.resources.execute(
							Supplier.SUPPLIER_A,
							SupplierOperation.SEARCH,
							() -> Mono.just(actions.incrementAndGet()));
			assertThat(actions).hasValue(0);
			assertThat(call.repeat(99).collectList().block()).hasSize(100);
			assertThat(actions).hasValue(100);
		}
	}

	@Test
	void releasesOnSynchronousFailureAndCancellationWithoutCallingRejectedAction() {
		try (var fixture = new SupplierResourceFixture(1, 1024, 1024)) {
			var failure = new IllegalStateException("before publisher creation");
			assertThatThrownBy(
							() ->
									fixture.resources
											.execute(
													Supplier.SUPPLIER_A,
													SupplierOperation.SEARCH,
													() -> {
														throw failure;
													})
											.block())
					.isSameAs(failure);
			var running =
					fixture.resources
							.execute(Supplier.SUPPLIER_A, SupplierOperation.SEARCH, Mono::never)
							.subscribe();
			AtomicInteger rejectedActions = new AtomicInteger();
			assertThatThrownBy(
							() ->
									fixture.resources
											.execute(
													Supplier.SUPPLIER_A,
													SupplierOperation.SEARCH,
													() ->
															Mono.just(
																	rejectedActions
																			.incrementAndGet()))
											.block())
					.isInstanceOfSatisfying(
							SupplierIntegrationException.class,
							error ->
									assertThat(error.getFailureType())
											.isEqualTo(SupplierFailureType.CAPACITY_EXCEEDED));
			assertThat(rejectedActions).hasValue(0);
			running.dispose();
			assertThat(
							fixture.resources
									.execute(
											Supplier.SUPPLIER_A,
											SupplierOperation.SEARCH,
											() -> Mono.just("recovered"))
									.block())
					.isEqualTo("recovered");
			await().atMost(Duration.ofSeconds(2))
					.untilAsserted(
							() ->
									assertThat(
													fixture.registry
															.get("supplier.calls")
															.tags(
																	"supplier",
																	"SUPPLIER_A",
																	"operation",
																	"SEARCH",
																	"failure",
																	"INTERNAL_ERROR")
															.timer()
															.count())
											.isEqualTo(1));
		}
	}

	@Test
	void rejectsUnlimitedOrInvalidResourceSettings() {
		try (var factory = Validation.buildDefaultValidatorFactory()) {
			var validator = factory.getValidator();
			var valid = new SupplierResourceProperties.Limits(1, 1024, 1, Duration.ofMillis(100));
			assertThat(validator.validate(new SupplierResourceProperties(valid, valid))).isEmpty();
			for (var invalid :
					java.util.List.of(
							new SupplierResourceProperties.Limits(
									0, 1024, 1, Duration.ofMillis(100)),
							new SupplierResourceProperties.Limits(1, -1, 1, Duration.ofMillis(100)),
							new SupplierResourceProperties.Limits(
									1, 1024, -1, Duration.ofMillis(100)),
							new SupplierResourceProperties.Limits(1, 1024, 1, Duration.ZERO))) {
				assertThat(validator.validate(new SupplierResourceProperties(invalid, valid)))
						.isNotEmpty();
			}
		}
	}
}
