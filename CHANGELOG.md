# Changelog

Every entry here is a fact about a released version. The record of *why* is `:knowledge`
metadata on the vars and namespaces themselves; see AGENTS.md for how to reach it.

## Unreleased — towards 0.1

The first version meant to be used from outside the repository it was built in.

- `fingerprint` on the facade: a stable SHA-256 identity for a shape.
- `dot` answers the drawing as graphviz source built from plain data; `draw!` renders it,
  writes a file with `:save`, opens a PNG without one, and swallows nothing — where graphviz
  leaves no usable file or nothing can open one, it throws.
- `drive/reorder-agrees`, a witness that applies a licensed pair of events both ways, and
  `drive/licence-agrees`, the soundness property over generated shapes that the concurrency
  licence had never had.
- Every function written by the machine carries the library's own `shape/Shape` in its
  signature.
- manifold 0.5.0, test.check 1.1.3. test.check is a runtime dependency on purpose:
  `check/laws` generates through malli.generator.
- No graph library: ubergraph is gone, and with it loom, potemkin's graph type, specter,
  dorothy, two priority maps and a ClojureScript 1.7.170 that had ridden onto every JVM
  classpath. A shape is a plain map, `{::nodes {id attrs} ::edges #{edge}}`, and `shape/Shape`
  is a closed malli schema over it. `shape/state-schema`, `shape/successors` and
  `shape/predecessors` are new, and are how `check` reads a shape. Fingerprints are unchanged.
  `check/labelled`, and so `dot` and `draw!`, now refuse a value that is not a shape;
  ubergraph used to throw on one by accident.
- A smaller public surface: `compile/Patch` and `drive/Applied` are gone, being schemas nothing
  validated with, and `check/produced`, `check/continued`, `shape/combines-of` and
  `shape/completions` are private. What a user is promised is the facade and what the README names.
- Running the suites needs graphviz on the `PATH`: the drawing tests render for real.
- The README's limit on a handler raising events is rewritten. The machine has found its own
  events through `:report` and `drive` since the crank landed; the old paragraph predated it and
  said there was no run-to-completion. What stays refused is only the handler doing it.
- No question about the library is left open. Ten `:open` knowledge nodes are superseded by
  decisions; seven of them were a consumer's wants, and the library decides nothing about what a
  consumer models with it — what v1 does not do is a specification, not a backlog.
- Its own repository, `github.com/robertluo/state-graph`, split out of `smart-boundary` on
  2026-09-16 with its history. Until it is on Clojars the coordinate is
  `io.github.robertluo/state-graph {:git/url "https://github.com/robertluo/state-graph" :git/sha "<a commit>"}`.
- Licence: MIT. Coordinates: `io.github.robertluo/state-graph`; `clojure -T:build ci`
  builds the jar and `clojure -T:build deploy` publishes it.
