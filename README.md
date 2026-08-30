This project is a library of shape finite state machine (FSM) with graph.

## Rational

Instead of using current Clojure FSM libraries, the pros:

 - Can use graph library (ubergraph) to manipulate it.
  - visualize to help human understand
  - stactically check (deadend etc.) to find FSM's potential problems.
 - Can store in a graph library (datahike)
  - a machines states, events, transitions become history

## Shape of a FSM

A FSM shape can be modeled as graph. Suggestion:

 - Each state definition (shaped by Malli schema, validate on enter).
 - Each event definition (by Malli schema)
 - Each transitions (by event only, a function handle the event, return value will be applied to a state)

## FSM shape compilation

A shape can be compiled into a Clojure function, which can be applied to a state (pure data, defined in the shape).

A lifecycle of an instance of the FSM can be seen as a reduction on a seq of events.

## Features

  - State graph definition. A pure clojure data with convinient functions as constructors.
  - Shape compilation
  - Default async handle (by manifold streams): an event stream reduced to a state stream by using transducers.
    - automatically parallelly transitions.
  - Default FSM state persistence (by datahike).
    - audition
    - trace

## Development

 - All data definition, functions guarded by mailli schemas and function schemas
 - Pure unit tests by generative tests (by test.check).
 - Integration tests in a separate meta of kaocha, only performed before commit
 
 - Do not use `clojure repl` to start a common repl
 - Discover nREPL via `clj-nrepl-eval --discover-ports`; if none, launch `clojure -M:dev:nrepl`; eval with `clj-nrepl-eval -p <port>` using `:reload` on all `:requires` 
 - Do NOT manually repair parenthesis errors — run clj-paren-repair

