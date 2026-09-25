# robertluo.state-graph

A finite state machine whose **shape is a graph** — so it can be drawn and checked as one,
and a compiler can turn it into an ordinary Clojure function.

```clojure
;; from Clojars, once `clojure -T:build deploy` has run
io.github.robertluo/state-graph {:mvn/version "0.1.<n>"}

;; or as a git dependency, straight from this repository
io.github.robertluo/state-graph {:git/url "https://github.com/robertluo/state-graph"
                                 :git/sha "<a commit>"}
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
  a seq of events and nothing more;
- **proven safe to run concurrently**, in the narrow place where that is true: which pairs
  of pending events may be applied in order of *completion* is a question about the graph
  and the schemas, answered before anything runs — and then actually taken, so two handlers
  of a join cost one of them.

Its results are data, so a machine's states, events and transitions become history — stored
by the caller, in a datalog database or anywhere else. **This library stores nothing.**

## The shape of a machine

Three definitions and no more.

- A **state** is a node, shaped by a malli schema and **validated on enter**. Entering is
  the only moment a state's schema can be checked, and it is the moment a bad transition
  becomes visible. A node **holds exactly what it declares**: the schema is not a lower
  bound, it is the whole of the state's own data, and anything not named in it is dropped on
  entry. `:id` (which node it is in) and `:instance` (which run it belongs to) are the
  machine's to write and are added for you. Exactly one state is `{:initial true}`. A key
  may also say **how a patch lands on it** — `{:combine f :combine/commutes true}` on its own
  map entry — where replacing it is not what merging means. See below.
- An **event** is shaped by a malli schema too, and it **carries its handler** — though given
  only an id and a schema it is a **pure lift**, answering exactly the keys that schema
  declares, which is what most events are. By default the
  handler takes *the event alone* — nothing of the state it is about to change — and answers a
  **patch**: a map merged into the state, checked against the target's own schema with every
  key optional and the map closed. So saying nothing is always allowed, and naming a key that
  state does not declare is **refused** rather than quietly dropped. It may also declare that
  map's schema, which is what makes the static check above possible, and a `{:sees …}` **view**
  where it does need to read the state. See below.
- A **transition** is an edge: from a state, on an event, to a state. Two different events
  may join the same pair of states, so the shape is a multi-digraph. It may carry a
  `{:when …}` **guard** — a schema over the event — so that one event leads two ways and
  the shape says which. See below.
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

   ;; A PURE LIFT needs only its schema: the handler answers the keys it declares and
   ;; the :out the static check reads is that same schema.
   (sg/event :invite [:map [:email :string]])
   (sg/event :accept [:map [:name :string]])
   (sg/event :bounce [:map [:reason :string]])
   (sg/event :close  [:map])

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
(sg/draw! signup)                                            ; renders a PNG and opens it
(sg/draw! signup {:save {:filename "signup.png" :format :png}})

(sg/dot signup)                                              ; the same drawing as DATA
;=> "digraph {\n  \"new\" [label=\"▸ new\"];\n..."
```

The drawing marks the initial state `▸`, a final state `◼`, and a nesting node `⊞ n` with the
number of states in the machine it nests; a guarded edge carries its guard after the event,
`guarded [n [:> 3]]`; and a completion transition is **dashed** — unlabelled where `:done`
names one state, `[ok]` and `[bad]` where it names one per outcome. **It does not label a
node with its schema**, deliberately: a drawing is
for the *structure* — that is the whole of what a map literal hides — and a schema is
exactly what a map literal shows. It also did not scale; a machine whose states accumulate a
vocabulary produced a 1,183-character label, and `dot -Tpng` answers that with a warning and
a zero-byte file. `draw!` is the drawing as an *effect* — with `:save` it shells out to
graphviz and writes the file, with no `:save` it renders a PNG and opens it, and either way
it answers nothing useful and **swallows nothing**: where graphviz left no usable file, or
nothing here can open one, it throws. `dot` is the same drawing as *data*, for anything that renders
diagrams itself. A notebook, a web page, a docs build: none of them wants a file, and `dot`
needs no graphviz installed.

### A published check answers about the machine

Every check that answers in **maps** — `subsumption`, `views`, `readings`, `yields`,
`coverage`, `confluence`, `laws`, `driving` — recurses into nested machines and carries
`:within`, the path of nodes it was found under. A check that answers a **set of ids** —
`reachable`, `traps`, `dead-ends`, `finishable` — is about one graph and stays there: two
machines may name a state the same, and a set has nowhere to say which one it meant.
`problems` bridges them by recursing itself.

One consequence worth knowing: `commuting`, which is the licence `run` hands to the async
layer, takes only the outermost machine's pairs. It is a lookup keyed by state id, and a
child's concurrency is the compiler's business inside one step.

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
machinery's: a handler that answers `{:sub …}` is **refused**. A child is an ordinary
shape, so `problems` checks it and reports its faults under the node that hosts it
(`:within [:paying]`), and `dot` marks a nesting node `⊞` — it does not draw the child
inside its parent, so ask the child for its own picture.

**A node may say what its child starts with**, using `{:seed <a map schema>}` — projected
off the node's own value on the way *in*, exactly as `:yield` is projected off the child's
final state on the way *out*. Without one a child starts with nothing at all, so what it was
doing could only ever come from the closure its shape was built from; with one, **the same
node can be re-entered with a different job**, which is what a loop over a child machine is.

```clojure
;; `payment` again, with a first state that needs to know what it is charging:
(sg/state :unpaid [:map [:total :int]] {:initial true})

;; and an order that sows it on the way in:
(sg/state :paying [:map [:total :int]] {:machine payment
                                        :seed  [:map [:total :int]]})
```

A seed is a crossing with two sides and either can be wrong, so `check/seeds` answers twice
for every seeded node: `:provides` asks whether this node can *give* it, `:accepts` whether
the child's first state will *take* it, and a `:no` is `:seed-unavailable` or `:seed-refused`.
It is `yields` read backwards, and deliberately shaped like it. A **seedless** node whose
child insists on data is `:machine-cannot-start` — referential, so a nesting that could never
begin is never built.

An **escape is an abort**, and it is unconditional: nothing stops `:ship` firing while
payment is half done, because "only when the child has finished" is a fact about the state
and not about the event. Aborting is the commoner need, so it stays the default. To have the
parent *wait* for its child instead, the node says where to go when it **completes** — the
next section.

## A completion transition: `:done` and `:yield`

A state can say where it goes when it is **finished**, with no event, no handler and no
patch. Here is `order` again with the same `payment` child, and `:ship` gone — that event
*was* the producer telling the machine that payment had finished, which is a decision the
shape can now make itself:

```clojure
(def order
  (sg/shape
   (sg/state :cart      [:map [:total :int]] {:initial true})
   (sg/state :paying    [:map [:total :int]] {:machine payment
                                              :done  :shipped
                                              :yield [:map [:auth :string]]})
   (sg/state :shipped   [:map [:total :int] [:auth :string]] {:done :closed})
   (sg/state :closed    [:map [:total :int] [:auth :string]] {:final true})
   (sg/state :cancelled [:map [:total :int]] {:final true})

   (sg/event :checkout [:map] (constantly {}) [:map])
   (sg/event :cancel   [:map] (constantly {}) [:map])

   (sg/transition :cart   :checkout :paying)
   (sg/transition :paying :cancel   :cancelled)))
```

**One rule, and it is UML's: a state completes when it has nothing left to do.** A state
with no `:machine` has no activity to finish, so *finishing it is arriving* — `:shipped`
above is passed straight through and the machine is never observed sitting in it. A state
*with* a machine completes when that child reaches a final state, which is the statechart
done-transition: the parent waits, and then goes on.

`:yield` is **what a finished child hands up** — a map schema, projected off the child's own
final state and merged in before the continuation lands. It needs a `:machine` to harvest
from and a `:done` to harvest *on*, because completing is the only moment the child is
guaranteed final, and so the only moment the schema is a guarantee rather than a hope. An
escape by an ordinary event still yields nothing; it is still an abort.

```clojure
(def step (sg/compile order))

(mapv (juxt :id (comp :id :sub))
      (reductions step (sg/initial order {:total 30})
                  [{:id :checkout} {:id :authorize :auth "tok_9"} {:id :capture}]))
;=> [[:cart nil] [:paying :unpaid] [:paying :authorized] [:closed nil]]
```

Look at the last step. **One event, and the machine moved three times**: `:capture`
finished the child, so `:paying` completed and harvested its `:auth`, so `:shipped` was
entered — and `:shipped` completes on arrival, so the machine went straight on to
`:closed`.

```clojure
(reduce step (sg/initial order {:total 30})
        [{:id :checkout} {:id :authorize :auth "tok_9"} {:id :capture}])
;=> {:id :closed :total 30 :auth "tok_9"}

(reduce step (sg/initial order {:total 30}) [{:id :checkout} {:id :cancel}])
;=> {:id :cancelled :total 30}          ; still an abort, and it yields nothing
```

The result stream reports **one row per event**, carrying the state the chain ended in. The
hops in between are a pure function of the shape and the state, so an auditor holding the
shape can reconstruct them — and an event is the thing a row could *not* be reconstructed
without.

**Or it may say where each outcome goes.** Given an id, `:done` is one target for every way
the child can finish. Given a **map keyed by the child's final state** it is one edge per
outcome, each carrying a `:yield` of its own — which is the answer whenever the two ways of
finishing *mean* different things:

```clojure
;; `payment` again, with a way to fail:
(sg/state :refused [:map [:total :int]] {:final true})
(sg/event :decline [:map])
(sg/transition :unpaid :decline :refused)

;; and an order that goes different ways depending on which way it finished:
(sg/state :paying [:map [:total :int]] {:machine payment
                                        :done {:captured {:to :shipped
                                                          :yield [:map [:auth :string]]}
                                               :refused  {:to :cancelled}}})
```

Three faults come with it, all referential: an outcome must name one of the child's own final
states (`:unknown-outcome`), outcomes need a `:machine` to have them
(`:outcome-without-machine`), and a `:yield` beside a per-outcome `:done` is a declaration
nobody reads, since each branch already carries its own (`:yield-with-outcomes`).

**It is not a guard**, in either form. What both read is a **structural** fact — that the
child has finished, and which of its final states it finished in — over a set that is finite
and known at construction, dispatched by a map lookup on an id. No schema, no predicate and
nothing to prove disjoint, so determinism is untouched. What a completion still cannot be is a
condition over the **data**, which is a different question and a harder one.

What that buys is a fault no guard could have: a **cycle** among states that complete on entry
is refused as `:done-cycle`. Those states have no child and so exactly one unconditional way
out, which makes the relation a plain graph — and a cycle in it *proves* the machine would
continue for ever.

```clojure
(sg/shape (sg/state :a [:map] {:initial true :done :b})
          (sg/state :b [:map] {:done :a}))
;; ExceptionInfo: The shape has problems
;;   {:problems [{:problem :done-cycle :id :a} {:problem :done-cycle :id :b}]}
```

Like `:ambiguous` this is **referential**, so it runs inside the constructor and a machine
that would spin for ever never gets built — ask `shape/problems` of the parts to look
without throwing. A cycle *through* a nesting node is legal: the events are what break it.

Two states may complete to one target. That is a **merge** and not a join: one arrival
continues.

The checks come almost free, and the reason is that a completion transition is a **real
edge**. `reachable`, `dead-ends`, `finishable` and `traps` all walk the graph, so a state
reached only by completing is reached and a state whose only way out is completing is not a
dead end — none of the four learned anything. On top of that:

- `subsumption` covers it, and it is **never `:undeclared`**. An event edge can only be
  checked where the event declared an `:out`, because what a closure answers is otherwise
  unknowable; a completion carries no closure at all, so what arrives is the state itself
  and its schema is known exactly.
- `yields` is `admits` for the third time, with the yield as the target and the child's own
  final state as what is produced. Under a bare `:done`, **every** final state is asked,
  because a child may finish in any of them and a yield resting on only some is a yield that
  is sometimes not there. A per-outcome yield rests on its **own** final state and no other,
  which is sharper rather than looser. A `:no` is `:yield-unavailable`.
- `dot` draws it **dashed**, which is UML's own notation: there is no event to name, and a
  `:yield` is about the data rather than about where the machine goes. Unlabelled where it is
  unconditional, and `[<the child's final state>]` where it is not — two dashed arrows out of
  one node being two structural facts.

```clojure
(check/yields order)
;=> ({:from :paying :final :captured :verdict :yes})
```

And `problems` is silent on all of it: `(sg/problems order)` `;=> []`.

## A conditional transition: a guard is a schema

One event, two ways, and **the branch is in the shape** rather than in a closure somewhere
else:

```clojure
(sg/event :judged [:map [:verdict [:enum :green :red]]
                        [:fault {:optional true} [:string {:min 1}]]]
          (fn [e] (select-keys e [:fault]))
          [:map [:fault {:optional true} [:string {:min 1}]]])

(sg/transition :written :judged :implemented {:when [:map [:verdict [:= :green]]]})
(sg/transition :written :judged :faulted     {:when [:map [:verdict [:= :red]]]})
```

`:when` is to a transition what `:sees` is to an event — an optional map schema, declared
where the thing it constrains lives. It describes the event's **payload**: what the event
carries, without `:id` and `:instance`, exactly as a state's schema describes the state
without them.

**A guard is a schema and not a predicate**, and that is the whole design. A schema is data,
so a guard can be drawn on the arrow, and — the part that pays — it can be *reasoned about*:

```clojure
(sg/shape ...
          (sg/event :go [:map [:v [:enum :x :y :z]]] (constantly {}) [:map])
          (sg/transition :a :go :b {:when [:map [:v [:enum :x :y]]]})
          (sg/transition :a :go :c {:when [:map [:v [:enum :y :z]]]}))
;; ExceptionInfo: The shape has problems
;;   {:problems [{:problem :ambiguous :from :a :event :go :to [:b :c] :verdict :unknown}]}
```

`:y` could fire either edge. This is **referential** — answerable from the parts alone — so
it runs inside the constructor and a machine nobody can predict never gets built.
`shape/problems` asks the same question of the parts without throwing.

**Two edges on one `[state, event]` must be provably disjoint or the shape is refused.**
This is the one check that demands proven *safety* rather than reporting a proven fault,
because determinism is the contract: `compile` stays a lookup, and there is no ordered
"first match wins" to fall back on. `shape/disjoint` proves it from a finite domain
(`[:= v]`, `[:enum …]`), from disjoint types, from a closed map with no room for a key the
other side insists on, or from numeric bounds that do not meet — and answers `:unknown`
rather than guessing, which is why an unprovable pair is refused.

**There is no `:else`.** An event no guard admits fires no edge, which is `ignored` — the
reduction stays total and the stream reports `:fired false`. So a lone guard is a **filter**
as well as a branch. A malformed event is still a defect and still throws: a guard refines a
schema the event must already satisfy.

`check/coverage` publishes whether the guards on a `[state, event]` leave a gap, with the
value that proves it — and it is **never a fault**, because a gap is exactly what a filter
is for:

```clojure
(check/coverage sh)
;=> ({:from :faulted :event :judged :verdict :no :witness {:verdict :red}}
;    {:from :written :event :judged :verdict :yes})
```

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

## Producing the event: a declared report

An event says how it **lands** — its schema, its handler, its `:out`. It may also say how it
is **found**:

```clojure
(sg/event :judged [:map [:verdict [:enum :green :red]]]
          (fn [event] (select-keys event [:fault]))
          nil
          {:reads  [:map [:brief Brief] [:code Code]]
           :report (fn [seen] (run-it (:brief seen) (:code seen)))})
```

`:report` is the function that goes and finds the fact; `:reads` is the view of the state it
needs to do so, projected and validated exactly as `:sees` is. A **pure lift keeps its short
form** — a handler is a `fn` and options are a `map`, so the third argument says which it is:

```clojure
(sg/event :again [:map [:round :int]]
          {:reads [:map [:round :int]] :report (fn [seen] {:round (inc (:round seen))})})
```

**An event with no `:report` comes from the world.** That is the whole of what this
declaration buys, and it is a distinction the shape could not previously make: a state
waiting on a reported event is one a driver can advance by itself, and a state waiting on an
unreported one is *parked* until somebody outside says what happened. `shape/reports` answers
which are which, so a driver reads the machine instead of being handed the same knowledge a
second time — and **the driver is `sg/drive`**, below.

**It is not an internal event.** The reduction is untouched — `compile`'s step still answers
one state from one event and never loops — and there is no queue a handler can put anything
on. This is the shape telling a caller *how* an event would be found, and a caller choosing to
ask. `drive`, below, is the caller this library ships, and it asks until the run is over or
parked: that loop is the machine finding its own events, from declarations in the shape and
never from a handler.

And it is **provable**, the same way a view is. `problems` reports `:reads-unavailable` when
the state a report would run in cannot guarantee what it reads, and refuses a `:reads` with
no `:report` at construction:

```clojure
(sg/problems bad)
;=> [{:from :a :event :go :problem :reads-unavailable}]
```

## Identifying a machine: the fingerprint

A transcript row that cannot say which machine produced it is a row nobody can audit. So a
shape has a **derived** id:

```clojure
(sg/fingerprint sh)      ;=> "5375cc61b250cc5b…"   64 hex chars, SHA-256 — on the facade since 2026-09-15
(shape/canonical sh)     ;=> the ordered data it is taken over
```

**`hash` will not do**, and this is measured rather than assumed: two structurally identical
shapes are neither `=` nor equal-hashed, because their handlers are distinct closures and
their schemas distinct compiled objects. It changes on every namespace load, so a transcript
written yesterday would match nothing today.

`canonical` puts everything that is **data** in — node ids, the *form* of every schema,
`:initial` `:final`, every edge as `[from event to]` with its guard, `:out`, `:sees` and
`:reads`, every completion edge with its `:yield` — ordered by printed form, since a shape
keeps its nodes in a map and its edges in a set. A nested machine is its child's fingerprint, so the
recursion terminates and a change deep in a child still moves the parent. Keep it for when two
fingerprints disagree and you need to know *why*: a hash can only say "different".

Everything that is a **closure** is in only as its presence, because `m/form` renders one as
`#object[… 0x3442b587 …]` — a hex address that differs every process. Two consequences worth
knowing before trusting one:

- it proves **the graph matched**, not that the same code ran. Change what a handler returns
  without changing its `:out`, or change what an `:fn` predicate checks, and it does not move;
- a shape built as a function of its environment has **the same fingerprint in every
  environment**, the environment being closed over in functions that are erased. That is right
  — it is the same machine — but it means the fingerprint does not tell you where it ran.

It carries **no name**. What a machine is called is a fact about the job rather than about the
graph, and belongs to whoever owns the job.

## The crank: driving a machine that finds its own events

`:report` says how an event is found. **`drive` is what goes and finds them** — a caller this
library ships, like `run`, and the door that declaration was missing.

```clojure
(def machine
  (sg/shape
   (sg/state :idle    [:map] {:initial true})
   (sg/state :fetched [:map [:page :string]])
   (sg/state :saved   [:map [:page :string]] {:final true})
   (sg/event :fetch [:map [:page :string]]
             {:reads [:map] :report (fn [_] {:page (slurp "…")})})
   (sg/event :approve [:map])
   (sg/transition :idle    :fetch   :fetched)
   (sg/transition :fetched :approve :saved)))

(sg/drive machine [])
;=> [{:page "<html>" :id :fetch}]
```

**A run is the vector of events**, and driving stops on its own. It stopped here because
`:approve` has no `:report` — nobody but the world can supply it:

```clojure
(drive/awaiting machine (sg/drive machine []))
;=> {:at :fetched :awaits #{:approve} :from :world}
```

`awaiting` is the whole driving rule as one value, and it answers four ways: `{:final true}`
is over, `{:from :world}` is the machine's own park, `{:from :driver :event e}` is something
to go and find out, and `{:held true}` is *this run's* park — what the caller said not to do
this turn:

```clojure
(drive/awaiting machine [] {:permitted #{}})
;=> {:at :idle :awaits #{:fetch} :from :driver :event :fetch :held true}
```

That last one is why supervising a run is an option and not a state: `:held` is nowhere in the
graph, so a workflow watched and a workflow left alone are the **same machine** with the same
fingerprint. `advance` is the door a person hands an event in by:

```clojure
(drive/advance machine (sg/drive machine []) {:id :approve})
;=> [{:page "<html>" :id :fetch} {:id :approve}]
```

**It counts what can be reported, not what is awaited.** A state offering a driver's event
beside a person's escape awaits two and is perfectly drivable.

**And you can ask the graph the same question before anything runs.** `check/driving` is one
verdict per state — the static half of `awaiting`:

```clojure
(check/driving machine)
;=> ({:id :fetched :awaits #{:approve} :reports #{}       :verdict :world}
;    {:id :idle    :awaits #{:fetch}   :reports #{:fetch} :verdict :driver}
;    {:id :saved   :awaits #{}         :reports #{}       :verdict :final})
```

The verdict worth looking for is **`:fork`** — several reportable events out of one state that
the shape does not prove confluent. A driver must stop there, and `problems` calls such a shape
fine, so this is the only thing that says so before you run it. It is published and never
faulted: a shape may want the world to choose between two events; what would be wrong is a
driver choosing for it.

**It reports into the machine that is running.** A nesting node has no edge for its child's
events, so the crank follows `:sub` as deep as it goes and asks the innermost machine first —
`:within` on the answer is the path of hosts, and `driving` answers with the same path, so a
fork three machines deep is visible from the outside.

**And it takes a join where `confluence` proves one.** Two reportable events out of one state
is a fork, and choosing between them would invent an order the shape never promised — unless
the shape has proved the order cannot be observed, which is exactly what the product
construction gives you:

```clojure
(drive/awaiting joined [])
;=> {:at :asked :awaits #{:write :draft} :from :driver :events [:draft :write]}
```

Both are found in one turn and applied in an order the shape has already said makes no
difference. Where it is *not* proven, the crank says `:from :world` and stops. The reports go
through `:reports`, which defaults to running them in order — hand it one that runs them at
once and a proven join costs the slower of the two rather than the sum.

Everything the crank is given is a value or a function: `:data`, `:instance`, `:context`,
`:permitted`, `:on` (told each applied event — where a caller writes a transcript) and
`:reports`. It stores nothing.

```clojure
(sg/drive machine [] {:on #(println (:from %) "->" (:to %))})
```

## Three doors, one machine

A shape compiles to an ordinary function of a state and an event. **The caller owns the
lifecycle in all three** — the stream door is the same reduction with the loop shipped, and the
crank is the same reduction with the events found rather than fed. Not three kinds of machine.

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
  one partition is one machine, all of them at once — that is where the parallelism is.
  *Within* one machine an event is applied to what the last one produced, except for a pair
  proven not to care which finished first: see below.
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

### Two events at once, where it is proven

A handler may answer a deferred, so a second event can arrive while the first is still in
flight — and a machine is in one state at a time. `sg/run` runs both handlers **only where
the order they finish in cannot be observed**, and serialises everywhere else.

A **join** is what this is for. There is no join operator: a state whose schema *requires*
both keys is reachable only once both events have been handled, and the intermediate states
are the join's progress with their schemas saying so.

```clojure
(def R [:map [:ok :boolean]])

(def verify
  (sg/shape
   (sg/state :verifying [:map] {:initial true})
   (sg/state :evaled    [:map [:eval R]])
   (sg/state :tested    [:map [:test R]])
   (sg/state :complete  [:map [:eval R] [:test R]] {:final true})
   (sg/event :eval [:map [:eval R]])                      ; pure lifts
   (sg/event :test [:map [:test R]])
   (sg/transition :verifying :eval :evaled)  (sg/transition :tested :eval :complete)
   (sg/transition :verifying :test :tested)  (sg/transition :evaled :test :complete)))

(check/commuting verify)                       ; `sg/run` computes this for you
;=> {:verifying #{#{:eval :test}}}
```

Two 400ms handlers on that pair cost **400ms and not 800**. Feed `:eval` then `:test` and
whichever finishes first is applied first, so the machine reaches `:complete` through
`:evaled` or through `:tested` — two routes, one destination.

What is proven, per pair of events pending in one state, is a **closing diamond** —
`[s a] -> ta`, `[s b] -> tb`, and `[ta b]` and `[tb a]` both existing and landing in the
same node — plus **Bernstein's conditions** on the patches: neither event writes what the
other writes, and neither *reads* through a `{:sees …}` view what the other writes. Anything
short of a proof serialises. A state that nests a machine licenses nothing at all, because
inner-first means a child may take the event and the edges being read are not what runs.

### How a patch lands: a combine

A patch is *merged* into the state, and a merge is last-write-wins. That one operation is
the only non-commutative thing in the whole apply phase, and **both halves of Bernstein
traced back to it**: two patches touching one key could never be licensed, and a merge
cannot express a change relative to what the state holds, which is what forces a `{:sees …}`
view — and a view closes the licence from the other side.

So a key may say how a patch lands on it:

```clojure
(defn better [a b]                                  ; a TOTAL order — see the warning below
  (if (pos? (compare [(:score a) (:by a)] [(:score b) (:by b)])) a b))

(sg/state :choosing
          [:map [:best {:optional true
                        :combine better
                        :combine/commutes true} Impl]]
          {:initial true})
```

Now two events that both write `:best` — fan out *k* implementations, take the best — are
licensed to run at once, which under a merge they never could be. A key with no combine
replaces, exactly as before.

**The combine is a function and the promise is data**, and the split is the point. Merging
is domain logic: keep the best-scoring implementation with its provenance, deduplicate
review comments by line. No fixed vocabulary of `:+` and `:max` expresses that, so the
combine is an ordinary closure — allowed here where it is refused for a *guard*, because a
guard decides **where the machine goes** (structural, and must be decided from its own
shape) while a combine decides **what a value is**, inside a state, exactly as a handler's
body always has. But no function yields its own algebra, so `:combine/commutes` is declared
beside it as data, and that declaration is the only part `commuting` reads.

**The declaration is checked, not trusted**, at two strengths — and it needs both:

- `check/laws` **refutes** it by generation from the key's own schema. It never answers
  `:yes`, because generation can refute a law and cannot prove one. Two laws: `:commutes` is
  *left*-commutativity over `(state, patch, patch)` triples — `f(f(s,a),b) = f(f(s,b),a)`,
  which is the shape the fold has, not commutativity of the binary op — and `:closed`, that
  `f` of two values of the key's schema answers a value of that schema, which has to hold or
  the static subsumption check is reasoning about the wrong type.
- **`sg/run` verifies it on the concrete values** whenever the licence is actually taken,
  before either patch lands. A false promise is then a defect that stops the machine, not an
  order-dependent flake.

Why both: a plausible domain rule — *a pinned choice wins outright* — survived 27,000
generated triples and is not commutative. Generation is where you find the mistakes that are
about **values**; the runtime is what catches the ones about **rare** values.

> **Ties are the trap.** `(if (>= (:score a) (:score b)) a b)` is not commutative: a tie has
> no canonical winner, so the answer depends on which patch arrived first. `check/laws`
> refutes it in a few dozen samples. Most domain merges are not commutative until you make
> the order total, so expect the licence to widen less than it first appears.

### Fan-out: *n* of one event

A combine also licenses **two events of the same id**, and that is the fan-out case: *n*
workers each reporting a result send *n* events of one kind into one accumulating state.

```clojure
(sg/state :gathering [:map [:seen {:combine into :combine/commutes true} [:set :int]]]
          {:initial true})
(sg/event :found [:map [:seen [:set :int]]])
(sg/transition :gathering :found :gathering)          ; a self-loop

(check/commuting gathering)
;=> {:gathering #{#{:found}}}                          ; a SINGLETON, and that is the licence
```

Two of one event were refused outright before, on the ground that they run one handler and
write one set of keys and so conflict by construction. True under a merge; untrue of a key
whose combine is commutative. `commutes` needed no change to say so — with the two events
sharing a handler, an `:out` and a target, the diamond closes wherever the target admits the
event again, and the write-write test then covers *every* key the `:out` writes, so the pair
is licensed only where all of them combine commutatively.

> **A vector is not an accumulator.** `into` on a vector is order-dependent, so which worker
> reported first is visible in the answer — `check/laws` refutes it in a handful of samples.
> **Set union is** what a join wants, or a map keyed by the item.

**The width is not in the shape**, and that is deliberate rather than missing. Who says
*that is all of them* is whoever dispatched the work, because it is the only party that
knows *n* — the same answer this library gives to branching and to retry budgets. A graph
shows structure, and a count is data.

The price, and it is the only one: **`:states` reports a licensed pair in completion order**,
so two rows may come back swapped against the order they were fed. No state is ever wrong —
the pair was proved to land in the same one either way — but an audit trail should represent
what happened rather than a sequence that did not. A caller who wants strict arrival order
everywhere drives `robertluo.state-graph.async/drive` with no licence.

## Covering the graph by running it

Everything above answers *could this ever have worked*, from the graph alone. `.explore`
answers **did it** — by driving, for real, through the handlers and the guards and the schema
at every crossing.

The technique is one sentence: **a shape is a function of its env**, so whatever a report
reaches for — a model, a socket, a clock, a budget — arrived as a *value*, and an ordinary
function goes in its place. Give one constructor a set of alternative envs and every branch is
reachable on purpose, at no cost and with no flake.

```clojure
(explore/covering signup-machine
                  {:notify (constantly :sent)}          ; the part that does not vary
                  {:verdict [(constantly :ok) (constantly :rejected)]
                   :budget  [1 2]})                     ; the part that does
;=> {:of 10 :covered 7 :runs 4
;    :gaps []
;    :uncovered [{:transition [:both :note :both]  :why :no-report}
;                {:transition [:ready :a :did-a]   :why :join-order}
;                {:transition [:did-a :b :both]    :why :unvisited-state}]}
```

One run per combination — the *product* of the alternatives, not a search over turns.

**What it adds over driving by hand is the accounting**, and the accounting has a subtlety.
Some transitions cannot be taken by *any* driver:

| | |
|---|---|
| `:no-report` | the event carries no `:report` — only the world supplies it |
| `:join-order` | the state is a proven `:join`, so the crank takes its events at once in **one** order, and the other orderings' halfway states are never entered |
| `:unvisited-state` | nothing entered the state this edge leaves from; the reason is upstream |

That middle row is the one worth knowing: *exactly the property that makes a join safe is what
makes half its diamond undrivable*. `confluence` proves the order cannot be observed, so the
crank picks one — and a report that did not know this would call a correct machine half-tested
for ever.

Subtract those and what is left is **`:gaps`** — a transition a driver *could* have taken and
your alternatives never produced a payload for. That is the only number that means you missed
something.

**Anything in the env, not just a function.** A budget that is an edge is covered by varying a
plain number, where otherwise it would take as many real laps as the budget allows.

A throw propagates rather than being collected: driving already enforces the event's schema,
the guards, the target's schema and a loud miss, so anything that goes wrong is a defect and
stopping on it beats a tally. `:steps` bounds a run, because exploration is exactly where you
find out that your stopping rule is not an edge after all.

## What v1 does not do

Said plainly, because each is a design decision and not an oversight.

- **An event is the only way a transition happens.** A handler answers a patch and nothing
  else: it may not name `:id`, `:instance` or `:sub`, and it is refused if it tries — not by a
  special case but by the patch check, since no state schema declares any of the three.
  Identity is the shape's to say, and a handler naming where it lands is asking for a
  transition it was not given. A handler may not raise an event either: the next event is
  found by a `:report` the shape declares and a driver runs, never by the handler that landed
  the last one.
- **A guard reads the event, never the state.** Branching on what the state already holds
  is not expressible: the guard is over the *cause*, and the cause is the event. Where a
  decision depends on the state, whoever produces the event reports it as a fact the guard
  can read. The one fact about the state the shape settles for itself is **completion** —
  `:done` reads a structural fact over a finite set known at construction, not a schema over
  the data — so "only when the child has finished", and "which way it finished", are
  expressible after all, while "only when the total is over 100" is not.
- **Two edges on one event must be provably disjoint**, so a guard `disjoint` cannot
  separate — two overlapping ranges, a bare predicate — is refused rather than resolved by
  declaration order. There is no order to resolve it with: out-edges are a set.
- **State-dependent update must be declared.** By default a handler answers from the event
  alone, which is what keeps it reusable. To compute from what the state already holds it
  declares a `{:sees …}` view — so the dependence is visible in the shape, narrowed to the
  keys named, and checkable. Two consequences: a state a view reads must *guarantee* those
  keys, and a handler that reads can never be licensed to run concurrently with one that
  writes what it read — no combine repairs that, since the value it read is already stale.
  Where an accumulation must also be concurrent, the way to it is a **combine** rather than a
  view: answer from the event alone and let the node say how the value lands.
- **A state cannot hand a key onward silently.** Since a node holds only what it declares,
  data that should survive several states must be declared by each of them. Dropping a field
  is free — declare one fewer — but carrying one is explicit.
- **A handler may not raise another event — and the machine finds its own events anyway.**
  `drive` runs every `:report` the shape declares until the run is over or parked, which is
  run-to-completion from declarations a checker can read: `check/driving` says which states a
  driver can advance and `explore/covering` says which it did. What stays refused is the
  *handler* raising. It answers a patch, a complete function the check proves and generates
  over, and an event in its return would be a second event source that nothing in the shape
  names.
- **A completion branches on the child's final state and on nothing else, and a node has one
  child.** "Complete to `:a` or `:b` depending on where the child stopped" is a map keyed by
  that state; "depending on whether the total is over 100" is a condition over the data and is
  refused, being undecidable in general where the other is a lookup on an id. And `:yield`
  harvests from the one machine the node nests, so a node that waits for *several* independent
  children is still out: that is orthogonal regions, and they need a shape to ask for them. A
  join over plain *events* needs none of this — it is the product lattice, and `confluence`
  proves it.
- **A fan-out's width is the driver's, and only two of its reports run at once.** *n*
  results accumulate into one state through a commutative combine, and two of those reports
  may land in either order — but not three, the licence being pairwise. Nothing in the shape
  says how wide the fan is or notices when it is full: "one child per element of this list,
  joined when all are done" is not a spelling this library has, because the width is a
  runtime value and counting to it would be a guard over the state. The driver dispatched
  the work, so the driver says when it is done.
- **No persistence, and no shape versioning.** The results are the history; storing them is
  yours.
- **Concurrency within one machine is only ever two events, and only where proven.**
  `check/commuting` is a *pairwise* relation on one state, so two handlers may be in flight
  and never three: a third would need the licence re-established at each intermediate state,
  which is not proven and so is not taken. An unproven pair — a guarded event, an undeclared
  `:out`, overlapping writes, a nesting node, a state a completion transition leaves — waits,
  which is always correct. (A *join* node may complete freely: both orders were proved to
  arrive at the same state, and a continuation is a pure function of it.) And nothing
  is *declared* concurrent: the shape already says which pairs commute, so there is no
  annotation to get wrong.

## The API

`robertluo.state-graph` is the only namespace an application needs.

| | |
|---|---|
| `state` `event` `transition` `shape` | build a machine, nesting and guarding where it helps |
| `problems` `draw!` `dot` `fingerprint` | look at it, and name what you looked at |
| `compile` `initial` | the reduction |
| `run` | the stream |
| `step` `drive` | the crank — one turn, and the loop |

Plus the schemas it publishes: `Instance`, `State`, `Event`, `Transition`.

Underneath, and directly usable — the facade is the convenience, these are the truth:
`.shape` (the graph and its referential checks), `.compile` (shape → function), `.check`
(the static checks and the drawing), `.drive` (the crank: `awaits`, `awaiting`, `where`,
`advance`, `step`, `drive`), `.explore` (`covering`: the same questions asked by *running*
it), `.async` (manifold streams: `drive` for one machine, `fan` for many). Nothing below
`.async` requires manifold, `.drive` included.

Dependencies: malli, manifold, test.check — no graph library, the graph being a plain map the
library owns; test.check at runtime and on purpose, since `check/laws` generates through
malli.generator. Drawing needs graphviz installed, and so does
running the suites: the drawing tests shell out to `dot`.

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
- Drawing shells out to `dot`, and a JVM inherits its `PATH` at launch: a REPL started before
  graphviz was installed cannot draw. The environment the suites need is a JDK, the Clojure CLI
  and graphviz on the `PATH` — `draw_test` and `dot_test` render for real, in the unit suite.
- `clojure -T:build ci` cleans, runs both suites and builds the jar; `clojure -T:build deploy`
  publishes it to Clojars as `io.github.robertluo/state-graph`. Licence: MIT, in LICENSE.
