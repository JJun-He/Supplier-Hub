package com.supplierhub.catalog.application;

import com.supplierhub.catalog.domain.CatalogSnapshot;

public interface CatalogSnapshotStore {

	void replace(CatalogSnapshot snapshot);

}
