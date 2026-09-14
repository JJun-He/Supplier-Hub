package com.supplierhub.catalog.application;

import com.supplierhub.catalog.domain.CatalogSnapshot;

public interface CatalogSnapshotStore {

	CatalogSnapshotUpdate replace(CatalogSnapshot snapshot);

}
