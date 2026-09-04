λ engage(nucleus).
[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h signal/noise order/entropy truth/provability self/other] | OODA
Human ⊗ AI ⊗ REPL

Refactor: [τ μ] | [Δ Σ/μ] → λcode. Δ(minimal(code)) where behavior(new) = behavior(old)
API: [φ fractal] | [λ ∞/0] → λrequest. match(pattern) → handle(edge_cases) → response
Debug: [μ] | [Δ λ ∞/0] | OODA → λerror. observe → minimal(reproduction) → root(cause)
Docs: [φ fractal τ] | [λ] → λsystem. map(λlevel. explain(system, abstraction=level))
Test: [π ∞/0] | [Δ λ] | RGR → λfunction. {nominal, edge, boundary} → complete_coverage
Review: [τ ∞/0] | [Δ λ] | OODA → λdiff. find(edge_cases) ∧ suggest(minimal_fix)
Architecture: [φ fractal euler] | [Δ λ] → λreqs. self_referential(scalable(growing(system)))

{:statechart/id :robertluo.state-graph.workflow
 :initial :initialize
 :context
 {:project "robertluo.state-graph"
  :one-line "A finite state machine whose SHAPE is a graph — so a graph library can draw it,
             check it and store it, and a compiler can turn it into an ordinary Clojure function."
  :why-it-exists
  "THIS LIBRARY EXISTS FOR THE SIBLING COMPONENT'S AGENT WORKFLOWS, said by the author 2026-09-01.
   A different concrete workflow per scenario, and the two ways to write that in ordinary code are
   both bad — a lot of long, nearly identical code, or a configuration format pretending to unify
   them at the surface. A state machine is the third way. FOUR CLAIMS, in one line each: complex is
   not difficult, and what those workflows need is GLUE; machines COMPOSE, so an assembly is
   (apply shape (concat parts wiring)); a workflow mostly in DATA is storable and drawable, and a
   person has to be able to SEE what an agent is running; and AUDITABILITY AND STATIC CHECKING are
   the position — `what happened` and `could this ever have worked`.
   AND SAY THE PARALLELISM CLAIM CAREFULLY. `The first FSM that supports parallelism` is NOT the
   claim to make in public — Harel had orthogonal regions in 1987, and this design puts them out of
   scope, so it argues against itself. What is defensible is narrower and stronger: one call runs
   THOUSANDS OF INSTANCES at once with real backpressure; a handler may answer a DEFERRED, so an
   instance waiting on a model call holds no thread; and CONCURRENCY CAN BE PROVEN statically, which
   no other FSM library appears to do. See DESIGN.md :why-it-exists."

  :source-of-truth
  "THREE FILES, AND EACH WINS ABOUT SOMETHING DIFFERENT.
   README.md IS THE SPECIFICATION and beats both of the others. Every example in it was run against
   the code before it was written down, so PROPOSED is not a category: everything is built, and a
   divergence is a bug in one of the three. It also carries the LIMITS deliberately — no
   state-dependent update without a view, no internal events, no persistence, no orthogonal regions
   — so a user meets one of those in the README and not in a surprise.
   THIS FILE IS THE OPERATIONAL AUTHORITY: the rules, the layering, the constraints, the workflow,
   one paragraph per decision, and the traps worth not re-learning. It is loaded in full every
   session, which is why every entry in it is short.
   DESIGN.md IS THE ARGUMENT behind each of those paragraphs — why a decision was made, what was
   turned down and why, and the full account of what running it taught. READ ON DEMAND, and READ IT
   BEFORE REOPENING A QUESTION or proposing something an entry records as refused. Each of its
   headings is a key in this file's :design or :project-knowledge, so `DESIGN.md :some-key` is a
   grep away. Where the two appear to disagree, THIS file is right about WHAT was decided and
   DESIGN.md is right about WHY, and the disagreement is a bug to fix in one of them."

  :constraints
  {:tests-clojure-test true
   :tests-generative-first true
   :test-tree "test/, one <ns>_test.clj per source namespace"
   :test-runner "kaocha, two suites: unit and integration, separated by a ^:integration meta"
   :notebook-cmd "clojure -X:notebook"
   :test-cmd-fast "clojure -M:dev:test unit"
   :test-cmd-gate "clojure -M:dev:test integration"
   :lint-cmd "clojure -M:lint --lint src test notebook — an ALIAS, not a binary on the path"
   :eval-mechanism :nrepl-exclusive
   :malli-shapes-all-data true
   :malli-function-schemas true
   :no-bare-try-catch true
   :paren-repair-tool "clj-paren-repair"
   :repl-launch-cmd "clojure -M:dev:nrepl"
   :repl-discover-cmd "clj-nrepl-eval --discover-ports"
   :repl-eval-cmd "clj-nrepl-eval -p <port>"
   :repl-eval-reload :per-namespace-in-dependency-order
   :deps {:ubergraph "0.9.0" :malli "0.20.1" :manifold "0.4.3" :test.check "1.1.1"
          :dev {:nrepl "1.3.0" :kaocha "1.91.1392"}}
   :inherited-from "GIT HISTORY, under smart-boundary/AGENTS.md — the sibling component the house
                    rules came from, removed 2026-09-02 once each component carried its own. The
                    rules hold here regardless (dependencies point down, errors are data, only
                    assert what can fail, a store is closed in a `finally`); its :project-knowledge
                    was about Anthropic, Datalevin and nREPL and was never about this project. Its
                    living descendants are ../coder/AGENTS.md and ../llm-function/AGENTS.md."}

  :global-rules
  ["Do NOT manually repair parenthesis errors — run clj-paren-repair"
   "NEVER start a plain `clojure repl` / `clj`. nREPL is the ONLY way code is evaluated:
    discover with clj-nrepl-eval --discover-ports, launch with clojure -M:dev:nrepl, eval with
    clj-nrepl-eval -p <port>. The session persists across invocations"
   "NEVER (require ... :reload-all). malli is a dependency here, and :reload-all reloads malli.core
    itself, redefining its protocols: every compiled schema already in malli's function-schema
    registry then satisfies neither m/schema? nor m/Schema, and every instrument! afterwards is a
    StackOverflowError, for every var, however trivial. Use :reload per namespace, in dependency
    order (see :layering). Recovery is (reset! @#'malli.core/-function-schemas* {}) and a :reload
    of each namespace — a plain :reload alone does NOT recover, the stale objects being in malli's
    registry rather than ours"
   "A REPL whose classpath predates a new dependency CANNOT be repaired from inside — neither
    clojure.repl.deps/add-lib nor sync-deps has worked here. After touching deps.edn, restart it.
    A JVM also INHERITS ITS PATH AT LAUNCH, so a REPL started before graphviz was installed cannot
    draw however current the devenv is: start it from inside the devenv"
   "ALWAYS read the full error message before acting — do not skip or guess"
   "After every edit, read the file back to verify the edit landed correctly"
   "Inspect the source code for the bug BEFORE running REPL tests — do not test blindly"
   "DEPENDENCIES POINT DOWN ONLY — a lower namespace never refers to a higher one, in code, in a
    docstring, or in a comment. A namespace NESTED under another is BELOW it, so
    robertluo.state-graph.shape may not require robertluo.state-graph. See :layering"
   "Do NOT thread options through layers we do not own — inject a function that closes over them.
    The async layer is handed a compiled step FUNCTION; it never learns what a shape is"
   "MALLI GUARDS EVERY CROSSING, data and functions both. A schema that is only written down is a
    comment: instrument in dev, and conform BY HAND at a seam that must hold in production"
   "Errors are DATA — a plain map (m/explain, or our own) over malli.error/humanize prose. Prose
    reads well to a person and matches badly to a program"
   "TESTS LIVE IN test/, NOT IN THE SOURCE. One <ns>_test.clj per source namespace, ordinary
    clojure.test, run by kaocha. A test that opens a database or a socket is marked ^:integration
    and is NOT in the fast loop"
   "ONLY ASSERT WHAT CAN FAIL. Do not re-test what a library promises — that ubergraph adds an
    edge, that malli validates, that manifold delivers what was put on a stream — and do not
    re-test our own code through a second door. A FACADE RE-EXPORT IS THAT SECOND DOOR: the
    delegation is not worth a test, and what the facade adds is"
   "A GENERATIVE TEST NEEDS AN INDEPENDENT INVARIANT. A property that recomputes the expected
    answer the way the implementation computes it agrees with every bug it contains: it catches a
    wrong implementation and never a wrong understanding. For an FSM the honest invariants are
    structural — a reduction over events lands only in states the graph admits, a state entered
    validates against its own schema, replaying a prefix and then the rest equals replaying the
    whole — and those hold whatever the handlers do"
   "EVERYTHING A TEST OPENS IS RELEASED IN A `finally`. `finally` is release and is NOT the
    forbidden try/catch. NOTHING HERE OPENS A DATABASE ANY MORE, so what this governs is files and
    streams — and a STREAM has a second obligation: a deref of a machine that never resolves hangs
    the suite, so EVERY DEREF IN A STREAM TEST IS BOUNDED"
   "COMMIT GATE: clojure -M:dev:test integration passes. The fast suite is for every save"
   "A DECISION BELONGS IN ONE PLACE. This file is loaded in full every session, so an entry here is a
    PARAGRAPH and the argument for it is DESIGN.md under the same key. When a decision changes, change
    BOTH — the paragraph and the entry — or they drift and the pointer starts lying. When one is
    ADDED, add both. Superseded reasoning goes to git history and not into either file"
   "EDITING THIS FILE: IT IS DATA, SO CHECK IT BY PARSING IT, and anchor a string replacement on a
    key AND ITS OPENING QUOTE. A bare `:some-key` matches its own PROSE REFERENCES, of which every
    decision here has several, so a replacement aimed at an entry lands INSIDE another entry's
    string, silently, and the file stops parsing. An unterminated string is INVISIBLE to a bracket
    balance — it shifts which quotes pair with which and leaves every { and } matched — so verify
    with (clojure.edn/read-string (subs s (index-of s \"{:statechart/id\"))) and nothing weaker.
    Three repairs so far, every one caught by the parse and none by a balance check.
    THE FORMATTER HOOK fires on the file-writing TOOLS and not on a shell heredoc, and it will
    reflow a whole source file on first touch — see :what-completion-taught"]

  :layering
  ["EVERY LAYER IS BUILT. Four namespaces and one arrow through them; the store that was once in
    this list is GONE, see :nothing-is-persisted-here.

    robertluo.state-graph          — THE FACADE: the vocabulary a user needs, and the only require
                                     an application should have. TWELVE functions — state, event,
                                     transition, shape; problems, draw!, dot; compile, initial;
                                     run; step, drive — being the constructors, the checks, and the
                                     THREE doors. Requires everything below it, `check` included,
                                     which is what one require costs.
                                     See :the-facade-is-a-vocabulary-and-two-doors
    robertluo.state-graph.drive    — THE CRANK: the door that FINDS its own events, added 2026-09-04
                                     because `:report` had been declared with nothing here consuming
                                     it, so two applications wrote the same driver. awaits, awaiting,
                                     where, advance, step, drive. Synchronous, one machine, and it
                                     RECURSES INTO A NESTED CHILD and takes a join `confluence`
                                     proves. Requires shape, compile and check — a SIBLING of async
                                     and not above it. See :the-crank-is-the-door-report-was-missing
    robertluo.state-graph.async    — A DEFAULT, not the core: manifold streams. Takes a compiled
                                     step FUNCTION, a way to make a first state, a way to make an
                                     OUTPUT VALUE and a `Licence`, all as VALUES, and knows nothing
                                     of shapes, schemas or graphs. `drive` is one machine,
                                     serialised; `fan` partitions on :instance and runs one per
                                     machine, concurrently. Both answer {:states :done}
    robertluo.state-graph.compile  — shape -> (fn [state event] state'). The only namespace that
                                     turns data into a function, and the only one both defaults are
                                     above in spirit and below in the arrow: they take its OUTPUT
                                     as a value, so neither requires it. `phases` splits the step
                                     into a PATCH half and an APPLY half; the step is BUILT from
                                     the two, so the one-call and two-call doors cannot drift
    robertluo.state-graph.check    — What the graph BUYS: the static checks and the drawing, which
                                     answer the same question by different means. A SIBLING of
                                     compile, not a part of shape: nothing here is on the runtime
                                     path, and an application shipping a working shape never loads
                                     it. Requires shape, ubergraph, malli and malli.generator —
                                     the last since `laws`, which means the facade loads test.check
                                     as well. Knowingly; see :what-the-combine-taught
    robertluo.state-graph.shape    — THE BOTTOM: the graph itself. Pure data plus constructors,
                                     ubergraph underneath, the malli schemas of a shape, and the
                                     REFERENTIAL checks — the ones answerable from the parts alone,
                                     `disjoint` among them. A node may carry a whole SHAPE as its
                                     :machine, so the type is recursive and every layer above
                                     recurses with it

    The default sits BELOW the facade rather than beside it because of the nesting rule — a child
    may not require its parent — and it costs nothing, since what it needs is a function and some
    data, handed over as values."]

  :layering-rule
  "A namespace NESTED under another is BELOW it: robertluo.state-graph.shape requiring
   robertluo.state-graph would be a child reaching for its parent. If something genuinely must sit
   ABOVE the facade it is a SIBLING of it and named accordingly — robertluo.state-graph-x, never
   robertluo.state-graph.x. The name has to agree with the direction of the arrow."


  :design
  ;; ONE PARAGRAPH PER DECISION. The argument for each — what was turned down, what it cost, what
  ;; was measured — is DESIGN.md under the same key. Read that before reopening one.
  {:the-shape-is-a-graph
   "Three definitions and no more. A STATE is a node, shaped by a malli schema, VALIDATED ON ENTER.
    An EVENT is shaped by a schema too, and is a value rather than a keyword with baggage. A
    TRANSITION is an edge keyed BY EVENT ONLY, carrying a function whose RETURN VALUE IS APPLIED TO
    the state. Two events may join one pair of states, so the shape is a MULTI-digraph. :initial is a
    NODE ATTRIBUTE, exactly one per shape, while the starting DATA stays an argument to the reduction."

   :what-the-graph-buys
   "The whole argument for not writing another FSM library: a shape that is a graph can be DRAWN, so
    a person SEES the machine; CHECKED STATICALLY, which is the part that pays; and STORED. The check
    worth building first is the one no other FSM library has — a transition whose handler cannot
    produce a value the target's schema admits is a bug findable WITHOUT RUNNING ANYTHING."

   :compilation-and-lifecycle
   "(compile shape) -> a pure function of a state and an event. The lifecycle is (reduce step initial
    events) and that is the whole runtime: no object, no atom, no protocol. Everything else is a way
    of getting events into that reduction or results out of it, which is why a stream is a layer
    above and not the core."

   :the-defaults-are-batteries
   "Async is a DEFAULT. Someone with their own stream library must use the compiled function directly
    and lose nothing, so it takes a step, an initial-of, a result-of and a licence, all as VALUES,
    and never a shape. THE HONEST STATEMENT OF THE RULE: the facade DOES load manifold; what it
    protects survives one level down, robertluo.state-graph.compile requiring none and never will."

   :two-kinds-of-check-and-two-places-for-them
   "shape/problems is REFERENTIAL — answerable from the PARTS alone, so it runs inside the constructor
    and a bad shape never exists. check/problems is STRUCTURAL — it needs the built graph, so it is a
    separate namespace and opt-in, off the runtime path. REACHABILITY IS A TRAVERSAL FROM THE ROOT
    and not `has no in-edge`: two states reaching only each other both have in-edges and are both
    unreachable. WHERE A CHECK LIVES IS DECIDED BY WHEN IT MUST ANSWER, not by what it resembles.
    THE FAULT VOCABULARY. A fault is a map carrying :problem, the id it is about, :within
    [<host node> ...] where nested, and a :witness where something could construct one.
      REFERENTIAL, refused by the constructor — :unused-event, :reserved-declared (a state declaring
      :id, :instance or :sub), :machine-cannot-start, :ambiguous, :done-cycle, :done-with-edges,
      :reads-without-report
      STRUCTURAL, reported by check/problems — :unreachable, :dead-end, :trap, :target-refuses,
      :view-unavailable, :yield-unavailable, :reads-unavailable
      PUBLISHED AND NEVER FAULTED, being coverage rather than fault — subsumption, views, coverage,
      confluence, commuting, laws, readings, yields, DRIVING
    AND A PUBLISHED CHECK ANSWERS ABOUT THE MACHINE. Every one that answers in MAPS recurses into
    nested children and carries :within; the ones answering a SET OF IDS — reachable, traps,
    dead-ends, finishable — are about ONE graph and stay there, a set having nowhere to say which
    machine it meant. `problems` bridges them by recursing itself, and takes only its OWN from the
    checks that now recurse. See :a-published-check-answers-about-the-machine"

   :a-partial-subsumption-checker
   "`admits` answers :yes, :no or :unknown, and IT NEVER LIES — malli has no subsumption, so it is
    written here structurally over :map entries. It proves a REQUIRED key that may be missing, a TYPE
    that cannot match (seven primitives verified pairwise disjoint), and a finite domain that can be
    TRIED. :unknown IS AN ANSWER AND NOT A FAILURE: `problems` reports only PROVEN faults, and a
    checker that cries about what it could not work out is one people turn off. WHAT IS CHECKED MUST
    BE WHAT RUNS — `produced` composes in the order compile composes, and `continued`, `accepted` and
    `yields` are the same discipline for the other crossings. Soundness is tested BY GENERATION."

   :the-first-target
   "The first target was THE SPINE — shape and compile — and deliberately NOT the checks, though they
    are the differentiator. The argument is worth keeping for the next such decision: a check written
    over a shape nothing has ever run is a check over a shape that is probably wrong."

   :a-shape-is-code
   "A STATE MACHINE SHAPE IS CODE. You WRITE it as data; it does not ROUND-TRIP as data. Built at
    namespace load, handlers are real closures, schemas compiled once. THAT KILLS every argument from
    EDN, from =, from storability — which were the only objections to the shape BEING an Ubergraph,
    so it is one. And it settles persistence: what is stored is HISTORY, not the shape. It is also
    what licenses a COMBINE to be a closure."

   :a-state-has-an-id
   "A state is a MAP with :id — the same word in both places it is needed, the NODE in the graph and
    the runtime value saying which node it is in. FORCED: the step is (fn [state event] state') and
    must know whose out-edges to search. A node's schema describes the REST of the map, and what is
    validated on enter is the DERIVED merge, never written by hand. THE EDGE ALWAYS WINS — the target's
    :id is assoc'd AFTER the merge, and a handler that tries to write one is now REFUSED."

   :a-handler-never-sees-the-state
   "The sharpest decision here: a handler takes THE EVENT ALONE — (handler event), or (handler event
    seen) where the event declares a {:sees} view. THE REASON IS DECOUPLING, and THE BIGGER PAYOFF IS
    THE CHECK: a handler with no state in it is a COMPLETE malli function on its own, both halves off
    the event definition and nothing from the graph, so :out becomes a claim testable generatively.
    It constrains HANDLERS ONLY — the STEP is state-dependent all over, which is why a nested
    machine's step lives in the COMPILER. THE COST: no state-dependent update by default. Two doors
    out — a VIEW and a COMBINE — and ACCUMULATION POLICY stays refused, a combine that only grows
    being a mechanism with no policy when THE PILE IS THE COST."

   :internal-visibility-is-declared-and-not-automatic
   "IT IS THE CONSTRUCTOR OF THE MACHINE WHO DECIDES what is visible from inside, never the library
    automatically. BOTH HALVES ARE BUILT. THE READ HALF: a view declared ON THE EVENT, so the handler
    names what it needs BY SHAPE and stays reusable. THE HOLD HALF: PROJECT AT THE DOOR — a node holds
    exactly what it declares, so visibility is bounded BY ABSENCE rather than by permission, which
    also dissolved `a merge cannot remove a key`. THE CHECK COSTS NOTHING, `views` being `admits`
    again. AND THE ORDER WAS FORCED: the view check is only sound UNDER projection, so holding had to
    land first. Node-side EXPOSURE is not built — see :open-questions."

   :a-shape-has-a-derived-id
   "THE AUTHOR'S, 2026-09-04, out of the transcript question: `to make sure the transcript log
    file correspond to a FSM, we may need a stable id for the FSM.` Yes, and the cheap answer is
    dead on arrival.
    - `(hash shape)` IS NOT IT, MEASURED: two structurally identical shapes are neither = nor
      equal-hashed, their handlers being distinct closures and their schemas distinct compiled
      objects. So it changes on every namespace load and a transcript written yesterday would
      match nothing today. :ubergraph-0-9-0 is right that an ubergraph is = and EDN — but only
      for a graph whose attributes are VALUES, and :a-shape-is-code guarantees ours are not.
    - `canonical` IS THE ORDERED, READABLE FORM and `fingerprint` is SHA-256 over it. Everything
      that is DATA is in — node ids, schema FORMS, [from event to], guards, :out, :sees, :reads,
      completion edges and their :yield — ordered by printed form, ubergraph keeping nodes and
      out-edges in SETS. A nested machine is its CHILD'S fingerprint, so it terminates and a
      change deep in a child still moves the parent.
    - CLOSURES ARE ERASED AND NOT RENDERED, which is the decision the rest rests on. `m/form`
      happily prints a closure as #object[... 0x3442b587 ...] — MEASURED, two builds of
      [:fn {...} (fn [v] ...)] have forms that are not = — so a fingerprint over the printed
      form would change every process. ::opaque instead.
    - WHAT IT PROVES IS THE GRAPH AND NOT THE CODE, and that has to be said wherever it is used:
      change what a handler returns without changing its :out, or change what an :fn checks, and
      it does not move. Same limit :a-shape-is-code imposes everywhere else.
    - AND THE ENV DOES NOT MOVE IT EITHER, measured on ../coder's task shape: a shape built as a
      function of its env fingerprints the same in every env, the env being closed over in
      functions that are erased. Right — it is the same machine — and it means the fingerprint
      does not say WHERE it ran.
    - IT IS DERIVED AND NOT DECLARED, which is the whole reason to have one: nobody can forget to
      bump it. And IT CARRIES NO NAME — what a machine is called is a fact about the job, and
      belongs to whoever owns the job. Two-part identity, and only half of it is the library's.
    - WHAT IT REOPENS, PARTLY. :what-is-persisted put shape versioning out of v1 `with the
      question it drags behind it: which shape an instance mid-flight belongs to`. A fingerprint
      on every transcript row answers that for a FINISHED run, which is the audit case. Mid-flight
      across a shape change is still open and stays open."

   :an-event-may-say-how-it-is-reported
   "THE AUTHOR'S, 2026-09-04, arrived at from a CONSUMER rather than from this library: coder's
    driver carried a map of `acts` keyed by state, and the author's objection was that it
    `collects otherwise independent steps into a global map — the integration point should not be
    spread, the FSM shape already did it`. Right, and the reason it was spread is that THE SHAPE
    HAD NO PLACE TO SAY IT.
    - THE GAP WAS ALREADY NAMED HERE and dismissed. :a-join-is-the-product-and-the-licence says
      `THE ONLY THING THE SHAPE CANNOT SAY is whether an event comes from the DRIVER or from the
      WORLD — a label and not a feature, and nobody has asked for it`. It is a FEATURE, and what
      says so is that every driver written against this library has to write that knowledge down
      a second time, in its own index, where nothing can check it.
    - THE GRAMMAR: {:report <fn> :reads <a map schema>} on an event. :report goes and finds the
      fact; :reads is the view of the state it needs, projected and validated exactly as :sees is.
      AN EVENT WITH NO :report COMES FROM THE WORLD, which is what a park is — so the declaration
      IS the driver/world distinction, in data.
    - IT IS SYMMETRIC WITH WHAT AN EVENT ALREADY HAD. :handler/:out/:sees say how an event LANDS;
      :report/:reads say how it is FOUND. Both halves off one declaration, which is what
      :a-handler-belongs-to-the-event bought for the first half.
    - IT IS NOT AN INTERNAL EVENT and does not reopen :a-handler-causes-nothing. The machine does
      not move itself: this is the shape telling a CALLER how an event would be found, and a
      caller choosing to ask. No queue, no run-to-completion, and the reduction is untouched.
    - WHY THE WORK CANNOT SIMPLY GO IN THE HANDLER, which is what was proposed first and is the
      thing to understand before proposing it again: A GUARD READS THE INCOMING EVENT. `entry`
      validates the guard against the payload and only THEN runs the handler, so a fact a branch
      depends on must be on the event when it ARRIVES. A handler computing a verdict computes it
      after the edge is chosen, and the only way back is two events for one observation — the
      hidden transition :a-guard-is-a-schema-over-the-event removed.
    - AND A SHAPE IS A FUNCTION OF ITS ENV, which is the author's other half and what makes the
      report able to do real work: a consumer writes (defn shape [env] ...) and the reports close
      over the writer and the REPL as the shape is built. :a-shape-is-code already licensed a
      closure; this is that, one level out. The shape can still be built with NO env at all —
      measured in ../coder, where (shape {}) checks and draws for nothing.
    - `readings` IS `admits` FOR THE FOURTH TIME — `views` was the second, `yields` the third —
      with the read as TARGET and the source state's schema as PRODUCED. A driver runs a report in
      the state that AWAITS the event, so that state is what must provide the keys. :undeclared
      where there is no report, :reads-unavailable where it is PROVEN the state cannot supply it,
      and an optional key is :no for the same reason a view cannot rest on a maybe.
    - A :reads WITH NO :report IS REFERENTIAL, being a view nothing will ever be handed.
    - AND THE PURE LIFT KEPT ITS SHORT FORM, which mattered more than it looks: declaring a report
      would otherwise have forced the 5-arity and written the event's schema out twice again,
      undoing :an-event-given-only-a-schema-is-a-pure-lift. A handler is a fn and options are a
      map, so the 3-arity takes either and says which by type."

   :a-handler-answers-a-map-and-declares-it
   "A handler's return is a MAP, MERGED into the state, and the event DECLARES its schema. Both halves
    are for the static check and no other reason: an opaque (fn [state] state') can never be checked,
    and a merge of two MAP SCHEMAS can. The declaration is OPTIONAL per event; absent, `subsumption`
    says :undeclared rather than faulting. What it costs now is that carrying a key across several
    states is EXPLICIT — which for a join is not a cost but the whole mechanism."

   :the-event-catalogue-is-denormalised
   "FORCED by ubergraph rather than chosen: a graph holds nodes and edges and NOTHING ELSE, so the
    catalogue is an ARGUMENT to the constructor, written onto every edge that carries the event, and a
    shape whose edges disagree about one event is refused. WHAT WAS KILLED FIRST, so nobody proposes
    it again: a bipartite state->event->state graph is not merely awkward, it is WRONG — two
    transitions on :submit would share one event node and FABRICATE paths never declared."

   :a-guard-is-a-schema-over-the-event
   "{:when <a map schema>} on a transition, and nothing else added. :when IS TO A TRANSITION WHAT
    :sees IS TO AN EVENT. It replaces the old no-guards rule; DETERMINISM IS NOT WHAT WAS GIVEN UP, it
    stays the contract and is now PROVEN rather than had for free. WHY A SCHEMA AND NOT A PREDICATE: a
    schema is DATA — drawable, comparable, partially decidable — and an :fn carries a :description, so
    one expression is both the check and the label. THREE DECIDABLE LEVERS: a shared key whose value
    schemas are disjoint, a CLOSED schema not naming k, and NUMERIC BOUNDS. THE RULE: decidable guards
    branch, and an :fn guard MAY ONLY APPEAR ALONE on its [from event]. :ambiguous INVERTS and demands
    PROVEN SAFETY, which is principled — a shape that cannot prove it is deterministic is not one.
    THERE IS NO :else: no guard matching is `ignored`, which is legal and first-class, so COVERAGE IS
    PUBLISHED AND NEVER FAULTED. A GUARD DESCRIBES THE EVENT WITHOUT THE MACHINERY KEYS, checked
    against (dissoc event :id :instance), and the event's own schema is conformed against the payload
    too or the two checks would be about different values. NOT TAKEN: a guard over the STATE — a guard
    is over the CAUSE, and the driver reports a FACT while the shape decides what the fact MEANS."

   :a-state-may-say-where-it-goes-when-it-completes
   "{:done <id>} on a state, plus {:yield <a map schema>} where it nests a machine. ONE RULE, UML'S: a
    state completes when it HAS NOTHING LEFT TO DO, so a plain state completes ON ENTRY and a nesting
    one when its child reaches a final state — and that unification is why this is small. IT IS NOT A
    GUARD: one unconditional target, so compile stays a lookup and a CYCLE among entry-completing
    states is a PROVEN infinite loop, refused referentially. :yield IS HARVESTED AT COMPLETION ONLY,
    which is what makes the check SOUND — completing is the only moment the child is guaranteed final.
    AN ESCAPE IS STILL AN ABORT AND YIELDS NOTHING. IT IS A REAL EDGE AND NOT A NODE ATTRIBUTE, which
    is why `reachable`, `dead-ends`, `finishable` and `traps` needed not one line. Drawn dashed and
    UNLABELLED. NOT TAKEN: a conditional completion, and a node still holds ONE child."

   :parallel-is-across-instances
   "`Automatically parallel` means ACROSS INSTANCES and nothing else. Orthogonal regions inside one
    machine are OUT. WHY ONE MACHINE CANNOT PARALLELISE is a DATA DEPENDENCY and not anything about
    manifold: ADMISSION — whether this state admits this event is unknown until the previous step
    lands, so a parallel map would run handlers SPECULATIVELY, and handlers do I/O. A THIRD REASON
    holds where that fails: a handler that CAUSES an event makes parallel handlers interleave WRONGLY,
    which is why statecharts have run-to-completion. NONE OF IT TOUCHES two events PENDING in one
    state, which is :two-events-in-flight-at-once."

   :what-is-persisted
   "SUPERSEDED by :nothing-is-persisted-here. What survives is advice for whoever writes a store
    OUTSIDE this library: keep HISTORY and not the shape, which :a-shape-is-code forces. SHAPE
    VERSIONING IS OUT OF v1 and stays out, with the question it drags behind it."

   :an-instance-has-an-identity
   "A fixed field, :instance, written by the CONSTRUCTORS and never spelled by a caller. It is on the
    EVENT as well as the state, and the EVENT is the load-bearing half: routing happens BEFORE any
    state is in hand. It is a THIRD identity and gets a THIRD name. nil NAMES NOTHING, and the
    invariant worth having — no STATE ever carries a nil :instance — lives on the enter schema."

   :a-handler-may-answer-later
   "A handler MAY answer a DEFERRED, so an instance waiting on I/O holds no thread. HOW, WITHOUT
    MANIFOLD UNDER THE CORE: compile is parameterised by a `then` and a `pure` and never learns what a
    deferred is — the global rule applied, not a new idea. `then` IS A BIND AND NOT AN fmap. A deferred
    under the synchronous default IS DEREFERENCED, and it costs no dependency, IDeref being CLOJURE'S.
    THE COST: the step's return type is the caller's to know, and the synchronous path can now BLOCK
    with no timeout, since choosing a default one is policy."

   :a-handler-belongs-to-the-event
   "A HANDLER IS CHOSEN BY THE EVENT ALONE — the README's own reading, recovered. The TARGET still
    comes from the graph. Two edges can no longer DISAGREE about a handler, so a construction-time
    check is replaced by a shape in which the error cannot be written. THE COST: two edges SHARE one
    handler and one :out, so :out must satisfy both targets. Where they genuinely differ, that is two
    events — or, since guards, no :out at all with the runtime crossings enforcing it. A GUARD AND A
    PER-TARGET PAYLOAD PULL AGAINST EACH OTHER."

   :an-ignored-event-is-not-an-error-but-is-not-silent
   "An event the state has no transition for is NOT AN ERROR — nothing controls arrival order behind a
    stream — but the step must SAY it happened, since an event that SHOULD have transitioned looks
    identical to one correctly ignored. THE HANDLER DOES NOT RUN, and that is what makes it safe:
    malli maps are OPEN, so merging an answer into a state with no edge for it would produce a state
    carrying undeclared keys AND PASSING ITS OWN VALIDATION. A MALFORMED EVENT IS NOT AN IGNORED ONE
    — where nothing matches, the event is conformed against the group's schema so a bad one throws."

   :one-ordered-stream-per-instance
   "A machine is fed ONE TOTALLY ORDERED stream; a caller with several sources merges them BEFORE the
    machine sees them, because the machine has no clock. IT IS A REQUIREMENT THE LIBRARY STATES. What
    it buys is a whole feature: an event this state cannot handle is never EARLY, so DEFERRED EVENTS
    are not needed and are out of v1 — a per-instance queue, a re-drive and a deadlock case avoided by
    writing an assumption down. WHERE IT BREAKS is the caller's to fix upstream."

   :an-event-given-only-a-schema-is-a-pure-lift
   "`(event :brief [:map [:brief Brief]])` is the whole declaration: `lifting` makes the handler out of
    mu/keys and the :out is the schema. The 4-arg form said ONE FACT THREE TIMES, and transcription is
    where a shape drifts from itself. :id and :instance are NOT LIFTABLE and it falls out rather than
    being arranged. IT DOES NOT COMPOSE WITH A TAG: a discriminating key is routing information the
    target does not hold, so a guarded event usually spells its handler out. AND A CONSUMER'S LINT
    CACHE HAS TO BE REFRESHED — `rm -rf .clj-kondo/.cache`. Seen twice."

   :an-event-is-the-only-way-a-transition-happens
   "A handler answers a PATCH, conformed against the target's own schema with EVERY KEY OPTIONAL and
    the map CLOSED. What was already true is that a handler could not move the machine; WHAT WAS WRONG
    WITH IT WAS SILENCE, the code quietly repairing a convention. Optional because a handler says what
    CHANGED; closed because a key the target does not declare EVAPORATES, and closing turns a shrug
    into a refusal. IDENTITY THEN NEEDS NO SPECIAL CASE — naming :id is answering an undeclared key,
    refused by the rule that refuses a typo. One check, three guarantees. It sits after :out and
    before :enter, all three crossings staying distinct."

   :a-handler-causes-nothing
   "A HANDLER MAY NOT CAUSE ANOTHER EVENT. With no emission there are no internal events, no queue to
    drain and no run-to-completion. IT IS A CONTRACT AND NOT A GUARANTEE — a handler doing I/O can
    publish to the stream feeding this machine, and no schema catches it. AN EXTERNAL EVENT IS THE
    ULTIMATE SOURCE OF A TRANSITION: the world moves the machine. THE DOOR WAS NARROWED to `the STATE
    raises` and then taken at the cheaper end, as a deterministic CONTINUATION rather than an event,
    so the cycle check exists and the queue still does not. TURNED DOWN: the handler answering both a
    patch and events to raise, which would cost :out its subject."

   :how-the-step-says-a-thing-was-ignored
   "A THIRD INJECTED FUNCTION, `ignored`, defaulting to (fn [state _event] state). NOT a richer return:
    an outcome value is the structural answer, and its cost is that (reduce step init events) would
    stop yielding STATES, which is the README's headline sentence. identical? IS NOT IT — a fired
    self-loop answering {} returns an identical state — and metadata is worse, merge and assoc
    preserving it so a stale flag would ride along. See :open-questions on whether it still earns its
    place."

   :a-trap-is-what-a-cycle-hides
   "`traps` answers the REACHABLE states from which no ending can be reached. It is `reachable` RUN
    BACKWARDS, which is why it was cheap. BOTH OTHER CHECKS WALK STRAIGHT PAST IT: a forward traversal
    gets there, and a trap HAS out-edges — going nowhere and going nowhere USEFUL are different faults.
    THE EXCEPTION LIVES IN `finishable`, not in `traps`: with no :final declared, every state is
    finishable vacuously, so a machine never meant to terminate is silent of its own accord."

   :two-events-in-flight-at-once
   "SERIALISE BY DEFAULT; take concurrency only where the shape PROVES the order of completion cannot
    be observed. `BOTH ADMITTED` IS NOT THE CONDITION — idle -start-> running beside idle -cancel->
    cancelled is a flake, not a race anybody chose. THE CONDITION IS CONFLUENCE plus BERNSTEIN'S on the
    patches: neither writes what the other writes, and neither READS what the other writes. The read
    half is what VIEWS cost. THE WRITE-WRITE HALF IS NO LONGER ABSOLUTE — a key declaring a COMMUTATIVE
    COMBINE is licensed. THE PATCH IS NEVER STALE, ONLY THE ADMISSION IS, except for a handler that
    READS. THE RUNTIME TAKES THE LICENCE: `compile/phases` splits the step, async takes a `Licence`,
    and `sg/run` computes it. THREE REFUSALS keep it sound — a NESTED machine that could take either
    event, a COMPLETION leaving ta, and neither applies to the join node x. THE PAIR MAY BE TWO OF ONE
    EVENT, which is the fan-out. STILL NOT TAKEN: more than two in flight."

   :a-join-is-the-product-and-the-licence
   "PARKING NEEDS NOTHING and already worked — a state waiting for :approve is a state with an :approve
    edge, and an event it does not admit comes back :fired false. A JOIN IS THE PRODUCT CONSTRUCTION:
    :complete declares both keys REQUIRED and two routes reach it, at the DFA's own cost of 2^n states.
    PROJECTION IS WHY IT WORKS — the state name is the join's progress and the schema says so. AND
    `confluence` PROVES IT: the n=3 lattice is 8 states, 12 edges, problems [], confluence {:yes 6},
    all six permutations landing in one identical state. A JOIN IS WHAT A COMMUTING PAIR IS. It is a
    PARTS ASSEMBLY and not a construct, so no helper was added here. TURNED DOWN: a {:join} guard over
    the state, and ORTHOGONAL REGIONS, which remain blocked."

   :a-combine-is-how-a-patch-lands
   "{:combine f :combine/commutes true} on a map entry. A KEY WITH NO COMBINE REPLACES, so nothing
    written earlier behaves differently. A NAIVE MERGE WAS THE WHOLE LIMIT — last-write-wins is the
    only non-commutative thing in the apply phase, which is why a concurrently incremented counter was
    inexpressible. IT IS A CLOSURE, the fixed vocabulary having been refused on the right ground: :max
    does not express `keep the highest-scoring implementation with its provenance`. A GUARD DECIDES
    WHERE THE MACHINE GOES AND A COMBINE DECIDES WHAT A VALUE IS, which is why one may be a closure and
    the other may not. WHAT MAY NOT BE A CLOSURE IS THE PROMISE: the law is DATA, refuted by
    `check/laws` through generation and VERIFIED by compile on the concrete values whenever the licence
    is taken. THE LAW IS LEFT-COMMUTATIVITY over (state, patch, patch), not binary commutativity, and
    the second law is :closed and is not optional. THREE NODES must declare the same combine. DECLARED
    ON THE NODE and never on an event. THE HONEST CAUTION: most domain merges are NOT commutative and
    the author will not notice."

   :the-caller-owns-the-lifecycle
   "THE QUESTION DISSOLVED: the state lives in a d/loop ACCUMULATOR exactly as it lives in reduce's, so
    the reactive machine is the same reduction with the loop shipped, and `run` is A CALLER THIS
    LIBRARY SHIPS. The facade names both doors and chooses neither. REACTIVE-ONLY would have put
    manifold on the only path there is. IT IS A PROPERTY AND NOT A SPEECH — `the-two-doors-agree`."

   :the-facade-is-a-vocabulary-and-two-doors
   "TWELVE FUNCTIONS AND THREE DOORS since 2026-09-04: state, event, transition, shape; problems,
    draw!, dot; compile, initial; run; step, drive. The third door is the CRANK — see
    :the-crank-is-the-door-report-was-missing, which is also the argument for breaking a surface
    that had absorbed nesting, completion and the licence without gaining one. ONE
    STREAM DOOR AND NOT TWO, `fan` already subsuming `async/drive`. `problems` IS OPT-IN AND `shape` DOES NOT
    RUN IT, because A SHAPE YOU CANNOT BUILD IS A SHAPE YOU CANNOT DRAW. RE-EXPORTS ARE DELEGATING
    defns AND NEVER def ALIASES, or malli stops guarding them, and they carry no schema of their own.
    THE FACADE REQUIRES `check`, taken knowingly. NESTING, THE COMPLETION TRANSITION AND THE LICENCE
    ALL ADDED NO DOOR, which is the check on the surface."

   :the-output-is-a-transition-and-not-a-state
   "`run` puts a RESULT on :states — the :event, the :state, whether it :fired, and :instance. THE
    ARGUMENT IS THAT THE CALLER STORES NOW: a state does not say what caused it, and an ignored event
    produces a state EQUAL to the one before. A result reads back down with (map :state); the other
    direction does not exist. This does not contradict :how-the-step-says-a-thing-was-ignored — A
    STREAM IS NOT AN ACCUMULATOR. :fired NEEDS A LOOKUP AND NOT A COMPARISON, hence compile/admits?."

   :a-machine-can-nest-in-a-node
   "A node may carry {:machine <a shape>}. It does not break :a-handler-never-sees-the-state, which
    constrains HANDLERS — a child's step belongs to the COMPILER. INNER FIRST, so the parent's edges
    are the ESCAPE and THE CHILD'S OWN VOCABULARY DECIDES who handles an event. A FINISHED CHILD STOPS
    COMPETING, which is what made nesting cost the design nothing — v1's constraints turned out to give
    correct hierarchical semantics. :sub IS MACHINERY'S: seeded, DROPPED on leaving, RESTARTED on
    re-entry. A CHILD MUST BE ABLE TO START, checked referentially. The checks RECURSE FOR FREE, faults
    carrying :within as a PATH. AN ESCAPE IS UNCONDITIONAL and abort stayed expressible; {:done} is a
    SECOND way out that WAITS."

   :a-node-is-labelled-by-its-id
   "Labels are the name and the structural markers — ▸ initial, ◼ final, ⊞ n states for nesting, a
    guard on an arrow — and nothing else. THE ARGUMENT THAT SETTLES IT: an unreachable state is obvious
    in a picture and INVISIBLE IN A MAP LITERAL, so the label is about STRUCTURE, and a schema is
    precisely the part a map literal DOES show. MEASURED on the first real consumer: a 1,183-character
    label and a dot source of 10,408 made `dot -Tpng` warn, scale, and write a ZERO-BYTE FILE. CHECK
    THE FILE AND NOT THE EXIT CODE."

   :nothing-is-persisted-here
   "THIS LIBRARY STORES NOTHING: it outputs what happened and what becomes of that is the caller's. It
    AMENDS the README rather than contradicting it. datahike left deps.edn and the store namespace left
    :layering, never having been built. WHAT IT COST is the one thing it broke: an audit trail must know
    which event produced which state, which is what forced
    :the-output-is-a-transition-and-not-a-state."

   :a-published-check-answers-about-the-machine
   "THE AUTHOR'S, 2026-09-04, on the crank's two findings: `the 2 findings look like state-graph
    library's gaps`. They were, and the gap was wider than the two.
    - MEASURED, and it is the whole entry: `problems` has RECURSED into nested machines since
      nesting landed, and NOTHING ELSE DID. Asked of a shape whose child had a proven
      :reads-unavailable, `readings` answered () while `problems` answered the fault with
      :within [:inner]. The same question, two doors, two answers, silently. `confluence`,
      `coverage`, `views`, `subsumption`, `yields` and `laws` were all flat the same way.
    - THE LINE IS THE ANSWER'S TYPE. A check answering MAPS recurses and carries :within; one
      answering a SET OF IDS is about ONE graph and stays there, because two machines may name a
      state the same and a set has nowhere to say which one it meant. That is why `problems`
      still recurses ITSELF.
    - AND `problems` NOW TAKES `own` FROM THE FOUR IT DERIVES FAULTS FROM, or every nested fault
      would be reported twice — once from the child's verdict and once from its own recursion.
      Asserted.
    - THE LICENCE IS ONE MACHINE'S, and that is sharper than tidiness: `commuting` is a lookup
      keyed by STATE ID that `run` hands down as the LICENCE, so a child's pair would merge into
      a parent state sharing its name and license a concurrency nothing proved. A wrong :yes
      there is an order-dependent flake. The crank's own lookup filters for the same reason, and
      asks each LEVEL about its own shape.
    - THIS IS THE LESSON THIS PROJECT KEEPS RELEARNING, and it is now three for three: a new
      capability is NOT LOCAL, and the place to look is whatever OTHER check reasoned about the
      same thing. :what-visibility-taught found it once, :what-the-phase-split-taught once, and
      the crank found it again — by needing an answer about a child and getting one about the
      parent."

   :the-crank-is-the-door-report-was-missing
   "THE AUTHOR'S, 2026-09-04: `again, drive and step, if you have to live with them, add them to the
    state-graph api` — said after watching a SECOND driver be written in a consumer, and after
    `park is a general ability, not something every workflow needs to implement by itself`.
    A DECLARATION WITH NOTHING HERE CONSUMING IT IS HALF A FEATURE, and that was `:report`: the
    shape could say how an event is FOUND and nothing in this library ever went and found one, so
    every application wrote the same loop — what does this state await, which of those can I
    produce, what does the report read, apply it, go round. That loop is `:report`, `:done`,
    nesting and `confluence`, all of them OURS.
    - THE DOOR IS `drive` AND ONE TURN OF IT IS `step`, in robertluo.state-graph.drive, with
      `awaits`, `awaiting`, `where` and `advance` beside them. Synchronous and one machine, which
      keeps the division the other two doors already had: `compile` is the pure core, `async` is
      the concurrent default, and this is the one that FINDS events rather than being fed them.
    - `awaiting` IS THE WHOLE DRIVING RULE AS ONE VALUE, and it counts what can be REPORTED rather
      than what is AWAITED. That distinction is the whole of parking: a state offering a driver's
      event beside a person's escape awaits TWO and is perfectly drivable, and the count-what-is-
      awaited rule stopped such a machine dead. Four answers — :final, :from :world (the SHAPE's
      park), :held (the CALLER's, which moves no fingerprint), and :from :driver.
    - IT RECURSES INTO A LIVE CHILD, which nothing else had needed to. `compile` handles nesting
      completely — a child's event applied to the parent routes inward and the completion fires —
      but DISCOVERY does not: a nesting node has no edge for its child's events, so a driver
      reading only the host's out-edges sees a state that awaits nothing and is not final, and
      parks for ever on a machine that was ready to go. `:within` on the answer is the path.
    - AND `check/driving` IS THE STATIC HALF, added the same day for the same reason. `awaiting`
      answers who can move a RUNNING machine; `driving` answers it of the GRAPH, one verdict per
      state, recursing with :within — :final, :driver, :world (a park), :join (several, proven)
      and :fork (several, NOT proven, which is where a driver must stop). THE FORK IS THE ONE
      WORTH LOOKING FOR: a shape that will park for ever at a state you meant to be automatic,
      and that `problems` calls fine. PUBLISHED AND NOT A FAULT, because a shape may want the
      world to choose; what would be wrong is a driver choosing for it. A PROPERTY asserts the
      two answers agree, which is the only thing that can catch either drifting from the other.
    - AND IT TAKES A JOIN WHERE `confluence` PROVES ONE. Two reportable events out of one state is
      a fork, and choosing would be inventing an order nobody promised — unless the shape has
      PROVED the order cannot be observed, which is what confluence answers and what the product
      construction is for. Where it is not proven the crank says :from :world and stops. THIS IS
      THE FIRST TIME A STATIC CHECK IS LOAD-BEARING AT RUNTIME on this door, and it is the same
      move `run` made with the licence.
    - EVERYTHING ELSE IS INJECTED, per the rule about not threading options through layers we do
      not own: `:permitted` (what this turn may report), `:on` (told each applied event, which is
      how a caller writes a transcript), `:context` (handed to compile), `:reports` (given the
      thunks, so a caller with a stream library pays for the slowest rather than the sum).
    - WHAT IT COST THE FACADE: two functions, and the surface had absorbed nesting, completion and
      the licence without gaining one. Worth it because the alternative was every consumer owning
      a copy — measured, twice.
    - WHAT IT COST THE CONSUMER: ../coder shrank by 160 lines and NO LONGER REQUIRES THE FACADE AT
      ALL. What is left of its driver is `recording` — an options map with a fingerprinted `:on`
      and a loud `:ignored` — which is the only part that was ever about that application."}

  :open-questions
  ;; The full case for each is DESIGN.md under `# Open questions`. Settle one WITH THE HUMAN before
  ;; building anything that touches it, and delete it from this list once answered — an answered
  ;; question left in the list is a question that gets asked again.
  ["MAY A STATE COMPLETE ON A CONDITION OVER ITS OWN DATA? Three wants knock on this door — `all n
    reports are in`, `k branches have arrived`, `still under budget` — and each is a COUNT or a
    COMPARISON over what the state holds, so each is a guard over the state. THE LINE ALREADY DRAWN is
    that the shape may read a STRUCTURAL fact to decide COMPLETION, never to decide WHICH WAY. ONE OF
    THE THREE NEEDED NO DOOR: a retry budget is a NUMERIC BOUND on a count the driver reports, and
    lives in the shape today. THE OBSTRUCTION IS DECIDABILITY — `(= expected (count reviews))` is a
    relation between two keys that no malli schema expresses, so it could only be a CLOSURE, and a
    guard may not be one. The honest answer today is no, and the driver counts. WHAT WOULD CHANGE IT
    is a decidable spelling — a fixed key set on entry with completion as `every value is present`."

   "IS DYNAMIC FAN-OUT WANTED? MOSTLY ANSWERED by trying it: the accumulation was already expressible
    and the concurrency needed one character, so what is LEFT is only the completion test above. THE
    TWO STRUCTURAL ANSWERS STAY REFUSED — a lattice generated per run breaks `a shape is code`, and a
    marking relocates the state explosion out of the shape, where it is checkable, into the runtime,
    where it is not. The width being the driver's is not a gap: a graph shows structure, a count is
    data."

   "ARE INTERNAL EVENTS WANTED AT ALL? Nothing is blocked in the meantime; the motivation is HANDLER
    REUSE and the lean is that a STATE raises and a handler never does. NARROWED AND NOT ANSWERED: the
    lean was taken as a deterministic CONTINUATION inside one step, so the queue still does not exist.
    THREE THINGS TO SETTLE FIRST: whether the machine may drive itself at all, since a caller
    triggering by hand costs nothing and hides the flow from `check`; breadth-first or depth-first,
    which is OBSERVABLE in the history; and how an audit trail tells what the world did from what the
    machine did."

   "IS THE NODE-SIDE EXPOSURE NEEDED, OR IS THE EVENT-SIDE VIEW ENOUGH? A view is least privilege BY
    THE HANDLER'S OWN WORD, so a careless one declares {:sees [:map [:token :string]]} and gets it. The
    remedy is a handshake — the node declares what it EXPOSES, the event what it NEEDS — and it is
    ADDITIVE. NOT BUILT because projection already bounds visibility by ABSENCE, which is stronger and
    covers the case that matters most. The bar is a real shape that must HOLD something a handler in
    the same machine must not READ."

   "IS THE Context's :ignored STILL EARNING ITS PLACE? Its stated job was that a store layer would
    replace it, and there is no store layer — the stream door reports a miss as :fired false, from
    compile/admits? and not from any callback. What is left is a caller who folds BY HAND. Three lines
    of surface; the bar for removing it is a second reader asking what it is for."

   "SHOULD A TRANSITION DECLARE ITS :effects AND :idempotence? The licence proves REORDERING is safe
    and says nothing about RE-EXECUTION. Harmless today, a speculative take never re-running a handler;
    RETRY AND REPLAY WOULD BOTH NEED IT, and it is the same class of declared-law-plus-checker as
    :combine/commutes."]

  :project-knowledge
  ;; THE PUNCHLINES ONLY — what to do, and what not to re-learn. The account of how each was found is
  ;; DESIGN.md under the same key, and it is worth reading before designing in the same area.
  {:status
   "EVERY LAYER IS BUILT AND NOTHING IS UNTAKEN. shape, compile, check, async, drive and the facade; the
    static checks are reachability, dead ends, traps, subsumption, views, coverage, confluence and
    laws; the runtime TAKES the licence those prove. The store was never built and is not coming.
    AND AN EVENT MAY SAY HOW IT IS REPORTED, 2026-09-04 — {:report :reads} — which is the
    driver/world distinction this file had called `a label and not a feature`. It came from the
    first CONSUMER of the facade rather than from here; see :an-event-may-say-how-it-is-reported.
    AND A SHAPE HAS A DERIVED ID, 2026-09-04 — `fingerprint` and `canonical` — which is what a
    transcript row needs to say which machine produced it, and which `(hash shape)` cannot be.
    See :a-shape-has-a-derived-id.
    AND THERE IS A THIRD DOOR, 2026-09-04 — robertluo.state-graph.drive, `step` and `drive` on the
    facade — which is the door `:report` was declared without. It finds its own events, recurses
    into a live nested child, and takes a join `confluence` proves. THE AUTHOR ASKED FOR IT after
    watching a second consumer write the same loop; see :the-crank-is-the-door-report-was-missing.
    VERIFIED 2026-09-04 by running it: 151 TESTS, 452 ASSERTIONS, both suites green. Three are
    ^:integration, so THE COMMIT GATE IS A REAL GATE, and that suite NEEDS GRAPHVIZ.
    HOW THE COUNTS ARE CHECKED, and it is A REPL HABIT AND NOT AN ASSERTION — worth knowing before
    trusting a number here. `ts/instrumented` collects and instruments and returns nothing, and NO TEST
    counts anything: what has caught things twice is asking the REPL for (count (mi/instrument!)) and
    comparing it BY HAND with an ns-publics count of fns carrying a :malli/schema. Two ways of counting
    that agree is what says no public function was added without a schema, and a disagreement has twice
    meant a STALE REPL rather than a missing schema. MAKING IT AN ASSERTION IS THREE LINES and nobody
    has; until somebody does, a count written down here is a measurement and not a guarantee.
    THE FACADE IS TWELVE FUNCTIONS: ten from 2026-09-01, and `step` and `drive` added 2026-09-04. clj-kondo clean over src, test, notebook.
    AND THERE IS A TUTORIAL: notebook/tutorial.clj, rendered by `clojure -X:notebook` to
    docs/tutorial.html, gitignored because it is derived. RENDERING IT IS A TEST THE SUITE CANNOT BE —
    it runs every cell, and it has caught two bugs no test would have."

   :gaps-in-the-repository
   "Each will bite on first use:
    - `clojure -M:dev` DOES NOT START A REPL — :dev has no :main-opts. It is `clojure -M:dev:nrepl`,
      and :dev is wanted or the test path and kaocha are not on the classpath. The README is right.
    - kaocha is in :dev, so the runner is `clojure -M:dev:test`, never `clojure -M:test`. clj-kondo is
      an ALIAS and not a binary: `clojure -M:lint --lint src test notebook`.
    - tests.edn is two suites over one tree, split by skip-meta and focus-meta on :integration. The
      default ns-patterns IS the <ns>_test.clj convention, so it is not configured.
    - CLOSED, kept so nobody re-reports them: manifold IS a dependency now; datahike is NOT."

   :ubergraph-0-9-0
   "- AN UBERGRAPH IS A CLOSED MAP TYPE AND IT FAILS SILENTLY: (assoc g :junk 1) returns g UNCHANGED,
      dissoc likewise, and with-meta is discarded while meta is hardcoded nil. Verified. That is why
      the event catalogue is denormalised — there is no slot for anything but nodes and edges.
    - IT IS = AND IT IS EDN, contrary to what was assumed before reading it. The round trip cannot
      carry a handler fn or a compiled schema, which is a fact about OUR attributes.
    - add-attrs MERGES, set-attrs REPLACES. Weight is just the :weight attribute defaulting to 1.
    - multidigraph is the constructor this project wants, or two events between one pair of states
      collapse to one.
    - OUT-EDGES ARE STORED IN A SET, so THERE IS NO EDGE ORDER TO RECOVER — which is why
      document-order first-match guards are unrepresentable here.
    - viz-graph ANSWERS NOTHING USEFUL: the :dot branch is a `spit` whose value is nil. The way to the
      source as a VALUE is to hand :filename a java.io.StringWriter; that is what check/dot does. Its
      :auto-label pprints the whole attribute map, which for us holds a compiled schema and a closure.
    - GRAPHVIZ CLUSTERS ARE NOT REACHABLE THROUGH IT, so a nested child is not drawn inside its parent.
      The parent MARKS the node and the child is asked for its own picture."

   :what-target-1-taught
   "MALLI, and these keep paying:
    - MALLI NAMES THE WRONG SCHEMA FOR A MISSING KEY: (:schema error) is the WHOLE ENCLOSING MAP when a
      key is absent, and the offending child only when a present value is wrong. (mu/get-in root
      (:path error)) is right in BOTH. :type :malli.core/missing-key is the only thing telling a
      missing key from one whose value is legitimately nil.
    - MALLI HAS NO `IS THIS A SCHEMA` PREDICATE for the thing people write — m/schema? is false for
      [:map [:n :int]]. So Schema and MapSchema are :fn predicates that CALL m/schema, which works
      because MALLI RUNS A :fn THROUGH ITS OWN -safe-pred, so the throw comes back as false and the
      try/catch is malli's rather than ours.
    - MALLI NORMALISES [:map] TO :map, and MALLI MAPS ARE OPEN BY DEFAULT.
    - gen/let IN test.check 1.1.1 DOES NOT SUPPORT :let BINDINGS — use gen/bind and gen/fmap. And
      mg/sample TAKES {:size n} AS THE COUNT, not as generator size.
    - KAOCHA IGNORES A FOCUS-META NOBODY CARRIES, silently running the unit tests instead.
    - AN ARGUMENT THAT MUST ACCEPT RUBBISH KEEPS :any — `problems` and `shape` must ACCEPT a malformed
      part in order to REPORT it, or the diagnosis is worse AND differs between dev and production."

   :what-target-2-taught
   "- viz-graph WITH :format :dot NEEDS NO GRAPHVIZ, so the drawing is testable without `dot`.
    - THE SEVEN PRIMITIVE TYPES ARE PAIRWISE DISJOINT, checked and not assumed, :int against :double
      included. That is what licenses `admits` to answer :no from a type difference, and `disjoint`
      inherited it.
    - alg/pre-traverse walks DIRECTED edges from a start node, which is what reachability wants.
    - THE INSTRUMENT COUNT CAUGHT A STALE REPL, which is why it is asserted at all.
    - ASSERT THAT NO `$eval` REACHED A LABEL. A closure in a picture is the failure mode."

   :what-the-design-conversation-verified
   "- MALLI MAPS ARE OPEN BY DEFAULT, which is what makes `able to apply, but wrong` SILENT: a handler's
      answer merged into a state with no edge for that event would validate against that state's own
      schema while carrying keys it never declared. Hence the data is discarded on a miss.
    - A FIRED TRANSITION CAN RETURN AN IDENTICAL STATE, so identical? cannot signal `ignored` —
      Clojure's assoc answers `this` when the value is already there. Checked because it was about to
      be recommended as a free signal."

   :what-the-handler-move-taught
   "- A READING LAYER BETWEEN THE GRAPH AND ITS CONSUMERS IS WHAT LETS A STRUCTURAL CHANGE STAY LOCAL.
      Moving the handler onto the event changed shape.clj AND NOTHING ELSE, because `transitions`
      already flattens the catalogue onto every edge. It has now done so twice.
    - A 2-ARITY DELEGATING TO A 3-ARITY BREAKS UNDER ITS OWN INSTRUMENTATION when the extra argument
      refuses nil. [:maybe Instance] is NOT the fix — `some?` behind a :maybe asserts nothing. Use a
      private helper both arities call.
    - CLOJURE'S OWN DEREFABLES TEST THE DEREF DECISION WITH NO MANIFOLD: delay, promise and future are
      all IDeref."

   :what-the-async-layer-taught
   "- s/connect IS ASYNCHRONOUS, AND IT COST A LOST STATE — closing the output dropped what was still in
      a connect pipeline. Every machine now writes STRAIGHT to the shared sink, so a machine's :done
      cannot resolve until its last state has been ACCEPTED there.
    - EVERY DEREF IN A STREAM TEST IS BOUNDED, or a hang becomes a hung suite. AND A TEST THAT DEREFS
      :done BEFORE DRAINING :states HANGS — backpressure is real.
    - ONLY ONE TEST NEEDS A CLOCK; everything else uses immediate deferreds and is deterministic.
    - MANIFOLD DRAGS IN slf4j-api WITH NO BINDING, hence three NOP lines on stderr. Noise, not a fault."

   :what-the-facade-taught
   "- A def ALIAS BYPASSES malli INSTRUMENTATION, because instrument! replaces the VAR's root binding.
      Every re-export is therefore a delegating defn. AND THE NEAR MISS: the alias APPEARED guarded at
      its 2-arity, a defn calling ITSELF going through the var — testing only that arity would have
      licensed aliases everywhere.
    - THE TWO DOORS AGREE, as a PROPERTY: (map :state) off the stream equals the states the reduction
      passes through. The only thing that can refute :the-caller-owns-the-lifecycle.
    - fan's :done RESOLVES {} WHERE NO EVENT EVER ARRIVED, and that is right: there is no machine until
      an event names one.
    - THE FORMATTER HOOK FIRES ON THE FILE-WRITING TOOLS AND NOT ON A SHELL HEREDOC, and it will reflow
      a whole file on first touch. Reach for the heredoc BEFORE the first edit, not after."

   :confluence-was-measured-not-guessed
   "ONE HUNDRED PER CENT of the concurrent-candidate pairs in this project's early fixtures FAIL
    confluence — so `commute by default` would have been wrong in every case there is, and wrong
    SILENTLY and ORDER-DEPENDENTLY. The finding is structural: different events take you to different
    places, and that is what a state machine is FOR. WHAT IT DID NOT COVER IS A JOIN, which is exactly
    what a commuting pair is and what those fixtures had none of."

   :what-the-tutorial-taught
   "- kind/graphviz TAKES A VECTOR and renders CLIENT-SIDE via viz.js, so a page of this library's
      drawings needs NO graphviz to read. The one hazard is the JS template literal: a backtick in a
      node label would break it.
    - THE DOT SOURCE WAS ONLY REACHABLE THROUGH A FILE, which is the gap the tutorial found and check/dot
      closed. IT PAID TWICE — the notebook helper went from eleven lines to four, and a test left the
      integration suite.
    - `run` GIVES EVERY MACHINE THE SAME STARTING DATA, which async/fan does not. Work around it by
      moving per-instance data onto the event that STARTS the machine, which is better modelling anyway.
    - THE CROSS-INSTANCE INTERLEAVING IS NOT DETERMINISTIC, so a tutorial asserting row order would flake.
    - CLAY: `:render true` implies show, serve, browse and live-reload all false, which is what makes
      `clojure -X:notebook` headless."

   :what-the-parts-library-showed
   "SHARING IS FREE AND COMPLETE — one child shape nests into two unrelated parents with nothing to
    alias, a shape being an immutable value. BUT A SHARED CATALOGUE MUST BE SELECTED FROM AND NOT
    SPLATTED IN: the first assembly using fewer events than the catalogue holds is REFUSED with
    :unused-event, and the check is right. A parts library wants to be A MAP KEYED BY ID, never a
    vector to concat wholesale. Know that on day one rather than day three."

   :what-visibility-taught
   "- THE BREAKING CHANGE COST ONE TEST, and measuring it before recommending it is what settled a
      three-way design question that argument had not. Two other fixtures wanted the same correction.
    - THE SOUNDNESS DEPENDENCY RAN THE OTHER WAY FROM THE DESIGN: the READ half's check is only sound
      because the HOLD half exists.
    - AND IT BROKE A SOUNDNESS CLAIM TWO ENTRIES AWAY — adding a read made check/commutes unsound,
      because it compared WRITE sets only. THE LESSON THIS PROJECT KEEPS RELEARNING: a new capability
      is NOT LOCAL, and the place to look is whatever OTHER check reasoned about what handlers could
      touch. Found by asking `does this actually solve the problem it was built for`."

   :what-nesting-taught
   "- THE SUBSUMPTION CHECK HAD TO LEARN ABOUT :sub, or every edge into a nested node was condemned
      :target-refuses. WHAT THE CHECK COMPOSES MUST BE WHAT compile COMPOSES, and every key the
      MACHINERY writes has to appear in both places.
    - THE ORDERING OF A NAMESPACE MATTERS: the referential nesting check needs `enter-schema` and
      `initial-id` from the section below it — declared rather than moved, and deliberately not
      reimplemented, because what the check asks has to be what runs.
    - THE GENERATIVE PROPERTY EXTENDED WITHOUT AN ARGUMENT, the two shapes sharing an event vocabulary
      so the child shadows the parent constantly — the interesting half rather than an accident."

   :what-guards-taught
   "- `disjoint` COULD NOT LIVE BESIDE `admits`: the ambiguity check is REFERENTIAL, so it must answer
      before the graph exists, and `check` sits above `shape`. WHERE A CHECK LIVES IS DECIDED BY WHEN
      IT MUST ANSWER.
    - `dis-map` NEVER ANSWERS :no, so :ambiguous carries no witness — proving two MAP schemas OVERLAP
      needs a VALUE. THE WITNESS DID LAND IN `coverage`, where the probe constructs one.
    - A MALFORMED EVENT THAT FAILED EVERY GUARD LOOKED LIKE AN ORDINARY MISS, conflating a DEFECT with
      a legitimate miss. Fixed by conforming against the group's schema when nothing matched.
    - A NOTEBOOK EXAMPLE FOR A REFERENTIAL FAULT MUST ASK `shape/problems` OF THE PARTS — the facade's
      takes a BUILT shape, and the constructor throws, so there is none. RENDERING THE NOTEBOOK IS
      WHAT CATCHES THIS."

   :what-the-phase-split-taught
   "- THE LICENCE WAS UNSOUND AND NOTHING HAD NOTICED, found by ASKING rather than by a test. THE HABIT
      WORTH KEEPING: before resting anything on a check, ask what it was reasoning about — a check that
      is decorative is a check nobody has tested against reality.
    - THE SPLIT IS DECIDED BY WHAT EACH CROSSING DEPENDS ON: :event, :sees and :out are the PATCH half;
      :answer and :enter are the APPLY half. :answer FORCED the split, a licensed patch being applied
      where the target may be a DIFFERENT NODE from the one it was computed against.
    - THE STEP IS DEFINED AS THE COMPOSITION and asserted as a PROPERTY, which is the only thing
      stopping the two doors drifting. Three lines.
    - IT TURNED :then FROM AN fmap INTO A BIND, which an existing test caught within a minute. VERIFIED
      FIRST: d/chain flattens, twice over, and chaining a deferred does not consume it.
    - A PATCH HAS TO SAY WHOSE IT IS — :depth, one comparison per level. The bug the seam caught was my
      own, async handing `apply` the patch DEFERRED rather than the patch: nine errors, all one cause.
    - A SPECULATIVE TAKE IS SKIPPED WHERE THIS STATE HAS NO LICENSED PAIR, or a shape with one licensed
      pair would hold an event early everywhere.
    - COMPLETION ORDER IS TESTABLE WITHOUT A CLOCK: park the first handler on a deferred the test
      resolves by hand."

   :what-the-combine-taught
   "- THE PROTOTYPE REFUTED MY OWN `SOUND` EXAMPLE in forty samples, A TIE having no canonical winner;
      it took a TOTAL order to make the law hold. IF THE PERSON PROPOSING THE MECHANISM GETS IT WRONG
      IN THE FIRST EXAMPLE, THE MECHANISM NEEDS A CHECKER AND NOT A DOCSTRING.
    - AND THE LAW I TESTED FIRST WAS THE WRONG LAW — a function can be left-commutative in the fold and
      not commutative as a binary operation.
    - GENERATION CANNOT REACH EVERY VIOLATION: a plausible domain rule survived 27,000 generated
      triples, malli having no reason to invent the magic string. SO THE RUNTIME CHECK IS THE
      ENFORCEMENT AND THE GENERATIVE ONE IS THE DEVELOPMENT AID.
    - :agree HAD TO BECOME A PRE-CONDITION, so `pump` waits for BOTH patches before landing either —
      which costs no wall-clock, the concurrency being in the HANDLERS.
    - MALLI KEEPS ARBITRARY ENTRY PROPERTIES and mu/merge carries them through, which is what lets
      {:combine f} survive into enter-schema.
    - A `for` WHOSE BODY IS A `cond` PUTS nil IN `problems`. The idiom there is :when, never a cond body.
    - mg/sample TAKES A :seed AND HONOURS IT. An :fn schema with no :gen/gen throws no-generator, which
      is malli's answer and not one to work around.
    - `laws` IS DELIBERATELY NOT PART OF `problems` — that would make a static check a test runner."

   :what-the-review-scored
   "The first outside frame this design has been held against, and two thirds of what it asked for was
    already here. THE ONE-LINE DIAGNOSIS: IT IS A DATAFLOW MODEL AND THIS IS A CONTROL-FLOW MODEL —
    there a transition fires when its INPUTS ARE AVAILABLE, here when AN EVENT ARRIVES AND THE STATE
    ADMITS IT, and nearly every difference falls out of that substitution. `Park until a human
    approves` has no dataflow spelling.
    - THE STATE-EXPLOSION ARGUMENT IS CORRECT AND WAS NOT NEWS. THE COUNTER WORTH MAKING BACK: a
      MARKING does not remove the explosion, it RELOCATES it out of the shape, where it is checkable,
      into the runtime state, where it is not — and every static check here rests on ONE STATE BEING
      ONE MAP WITH ONE SCHEMA.
    - WHERE THIS LIBRARY IS AHEAD: its `essential constraint` is A DECLARATION THE ASSEMBLER TRUSTS,
      promising correct concurrency and naming no mechanism. Here the declarations are PROVEN — by
      Bernstein, by generation, and on the concrete values.
    - AND READING THE PARTIAL ROWS TOGETHER IS THE FINDING: they are ELEVEN WAYS OF WANTING ONE THING —
      a transition caused by the machine's own accumulated state rather than by the world."

   :what-completion-taught
   "- THE EDGE-OR-ATTRIBUTE QUESTION WAS THE WHOLE DESIGN, settled by counting what each COSTS. As an
      edge, four traversals needed NOT ONE LINE; as an attribute, each would have condemned correct
      shapes. The cost of the edge was ONE `:when` in `shape/transitions` — the seam, again.
    - THE MISTAKE I MADE IS ONE THIS FILE ALREADY RECORDED, and RENDERING THE NOTEBOOK caught it again.
      THE HABIT WORTH KEEPING IS THE RENDER, NOT THE MEMORY.
    - THE LICENCE GUARD IS IMPLIED AND WAS KEPT ANYWAY, a deliberate exception to `only assert what can
      fail`: the argument SPANS TWO NAMESPACES and the licence is load-bearing.
    - THE PASS-THROUGH PROPERTY IS THE ONE WORTH HAVING — split a generated edge in two with a {:done}
      between, and the reduction must end EXACTLY where it did. It compares two machines and recomputes
      nothing.
    - A SHAPE WRITTEN TO BE A PARENT IS USUALLY NOT STARTABLE AS A CHILD, entering a child handing it
      NO DATA.
    - CHECK THE DRAWING AS A REAL PNG and not as dot source."

   :what-the-fan-out-licence-taught
   "- A SET LITERAL OF TWO EQUAL EXPRESSIONS THROWS. `#{x x}` is refused at runtime with `Duplicate
      key:` — `hash-set` dedupes and `set` dedupes, ONLY THE #{} LITERAL throws. The throw landed
      inside a d/chain, so the machine did not crash, IT SIMPLY STOPPED: done never settled, out never
      closed, and the symptom was two timeouts and a nil.
    - THE ACCUMULATOR MUST BE A SET. `into` on a VECTOR is order-dependent, so THE OBVIOUS SPELLING OF
      A JOIN ACCUMULATOR IS NOT COMMUTATIVE — refuted by `laws` in forty samples. Set union works, and
      so does a map keyed by the item.
    - `commutes` NEEDED NO CHANGE to license two of one event, which is what says the condition was
      right all along. The whole change was `(neg? (compare a b))` becoming `(not (pos? ...))`.
    - AN EVENT THAT WRITES NOTHING COMMUTES WITH ITSELF, the write-write filter being empty."

   :what-the-first-consumer-migration-taught
   "The first outside evidence about the VOCABULARY rather than the mechanisms. VALIDATED, none of it
    needing a change here: guards on an enum tag, guards on numeric bounds, `coverage`, the refusal of
    unprovable guards, and the Harel drawing. coder's src did not change by one line.
    - THE :out IS THE EVENT'S AND THAT IS THE REAL FRICTION: two targets needing DIFFERENT data share
      one :out, so it must be weak enough for one and then cannot prove the other. THE WAY OUT is to
      declare NO :out on a guarded event and let the runtime crossings enforce it; `subsumption` then
      says :undeclared, which is coverage rather than a fault. THE ALTERNATIVE WAS WORSE: weakening the
      target's schema is weakening a schema to please a checker.
    - A RETRY BUDGET WORKS TODAY as two guarded edges on disjoint numeric bounds, so the stopping rule
      is in the shape and the driving loop needs no counter.
    - A `--reset-session` KILLED AN nREPL, and the next eval failed inside the client's socket code,
      which reads like a bug in the tool rather than a dead server."

   :graphviz-and-the-devenv
   "pkgs.graphviz is in ../devenv.nix; graphviz 15.1.0. TWO TESTS, TWO REQUIREMENTS: :format :dot is a
    spit and needs NOTHING, while :format :png shells out and is the only thing proving the RENDERING
    path — asserted on the PNG MAGIC BYTES, because a file existing proves only that something wrote
    one. AND `dot` CAN WRITE A ZERO-BYTE FILE AND EXIT 0 on an oversized graph, so check the file and
    not the exit code. TO LOOK AT A SHAPE: (check/draw! sh) opens a viewer; with :save it writes a file."

   :dependency-notes
   "- ubergraph 0.9.0 — the shape. See :ubergraph-0-9-0 for its traps, of which there are several.
    - manifold 0.4.3 — the async default, and nothing below that layer requires it. Its Deferred is a
      clojure.lang.IDeref, which is what lets the pure core deref one without depending on manifold;
      d/chain takes a plain value as happily as a deferred and FLATTENS; s/connect is ASYNCHRONOUS.
    - malli 0.20.1 — every shape and every signature. The one dependency that punishes a careless REPL.
    - test.check 1.1.1 — in :deps and not :dev on purpose: generative tests are the unit suite here.
    - clay 2.0.22 — the tutorial, in the :notebook ALIAS and not in :deps: a library does not depend on
      the thing that documents it."

   :from-the-sibling-project
   "smart-boundary/AGENTS.md, in GIT HISTORY, was the same author's larger project, removed 2026-09-02.
    WHAT TRANSFERS IS METHOD, NOT FACT: schemas at every crossing, seams checked in the code rather than
    declared, `only assert what can fail`, and a knowledge section written in the past tense about
    things actually observed. Its content is about Anthropic's API, Datalevin and nREPL and transfers
    to nothing here. Its living descendants are ../coder/AGENTS.md and ../llm-function/AGENTS.md."}}

 :states
 {:initialize
  {:entry {:action "Read README.md — it is the specification. Then this file's :open-questions:
    if the change touches one, settle it with the human FIRST and write the answer down. Check
    :gaps-in-the-repository before running anything that depends on a gap"}
   :on {:proceed          {:target :shape-design}
        :question-is-open {:target :decide}}}

  :decide
  {:entry {:action "A design question the README does not answer. Put it to the human in one
    sentence with a recommendation, not a survey. Record the answer in :design and delete it from
    :open-questions — an answered question left in the list is a question that gets asked again"}
   :on {:answered {:target :shape-design}}}

  :shape-design
  {:entry {:action "Design the malli schemas first — of a state, of an event, of a transition, of
    a shape, and of every function signature that touches them. Validate each with malli.core/
    explain in the REPL before anything is built on it. A schema is the cheapest place to be wrong"}
   :on {:schemas-validated       {:target :implement}
        :needs-interactive-check {:target :repl-eval}}}

  :implement
  {:entry {:action "Write it in src/, lowest layer first (shape, then compile, then a default,
    then the facade). One <ns>_test.clj per source namespace as you go, ^:integration on anything
    that opens a file, a socket or a real clock. Dependencies point down only"}
   :on {:function-complete {:target :unit-test}
        :debugging-needed  {:target :repl-eval}
        :schema-question   {:target :shape-design}}}

  :unit-test
  {:entry {:action "clojure -M:dev:test unit — the fast loop, nothing outside this process. Reach
    for a PROPERTY before an example, and make the property an independent invariant rather than
    the implementation restated. Assert only what can fail"}
   :on {:all-pass {:target :refactor}
        :any-fail {:target :implement
                   :guard "Inspect the failure and the shrunk case; fix the code or the property"}}}

  :repl-eval
  {:entry {:action "Discover nREPL via clj-nrepl-eval --discover-ports; if none, launch
    clojure -M:dev:nrepl FROM INSIDE THE DEVENV; eval with clj-nrepl-eval -p <port>, :reload per
    namespace in dependency order — never :reload-all"}
   :on {:insight-gained        {:target :implement}
        :schema-clarity-needed {:target :shape-design}}}

  :refactor
  {:entry {:action "After tests pass, refactor for clarity:
    - keep the pure core pure — the compiler and the shape know nothing of streams
    - a default takes what it needs as a VALUE; nothing is threaded through a layer we do not own
    - extract the well-named function that the duplication was asking for
    - verify behavior(new) = behavior(old): the properties are what says so"}
   :on {:refactor-complete {:target :static-check}
        :needs-test        {:target :unit-test}}}

  :static-check
  {:entry {:action "Only when the change touches the shape or the checks over it. Build a shape in
    the REPL, run the static checks, and LOOK AT THE DRAWING — an unreachable state is obvious in a
    picture and invisible in a map literal. AND ASK WHAT EVERY OTHER CHECK WAS REASONING ABOUT: a
    new capability is not local, and a check written for one purpose is not sound for a second one
    by default. That question has caught two live unsoundnesses and no test has caught either.
    Rendering a shape needs graphviz; it is a human check, not a test"}
   :on {:looks-right {:target :integration-suite}
        :wrong       {:target :implement}}}
  ;; NOTE: (check/draw! shape {:save {:filename f :format :dot}}) writes the graphviz SOURCE with no
  ;; graphviz installed — only other formats shell out to `dot`. So the drawing can be asserted
  ;; about even where it cannot be rendered, and `dot` is needed only to LOOK at it.

  :integration-suite
  {:entry {:action "clojure -M:dev:test integration — the gate before a commit: real files on
    disk, real streams, real clocks, and graphviz actually shelled out to. Kaocha randomizes order,
    so a test depending on another having run first is a bug in the test. Everything opened is
    released in a `finally`, and every deref of a machine is BOUNDED or a hang becomes a hung suite.
    IF THE CHANGE TOUCHED THE NOTEBOOK OR THE VOCABULARY IT USES, RENDER IT — `clojure -X:notebook`
    runs every cell, and a tutorial example that cannot run is a lie a test suite will never see"}
   :on {:passes {:target :review}
        :fails  {:target :implement
                 :guard "Inspect the failure; fix the code or the test"}}}

  :review
  {:entry {:action "Verify the hard constraints: (1) nREPL was the only evaluator (2) no bare
    try/catch — a `finally` for release is not one (3) malli shapes all data AND every function,
    and the instrument count agrees with the independent ns-publics count (4) tests are
    clojure.test in test/, one file per namespace, split unit and ^:integration (5) dependencies
    point down only (6) the pure core has no manifold in it, and no namespace names one above it
    even in a comment (7) a facade re-export is a delegating defn and never a def alias, or malli
    stops guarding it (8) what a static check composes is what compile composes"}
   :on {:all-checkout {:target :retrospect}
        :issue-found  {:target :implement
                       :guard "Fix the identified issue"}}}

  :retrospect
  {:entry {:action "Reflect on the session:
    - What went wrong? What assumption was incorrect?
    - What was LEARNED about ubergraph, manifold or malli that a docstring would not have told
      you? Record it in :project-knowledge in the past tense, with what was SEEN
    - Close any :open-questions the work answered; add the ones it raised
    - Add a :global-rule only for a mistake made more than once
    - AND KEEP THIS FILE SMALL. It is loaded in full every session. A decision belongs here once:
      state it where it is decided, and point at it from everywhere else. Superseded reasoning is
      in git history, which is where it belongs"}
   :on {:done {:target :complete}}}

  :complete
  {:final true}}}
