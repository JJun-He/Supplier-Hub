package com.supplierhub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.supplierhub.SchedulingConfiguration;

class CatalogSynchronizationSchedulerTests {

	@Test
	void runsInitialSynchronizationOnSchedulerThread() throws InterruptedException {
		CatalogSynchronizationService service = mock(
			CatalogSynchronizationService.class
		);
		CountDownLatch synchronizationStarted = new CountDownLatch(1);
		AtomicReference<Thread> synchronizationThread = new AtomicReference<>();
		doAnswer(invocation -> {
			synchronizationThread.set(Thread.currentThread());
			synchronizationStarted.countDown();
			return null;
		}).when(service).synchronizeAll();

		try (AnnotationConfigApplicationContext context =
			new AnnotationConfigApplicationContext()) {
			TestPropertyValues.of(
				"supplier.catalog.enabled=true",
				"supplier.catalog.initial-delay=0",
				"supplier.catalog.fixed-delay=1h"
			).applyTo(context);
			context.registerBean(
				CatalogSynchronizationService.class,
				() -> service
			);
			context.register(
				SchedulingConfiguration.class,
				CatalogSynchronizationScheduler.class
			);
			context.refresh();

			assertThat(synchronizationStarted.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(synchronizationThread.get()).isNotEqualTo(
				Thread.currentThread()
			);
		}
	}

}
