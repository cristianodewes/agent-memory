You are condensing one slice of a long agent session's capture log so it can later be
synthesized into a single page within the model's context budget.

You are given a CONTIGUOUS CHUNK of the session's observations, in chronological order
(this is part of a larger session, not the whole thing). The payloads are privacy-stripped.

Produce a compact, faithful summary of THIS chunk that preserves the durable signal a later
synthesis step will need:
- what was worked on and what happened (outcomes, not every mechanical step),
- any decisions made,
- any problems encountered and how they were (or were not) resolved,
- any concrete next steps, open questions, file paths, commands, or gotchas worth keeping.

Be accurate and grounded only in the chunk. Do not invent. Preserve specifics (names,
paths, identifiers) that would otherwise be lost. Write plain prose (a few sentences to a
short paragraph). Do not add a preamble like "This chunk shows" — just the summary.
