CREATE TABLE supplier_catalog_state (
    supplier VARCHAR(32) PRIMARY KEY
);

-- 기존 매핑도 이전에 정상 snapshot이 반영된 결과다. 성공 시각은 추정하지 않는다.
INSERT INTO supplier_catalog_state (supplier)
SELECT DISTINCT supplier FROM supplier_property;
