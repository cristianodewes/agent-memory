package com.agentmemory.consolidate;

import com.agentmemory.core.Observation;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a session's {@link Observation}s into the plain-text transcript fed to the LLM, and splits
 * that transcript into context-budget-sized chunks for long sessions (issue #18: "chunk/summarize
 * long sessions to fit the model context").
 *
 * <h2>Budget model</h2>
 * The budget is expressed in <strong>characters</strong> rather than tokens so it stays
 * provider-agnostic — no real tokenizer is pulled in, mirroring how the rest of the LLM layer treats
 * sizing. A rough 4-chars-per-token rule of thumb means a char budget of {@code N} keeps a chunk near
 * {@code N/4} tokens; callers derive the char budget from the model's token budget. A single
 * observation larger than the budget is hard-truncated (with a marker) so one giant payload cannot
 * stall chunking.
 *
 * <p>Each observation renders as a compact, labeled block ({@code [seq ts kind] payload}) so the
 * model can follow the timeline and distinguish a prompt from a tool result.
 */
public final class ObservationTranscript {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneOffset.UTC);

    /** Marker appended when a single oversized observation payload is truncated to fit the budget. */
    static final String TRUNCATION_MARKER = " …[truncated]";

    private ObservationTranscript() {
    }

    /**
     * Render one observation as a labeled transcript block.
     *
     * @param seq 1-based position in the session.
     * @param o   the observation.
     * @return the block text (no trailing newline).
     */
    public static String renderBlock(int seq, Observation o) {
        String header = "[" + seq + " " + TS.format(o.createdAt()) + " " + o.kind().wire() + "]";
        String payload = o.payload() == null ? "" : o.payload().strip();
        return payload.isEmpty() ? header : header + " " + payload;
    }

    /**
     * Render the full transcript of all observations as one string (blocks separated by blank lines).
     *
     * @param observations the session observations, in order.
     * @return the joined transcript.
     */
    public static String renderAll(List<Observation> observations) {
        StringBuilder sb = new StringBuilder();
        int seq = 1;
        for (Observation o : observations) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(renderBlock(seq++, o));
        }
        return sb.toString();
    }

    /**
     * Split the observations into chunks whose rendered text each stays within {@code charBudget}.
     * Observations are packed greedily in order; a single observation whose own block exceeds the
     * budget becomes its own chunk, truncated to the budget so it cannot stall the pipeline.
     *
     * @param observations the session observations, in order; must be non-empty.
     * @param charBudget   the per-chunk character budget; must be {@code > 0}.
     * @return one or more chunk strings, each within (or, for a lone oversized block, truncated to)
     *     the budget, preserving overall order.
     */
    public static List<String> chunk(List<Observation> observations, int charBudget) {
        if (observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("observations must be non-empty");
        }
        if (charBudget <= 0) {
            throw new IllegalArgumentException("charBudget must be > 0, was " + charBudget);
        }
        // Render each observation to its block, then greedily pack blocks into budget-sized chunks.
        List<String> blocks = new ArrayList<>(observations.size());
        int seq = 1;
        for (Observation o : observations) {
            blocks.add(renderBlock(seq++, o));
        }
        return packBlocks(blocks, charBudget);
    }

    /**
     * Split an arbitrary text into chunks each within {@code charBudget}, breaking on blank-line
     * (paragraph) boundaries and packing greedily. A single paragraph larger than the budget is
     * hard-truncated (with a marker) so it cannot stall the split. Used by the map-reduce reduce step
     * to keep the joined chunk-summaries within budget across collapse rounds (so the final synthesis
     * never overflows the model context), the same packing the observation-block {@link #chunk} uses.
     *
     * @param text       the text to split; must be non-blank.
     * @param charBudget the per-chunk character budget; must be {@code > 0}.
     * @return one or more chunk strings within (or, for a lone oversized paragraph, truncated to) the
     *     budget, preserving order.
     */
    public static List<String> chunkText(String text, int charBudget) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must be non-blank");
        }
        if (charBudget <= 0) {
            throw new IllegalArgumentException("charBudget must be > 0, was " + charBudget);
        }
        return packBlocks(List.of(text.split("\n\n", -1)), charBudget);
    }

    /**
     * Greedily pack pre-rendered blocks into chunks each within {@code charBudget}, joining blocks
     * with a blank line. A block longer than the budget is flushed alone, truncated to the budget.
     */
    private static List<String> packBlocks(List<String> blocks, int charBudget) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (block.isEmpty()) {
                continue;
            }
            if (block.length() > charBudget) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.add(truncate(block, charBudget));
                continue;
            }
            int separator = current.length() > 0 ? 2 : 0; // the "\n\n" join
            if (current.length() + separator + block.length() > charBudget) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(block);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static String truncate(String block, int charBudget) {
        int keep = Math.max(0, charBudget - TRUNCATION_MARKER.length());
        if (block.length() <= keep) {
            return block;
        }
        return block.substring(0, keep) + TRUNCATION_MARKER;
    }
}
