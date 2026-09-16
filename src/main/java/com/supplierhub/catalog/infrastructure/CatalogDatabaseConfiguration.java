package com.supplierhub.catalog.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogDatabaseProperties.class)
public class CatalogDatabaseConfiguration {
}
