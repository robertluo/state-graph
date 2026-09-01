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
  becomes visible. A node **holds exactly what it declares**: the schema is not a lower
  bound, it is the whole of the state's own data, and anything not named in it is dropped on
  entry. `:id` (which node it is in) and `:instance` (which run it belongs to) are the
  machine's to write and are added for you. Exactly one state is `{:initial true}`.
- An **event** is shaped by a malli schema too, and it **carries its handler**. By default the
  handler takes *the event alone* — nothing of the state it is about to change — and answers a
  map that is **merged into** the state. It may also declare that map's schema, which is what
  makes the static check above possible, and a `{:sees …}` **view** where it does need to read
  the state. See below.
- A **transition** is an edge: from a state, on an event, to a state. Two different events
  may join the same pair of states, so the shape is a multi-digraph.
- A state may **nest a whole machine** — `{:machine sh}` — which is how a machine big enough
  to matter stays readable. See below.

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

## Nesting a machine in a node

A state can carry a machine of its own. While the parent sits there, the child runs inside
it:

```clojure
(def payment
  (sg/shape
   (sg/state :unpaid     [:map]                 {:initial true})
   (sg/state :authorized [:map [:auth :string]])
   (sg/state :captured   [:map [:auth :string]] {:final true})

   (sg/event :authorize [:map [:auth :string]] (fn [e] {:auth (:auth e)}) [:map [:auth :string]])
   (sg/event :capture   [:map]                 (constantly {})           [:map])

   (sg/transition :unpaid     :authorize :authorized)
   (sg/transition :authorized :capture    :captured)))

(def order
  (sg/shape
   (sg/state :cart      [:map] {:initial true})
   (sg/state :paying    [:map] {:machine payment})     ; <- a whole machine
   (sg/state :shipped   [:map] {:final true})
   (sg/state :cancelled [:map] {:final true})

   (sg/event :checkout [:map] (constantly {}) [:map])
   (sg/event :ship     [:map] (constantly {}) [:map])
   (sg/event :cancel   [:map] (constantly {}) [:map])

   (sg/transition :cart   :checkout :paying)
   (sg/transition :paying :ship     :shipped)
   (sg/transition :paying :cancel   :cancelled)))

(def step (sg/compile order))

(mapv (juxt :id (comp :id :sub))
      (reductions step (sg/initial order {})
                  [{:id :checkout} {:id :authorize :auth "tok_9"}
                   {:id :capture} {:id :ship}]))
;=> [[:cart nil] [:paying :unpaid] [:paying :authorized] [:paying :captured] [:shipped nil]]
```

**Inner first.** A nested machine gets every event before the node's own edges do, so the
parent's edges are the *escape*. Which means **the child's own vocabulary decides who
handles an event**: `:authorize` is the child's word and the parent never sees it; `:cancel`
is not, so it escapes at once.

**A finished child stops competing**, and this is what makes nesting cost the design
nothing. A final state admits nothing, so once the child is done every later event falls
straight through to the parent — no guards, no done-event, no queue.

The child's state lives under `:sub`, seeded when the node is entered, dropped on the way
out, and restarted if the node is re-entered. Like `:id` and `:instance` it is the
machinery's: a handler that answers `{:sub …}` is simply overwritten. A child is an ordinary
shape, so `problems` checks it and reports its faults under the node that hosts it
(`:within [:paying]`), and `dot` marks a nesting node `⊞` — it does not draw the child
inside its parent, so ask the child for its own picture.

The limit, said plainly: **the escape is unconditional.** Nothing stops `:ship` firing while
payment is half done, because that would be a guard and v1 has none. Deciding *when* is the
producer's job — and the child's state is on every result, so a producer can see it.

## Reading the state: a declared view

A handler takes the event alone, which is what keeps it reusable across states. When it does
need something from the state it is changing, the **event declares what it may see** — never
the library, and never the whole state:

```clojure
(sg/event :ask [:map [:q :string]]
          (fn [event seen] {:answer (llm (:goal seen) (:q event))})
          [:map [:answer :string]]
          {:sees [:map [:goal :string]]})
```

The handler is handed the state projected onto the view's keys and validated against it — so
a state holding `{:goal "ship it" :token "s3cret"}` gives the handler `{:goal "ship it"}` and
nothing else. **Declared on the event, not on the node**, which is what keeps the handler
reusable: it names what it needs *by shape*, so it works in every state that satisfies the
view. Without `:sees` the handler keeps its single argument.

And it is **provable**. `problems` reports `:view-unavailable` when a state a handler reads
cannot guarantee the view — including the case where the key is merely *optional* there,
because a view cannot rest on a maybe:

```clojure
(sg/problems bad)
;=> [{:from :a :event :go :problem :view-unavailable}]
```

That check is sound only because a node holds exactly what it declares. Two halves of one
question: what a state *holds* bounds what anything inside can *see*, and the security
property comes from the data not being there rather than from a rule saying don't look.

### Accumulating

A view is also how a machine accumulates, and the **policy stays ordinary code** — which is
why there is no combining-key mechanism in the shape. Read the old value, answer the new one:

```clojure
(sg/event :say [:map [:text :string]]
          (fn [event seen]
            {:messages (vec (take-last 2 (conj (:messages seen) (:text event))))})
          [:map [:messages [:vector :string]]]
          {:sees [:map [:messages [:vector :string]]]})

;; four :say events
;=> {:id :talking :messages ["three" "four"]}
```

Cap it, summarise it, drop the oldest entry, keep everything — the handler decides, because it
is a function. A counter that must see its own total is the same pattern with one key. What the
*shape* decides is who may read what, and which nodes carry it at all.

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
  different events, which pushes the decision onto whoever produces the event — and it is
  why a nested machine's escape is unconditional.
- **State-dependent update must be declared.** By default a handler answers from the event
  alone, which is what keeps it reusable. To compute from what the state already holds it
  declares a `{:sees …}` view — so the dependence is visible in the shape, narrowed to the
  keys named, and checkable. Two consequences: a state a view reads must *guarantee* those
  keys, and a handler that reads can never be licensed to run concurrently with one that
  writes what it read.
- **A state cannot hand a key onward silently.** Since a node holds only what it declares,
  data that should survive several states must be declared by each of them. Dropping a field
  is free — declare one fewer — but carrying one is explicit.
- **A handler may not raise another event.** With no internal events there is no queue to
  drain and no run-to-completion to implement; a cascade is the caller feeding the next
  event.
- **No persistence, and no shape versioning.** The results are the history; storing them is
  yours.
- **Concurrency within one machine is proven but not taken.** `check/confluence` and
  `check/commuting` compute which pairs of pending events could safely be applied in
  completion order — the diamond must close, and the patches must satisfy Bernstein's
  conditions: neither writes what the other writes, and neither *reads* through a view what
  the other writes. `sg/run` serialises always, and says so rather than pretending.

## The API

`robertluo.state-graph` is the only namespace an application needs.

| | |
|---|---|
| `state` `event` `transition` `shape` | build a machine, nesting where it helps |
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
