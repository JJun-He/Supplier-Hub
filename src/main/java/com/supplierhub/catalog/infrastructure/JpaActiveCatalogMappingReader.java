package com.supplierhub.catalog.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.application.ActiveCatalogMappingReader;

@Component
public class JpaActiveCatalogMappingReader implements ActiveCatalogMappingReader {

	private final RoomTypeRepository roomTypeRepository;

	public JpaActiveCatalogMappingReader(RoomTypeRepository roomTypeRepository) {
		this.roomTypeRepository = roomTypeRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ActiveCatalogMapping> findAllActive() {
		return roomTypeRepository.findAllActiveMappingsForSearch();
	}

}
