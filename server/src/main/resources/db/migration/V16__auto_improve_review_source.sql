-- V16 — per-source auto-improve claims (#150).
--
-- Until now every claim in `auto_improve_session_review` belonged to the single wired engine (the #29
-- rule-based curator adapter). #150 adds a second, LLM-backed engine (the session-review source), and a
-- session reviewed by the curator must still be reviewable by the LLM engine — the two are different
-- kinds of work. Namespacing the claim by `source` makes each engine's claim independent:
-- the curator's `done` no longer hides a session from the review engine, and vice versa.
--
-- Legacy rows default to 'curator': every pre-#150 claim was made by the curator adapter (it was the
-- only engine), so tagging them preserves their exact meaning and leaves those sessions due for the
-- new review engine — the catch-up required by the issue's migration criterion.

ALTER TABLE auto_improve_session_review
    ADD COLUMN source text NOT NULL DEFAULT 'curator';

ALTER TABLE auto_improve_session_review
    DROP CONSTRAINT auto_improve_session_review_pkey;

ALTER TABLE auto_improve_session_review
    ADD PRIMARY KEY (session_id, source);

COMMENT ON COLUMN auto_improve_session_review.source IS
    'The proposal source that owns this claim (#150): curator (rule-based) | review (LLM session review).';
