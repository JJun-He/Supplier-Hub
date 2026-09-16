package com.supplierhub.catalog.application;

import java.util.List;

public interface ActiveCatalogMappingReader {

	/**
	 * 호출자와 공유한 시간 예산으로 활성 매핑을 읽는다.
	 * 호출 전 경과 시간과 연결 대기·조회·완료 시간을 같은 예산에 반영한다.
	 * 자원 대기로 종료 시각을 넘길 수 있어 반환·HTTP 완료 상한은 보장하지 않는다.
	 * 예상 밖 내부 오류는 그대로 전파한다.
	 *
	 * @param deadlineNanos 같은 JVM의 {@link System#nanoTime()} 기준 종료 시각(ns).
	 *     epoch 시각이나 기간 자체가 아니다.
	 * @return 활성 카탈로그 매핑
	 * @throws CatalogReadException 진입 전·완료 시 예산 소진 또는 조회·잠금 시간 초과는
	 *     {@code TIMEOUT}, 알려진 연결 획득 실패·연결 단절은 {@code UNAVAILABLE}
	 */
	List<ActiveCatalogMapping> findAllActive(long deadlineNanos);

}
