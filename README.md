# robertluo.state-graph

A finite state machine whose **shape is a graph** — so a graph library can draw it and
check it, and a compiler can turn it into an ordinary Clojure function.

```clojure
robertluo/state-graph {:local/root "../state-graph"}
```

## Tutorial

[`notebook/tutorial.clj`](notebook/tutorial.clj) works the whole API through in order and
ends with a nine-state publishing workflow, drawn and run. It is a
[Clay](https://scicloj.github.io/clay/) notebook, so it renders to a page with the machines
as diagrams:

```
clojure -X:notebook     # writes docs/tutorial.html
```

## Rationale

Instead of another Clojure FSM library. A shape that really is a graph can be:

- **drawn**, so a person sees the machine they described rather than reading a map literal;
- **checked statically**, which is the part that pays — a state nothing reaches, a state
  with nowhere to go, a state the machine can never finish from, and a handler whose answer
  the target state will not admit, all found **without running anything**;
- **turned into a plain function**, because the lifecycle of an instance is a reduction over
  a seq of events and nothing more.

Its results are data, so a machine's states, events and transitions become history — stored
by the caller, in a datalog database or anywhere else. **This library stores nothing.**

## The shape of a machine

Three definitions and no more.

- A **state** is a node, shaped by a malli schema and **validated on enter**. Entering is
  the only moment a state's schema can be checked, and it is the moment a bad transition
  becomes visible. The schema describes the state's own data; `:id` (which node it is in)
  and `:instance` (which run it belongs to) are the machine's to write and are added for
  you. Exactly one state is `{:initial true}`.
- An **event** is shaped by a malli schema too, and it **carries its handler**. The handler
  takes *the event alone* — never the state it is about to change — and answers a map that
  is **merged into** the state. It may also declare that map's schema, which is what makes
  the static check above possible.
- A **transition** is an edge: from a state, on an event, to a state. Two different events
  may join the same pair of states, so the shape is a multi-digraph.

```clojure
(require '[robertluo.state-graph :as sg])

(def signup
  (sg/shape
   (sg/state :new     [:map]                                   {:initial true})
   (sg/state :invited [:map [:email :string]])
   (sg/state :active  [:map [:email :string] [:name :string]])
   (sg/state :bounced [:map [:email :string] [:reason :string]] {:final true})
   (sg/state :closed  [:map [:email :string]]                   {:final true})

   ;;        id       event schema             handler                    what it answers
   (sg/event :invite [:map [:email :string]]  (fn [e] {:email (:email e)})   [:map [:email :string]])
   (sg/event :accept [:map [:name :string]]   (fn [e] {:name (:name e)})     [:map [:name :string]])
   (sg/event :bounce [:map [:reason :string]] (fn [e] {:reason (:reason e)}) [:map [:reason :string]])
   (sg/event :close  [:map]                   (constantly {}))

   (sg/transition :new     :invite :invited)
   (sg/transition :invited :accept :active)
   (sg/transition :invited :bounce :bounced)
   (sg/transition :active  :close  :closed)))
```

A shape is **code**: it is built at load time, its handlers are real closures and its
schemas are compiled once. You *write* it as data; it does not round-trip as data.

`sg/shape` refuses to build a broken one. Everything answerable from the parts alone — a
transition naming a state nobody defined, two transitions leaving one state on one event,
an event no transition fires, a state redeclaring `:id` — throws with the faults in
`ex-data`:

```clojure
(sg/shape (sg/state :a [:map] {:initial true})
          (sg/event :go [:map] (constantly {}))
          (sg/transition :a :go :nowhere))
;; ExceptionInfo: The shape has problems
;;   {:problems [{:problem :unknown-state :in [:a :go :nowhere] :key :to :id :nowhere}]}
```

## What the graph buys

Everything that needs the *built graph* is opt-in, because a half-finished machine is worth
looking at.

```clojure
(sg/problems signup)                                       ;=> []

(def leaky
  (sg/shape
   (sg/state :draft     [:map]               {:initial true})
   (sg/state :review    [:map [:by :string]])
   (sg/state :published [:map [:by :string]] {:final true})
   (sg/state :archived  [:map [:by :string]] {:final true})  ; nothing leads here
   (sg/event :submit  [:map] (constantly {}) [:map])         ; and :by never gets set
   (sg/event :publish [:map] (constantly {}))
   (sg/transition :draft  :submit  :review)
   (sg/transition :review :publish :published)))

(sg/problems leaky)
;=> [{:problem :unreachable :id :archived}
;    {:from :draft :event :submit :to :review :problem :target-refuses}]
```

- `:unreachable` — a traversal from the initial state never arrives. Not *has no in-edge*:
  two states that reach only each other both have in-edges and are both unreachable.
- `:dead-end` — a state with no way out that is not `{:final true}`.
- `:trap` — reachable, has somewhere to go, and can never reach an ending. A dead end is a
  trap of size one; two states bouncing off each other are the smallest interesting one.
- `:target-refuses` — the handler's declared answer, merged over the source state's schema,
  **cannot** satisfy the target's. Proven, not guessed: the subsumption checker answers
  yes, no, or *don't know*, reports only the proven faults, and never lies.

And the same question by the means a person is better at:

```clojure
(sg/draw! signup)                                            ; opens a viewer
(sg/draw! signup {:save {:filename "signup.png" :format :png}})

(sg/dot signup)                                              ; the same drawing as DATA
;=> "digraph {\ngraph [layout=dot];\nnew [label=\"new ▸\n:map\"];\n..."
```

The drawing marks the initial state, gives a final state a double circle, and labels every
node with its schema. `draw!` is the drawing as an *effect* — it shells out to graphviz and
answers nothing useful; `dot` is the same drawing as *data*, for anything that renders
diagrams itself. A notebook, a web page, a docs build: none of them wants a file, and `dot`
needs no graphviz installed.

## Two doors, one machine

A shape compiles to an ordinary function of a state and an event. **The caller owns the
lifecycle either way** — the stream door is the same reduction with the loop shipped, not a
different kind of machine.

### The reduction

```clojure
(def step (sg/compile signup))

(reduce step (sg/initial signup {})
        [{:id :invite :email "ada@example.com"} {:id :accept :name "Ada"} {:id :close}])
;=> {:id :closed :email "ada@example.com" :name "Ada"}
```

No object, no atom, no protocol. A step goes anywhere an accumulator does — a fold, a
transducer, core.async, a test.

### The stream

```clojure
(require '[manifold.stream :as s])

(def events (s/stream))
(def machine (sg/run signup {} events))     ;=> {:states <source> :done <deferred>}

(s/put! events {:id :invite :email "ada@example.com" :instance "u-1"})

@(s/take! (:states machine))
;=> {:event    {:id :invite :email "ada@example.com" :instance "u-1"}
;    :state    {:id :invited :instance "u-1" :email "ada@example.com"}
;    :fired    true
;    :instance "u-1"}
```

- `:states` carries one **transition result** per event — what the machine was told, the
  state it produced, and whether it fired at all. `(map :state ...)` if states are all you
  want. Consume it, or `:done` may never resolve: backpressure is real.
- `:done` is a deferred `{instance -> final state}`, or an error carrying whatever the step
  threw. A caller who names nothing finds their machine under `nil`.
- **One function for one machine and for many.** The stream is partitioned on `:instance`;
  one partition is one machine. Each is reduced strictly in order, all of them at once —
  parallelism is *across* instances, serialisation is *within* one.
- A handler may answer a **manifold deferred**, so a machine waiting on I/O holds no thread
  and a slow handler slows only its own machine. Under `sg/compile`'s synchronous default a
  derefable answer is simply dereferenced, so the same shape works in both doors.

### An event the state cannot handle

Not an error — nothing controls the order events arrive in behind a stream, so a `:close`
landing before an `:accept` is ordinary traffic. The handler never runs, the state is
unchanged, and the result says so:

```clojure
;; :invite, then a :close that arrived too early, then :accept — read off :states
(map (juxt (comp :id :event) (comp :id :state) :fired) results)
;=> ([:invite :invited true] [:close :invited false] [:accept :active true])
```

`:fired` is the one fact a consumer could not recover for itself: an ignored event answers
the state unchanged, and a self-loop whose handler answers `{}` answers a state identical
to the old one.

**The caller feeds one totally ordered stream per instance.** Several sources are merged
into one order *before* the machine sees them, because only the caller can: the machine has
no clock and no way to know two events were concurrent.

## What v1 does not do

Said plainly, because each is a design decision and not an oversight.

- **No guards.** A state and an event have exactly one target, which is what makes the
  compiled step a lookup and every static check answerable. Branching is spelled as two
  different events, which pushes the decision onto whoever produces the event.
- **No state-dependent update.** A handler sees only the event, which is what lets it be
  reused across states and checked as an ordinary function. A total that must see the old
  total is a query over history, not a field.
- **A merge cannot remove a key.** A state that must drop a field is not expressible.
- **A handler may not raise another event.** With no internal events there is no queue to
  drain and no run-to-completion to implement; a cascade is the caller feeding the next
  event.
- **No persistence, and no shape versioning.** The results are the history; storing them is
  yours.
- **Concurrency within one machine is proven but not taken.** `check/confluence` and
  `check/commuting` compute which pairs of pending events could safely be applied in
  completion order; `sg/run` serialises always, and says so rather than pretending.

## The API

`robertluo.state-graph` is the only namespace an application needs.

| | |
|---|---|
| `state` `event` `transition` `shape` | build a machine |
| `problems` `draw!` `dot` | look at it |
| `compile` `initial` | the reduction |
| `run` | the stream |

Plus the schemas it publishes: `Instance`, `State`, `Event`, `Transition`.

Underneath, and directly usable — the facade is the convenience, these are the truth:
`.shape` (the graph and its referential checks), `.compile` (shape → function), `.check`
(the static checks and the drawing), `.async` (manifold streams: `drive` for one machine,
`fan` for many). Nothing below `.async` requires manifold.

Dependencies: ubergraph, malli, manifold, test.check. Drawing needs graphviz installed.

## Development

- All data and all functions are guarded by malli schemas and function schemas, instrumented
  in the test fixture and conformed by hand at the seams that must hold in production.
- Unit tests are generative first (test.check), and a property is an *independent* invariant
  rather than the implementation restated.
- Two suites over one tree, split by a `^:integration` meta: `clojure -M:dev:test unit` for
  every save, `clojure -M:dev:test integration` as the gate before a commit.
- Do not start a plain `clojure repl`. nREPL is the only evaluator: discover with
  `clj-nrepl-eval --discover-ports`, launch with `clojure -M:dev:nrepl`, eval with
  `clj-nrepl-eval -p <port>`. Use `:reload` per namespace in dependency order — never
  `:reload-all`, which redefines malli's own protocols and breaks every instrumented var.
- A JVM inherits its `PATH` at launch, so start the REPL from inside the devenv or it will
  not find `dot`: `devenv shell -- sh -c 'cd state-graph && clojure -M:dev:nrepl'`.
- Do not manually repair parenthesis errors — run `clj-paren-repair`.
