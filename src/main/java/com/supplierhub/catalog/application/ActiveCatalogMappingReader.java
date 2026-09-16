package com.supplierhub.catalog.application;

import java.util.List;

public interface ActiveCatalogMappingReader {

	/**
	 * Reads active mappings using the caller's shared monotonic deadline.
	 * The caller starts the budget before this call; connection acquisition,
	 * reading and completion consume that same budget without restarting it.
	 * Resource waits may exceed the deadline, so this is not a guarantee of
	 * return or HTTP response completion by that instant.
	 * Unexpected internal failures propagate unchanged.
	 *
	 * @param deadlineNanos end time in nanoseconds on the same JVM's
	 *     {@link System#nanoTime()} clock, not an epoch timestamp or a duration
	 * @return active catalog mappings
	 * @throws CatalogReadException with {@code TIMEOUT} if the budget is already
	 *     expired, is exhausted by completion, or a read or lock times out;
	 *     with {@code UNAVAILABLE} for known connection acquisition or connection
	 *     availability failures
	 */
	List<ActiveCatalogMapping> findAllActive(long deadlineNanos);

}
