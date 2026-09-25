(ns robertluo.state-graph
  "THE FACADE: the vocabulary a user needs, and the only require an application should
   have.

   BUILD a shape out of states, events and transitions; LOOK at it with `problems`, `draw!`
   and `dot`, and WALK it with `path`, `components`, `topsort` and `isomorphism`, which is
   what having a graph buys; then RUN it through one of three doors.

   A NODE MAY NEST A WHOLE MACHINE — {:machine sh} on a state — which is how a big problem
   stays readable. It is SOWN with {:seed} on the way in and HARVESTED with {:yield} on the
   way out, and {:done} may say where each of its OUTCOMES goes. See `state`.

   WHAT ANYTHING INSIDE THE MACHINE MAY SEE IS DECLARED, never automatic. A node holds exactly
   the keys its schema names, and a handler reads only through a view its event declares —
   {:sees <a map schema>} on `event`, which is also how a machine accumulates.

   THREE DOORS, ONE MACHINE, AND THE CALLER OWNS THE LIFECYCLE IN ALL OF THEM. That is the
   whole answer to who owns it, and the doors are not three designs:

     the reduction — (reduce (compile sh) (initial sh {}) events), the README's own
                     headline sentence. A step is an ordinary function of a state and an
                     event, so it goes in a fold, a transducer, core.async, a test, or
                     anything else that can hold an accumulator.

     the stream    — (run sh {} events) -> {:states :done}. A channel of events in, a
                     channel of Transitions out. The state lives in a go loop's
                     accumulator exactly as it lives in reduce's; there is no cell
                     holding it and no object to own. `run` is a CALLER THIS LIBRARY
                     SHIPS, not a second kind of machine.

     the crank     — (drive sh []) -> the run. The events are FOUND rather than fed: the
                     shape says which event each state awaits and `:report` says how to
                     go and find it out. One machine, synchronous, and it STOPS ON ITS
                     OWN — at a final state, at an event only the world can supply, or
                     wherever the caller said not yet. `step` is one turn of it.

   THE CRANK IS THE DOOR `:report` WAS MISSING, and it was added late because two
   applications had already written it. An event may declare how it is FOUND and nothing
   here consumed that, so every driver rewrote the same loop — which is a fact about a
   SHAPE and not about anybody's application.

   IT STORES NOTHING. What comes out of `run` is what happened — the event, the state it
   produced, and whether it fired at all — which is everything an audit trail or a trace
   needs. Writing it down is the caller's, and this library has no database in it.

   THESE ARE DELEGATIONS AND NOT ALIASES, which is not cosmetic: (def state shape/state)
   would capture the raw function, and malli's instrument! replaces the VAR's root, so
   every call through such an alias would silently skip the guard the owning namespace
   declares. Measured 2026-09-01. They carry no :malli/schema of their own for the
   matching reason — the contract belongs to the namespace that owns the function, one
   declaration and not two, and a copy here could only drift. `run` is the one function
   this namespace really adds, so it is the one that carries a schema.

   Requires everything below it, which is what makes one require enough — including
   `check`, so an application loads the graph algorithms it may never run. That is what a
   facade costs; a user who minds requires robertluo.state-graph.compile directly."
  {:knowledge
   [{:id :knowledge-is-metadata
     :kind :rule
     :says "What this library knows about itself lives in metadata on the var or namespace it is about, under :knowledge — a vector of nodes — and never in a standalone file. The source is the source of facts, and a decision attached to the construct it decides is found by whoever finds the construct."
     :why "The record was two markdown files, and a markdown file is a TREE: every decision got one place, one section and one reading order, its edges to code and to other decisions were prose pointers nothing could follow, and retrieval was loading 250KB or grepping a key the author chose. Knowledge is a graph — nodes with edges to code and to each other — and the author of a node supplies edges, never a path. Migrated 2026-09-14 from AGENTS.md and DESIGN.md, which are in git history."
     :from "the author, 2026-09-14: `Knowledges (decisions/policies) should be encoded independently, and have edges to different entities (code, other knowledge), it is a multi-bigraph, the user can traverse by graph walking in different way. The author should never limit how to retrieve.` and `:doc is a perfect example of where we should put knowledge: after all, the source code is the source of facts`"
     :when "2026-09-14"}
    {:id :the-knowledge-vocabulary
     :kind :rule
     :says "A node is a map. :id is a keyword unique in the library, the name other nodes cite. :kind is one of :decision (what was decided), :rule (a standing policy for whoever changes this code), :rejected (an alternative turned down, and why), :lesson (what running it taught, in the past tense), :open (a question not settled). :says is the knowledge in a sentence or two; :why the reason; :from the author's own words that decided it; :when an ISO date. The edges: :see names code entities — a qualified keyword is a var, an unqualified one a namespace; :cites names the ids this rests on or refines; :supersedes names the ids this replaces, and the old node stays. Attachment itself is the edge `about`."
     :cites [:knowledge-is-metadata]}
    {:id :knowledge-edges-point-down
     :kind :rule
     :says "A node's :see and :cites point DOWN the arrow or sideways, never up: a namespace's knowledge may name what it requires and its siblings, and never a namespace that requires it. A decision that spans layers is attached at the highest one and looks down from there. Asserted by the suite, which also asserts that every id is unique, every :cites resolves and every :see names a var or namespace that exists."
     :cites [:knowledge-is-metadata :dependencies-point-down-only]}
    {:id :why-it-exists
     :kind :decision
     :says "This library exists for a sibling component's agent workflows: a different concrete workflow per scenario, where the two ways to write that in ordinary code are both bad — a lot of long, nearly identical code, or a configuration format pretending to unify them at the surface. A state machine is the third way. Four claims: complex is not difficult and what those workflows need is GLUE; machines COMPOSE, so an assembly is (apply shape (concat parts wiring)); a workflow mostly in data is storable and drawable, and a person has to be able to SEE what an agent is running; and auditability and static checking are the position — `what happened` and `could this ever have worked`."
     :from "the author, 2026-09-01"
     :when "2026-09-01"
     :cites [:what-the-graph-buys :a-shared-catalogue-must-be-selected-from]}
    {:id :say-the-parallelism-claim-carefully
     :kind :rule
     :says "`The first FSM that supports parallelism` is NOT the claim to make in public: Harel statecharts have had orthogonal regions since 1987, every workflow engine runs steps at once, and this design puts orthogonal regions deliberately out of scope, so it would argue against itself. What is defensible is narrower and stronger: one call runs THOUSANDS of instances at once with real backpressure; a handler may answer a deferred, so an instance waiting on a model call holds no thread; and CONCURRENCY CAN BE PROVEN statically, which no other FSM library appears to do."
     :cites [:parallel-is-across-instances :a-handler-may-answer-later :two-events-in-flight-at-once]}
    {:id :the-facade-is-a-vocabulary-and-two-doors
     :kind :decision
     :says "Twelve functions and three doors: state, event, transition, shape to build a machine; problems, draw! and dot to look at it; compile and initial for the reduction; run for the stream; step and drive for the crank. The author asked for the fewest, so each collapse was argued for rather than assumed, and the third door was added late because two applications had already written it."
     :why "Nesting, the completion transition and the licence all added NO door, which is the check on the surface: a feature that needs no new door is a feature that fitted. The crank cost two, and the alternative was every consumer owning a copy of a loop that is about shapes."
     :cites [:the-crank-is-the-door-report-was-missing :dot-arrived-from-a-consumer]}
    {:id :a-fold-was-turned-down
     :kind :rejected
     :says "A `fold` doing the whole reduction in one call was turned down. It gives strictly LESS than `compile` — a step goes in a transducer and a fold does not — while hiding the thing the README names as a feature."
     :cites [:the-facade-is-a-vocabulary-and-two-doors]}
    {:id :problems-is-opt-in-and-shape-does-not-run-it
     :kind :decision
     :says "`problems` is opt-in and `shape` does not run it. Fewest-functions argued for a strict constructor and no `problems` at all, and it is wrong for one decisive reason: A SHAPE YOU CANNOT BUILD IS A SHAPE YOU CANNOT DRAW, and the whole argument for this library is that a half-finished machine is worth looking at."
     :cites [:the-facade-is-a-vocabulary-and-two-doors :two-kinds-of-check-and-two-places-for-them]
     :see [:robertluo.state-graph/problems :robertluo.state-graph/shape]}
    {:id :re-exports-are-delegating-defns-and-never-def-aliases
     :kind :rule
     :says "Every re-export here is a delegating defn and never a def alias, and carries NO :malli/schema of its own: the contract belongs to the namespace that owns the function, one declaration and not two, and a copy here could only drift. `run` is the one function the facade really adds, so it is the one that carries a schema."
     :cites [:a-def-alias-bypasses-instrumentation]}
    {:id :a-def-alias-bypasses-instrumentation
     :kind :lesson
     :says "A def alias bypasses malli instrumentation: instrument! replaces the VAR'S root binding, so a value captured by (def state shape/state) is the raw function for ever — handed a bad argument it answers happily where the var throws. Measured both ways, 2026-09-01. And the near miss: the alias APPEARED guarded at its 2-arity, because a defn whose body calls itself goes through the var, so the delegation landed in the instrumented wrapper. Testing only the 2-arity would have licensed aliases everywhere."
     :when "2026-09-01"}
    {:id :the-facade-requires-check-knowingly
     :kind :decision
     :says "The facade requires `check`, breaking the property the layering once claimed for it, and loads manifold through `async` and test.check through `laws`. Taken knowingly: it is a load-time cost paid by a require and never by a step, the checks and the drawing are the reason the library exists, and a caller who minds requires robertluo.state-graph.compile directly. What the batteries rule protects survives one level down — compile requires no manifold and never will."
     :cites [:the-defaults-are-batteries :dependency-test-check]}
    {:id :nothing-is-persisted-here
     :kind :decision
     :says "This library stores nothing: it outputs what happened, and what becomes of that is the caller's. datahike left deps.edn, where it had been a dependency nothing used, and the store namespace left the layering, never having been built. What it cost is the one thing it broke: an audit trail must know which event produced which state, and a stream of bare states cannot say — which is what forced the output to be a transition."
     :from "the author, 2026-09-01"
     :when "2026-09-01"
     :supersedes [:what-is-persisted]
     :cites [:the-output-is-a-transition-and-not-a-state :a-run-is-the-vector-of-events]}
    {:id :what-is-persisted
     :kind :decision
     :says "What a caller should keep is HISTORY and not the shape — a shape built at load time out of closures and compiled schemas is not something a database reloads a machine FROM. Shape versioning is out of v1 and stays out, with the question it drags behind it: which shape an instance mid-flight belongs to."
     :why "Superseded by :nothing-is-persisted-here in the sense that nothing is persisted BY THIS LIBRARY; what survives is advice for whoever writes a store outside it."
     :cites [:a-shape-is-code :mid-flight-shape-versioning-stays-open]}
    {:id :dependencies-point-down-only
     :kind :rule
     :says "Dependencies point down only — a lower namespace never refers to a higher one, in code, in a docstring, in a comment or in a :see. A namespace NESTED under another is BELOW it: robertluo.state-graph.shape may not require robertluo.state-graph. Anything that must sit ABOVE the facade is a SIBLING and named accordingly — robertluo.state-graph-x, never robertluo.state-graph.x — so the name agrees with the direction of the arrow."}
    {:id :malli-guards-every-crossing
     :kind :rule
     :says "Malli guards every crossing, data and functions both. A schema that is only written down is a comment: instrument in dev, and conform BY HAND at a seam that must hold in production."
     :cites [:every-seam-is-conformed-in-the-code]}
    {:id :errors-are-data
     :kind :rule
     :says "Errors are DATA — a plain map, m/explain's or our own, over malli.error/humanize prose. Prose reads well to a person and matches badly to a program."
     :see [:robertluo.state-graph.shape/explain]}
    {:id :only-assert-what-can-fail
     :kind :rule
     :says "Only assert what can fail. Do not re-test what a library promises — that ubergraph adds an edge, that malli validates, that manifold delivers what was put on a stream — and do not re-test our own code through a second door. A facade re-export IS that second door: the delegation is not worth a test, and what the facade adds is."
     :cites [:re-exports-are-delegating-defns-and-never-def-aliases]}
    {:id :a-generative-test-needs-an-independent-invariant
     :kind :rule
     :says "A property that recomputes the expected answer the way the implementation computes it agrees with every bug it contains: it catches a wrong implementation and never a wrong understanding. For an FSM the honest invariants are structural — a reduction over events lands only in states the graph admits, a state entered validates against its own schema, replaying a prefix and then the rest equals replaying the whole — and those hold whatever the handlers do."
     :cites [:the-two-doors-agree :the-pass-through-property]}
    {:id :what-the-review-scored
     :kind :lesson
     :says "Scored 2026-09-03 against the code, when the author brought a DECLARATIVE TRANSITION GRAPH proposal as a review — transition fragments with declared inputs, outputs and effects, a separate declarative assembly, and a TOKEN-FLOW runtime owning scheduling, persistence, replay and cancellation. Two thirds of what it asked for was already here. The one-line diagnosis: it is a DATAFLOW model and this is a CONTROL-FLOW model — there a transition fires when its inputs are available, here when an event arrives and the state admits it — and nearly every difference falls out of that substitution. `Park until a human approves` has no dataflow spelling."
     :why "Reading the partial rows together was the finding: they were eleven ways of wanting one thing — a transition caused by the machine's own accumulated state rather than by the world — which this library had refused three times, each for a good and different reason, and each refusal left a named door. The review was the accumulated case for opening exactly one, which is what was built as the completion transition."
     :when "2026-09-03"
     :cites [:a-state-may-say-where-it-goes-when-it-completes :should-a-transition-declare-its-effects-and-idempotence]}
    {:id :a-marking-relocates-the-explosion
     :kind :rejected
     :says "The state-explosion argument against the product construction is correct and was not news — a join IS 2^n states. The counter worth making: a MARKING does not remove the explosion, it RELOCATES it out of the shape, where it is drawable and checkable, into the runtime state, where it is neither. Every static check here rests on ONE STATE BEING ONE MAP WITH ONE SCHEMA; under a marked graph merge(from, out) ⊆ to loses its subject. It trades a decidable checker for a nicer picture."
     :cites [:what-the-review-scored :a-join-is-the-product-and-the-licence]}
    {:id :a-declaration-the-assembler-trusts-is-not-a-proof
     :kind :lesson
     :says "Where this library is ahead of the reviewed proposal: its `essential constraint` was {:purity :effects :idempotence}, a declaration the assembler TRUSTS, promising correct concurrency and naming no mechanism. Here :sees declares reads, :out declares writes, :combine declares how a value lands, and then `commuting` PROVES the reorder by Bernstein, `laws` refutes a false combine by generation, and :agree re-verifies on the concrete values. A rule that lives only in a declaration is the repository's own named anti-pattern."
     :cites [:what-the-review-scored :the-promise-is-data-and-checked-at-two-strengths]}
    {:id :a-runtime-owning-persistence-is-a-framework
     :kind :rejected
     :says "`The runtime owns persistence, replay, observability` is a FRAMEWORK, and a runtime owning persistence has to own shape identity and versioning, which is the question v1 pushed out. Not adopted. The ergonomic complaint — that a named join-all reads better than 8 states and 12 edges — is legitimate and belongs in the consumer."
     :cites [:what-the-review-scored :nothing-is-persisted-here]}
    {:id :can-the-shape-answer-what-a-run-will-cost-at-worst
     :kind :open
     :says "Can the shape answer what a run will cost at worst? A consumer keeps a hand-written formula — laps times lenses times a fan bound times rounds — that is right today and is a second source of truth about a graph this library owns. Three real questions: an event would have to DECLARE that it spends, since the library never knows what a report does, which makes it an annotation on `event`, the one form every consumer writes; the number is a longest path over a cyclic graph, finite only because every cycle is bounded by a guard over a counter, so the bound is inside the guard schema and would have to be read back out of it, and a shape bounded by data has to be able to answer `unbounded, as far as the shape can see`; and a nested machine multiplies, once per entry, which is where a hand formula stops being maintainable. The answer cannot be a scalar: it is keyed by event and the consumer prices it, as `covering` reports per transition and has no opinion about what the report means."
     :why "What says wait: one consumer, one formula, currently right. Do not build it without the human — it changes the one form every consumer writes."
     :from "the author, 2026-09-09, from ../coder: `the ceiling is not an estimate, it is the number calculated from the shape: how many LLM calls will we do in maximum — because it is costly, we always want to know beforehand — it is the whole budget.`"
     :when "2026-09-09"
     :cites [:a-handler-belongs-to-the-event :a-guard-is-a-schema-over-the-event :cover-the-graph-by-running-it]}
    {:id :is-dynamic-fan-out-wanted
     :kind :open
     :says "Is dynamic fan-out wanted — one child per element of a list discovered at runtime, joined when all are done? Mostly answered 2026-09-03 by trying it: the ACCUMULATION was already expressible and the CONCURRENCY needed one character, so what is left is only the completion test. The two structural answers stay refused: a lattice generated per run makes a shape per run and breaks `a shape is code`; a marking relocates the explosion. The width being the driver's is not a gap — a graph shows structure and a count is data — and fan-out ACROSS instances is what `run` already does, nothing joining those back."
     :cites [:may-a-state-complete-on-a-condition-over-its-own-data :the-diagonal-is-the-fan-out :a-marking-relocates-the-explosion :a-shape-is-code]}
    {:id :from-the-sibling-project
     :kind :lesson
     :says "The house rules came from smart-boundary/AGENTS.md, the same author's larger project, in git history since 2026-09-02. What transfers is METHOD and not fact: schemas at every crossing, seams checked in the code and not merely declared, only assert what can fail, and knowledge written in the past tense about things actually observed. Its content was about Anthropic's API, Datalevin and nREPL and applies to nothing here."
     :when "2026-09-02"}
    {:id :a-machine-s-signature-is-bound-to-the-library-s-schemas-at-landing
     :kind :decision
     :says "Six vars here were written by robertluo.coder's authoring machine from briefs, and a brief's signature is self-contained — the trial evaluates it with no library in hand — so a shape in one is `\"Shape\" :any` in a registry, or a bare :any, and the landed code kept that: five vars typed a shape as :any where `check/problems` types it `shape/Shape`. The landing page puts the library's own schema where the placeholder was, by PATH inside the :malli/schema value, as data beside the file and the neighbour. A gate of suite and lint cannot see a schema that is too wide; review rule (3) can."
     :when "2026-09-15"
     :see [:robertluo.state-graph.check/labelled :robertluo.state-graph.check/dot :robertluo.state-graph.check/draw! :robertluo.state-graph.drive/reorder-agrees :robertluo.state-graph.drive/licence-agrees]
     :cites [:re-exports-are-delegating-defns-and-never-def-aliases]}
    {:id :patch-and-applied-were-documentation-types-and-went
     :kind :decision
     :says "`compile/Patch` and `drive/Applied` were public schemas nothing validated with: Patch named what a phase's :patch answers and Applied what a driver's :on is told, and both were reached only by docstrings. Dropped 2026-09-16 for the release — a public var is API surface, and a schema nothing checks with is a comment. What they said is in the docstrings of `phases` and `Options`: a patch is {:answer m :depth n} or ::missed, and :on is told a Transition. Patch's two decisions moved to the compile namespace, where the depth check they are about lives."
     :from "the author, 2026-09-16: `drop`"
     :when "2026-09-16"
     :see [:robertluo.state-graph.compile/phases :robertluo.state-graph.drive/Options]}
    {:id :a-building-namespace-publishes-what-another-reaches-for
     :kind :rule
     :says "A var in a building namespace is public when another namespace or a test reaches for it, and private when only its own file does. `check/produced`, `check/continued`, `shape/combines-of` and `shape/completions` became private 2026-09-16 on that test — no caller outside their file, no test — and their :malli/schema went with the `-`, private helpers carrying none here because instrumentation collects ns-publics. The API a user is promised is the facade's and what the README names; the building namespaces are directly usable and publish no more than that."
     :from "the author, 2026-09-16: `Keep the public api minimum while complete.`"
     :when "2026-09-16"
     :see [:robertluo.state-graph.check/produced :robertluo.state-graph.check/continued :robertluo.state-graph.shape/combines-of :robertluo.state-graph.shape/completions]}
    {:id :what-a-consumer-models-is-not-the-library-s-question
     :kind :decision
     :says "This library provides a state machine whose shape is a graph, and decides nothing about what a consumer models with it. Seven questions this record held open since the library was built are not its to answer, and close together: what a run costs at worst, which is what a consumer's reports spend and the library never knows; whether dynamic fan-out is wanted, whether a state may complete on a condition over its own data, whether node-side exposure is needed, whether internal events are wanted, and whether a transition should declare its effects for retry and replay — each a want a consumer would arrive with as a real shape, and none has; and what becomes of an instance in flight across a shape change, which is the consumer's because this library stores nothing. The refusals that stand under them stand: a completion on a data condition is undecidable, a handler causes nothing, a shape is code. A consumer that needs one of these asks with the shape that needs it."
     :why "Every one of the seven was asked from the other side of the arrow, by the application this library was built beside, about what that application wanted. From here there is no application. A library that holds its consumer's wants as open questions is designing the consumer, and the arrow points the other way — what the README says v1 does not do is the whole of the library's position, and it is a specification and not a backlog."
     :from "the author, 2026-09-16, closing the release's last blocker: `From the state-graph's perspective: there is no machine, all its goal is to provide a state machine using graph. So anything related to it is not a decision should be made by the library.`"
     :when "2026-09-16"
     :supersedes [:can-the-shape-answer-what-a-run-will-cost-at-worst :is-dynamic-fan-out-wanted :may-a-state-complete-on-a-condition-over-its-own-data :is-the-node-side-exposure-needed :are-internal-events-wanted-at-all :should-a-transition-declare-its-effects-and-idempotence :mid-flight-shape-versioning-stays-open]
     :cites [:why-it-exists :nothing-is-persisted-here :a-shape-is-code :a-completion-on-a-data-condition-is-refused :a-handler-causes-nothing]}
    {:id :run-seeds-every-instance-alike-and-fan-seeds-each
     :kind :decision
     :says "`run` gives every instance the same starting data and `async/fan` takes a function of the instance, and the asymmetry stands. `fan` fans ACROSS instances, so a function of the instance is the only way each can be seeded; `run` reduces one stream of events over whatever instances they name, and what differs per instance arrives on the event that STARTS it — where the one pipeline that met this put it, and called it better modelling. The second door's shape is not a fault in the first, and `run`'s signature does not change for the release."
     :when "2026-09-16"
     :see [:robertluo.state-graph/run :robertluo.state-graph.async/fan]
     :supersedes [:run-gives-every-machine-the-same-starting-data]
     :cites [:one-stream-door-and-not-two]}
    {:id :ignored-earns-its-place-and-covering-is-the-second-reader
     :kind :decision
     :says "`Context`'s `:ignored` earns its place, and the second reader the question waited for is this library's own: `explore/covering` drives a shape over the events the shape itself reported and hands `compile` an `:ignored` that is LOUD, because there a miss is not somebody else's stream but a gap between guards arriving late. That is exactly the caller the key was kept for — one who folds by hand and wants to hear about a miss — and it is in the tree. Three lines of surface, read twice."
     :when "2026-09-16"
     :see [:robertluo.state-graph.compile/Context :robertluo.state-graph.explore/covering]
     :supersedes [:is-the-contexts-ignored-still-earning-its-place]
     :cites [:how-the-step-says-a-thing-was-ignored :cover-the-graph-by-running-it]}
    {:id :the-limit-on-handlers-raising-predated-the-crank
     :kind :lesson
     :says "The README's limits said a handler may not raise an event because `there is no queue to drain and no run-to-completion to implement; a cascade is the caller feeding the next event` — and said so for twelve days after `drive` had made run-to-completion a caller this library ships. The sentence was written 2026-09-03 against a machine that could not move itself; the crank landed 2026-09-04; nobody read the limits again. The refusal had itself been conditional — the author's `in our current design, the machine does not own the event queue` — and the condition lapsed the next day. What survives is narrower and was the reason all along: a HANDLER answers a patch, a complete function the check proves and generates over, and the next event comes from a `:report` the shape declares. Three passages said the stale thing and were rewritten 2026-09-16; the decision itself stands."
     :why "A limit written the day before the feature that lifts half of it is the record lying by omission, in the one file this library calls its specification. The check is to read the limits against the API table whenever the table gains a mechanism: a limit that names what the table now has is a limit to reread."
     :from "the author, 2026-09-16: `There is one limitation I think is relevent: no events produced by a handler. I think it should be legit.` — and, offered the machine-may-move-itself reading against the handler-may-emit one, `1`."
     :when "2026-09-16"
     :see [:robertluo.state-graph/drive :robertluo.state-graph.shape/reports]
     :cites [:a-handler-causes-nothing :an-event-may-say-how-it-is-reported :cover-the-graph-by-running-it]}]}
  (:refer-clojure :exclude [compile])
  (:require [robertluo.state-graph.async :as async]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.compile :as compile]
            [robertluo.state-graph.drive :as drive]
            [robertluo.state-graph.shape :as shape]))

;;; ---------------------------------------------------------------- vocabulary

(def Instance
  "What names a RUN of the machine. The caller's to choose — a uuid, an order number, a
   string — and never nil."
  shape/Instance)

(def State
  "A state as it exists at runtime: a map saying which node it is in, and which run it
   belongs to when it has a name."
  compile/State)

(def Event
  "An event as it arrives: a map saying its own type, and which run it is for."
  compile/Event)

(def ^{:knowledge
       [{:id :the-output-is-a-transition-and-not-a-state
         :kind :decision
         :says "`run` puts a RESULT on :states and not a bare state: the :event, the :state it produced, whether it :fired, and :instance where there is one. The argument is that the caller stores now: a state does not say what caused it, and an event nobody handled produces a state EQUAL to the one before, so from a stream of states alone no consumer can build the history this library has declined to keep. A result reads back down with (map :state); the other direction does not exist."
         :why "It does not contradict the step refusing a richer return: that refused an outcome value because the reduction must answer STATES, and A STREAM IS NOT AN ACCUMULATOR — the pump holds the state itself and what it puts is free to be richer. :fired needs a lookup and not a comparison, hence `admits?`; :instance is derived from the state so there is one source for it. The cost is paid by the consumer who needs less."
         :cites [:nothing-is-persisted-here :a-richer-step-return-was-turned-down :fired-needs-a-lookup-and-not-a-comparison]}]}
  Transition
  "WHAT `run` PUTS ON :states — a transition result and not a bare state.

   The reason is that this library stores nothing and the caller does. A state does not
   say what caused it, and an event that nobody handled produces a state identical to the
   one before it — so from a stream of states alone, no consumer can build the history
   this library has just declined to keep. A result can be read back down to states with
   (map :state) whenever that is all somebody wants; the other direction does not exist.

   :fired is false where the state had no transition for the event. That is NOT an error —
   nothing controls the order events arrive in behind a stream — but it is the one fact no
   consumer could recover for itself, and the reason `admits?` is public.

   :instance is DERIVED FROM THE STATE and not from the event, so there is one source for
   it, and it is absent where a caller never named the run."
  [:map [:event Event]
        [:state State]
        [:fired :boolean]
        [:instance {:optional true} Instance]])

;;; -------------------------------------------------------------- building one

(defn state
  "A state: an id, the malli schema of its DATA, and optionally {:initial true},
   {:final true}, {:machine <a shape>} or {:done <id>}. Exactly one state in a shape is the
   initial one.

   THE SCHEMA IS WHAT THIS NODE HOLDS, not a lower bound on it: the merge is projected onto
   these keys on entry, so anything a state does not declare is dropped at its door. Data that
   must survive several states is declared by each of them, and dropping a field is declaring
   one fewer. That is also what bounds what anything INSIDE the machine can see — a state that
   never held a secret cannot leak one.

   It describes the map WITHOUT :id, :instance and :sub — what a state is called, which run it
   belongs to, and what a nested machine is doing are the machine's to say and never a
   handler's.

   {:machine sh} NESTS A WHOLE MACHINE IN THIS NODE. While the parent sits here, that child
   gets every event FIRST and this node's own edges get only what the child does not know —
   so the child's vocabulary decides who handles what, and a child that has finished admits
   nothing and stops competing. The child's state lives under :sub, seeded on entry, dropped
   on the way out, and visible on every result. It is an ordinary shape, so it is checked
   and drawn as one.

   {:seed <a map schema>} IS WHAT THAT CHILD IS STARTED WITH, projected off this node's own
   value on the way in — :yield's MIRROR, and the reason a nesting node can be RE-ENTERED
   with a different job. Without one the child starts with nothing at all, so what it was
   doing could only come from the closure its shape was built from.

   {:done <id>} IS A COMPLETION TRANSITION — where this state goes when it COMPLETES, with
   no event, no handler and no patch. A state with no :machine completes ON ENTRY, so it is
   passed straight through; one WITH a machine completes when that child reaches a final
   state, which is the statechart done-transition and is how a parent waits for its child
   rather than aborting it. One rule, and it is UML's: a simple state has no activity to
   finish, so finishing it is arriving.

   IT MAY SAY WHERE EACH OUTCOME GOES. Given an id it is one target for every way the child
   can finish; given a MAP FROM THE CHILD'S FINAL STATE it is one target per outcome, each
   with a :yield of its own — {:done {:paid {:to :shipping :yield [:map [:receipt :string]]}
   :refused {:to :cancelled}}}.

   It is not a guard and not an event. What it reads is the STRUCTURAL fact it always read —
   which state the child is in — over a set that is finite and known at construction, so the
   dispatch is a map lookup on an id and there is no schema to prove disjoint. A CYCLE among
   states that complete on entry is refused as :done-cycle — an unconditional relation is a
   plain graph, so a cycle in it PROVES the machine would continue for ever rather than
   merely suggesting it might. Two states may complete to ONE target, which is a MERGE and
   not a join: one arrival continues.

   {:yield <a map schema>} IS WHAT A FINISHED CHILD HANDS UP, harvested off the child's own
   final state and merged in before the continuation lands. It needs a :machine and a :done:
   completing is the only moment the child is GUARANTEED final, and so the only moment the
   schema is a guarantee rather than a hope. An escape by an ordinary event is still an
   ABORT and still yields nothing. Beside a per-outcome :done it belongs to the outcome —
   and is then checked against THAT final state alone, which is sharper rather than looser."
  ([id schema] (shape/state id schema))
  ([id schema opts] (shape/state id schema opts)))

(defn event
  "An event: an id, the malli schema of its DATA, the HANDLER that answers it, and
   optionally the schema of what that handler answers.

   GIVEN ONLY AN ID AND A SCHEMA it is a PURE LIFT — the handler answers exactly the keys
   the schema declares and the :out is that schema, which is what most events are and what
   the longer form says three times:

     (event :brief [:map [:brief Brief]])       ; handler and :out are the schema's to give
     (event :green [:map])                      ; an event that carries nothing

   BY DEFAULT the handler takes THE EVENT ALONE — nothing of the state it is about to change —
   and answers a map that is merged into the state. Declaring that map's schema is what lets
   `problems` prove, without running anything, that a target will not admit what a handler
   produces.

   {:sees <a map schema>} as a fifth argument gives the handler a VIEW, and it is the only way
   anything inside the machine reads the state: declared, never automatic, and narrowed to
   exactly the keys named. The handler then takes two arguments, (handler event seen).
   Declaring it on the EVENT rather than the node is what keeps the handler reusable — it names
   what it needs by shape, not by node — and `problems` proves whether the states it reads can
   actually provide it.

   A VIEW IS ALSO HOW A MACHINE ACCUMULATES, and the policy stays ordinary code: read the old
   value, answer the new one, cap or summarise it however the task wants. That is why nothing
   in the shape combines keys for you — a combine could only ever grow."
  ([id schema] (shape/event id schema))
  ([id schema handler] (shape/event id schema handler))
  ([id schema handler out] (shape/event id schema handler out))
  ([id schema handler out opts] (shape/event id schema handler out opts)))

(defn transition
  "An edge: from a state, on an event, to a state. What handles the event belongs to the
   event, so two edges firing one event cannot disagree about it.

   {:when <a map schema>} GUARDS IT, so one event can lead two ways and the SHAPE says
   which rather than a closure somewhere else. Two guarded edges on one [state, event]
   must be provably disjoint or the shape is refused; an event no guard admits fires
   nothing, which is `ignored`."
  ([from event to] (shape/transition from event to))
  ([from event to opts] (shape/transition from event to opts)))

(defn shape
  "The parts as a graph, or a throw carrying what is wrong with them in ex-data.

   What is checked here is REFERENTIAL — answerable from the parts alone, so a shape with a
   transition to a state nobody defined cannot be built. What needs the built graph is
   `problems`, and it is deliberately not run here: a shape under construction is worth
   drawing, and a shape you cannot build is a shape you cannot look at."
  [& parts]
  (apply shape/shape parts))

;;; -------------------------------------------------- looking at what you built

(defn problems
  "What is STRUCTURALLY wrong with a built shape, as DATA — a state nothing reaches, a dead
   end, a trap it can never finish from, a handler whose answer the target refuses. Empty
   when nothing is.

   Only PROVEN faults, and this is the check no other FSM library has: it is answered
   WITHOUT RUNNING ANYTHING."
  [sh]
  (check/problems sh))

(defn draw!
  "The shape as a picture — the same question `problems` answers, by the means a person is
   better at. An unreachable state is obvious in a drawing and invisible in a map literal.

   Needs graphviz, except for {:save {:filename f :format :dot}}, which writes the source
   and is a plain spit. No :save at all opens a viewer. It answers nothing: for the source
   as a VALUE, ask `dot`."
  ([sh] (check/draw! sh))
  ([sh opts] (check/draw! sh opts)))

(defn fingerprint
  "A stable id for the SHAPE of this machine: SHA-256 over its canonical ordered form, as hex.

   Two structurally identical shapes built separately answer one string, and the env a shape
   was built as a function of does not move it, its reports being closures and closures being
   erased.

   WHAT IT PROVES IS THAT THE GRAPH MATCHED — the same states, schemas, events, guards,
   targets, completions and nested children — and NOT that the same code ran: change what a
   handler returns without changing its :out, or change what an :fn predicate checks, and the
   fingerprint does not move.

   It carries no NAME: what a machine is called is a fact about the job and belongs to
   whoever owns the job. This library stores nothing, so writing it beside a transcript row
   is the caller's — and it is what lets that row say which machine produced it."
  [sh]
  (shape/fingerprint sh))

(defn dot
  "The same drawing as GRAPHVIZ SOURCE, as a string — for anything that renders a diagram
   itself rather than shelling out to graphviz: a notebook, a web page, a docs build. Needs
   nothing installed."
  [sh]
  (check/dot sh))

;;; ----------------------------------------------------- walking it as a graph

(defn paths
  "{state -> the shortest path to it from `from`} for every state a run can reach, a path
   being a vector of EDGES — {:from :event :to}, or {:from :done true :outcome :to} for a
   completion — so it reads as the events that get there."
  [sh from]
  (shape/paths sh from))

(defn path
  "The shortest path from one state to another as `paths` answers it, or nil."
  [sh from to]
  (shape/path sh from to))

(defn components
  "The strongly connected components: sets of states each of which a run can get from any
   other in the set."
  [sh]
  (shape/components sh))

(defn topsort
  "The states in an order every edge goes forward in, or nil where there is a cycle."
  [sh]
  (shape/topsort sh))

(defn dag?
  "Does no run ever come back to a state it has left?"
  [sh]
  (shape/dag? sh))

(defn isomorphism
  "{state-of-a state-of-b} under which the two are the same machine with its states renamed,
   or nil. Everything but the state ids must match as it stands."
  [a b]
  (shape/isomorphism a b))

(defn subgraph?
  "Is every state and edge of `a` in `b`, as it stands?"
  [a b]
  (shape/subgraph? a b))

(defn out-degree
  "How many edges leave this state, parallel ones counted each."
  [sh id]
  (shape/out-degree sh id))

(defn in-degree
  "How many edges arrive at this state, parallel ones counted each."
  [sh id]
  (shape/in-degree sh id))

;;; ------------------------------------------------------------- the reduction

(defn compile
  "The shape as an ordinary Clojure function of a state and an event, answering the next
   state. The runtime is then (reduce step (initial sh {}) events) and there is nothing
   else to it — no object, no atom, no protocol.

   An event the state has no transition for answers the state UNCHANGED and never calls the
   handler. Pass a Context to be told about it, or use the stream door, which says so as
   data. What throws is a crossing that does not hold — those are defects, not facts about
   the run."
  ([sh] (compile/compile sh))
  ([sh context] (compile/compile sh context)))

(defn initial
  "The first state: the node the shape starts in, carrying the caller's data, and entered
   through the same validation as every other state.

   NAME THE RUN and the key is written for you — a caller says which machine they mean and
   never spells :instance again, here or in a handler, which could not reach it anyway."
  ([sh data] (compile/initial sh data))
  ([sh instance data] (compile/initial sh instance data)))

;;; ------------------------------------------------------------------ the crank

(defn step
  "One turn of the crank: find what this state is waiting to be told, go and find it out,
   and answer the run with the event saying it.

   A run that is over, PARKED on an event only the world can report, or HELD by what the
   caller permitted this turn comes back UNCHANGED. `drive/awaiting` says which of the
   three it was, and takes the same options.

   IT IS NOT `(compile sh)`, which answers the next STATE from an event you already have.
   This one finds the event."
  ([sh events] (drive/step sh events))
  ([sh events opts] (drive/step sh events opts)))

(defn drive
  "Turn the crank until the machine stops moving, and answer the run it got to.

   IT NEEDS NO COUNTER, which is the only reason a loop belongs in a library: the stopping
   rule is IN THE SHAPE, so a budget or a give-up rule is an EDGE where it can be drawn and
   checked. Driving from [] runs the whole machine and driving from a run carries it on —
   there is no second code path for resuming, because carrying on is what this already is.

   See `robertluo.state-graph.drive` for the rest of the vocabulary: `awaiting`, `where`
   and `advance`, which is the door a person hands an event in by."
  ([sh events] (drive/drive sh events))
  ([sh events opts] (drive/drive sh events opts)))

;;; ----------------------------------------------------------------- the stream

(defn run
  "A shape, the data every machine starts with, and a channel of events -> {:states :done}.

   :states is a channel of Transitions, one per event, closed when no more are coming.
   CONSUME IT, OR :done MAY NEVER RESOLVE — backpressure is real, so a machine whose
   results nobody reads stops rather than racing ahead.

   :done is a promise-chan delivering {instance -> final state}, or the exception the step
   threw, as it was thrown. A caller who named nothing finds their machine under nil.

   ONE FUNCTION FOR ONE MACHINE AND FOR MANY, because the stream is partitioned on
   :instance and one partition is one machine — all of them at once. That is where the
   parallelism is.

   AND WITHIN ONE MACHINE, WHERE IT IS PROVEN. This door computes `check/commuting` and
   hands it down as the LICENCE, so two events pending in one state whose order of
   completion cannot be observed have their handlers run AT ONCE — which is what makes a
   fork-and-join of two slow handlers cost one of them rather than both. Everything else is
   applied strictly in order, an event to what the last one produced.
   THE PRICE, and it is the reason this is worth saying in the docstring: :states then
   reports a licensed pair in COMPLETION order, so the two rows may appear swapped against
   the order they were fed. The pair was PROVED to land in the same state either way, so no
   state is ever wrong; what changes is the history, which should say what happened. A
   caller who wants strict arrival order everywhere drives `async/drive` with no licence.

   Handlers may answer channels here, which is the point of the door: a machine waiting on
   I/O holds no thread, and a slow handler slows only its own machine."
  {:malli/schema [:=> [:cat shape/Shape :map async/Source] async/Machine]
   :knowledge
   [{:id :the-caller-owns-the-lifecycle
     :kind :decision
     :says "The author asked who owns an instance — the caller reducing over a seq, or a reactive machine over a stream — and the question DISSOLVED: they are the same ownership. The state lives in a d/loop accumulator exactly as it lives in reduce's; there is no cell holding it and no object, and the atom `fan` keeps holds per-instance streams and never a state. The reactive machine is the same reduction with the loop shipped, and `run` is A CALLER THIS LIBRARY SHIPS. The facade names both doors and chooses neither."
     :cites [:compilation-and-lifecycle :the-defaults-are-batteries]}
    {:id :reactive-only-was-turned-down
     :kind :rejected
     :says "Reactive-only — the stream as the only door — was the tempting answer and was turned down: manifold would then be on the ONLY path there is, and the one rule the batteries have is that it must not be. The reduction is the README's own headline sentence, and the step is what a caller with core.async, a transducer or a plain fold needs."
     :cites [:the-caller-owns-the-lifecycle]}
    {:id :the-two-doors-agree
     :kind :lesson
     :says "It is a property and not a speech: for a generated shape and a generated event sequence, (map :state) off the stream equals the states the reduction passes through. Worth more than any number of examples, and the only thing that can refute :the-caller-owns-the-lifecycle."
     :cites [:the-caller-owns-the-lifecycle]}
    {:id :one-stream-door-and-not-two
     :kind :decision
     :says "One stream door and not two: `fan` already subsumes `async/drive` — one partition IS one machine — so `run` builds the initial-of function out of the shape and a caller never spells :instance. `async/drive` stays public for somebody who has already partitioned. This is the only layer that knows the shape, so it is the one that computes the licence and hands it down."
     :cites [:the-facade-is-a-vocabulary-and-two-doors :the-runtime-takes-the-licence :done-is-a-map-keyed-by-instance]}
    {:id :run-gives-every-machine-the-same-starting-data
     :kind :open
     :says "`run` gives every machine the same starting data, which `async/fan` does not — fan takes a function of the instance. Found writing a pipeline whose initial state carried a per-manuscript title, and worked around by moving the title onto the event that STARTS the machine, which is better modelling anyway. Whether `run` should accept a function is the author's call."
     :cites [:one-stream-door-and-not-two]}]}
  [sh data events]
  (let [idx (compile/index sh)
        ph  (compile/phases sh async/context)]
    (async/fan (:step ph)
               (fn [instance] (compile/initial sh instance data))
               events
               (fn [state event state']
                 (cond-> {:event event
                          :state state'
                          :fired (compile/admits? idx state event)}
                   (some? (:instance state')) (assoc :instance (:instance state'))))
               {:patch (:patch ph)
                :apply (:apply ph)
                :agree (:agree ph)
                :pairs (check/commuting sh)})))
