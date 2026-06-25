package com.agentmemory.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agentmemory.core.Identity;
import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.Page;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ChatService} (issue #37): it grounds an answer in compiled pages with
 * numbered citations, bounds the context by the cost-control caps, and — the project guardrail — is
 * <strong>read-only</strong> (it only reads pages, never writes). Mockito doubles for recall + the page
 * store; a scripted offline {@link TestDoubleProvider} captures the prompt the LLM is asked.
 */
class ChatServiceTest {

    private final RecallService recall = mock(RecallService.class);
    private final PageRepository pages = mock(PageRepository.class);
    private final AtomicReference<ChatRequest> captured = new AtomicReference<>();
    private final LlmProvider llm = TestDoubleProvider.builder()
            .chatResponder(req -> {
                captured.set(req);
                return "Recall fuses the FTS and graph arms [1][2].";
            })
            .build();

    private ChatService service(ChatProperties props) {
        return new ChatService(recall, pages, llm, new ChatPrompts(), props);
    }

    private static RecallHit pageHit(String id, String path, String title, int rank) {
        return new RecallHit(HitSource.PAGE, id, path, title, null, 1.0 - rank * 0.1, rank, "snippet");
    }

    private static PageRecord pageWithBody(String body) {
        Identity identity = Identity.ofPage(
                WorkspaceId.of("acme"), ProjectId.of("proj"), PagePath.of("notes/page.md"));
        Page page = new Page(
                PageId.newId(), identity, "Stored Title", body, true, null,
                Instant.parse("2026-06-25T12:00:00Z"), Instant.parse("2026-06-25T12:00:00Z"));
        return new PageRecord(page, MemoryLayer.EPISODIC, 0L, null);
    }

    @Test
    void groundsAnswerInPagesWithNumberedCitationsResolvingToRealPaths() {
        when(recall.search(any())).thenReturn(RecallResult.ofPages(List.of(
                pageHit("id1", "notes/one.md", "Page One", 1),
                pageHit("id2", "notes/two.md", "Page Two", 2))));
        when(pages.readLatest(any()))
                .thenReturn(Optional.of(pageWithBody("body about recall fusion one")))
                .thenReturn(Optional.of(pageWithBody("body about recall fusion two")));

        ChatService.ChatResult result = service(new ChatProperties(true, 6, 6000, 1024))
                .chat(Scope.of("acme", "proj"), "how does recall work");

        assertThat(result.answer()).isEqualTo("Recall fuses the FTS and graph arms [1][2].");
        assertThat(result.citations()).hasSize(2);
        assertThat(result.citations()).extracting(ChatService.Citation::index).containsExactly(1, 2);
        assertThat(result.citations()).extracting(ChatService.Citation::path)
                .containsExactly("notes/one.md", "notes/two.md");
        assertThat(result.citations()).extracting(ChatService.Citation::title)
                .containsExactly("Page One", "Page Two");

        // The LLM was given the numbered excerpts (so it can cite them) and the question.
        String userPrompt = captured.get().messages().get(1).content();
        assertThat(userPrompt).contains("how does recall work").contains("[1]").contains("[2]")
                .contains("notes/one.md").contains("body about recall fusion one");
    }

    @Test
    void boundsTheGroundingContextByMaxContextChars() {
        int maxContextChars = 40;
        when(recall.search(any())).thenReturn(RecallResult.ofPages(List.of(
                pageHit("id1", "notes/one.md", "Page One", 1))));
        when(pages.readLatest(any())).thenReturn(Optional.of(pageWithBody("x".repeat(5000))));

        service(new ChatProperties(true, 6, maxContextChars, 1024))
                .chat(Scope.of("acme", "proj"), "question");

        String userPrompt = captured.get().messages().get(1).content();
        String excerpts = userPrompt.substring(userPrompt.indexOf("--- MEMORY EXCERPTS ---")
                + "--- MEMORY EXCERPTS ---".length());
        // The assembled context is hard-capped at maxContextChars (+ a single ellipsis char).
        assertThat(excerpts.strip().length()).isLessThanOrEqualTo(maxContextChars + 1);
    }

    @Test
    void neverWritesToThePageStore() {
        when(recall.search(any())).thenReturn(RecallResult.ofPages(List.of(
                pageHit("id1", "notes/one.md", "Page One", 1))));
        when(pages.readLatest(any())).thenReturn(Optional.of(pageWithBody("body")));

        service(new ChatProperties(true, 6, 6000, 1024)).chat(Scope.of("acme", "proj"), "q");

        // The only store interaction a chat turn may make is reading the cited page — never a write.
        verify(pages, times(1)).readLatest(any());
        verifyNoMoreInteractions(pages);
    }

    @Test
    void emptyRecallStillAnswersWithNoCitations() {
        when(recall.search(any())).thenReturn(RecallResult.empty());

        ChatService.ChatResult result = service(new ChatProperties(true, 6, 6000, 1024))
                .chat(Scope.of("acme", "proj"), "anything");

        assertThat(result.citations()).isEmpty();
        assertThat(result.answer()).isNotBlank();
        // With nothing recalled, no page is ever read.
        verify(pages, never()).readLatest(any());
    }
}
