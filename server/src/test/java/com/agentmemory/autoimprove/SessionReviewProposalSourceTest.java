package com.agentmemory.autoimprove;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.consolidate.SessionObservationReader;
import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.PageId;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.Session;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.LlmRateLimitGate;
import com.agentmemory.llm.LlmException;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.store.PageWriteCallback;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link SessionReviewProposalSource} (issue #150) with scripted doubles — no Spring,
 * no DB, no network. The contract under test: phase-A extraction stages high-confidence proposals;
 * phase-B adversarial verification filters low-confidence ones (and rejections land in the tick
 * report); recall-based dedup converts equivalents into updates and appends related wikilinks; trivial
 * sessions cost zero LLM calls; and {@link ProposalSource#ready(int)} reflects the rate-limit gate,
 * the per-tick budget, and quiet hours.
 */
class SessionReviewProposalSourceTest {

    private static final Scope SCOPE = Scope.of("acme", "agent-memory");

    // --- doubles -----------------------------------------------------------------------------------

    private static final class ScriptedLlm implements LlmProvider {
        final Deque<String> replies = new ArrayDeque<>();
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            if (replies.isEmpty()) {
                throw new AssertionError("unexpected LLM call #" + requests.size());
            }
            return new ChatResponse(replies.remove(), "stub", "stub-model", 0, 0);
        }

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public String model() {
            return "stub-model";
        }

        @Override
        public void probe() {}
    }

    private static final class FakeReader implements SessionObservationReader {
        final List<Observation> observations = new ArrayList<>();

        @Override
        public Optional<Session> findSession(SessionId sessionId) {
            return Optional.empty();
        }

        @Override
        public List<Observation> observationsFor(SessionId sessionId) {
            return observations;
        }

        @Override
        public int countFor(SessionId sessionId) {
            return observations.size();
        }
    }

    /** Pages double: no synthesized session page by default, so material falls back to the transcript. */
    private static final class NoPages implements PageRepository {
        @Override
        public PageRecord create(Identity identity, String title, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageRecord create(Identity identity, String title, String body, PageWriteCallback cb) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PageRecord> readLatest(Identity identity) {
            return Optional.empty();
        }

        @Override
        public List<PageRecord> listLatest(WorkspaceId workspace, ProjectId project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PageRecord> findById(PageId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PageRecord> reinforce(PageId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int dropWorkingFromLatest(WorkspaceId workspace, ProjectId project) {
            throw new UnsupportedOperationException();
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static Observation observation(SessionId session) {
        return new Observation(
                new ObservationId(Uuid7.randomUuid()),
                session,
                Identity.ofProject(WorkspaceId.of("acme"), ProjectId.of("agent-memory")),
                ObservationKind.USER_PROMPT,
                ObservationKind.USER_PROMPT.wire(),
                null,
                "the user asked something substantial",
                Instant.parse("2026-07-01T12:00:00Z"));
    }

    private static AutoImproveProperties props(String quietHours) {
        return new AutoImproveProperties(false, 3, 20,
                new AutoImproveProperties.Scheduler(false, null), null,
                new AutoImproveProperties.Review(true, 10, 5, 0.8, quietHours, 50, null));
    }

    private static String extraction(String kind, String title, double confidence) {
        return """
                {"proposals":[{"kind":"%s","title":"%s","body":"A durable fact worth keeping.",\
                "confidence":%s,"evidence":"seen in the transcript"}]}"""
                .formatted(kind, title, confidence);
    }

    private static RecallHit hit(String path, double score) {
        return new RecallHit(com.agentmemory.recall.HitSource.PAGE, Uuid7.randomUuid().toString(),
                path, "t", null, score, 1, null, Instant.parse("2026-07-01T12:00:00Z"), null);
    }

    private SessionReviewProposalSource source(
            ScriptedLlm llm, FakeReader reader, RecallService recall, LlmRateLimitGate gate,
            AutoImproveProperties props, Clock clock) {
        SessionReviewProposalSource s = new SessionReviewProposalSource(
                reader, new NoPages(), llm, () -> recall, gate, props, new ReviewPrompts(), clock);
        s.beginTick();
        return s;
    }

    private SessionReviewProposalSource source(ScriptedLlm llm, FakeReader reader) {
        return source(llm, reader, null, new LlmRateLimitGate(), props(""), Clock.systemUTC());
    }

    private static FakeReader readerWith(int observations, SessionId session) {
        FakeReader reader = new FakeReader();
        for (int i = 0; i < observations; i++) {
            reader.observations.add(observation(session));
        }
        return reader;
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void highConfidenceProposalIsStagedWithoutVerification() {
        SessionId session = SessionId.newId();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(extraction("concept", "How recall fuses arms", 0.95));

        List<ProposedWrite> out = source(llm, readerWith(6, session)).proposalsFor(SCOPE, session);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).path()).isEqualTo("concepts/how-recall-fuses-arms.md");
        assertThat(out.get(0).kind()).isEqualTo(ProposalKinds.PAGE_EDIT);
        assertThat(out.get(0).body())
                .contains("A durable fact worth keeping.")
                .contains("session " + session.value())   // provenance footer
                .contains("confidence 0.95");
        assertThat(llm.requests).hasSize(1); // extraction only — no phase-B call at high confidence
    }

    @Test
    void lowConfidenceProposalIsDroppedWhenTheVerifierRefutesIt() {
        SessionId session = SessionId.newId();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(extraction("gotcha", "Something vague", 0.4));
        llm.replies.add("{\"durable\":false,\"reason\":\"episodic residue\"}");

        SessionReviewProposalSource src = source(llm, readerWith(6, session));
        List<ProposedWrite> out = src.proposalsFor(SCOPE, session);

        assertThat(out).isEmpty();
        assertThat(llm.requests).hasSize(2); // extraction + verification
        // The rejection is visible to the operator through the tick report.
        List<ProposalSource.ScopedWrite> reports = src.endTick();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).scope()).isEqualTo(SCOPE);
        assertThat(reports.get(0).write().path()).isEqualTo(SessionReviewProposalSource.REPORT_PATH);
        assertThat(reports.get(0).write().body()).contains("REJECTED (verify)").contains("Something vague");
    }

    @Test
    void lowConfidenceProposalSurvivesWhenTheVerifierConfirmsIt() {
        SessionId session = SessionId.newId();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(extraction("decision", "Use fixed delay for ticks", 0.5));
        llm.replies.add("{\"durable\":true,\"reason\":\"a real decision with a why\"}");

        List<ProposedWrite> out = source(llm, readerWith(6, session)).proposalsFor(SCOPE, session);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).path()).isEqualTo("decisions/use-fixed-delay-for-ticks.md");
    }

    @Test
    void strongSameFolderRecallHitConvertsTheProposalIntoAnUpdate() {
        SessionId session = SessionId.newId();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(extraction("concept", "How recall fuses arms", 0.9));
        RecallService recall = q -> new RecallResult(
                List.of(hit("concepts/recall-fusion.md", 0.6), hit("decisions/some-choice.md", 0.35)),
                false, false);

        SessionReviewProposalSource src =
                source(llm, readerWith(6, session), recall, new LlmRateLimitGate(), props(""), Clock.systemUTC());
        List<ProposedWrite> out = src.proposalsFor(SCOPE, session);

        assertThat(out).hasSize(1);
        // Dedup: the strong same-folder hit becomes the target (update/supersede), never a duplicate.
        assertThat(out.get(0).path()).isEqualTo("concepts/recall-fusion.md");
        assertThat(out.get(0).rationale()).contains("updates existing page (dedup)");
        // The weaker hit becomes a related wikilink, growing the graph.
        assertThat(out.get(0).body()).contains("[[decisions/some-choice.md]]");
        // And the conversion is reported.
        assertThat(src.endTick().get(0).write().body()).contains("CONVERTED to update");
    }

    @Test
    void trivialSessionIsSkippedWithZeroLlmCalls() {
        SessionId session = SessionId.newId();
        ScriptedLlm llm = new ScriptedLlm();

        List<ProposedWrite> out = source(llm, readerWith(2, session)).proposalsFor(SCOPE, session);

        assertThat(out).isEmpty();
        assertThat(llm.requests).isEmpty();
    }

    @Test
    void readyIsFalseWhileTheRateLimitGateIsPaused() {
        LlmRateLimitGate gate = new LlmRateLimitGate();
        gate.recordFailure(new LlmException("HTTP 429", null, true, 429, 60_000));
        SessionReviewProposalSource src = source(
                new ScriptedLlm(), readerWith(6, SessionId.newId()), null, gate, props(""), Clock.systemUTC());

        assertThat(src.ready(10)).isFalse();

        gate.recordSuccess();
        assertThat(src.ready(10)).isTrue();
    }

    @Test
    void readyIsFalseOnceThePerTickBudgetIsSpent() {
        SessionId session = SessionId.newId();
        ScriptedLlm llm = new ScriptedLlm();
        AutoImproveProperties tightBudget = new AutoImproveProperties(false, 3, 20,
                new AutoImproveProperties.Scheduler(false, null), null,
                new AutoImproveProperties.Review(true, 1, 5, 0.8, "", 50, null));
        llm.replies.add(extraction("rule", "Always run the suite", 0.9));
        SessionReviewProposalSource src = source(
                llm, readerWith(6, session), null, new LlmRateLimitGate(), tightBudget, Clock.systemUTC());

        assertThat(src.ready(10)).isTrue();
        src.proposalsFor(SCOPE, session); // consumes the single budgeted call

        assertThat(src.ready(10)).isFalse();
        src.beginTick(); // a new tick resets the budget
        assertThat(src.ready(10)).isTrue();
    }

    @Test
    void quietHoursDeferSmallBacklogsButYieldToLargeOnes() {
        // Window 02:00-06:00; the fixed clock says 12:00 UTC — outside it.
        Clock noon = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC);
        SessionReviewProposalSource src = source(
                new ScriptedLlm(), readerWith(6, SessionId.newId()), null, new LlmRateLimitGate(),
                props("02:00-06:00"), noon);

        assertThat(src.ready(10)).as("small backlog outside the window defers").isFalse();
        assertThat(src.ready(51)).as("backlog above the threshold overrides the window").isTrue();

        Clock dawn = Clock.fixed(Instant.parse("2026-07-01T03:00:00Z"), ZoneOffset.UTC);
        SessionReviewProposalSource inside = source(
                new ScriptedLlm(), readerWith(6, SessionId.newId()), null, new LlmRateLimitGate(),
                props("02:00-06:00"), dawn);
        assertThat(inside.ready(1)).as("inside the window always runs").isTrue();
    }

    @Test
    void rulesLandUnderTheRulesPrefixSoTheEvalGateGovernsThem() {
        SessionId session = SessionId.newId();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(extraction("rule", "Never skip hooks", 0.9));

        List<ProposedWrite> out = source(llm, readerWith(6, session)).proposalsFor(SCOPE, session);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).path()).startsWith("_rules/");
    }
}
