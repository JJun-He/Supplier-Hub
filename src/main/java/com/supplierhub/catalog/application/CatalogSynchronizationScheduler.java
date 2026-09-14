package com.supplierhub.catalog.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "supplier.catalog",
	name = "enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class CatalogSynchronizationScheduler {

	private final CatalogSynchronizationService synchronizationService;

	public CatalogSynchronizationScheduler(
		CatalogSynchronizationService synchronizationService
	) {
		this.synchronizationService = synchronizationService;
	}

	@Scheduled(
		initialDelayString = "${supplier.catalog.initial-delay:0}",
		fixedDelayString = "${supplier.catalog.fixed-delay:10m}"
	)
	public void synchronizePeriodically() {
		synchronizationService.synchronizeAll();
	}

}
