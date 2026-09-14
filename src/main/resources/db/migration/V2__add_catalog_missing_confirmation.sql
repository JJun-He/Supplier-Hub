ALTER TABLE supplier_property
    ADD COLUMN consecutive_missing_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_supplier_property_missing_count
        CHECK (consecutive_missing_count >= 0);

ALTER TABLE supplier_room_type
    ADD COLUMN consecutive_missing_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_supplier_room_type_missing_count
        CHECK (consecutive_missing_count >= 0);
