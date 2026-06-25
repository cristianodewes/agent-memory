package com.agentmemory.autoimprove;

import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;

/**
 * A finished session the scheduler should review (issue #30): the session plus the project it belongs to.
 * Produced by {@link JdbcAutoImproveStateRepository#dueSessions} — sessions finished after their project's
 * watermark that are not yet reviewed (or are retryable-failed under the attempt cap).
 *
 * @param scope   the session's project.
 * @param session the finished session to review.
 */
public record DueSession(Scope scope, SessionId session) {}
