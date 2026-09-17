package com.supplierhub.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

/** 행의 존재는 빈 목록을 포함한 정상 snapshot이 한 번 이상 반영됐음을 뜻한다. */
@Entity
@Table(name = "supplier_catalog_state")
public class CatalogSyncState {

	@Id
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private Supplier supplier;

	protected CatalogSyncState() {
	}

}
