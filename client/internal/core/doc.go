// Package core is the Go mirror of the server's com.agentmemory.core domain vocabulary
// (issue #3). It defines the typed 3-tuple identity (workspace, project, path), the surrogate
// ids, the canonical observation kinds and the domain records, with JSON tags that match the
// server's serialization byte-for-byte. The shared golden fixtures under docs/contracts/fixtures/
// round-trip identically through this package and the Java records — that cross-language
// agreement is the contract (see docs/contracts/serialization.md).
//
// Like the Java core, this package pulls no third-party dependencies: UUIDv7 generation and
// parsing are implemented here against crypto/rand so the wire identity strategy is owned by the
// domain, not a vendored library.
package core
