package com.agentmemory.forget;

/**
 * One page affected (or that would be affected) by a forget sweep (issue #25): its path, retention
 * layer, and the score that decided its fate. Used both in the dry-run preview and the applied
 * report so a caller sees exactly which pages were soft-deleted or purged and why.
 *
 * @param path  the page's project-relative path.
 * @param layer the page's retention layer wire token (e.g. {@code episodic}).
 * @param score the retention score at sweep time (for soft-deletes; {@code 0} where not computed,
 *     e.g. a purge of an already-cold soft-delete).
 */
public record SweepCandidate(String path, String layer, double score) {}
