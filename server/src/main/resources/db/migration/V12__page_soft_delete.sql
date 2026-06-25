-- V12 — `pages.deleted_at`: the soft-delete state the forget sweep flips (issue #25; ARCHITECTURE
-- §5.1).
--
-- The forget sweep is a two-stage eviction (Survey §2.6): a COLD page is first *soft-deleted* — kept
-- on disk (the markdown + git history survive, DD-002) but dropped from "latest" so it stops
-- surfacing in recall/listings — and only a soft-delete that stays cold for `hard_delete_after_days`
-- is finally *purged* (the row hard-deleted). `deleted_at` is the single marker for stage one:
--   NULL      => live
--   non-NULL  => soft-deleted at that instant (recoverable until purge)
-- Soft-delete sets is_latest=false AND deleted_at=now() together; recovery clears deleted_at and (when
-- no newer version exists) restores is_latest. This reuses the existing is_latest mechanic — a
-- soft-deleted page is "not latest" like a superseded one — with deleted_at distinguishing the two so
-- a sweep never touches a normal superseded version and a recovery never resurrects history.

ALTER TABLE pages
    ADD COLUMN deleted_at timestamptz;

COMMENT ON COLUMN pages.deleted_at IS
    'Forget-sweep soft-delete marker (#25): NULL = live; non-NULL = soft-deleted at that instant, '
    'kept until purge (hard delete) after hard_delete_after_days. Soft-delete also flips is_latest off.';

-- The sweep scans soft-deleted rows to find purge candidates (those soft-deleted long enough and not
-- since accessed). A partial index over just the soft-deleted rows keeps that scan cheap and out of
-- the way of the hot "latest" path.
CREATE INDEX pages_soft_deleted_idx
    ON pages (workspace, project, deleted_at)
    WHERE deleted_at IS NOT NULL;
