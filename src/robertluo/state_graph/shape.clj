(ns robertluo.state-graph.shape
  "THE BOTTOM: a state machine's shape, which is a graph.

   A STATE is a node, shaped by a malli schema and validated on enter. An EVENT is
   shaped by a malli schema too, and it CARRIES ITS HANDLER. A TRANSITION is an edge
   naming the event that fires it and where the machine lands. Two events may join one
   pair of states, so the graph is a MULTI-digraph and a plain digraph would silently
   keep one of them.

   The shape IS CODE — built at load time, handlers are closures, schemas are compiled.
   Nothing here serialises and nothing here needs to. That is also why the event
   catalogue is DENORMALISED onto the edges: an Ubergraph holds nodes and edges and
   nothing else, and says so by silently ignoring an assoc and discarding metadata.

   Requires ubergraph and malli, and nothing else — ever."
  {:knowledge
   [{:id :the-shape-is-a-graph
     :kind :decision
     :says "Three definitions and no more: a STATE is a node shaped by a malli schema and validated on enter; an EVENT is a value shaped by a schema; a TRANSITION is an edge keyed by event, carrying a function whose return value is applied to the state."
     :why "Two events may join one pair of states, so the graph is a multi-digraph and a plain digraph would silently keep one. :initial is a node attribute, exactly one per shape, while the starting DATA stays an argument to the reduction: a node and its value are different things."
     :see [:robertluo.state-graph.shape/state :robertluo.state-graph.shape/event
           :robertluo.state-graph.shape/transition :robertluo.state-graph.shape/shape]}
    {:id :ubergraph-is-a-closed-map-and-fails-silently
     :kind :lesson
     :says "An ubergraph is a closed map type that fails silently: (assoc g :junk 1) answers g unchanged, dissoc likewise, with-meta is discarded and meta is hardcoded nil. There is no slot for anything but nodes and edges."
     :why "Verified 2026-09-01, and it is what forced the event catalogue onto the edges. add-attrs MERGES and set-attrs REPLACES; multidigraph is the constructor this library needs."
     :when "2026-09-01"
     :cites [:the-event-catalogue-is-denormalised]}
    {:id :ubergraph-is-equal-and-edn-for-value-attributes
     :kind :lesson
     :says "An ubergraph IS = and IS EDN, contrary to what was assumed before reading it. The round trip cannot carry a handler fn or a compiled schema, which is a fact about OUR attributes and not about the graph."
     :cites [:a-shape-is-code :hash-shape-is-not-an-id]}
    {:id :ubergraph-out-edges-are-a-set
     :kind :lesson
     :says "Out-edges are stored in a SET — node-info is {:out-edges {dest-id #{edge}}} — so there is no edge order to recover. Anything that needs an order over a node's edges has to compute one."
     :why "It is why document-order first-match guards are unrepresentable here and determinism has to be PROVEN rather than ordered, and why `canonical` and `continuations` sort by printed form."
     :cites [:document-order-first-match-is-unrepresentable]
     :see [:robertluo.state-graph.shape/canonical :robertluo.state-graph.shape/continuations]}
    {:id :malli-maps-are-open-by-default
     :kind :lesson
     :says "Malli maps are OPEN by default and only {:closed true} refuses an extra key; [:map] normalises to :map."
     :why "It is what makes `able to apply, but wrong` silent: a handler's answer merged into a state with no edge for that event would validate against that state's own schema while carrying keys it never declared. Verified before the design was settled, and it decided that a miss discards the data."}
    {:id :never-reload-all
     :kind :rule
     :says "Never (require ... :reload-all) in a REPL with this library loaded. Reload per namespace, in dependency order — shape, compile, check, async, drive, explore, the facade."
     :why "malli.core reloading redefines its protocols, so every compiled schema already in malli's function-schema registry satisfies neither m/schema? nor m/Schema and every instrument! afterwards is a StackOverflowError for every var. Recovery is (reset! @#'malli.core/-function-schemas* {}) and a :reload of each namespace; a plain :reload alone does not recover."}
    {:id :what-is-checked-must-be-what-runs
     :kind :rule
     :says "A static check composes its schemas in exactly the order the runtime composes its values, or the check is about something that never runs. `produced`, `continued`, `accepted` and `yields` each pair with a step in the compiler, and every key the MACHINERY writes appears in both places."
     :why "The subsumption check condemned every edge into a nested node as :target-refuses until it learned about :sub, one minute after nesting first worked. The referential nesting check `declare`s `enter-schema` and `initial-id` from the reading section rather than reimplementing them for the same reason."
     :see [:robertluo.state-graph.shape/accepted :robertluo.state-graph.shape/enter-schema]}
    {:id :dependency-ubergraph
     :kind :decision
     :says "ubergraph 0.9.0 is the shape: multigraph and digraph in one library, attributes on nodes and edges, viz-graph for drawing. Its traps are recorded on this namespace and on check's drawing."
     :cites [:ubergraph-is-a-closed-map-and-fails-silently :ubergraph-out-edges-are-a-set]}
    {:id :dependency-malli
     :kind :decision
     :says "malli 0.20.1 shapes every state, every event and every function signature here. It is the one dependency that punishes a careless REPL."
     :cites [:never-reload-all]}]}
  (:require [malli.core :as m]
            [malli.util :as mu]
            [ubergraph.core :as uber]))

;;; ---------------------------------------------------------------- vocabulary

(def Id
  "What names a state or an event."
  :keyword)

(def ^{:knowledge
       [{:id :an-instance-has-an-identity
         :kind :decision
         :says "An instance is named by a fixed field, :instance, written by the constructors and never spelled by a caller. It is on the EVENT as well as the state, and the event is the load-bearing half: routing happens before any state is in hand."
         :why "It is a THIRD identity and gets a third name — :id on a state is which node, :id on an event is its type. The word is the README's own, and a plain keyword because it is data a user reads and writes in their own maps. nil names nothing: `fan` keys an event with no :instance under nil, and the invariant worth having — no STATE carries a nil :instance — lives on the enter schema."
         :see [:robertluo.state-graph.shape/enter-schema]}
        {:id :an-instance-key-fn-was-turned-down
         :kind :rejected
         :says "A key-fn handed to the async layer, leaving the core ignorant that instances exist, was turned down."
         :why "More decoupled, and not chosen: a fixed field the constructors own is simpler to document, and it makes a state self-describing with no second argument travelling beside it."
         :cites [:an-instance-has-an-identity]}]}
  Instance
  "What names a RUN of the machine — the third identity here, and it gets a third name.
   :id on a state says which NODE it is in and :id on an event says its TYPE; this says
   which machine either belongs to. See the README: `a lifecycle of an INSTANCE of the
   FSM can be seen as a reduction on a seq of events`, which is where the word comes from.

   Deliberately loose — a uuid, an order number, a string — because what an instance is
   called is the caller's business and never this library's. Not nil, though: a partition
   key that may be nil is a bug waiting for the second machine."
  some?)

(def ^{:knowledge
       [{:id :malli-has-no-schema-predicate
         :kind :lesson
         :says "Malli has no `is this a schema` predicate for the thing people write: m/schema? is true only of a COMPILED schema and false for the form [:map [:n :int]]. So `Schema` and `MapSchema` are :fn predicates that call m/schema."
         :why "It works because malli runs a :fn predicate through its own -safe-pred, so the throw comes back as false and the try/catch is malli's rather than ours, which is what lets this honour the no-bare-try-catch rule. (m/schema x) on an already-compiled x is identical? to x, so it costs nothing on the common path."
         :see [:robertluo.state-graph.shape/MapSchema]}]}
  Schema
  "Anything malli can make a schema of: a FORM like [:map [:n :int]], or one already
   compiled.

   MALLI HAS NO SUCH PREDICATE — m/schema? is true only of a COMPILED schema, and a
   form is the thing people actually write. So it is written here, and written as a
   :fn, because malli calls a :fn predicate through its own -safe-pred: m/schema THROWS
   on nonsense and the throw comes back as `false` rather than escaping. That is also
   why this needs no try/catch of ours. Verified false for a string, nil, a number and
   an unknown schema type; true for a form, a compiled schema and a bare keyword one."
  [:fn {:error/message "should be a malli schema, or a form malli can make one of"}
   #(m/schema? (m/schema %))])

(def MapSchema
  "A Schema whose type is :map — what a state, an event and a handler's answer must be,
   because merge and subsumption are defined over nothing else.

   Accepts a form or a compiled one, and says nothing about which: the constructors
   compile, so what a shape STORES is always compiled, and nothing downstream needs it
   to be — mu/keys, mu/merge and m/validate all take a form just as happily."
  [:fn {:error/message "should be a malli schema over a map"}
   #(= :map (m/type (m/schema %)))])

(def Shape
  "The graph. Its innards are ubergraph's business, so what is guarded is what goes in."
  [:fn uber/ubergraph?])

(def ^{:knowledge
       [{:id :a-state-has-an-id
         :kind :decision
         :says "A state is a MAP with :id, the same word in both places it is needed: the node in the graph and the runtime value saying which node it is in. A node's schema describes the REST of the map, and what is validated on enter is the derived merge, never written by hand."
         :why "Forced as well as chosen: the compiled step is (fn [state event] state') and has to know whose out-edges to search, so the identity cannot live only in the graph. THE EDGE ALWAYS WINS — the compiler assocs the target's :id after the merge, so a handler cannot move the machine sideways past the edge that decides the target, and since 2026-09-03 a handler that tries is refused."
         :cites [:an-event-is-the-only-way-a-transition-happens]
         :see [:robertluo.state-graph.shape/enter-schema]}
        {:id :the-schema-describes-the-map-without-the-machinery-keys
         :kind :decision
         :says ":id, :instance and :sub are the machinery's words. A state's schema describes the map without them, an event's schema describes its PAYLOAD without :id and :instance, and a part that redeclares one is refused as :reserved-declared."
         :why "A state redeclaring :id would describe something written over it on every entry. An event's :id and :instance are ridden in rather than carried, which is why the step conforms an event against its schema with those keys taken off."
         :see [:robertluo.state-graph.shape/problems :robertluo.state-graph.shape/patch-schema]}]}
  StateDef
  "A node. :schema describes the map WITHOUT its :id — what a state is called is the
   shape's to say, not the user's.

   :machine NESTS A WHOLE MACHINE IN THIS NODE. It is a built Shape, and while the parent
   sits here that child runs inside it: the compiled step offers every event to the child
   FIRST and only then to this node's own edges. The child's state lives under :sub, which
   the machinery writes and a handler may not.

   :seed IS WHAT THE CHILD IS STARTED WITH — a map schema, projected off this node's own
   value on the way in and handed to the child as its first state's data. It is :yield's
   MIRROR: one carries parent -> child at entry, the other child -> parent at completion,
   and a nesting that could only ever be told its job by the closure its shape was built
   from is a nesting that cannot be re-entered with a different job.

   :done IS A COMPLETION TRANSITION — where this state goes when it COMPLETES, with no
   event, no handler and no patch. A state with no :machine completes ON ENTRY; one with a
   machine completes when that child reaches a final state. ONE RULE, and it is UML's: a
   simple state has no activity to finish, so finishing it is arriving.

   IT MAY SAY WHERE EACH OUTCOME GOES. Given an Id it is one unconditional target for every
   way the child can finish; given a MAP FROM THE CHILD'S FINAL STATE it is one target per
   outcome, each with a :yield of its own:

     {:done {:implemented {:to :working :yield [:map [:code Code]]}
             :abandoned   {:to :refused}}}

   That is still not a guard. What it reads is the STRUCTURAL fact :done already reads —
   which state the child is in — one notch finer, over a finite set known at construction,
   dispatched by a map lookup on an id. No schema, no predicate, nothing to prove disjoint.

   :yield IS WHAT A FINISHED CHILD HANDS UP — a map schema, projected off the child's own
   final state and merged in before the continuation lands. It needs a :machine to harvest
   from and a :done to harvest ON, because completing is the only moment the child is
   GUARANTEED final and so the only moment the schema is a guarantee rather than a hope.
   An escape by an ordinary event is still an ABORT and still yields nothing. Beside a
   per-outcome :done it belongs to the outcome and not here."
  [:map [::kind [:= :state]] [:id Id] [:schema MapSchema]
   [:initial {:optional true} :boolean]
   [:final {:optional true} :boolean]
   [:machine {:optional true} Shape]
   [:seed {:optional true} MapSchema]
   [:done {:optional true} [:or Id [:map-of Id [:map [:to Id]
                                                [:yield {:optional true} MapSchema]]]]]
   [:yield {:optional true} MapSchema]])

(def ^{:knowledge
       [{:id :a-handler-belongs-to-the-event
         :kind :decision
         :says "A handler is chosen by the EVENT alone: (event id schema handler out) and (transition from event to). The target still comes from the graph, so A -submit-> B beside C -submit-> D stays expressible."
         :why "The README's own reading recovered — the first implementation had keyed the handler on [state, event]. Two edges can no longer disagree about a handler, so a construction-time check is replaced by a shape in which the error cannot be written, and both halves of the handler's function schema come off the event definition and nothing off the graph."
         :from "the author, 2026-08-31, from the README: `Each transitions (by event only, a function handle the event, return value will be applied to a state)`"
         :when "2026-08-31"
         :cites [:the-event-catalogue-is-denormalised]}
        {:id :two-edges-share-one-out
         :kind :decision
         :says "The cost of the handler being the event's: A -submit-> B and C -submit-> D share one handler and one :out, so :out must satisfy B's schema AND D's. Where two edges genuinely need different data, that is two events — or, since guards, no :out at all and the runtime crossings enforcing it."
         :why "A guard and a per-target payload pull against each other; the first consumer met exactly this and declared no :out on its guarded event."
         :cites [:a-handler-belongs-to-the-event :a-guard-is-a-schema-over-the-event]}]}
  EventDef
  "A catalogue entry, and WHERE A HANDLER LIVES. It is consumed at construction and not
   kept: its schema, its handler and its :out are written onto every edge that fires it.

   THE HANDLER IS THE EVENT'S AND NOT THE EDGE'S, so two edges firing one event cannot
   disagree about what handles it — there is one declaration where there were two, and a
   construction-time check is replaced by a shape in which the error cannot be written.
   It also completes the claim: the handler is a [:=> [:cat <this :schema>] <this :out>],
   BOTH HALVES off this map and nothing at all off the graph."
  [:map [::kind [:= :event]] [:id Id] [:schema MapSchema] [:handler fn?]
   [:out {:optional true} MapSchema]
   [:sees {:optional true} MapSchema]
   [:report {:optional true} fn?]
   [:reads {:optional true} MapSchema]])

(def ^{:knowledge
       [{:id :a-guard-describes-the-event-without-the-machinery-keys
         :kind :decision
         :says "A guard is checked against (dissoc event :id :instance), exactly as a state's schema describes the state without :id, :instance and :sub — and the event's own schema is conformed against the PAYLOAD too, or the two checks would be about different values."
         :why "Without it a closed guard would fail on :id every time; with it a closed event schema is usable. What it broke is a correction: an event declaring :id or :instance in its own schema used to validate and now does not."
         :from "the author's payload convention, 2026-09-03"
         :when "2026-09-03"
         :cites [:a-guard-is-a-schema-over-the-event :the-schema-describes-the-map-without-the-machinery-keys]
         :see [:robertluo.state-graph.shape/accepted]}]}
  TransDef
  "An edge: which event moves the machine from where to where. What handles the event is
   the EVENT's to say — see EventDef. The TARGET is still the graph's, because
   A -submit-> B beside C -submit-> D is what a multidigraph is for.

   :when IS A GUARD, and it is a SCHEMA over the event rather than a predicate over
   anything: this edge fires only for events the schema admits. It is to a transition what
   :sees is to an event — an optional map schema, declared where the thing it constrains
   lives. A schema is DATA, so a guard can be drawn, compared and REASONED ABOUT, which a
   closure could never be: `disjoint` is what proves two guards on one [state, event] can
   never both fire, and without that proof the shape is refused."
  [:map [::kind [:= :transition]] [:from Id] [:event Id] [:to Id]
   [:when {:optional true} MapSchema]])

;;; -------------------------------------------------------------- constructors

(defn state
  "A state: an id, the malli schema of its DATA, and optionally {:initial true} or
   {:final true}. The schema form is compiled here, so what a shape stores is always
   a compiled schema and MapSchema is honest rather than aspirational.

   A KEY MAY SAY HOW A PATCH LANDS ON IT, as properties on its own map entry:

     (state :best [:map [:best {:combine better :combine/commutes true} Impl]])

   Without one a key REPLACES, which is what a merge always did. See `combines-of` for why
   the combine is a closure and the promise is data.

   A STATE MAY SAY WHERE IT GOES WHEN IT COMPLETES, with {:done <id>} — no event and no
   handler, and for a node nesting a machine, {:yield <a map schema>} to harvest the child's
   result on the way, or a {:done {<the child's final state> {:to ... :yield ...}}} saying
   where each OUTCOME goes.

   A NESTING NODE MAY SAY WHAT ITS CHILD STARTS WITH, using {:seed <a map schema>} — the
   mirror of :yield, projected off this node's own value on the way in. See StateDef."
  {:malli/schema [:function [:=> [:cat Id MapSchema] StateDef]
                  [:=> [:cat Id MapSchema [:maybe :map]] StateDef]]
   :knowledge
   [{:id :a-machine-can-nest-in-a-node
     :kind :decision
     :says "A node may carry {:machine <a shape>}, and while the parent sits there that child runs inside it. A child is an ordinary shape, so it is checked, compiled and drawn as one, and the checks recurse for free with faults carrying :within as a PATH."
     :why "Nine states in one graph is about where one graph stops being readable; nesting keeps every machine the size a person can hold. It does not break :a-handler-never-sees-the-state, which constrains HANDLERS — a child's step belongs to the compiler, exactly as :id does. Nesting cannot be circular and needs no check to say so: a shape is an immutable value built out of already-built children."
     :cites [:a-handler-never-sees-the-state]
     :see [:robertluo.state-graph.shape/machines]}
    {:id :an-escape-is-unconditional
     :kind :decision
     :says "A parent's own edges are the ESCAPE from a nesting node and fire whether or not the child is finished. Escaping is an abort and yields nothing; {:done} is the second way out, the one that WAITS."
     :why "Nothing stops the parent leaving while the child is half done, and a guard would not change it — `the child has finished` is a fact about the STATE. Making the parent's edges wait for a final child was turned down because it would have made abort inexpressible, and abort is the commoner need."
     :cites [:a-machine-can-nest-in-a-node :a-state-may-say-where-it-goes-when-it-completes]}
    {:id :parent-edges-waiting-for-a-final-child-were-turned-down
     :kind :rejected
     :says "Making a nesting node's own edges wait until the child is in a final state was turned down."
     :why "It would have made ABORT inexpressible, and abort is the commoner need. A parent that wants to wait declares {:done} instead."
     :cites [:an-escape-is-unconditional]}
    {:id :a-state-may-say-where-it-goes-when-it-completes
     :kind :decision
     :says "{:done <id>} on a state is a COMPLETION TRANSITION — where it goes when it completes, with no event, no handler and no patch. One rule, and it is UML's: a state completes when it has nothing left to do, so a plain state completes ON ENTRY and a nesting one when its child reaches a final state."
     :why "Built 2026-09-03 out of a review of this architecture that named one thing genuinely missing and that this record had already named twice. The unification of a simple state and a composite one is why the feature is small: they are not two features. It is not a guard — one target, unconditional, so `compile` stays a lookup and nothing has to be proved disjoint — and a cycle among entry-completing states is then a PROVEN infinite loop, refused referentially as :done-cycle."
     :when "2026-09-03"
     :cites [:a-completion-is-an-edge-and-not-a-node-attribute :a-handler-causes-nothing]
     :see [:robertluo.state-graph.shape/completions :robertluo.state-graph.shape/continuations]}
    {:id :yield-is-harvested-at-completion-only
     :kind :decision
     :says "{:yield <a map schema>} is what a finished child hands up, and it is harvested at COMPLETION ONLY. Taken on an ordinary escape the child could be in any state, so the yield schema would be a hope; at completion it is a guarantee."
     :why "It is what makes the `yields` check SOUND: completing is the only moment the child is guaranteed final. An escape is still an abort and still yields nothing."
     :cites [:a-state-may-say-where-it-goes-when-it-completes :an-escape-is-unconditional]}
    {:id :done-may-say-where-each-outcome-goes
     :kind :decision
     :says "Given a MAP FROM THE CHILD'S FINAL STATE, {:done {<outcome> {:to <id> :yield <schema>}}} is one target per outcome, each with a :yield of its own — one edge per outcome. The bare form is unchanged and fingerprints as before."
     :why "It is still not a guard: it reads the structural fact :done already reads — which state the child is in — one notch finer, over a set finite and known at construction, dispatched by a map lookup on an id. It makes `yields` SHARPER, a per-outcome yield resting on its own final state and no other. What it unblocks is a parent that can tell `it worked` from `it gave up`: before it, both landed in one state and a consumer had to read a :fault key's presence as a tea leaf and copy good code aside under an invented key."
     :when "2026-09-05"
     :cites [:a-state-may-say-where-it-goes-when-it-completes :a-limit-was-read-as-a-principle]
     :see [:robertluo.state-graph.shape/completions]}
    {:id :a-completion-on-a-data-condition-is-refused
     :kind :rejected
     :says "A completion on a condition over the state's DATA — `all n reports are in`, `k branches have arrived` — is refused."
     :why "Each is a relation between keys that no malli schema expresses, so it could only be a CLOSURE, and a closure may decide a VALUE but never where the machine goes. The line drawn is that the shape may read a STRUCTURAL fact to decide completion, never a data one to decide anything. The per-outcome :done is a structural fact and was not this refusal's subject."
     :cites [:done-may-say-where-each-outcome-goes :a-guard-is-a-schema-over-the-event :a-combine-is-how-a-patch-lands]}
    {:id :may-a-state-complete-on-a-condition-over-its-own-data
     :kind :open
     :says "May a state complete on a condition over its own data? Three wants knock on this door — `all n reports are in`, `k branches have arrived`, `still under budget` — and each is a count or a comparison over what the state holds."
     :why "One of the three needed no door: a retry budget is a numeric bound on a count the DRIVER reports on the event, and two bounds that do not meet are provably disjoint, so it lives in the shape today. The obstruction for the rest is decidability. What would change it is a decidable spelling — a node holding a map keyed by item, the key set fixed on entry, completion as `every value is present`, which is `every sub is final` in different clothes. The useful question may be `which facts are structural`: a count of arrived branches is not one today because nothing in the shape names the arrivals."
     :cites [:a-completion-on-a-data-condition-is-refused]}
    {:id :a-node-may-sow-its-child
     :kind :decision
     :says "{:seed <a map schema>} on a nesting node is what the child is started with, projected off this node's own value on the way in — :yield's mirror. Without it a nested machine could only be told its job by the closure its shape was built from, so a host could not RE-ENTER it with a different job."
     :why "Added 2026-09-05 for the first consumer that wanted to LOOP over a child machine. Sown off the PROJECTED value and not the merge in flight, which is what keeps the `seeds` check local and sound: a node holds exactly what it declares. The seed and the per-outcome completion are one feature in practice — one lets a host re-enter a child with a new job, the other lets it tell what the child made of the last one."
     :when "2026-09-05"
     :cites [:a-machine-can-nest-in-a-node :done-may-say-where-each-outcome-goes :a-limit-was-read-as-a-principle]
     :see [:robertluo.state-graph.shape/seed]}
    {:id :a-limit-was-read-as-a-principle
     :kind :lesson
     :says "`A nested child is entered with no data` and `a yield must hold at every final state` were read as facts about what nesting IS, and a whole alternative was designed around them. Both were absences — nothing had ever carried the other direction, and the single completion target was argued from decidability, which says nothing about a finite set of node ids."
     :why "The test that separates an implementation limit from a principle limit is to find the sentence that REFUSED it. For a seed there was none, only a check recording the consequence. For a branching completion there was one, and reading it showed it was about data conditions."
     :from "the author, 2026-09-05: these are implementation limits and not principle limits, and letting one pick the design is the expensive mistake"
     :when "2026-09-05"}
    {:id :orthogonal-regions-are-out
     :kind :rejected
     :says "Orthogonal regions — {:machines {...} :done :x}, several independent children in one node — are out. A node holds ONE child."
     :why "Blocked on a question nesting has never had to answer: a child that finishes in :sub is left behind entirely when the parent escapes, because escape means ABORT and discarding is correct, while a join must COLLECT. :yield exists for one child and did not bring regions with it. A parent waiting on several children is still the regions question."
     :cites [:a-machine-can-nest-in-a-node]}]}
  ([id schema] (state id schema nil))
  ([id schema opts] (into {::kind :state :id id :schema (m/schema schema)} opts)))

(defn- lifting
  "The handler a PURE LIFT needs: the event's own declared keys, and nothing else.

   MOST HANDLERS ARE THIS, and spelling one out says the same thing three times — the
   event's schema, a `(fn [e] {:k (:k e)})` per key, and an :out that is the schema again.
   One of those is the fact; the other two are transcription.

   :id AND :instance ARE NOT LIFTED and cannot be: they are not in the declared schema, so
   `mu/keys` does not name them — which is the same reason the patch check refuses a handler
   that reaches for them. An event carries its :id at runtime and the machine reads it; a
   state never holds it."
  [schema]
  (let [ks (mu/keys schema)]
    (fn [event] (select-keys event ks))))

(defn event
  "An event: an id, the malli schema of its DATA, the HANDLER that answers it, optionally
   the schema of what that handler answers, and optionally {:sees <a map schema>}.

   GIVEN ONLY AN ID AND A SCHEMA the event is a PURE LIFT: its handler answers exactly the
   keys the schema declares and its :out is that schema. Which is what most events are, and
   what the four-argument form was saying three times —

     (event :brief [:map [:brief Brief]])                          ; this
     (event :brief [:map [:brief Brief]]                           ; and this are the same
            (fn [e] {:brief (:brief e)}) [:map [:brief Brief]])

   An event that carries nothing is `(event :green [:map])`. Reach for the longer forms when
   a handler does something a `select-keys` does not.

   The handler takes THE EVENT ALONE and answers a map that is merged into the state. The
   :id rides in the value at runtime for the same reason a state's does: the step function
   matches an edge on it.

   :sees IS A VIEW, and it is the one way anything inside the machine reads the state it is
   changing. Declared, never automatic — too broad a visibility from the inside is a
   security problem — and DECLARED HERE RATHER THAN ON THE NODE on purpose: a handler names
   what it needs BY SHAPE, so it stays reusable across every state that satisfies the view,
   which is stronger reuse than seeing nothing at all. Where a view is declared the handler
   takes TWO arguments, (handler event seen), and `seen` is the state projected onto the
   view's keys and validated against it — so a handler sees exactly what was declared and
   never the rest of the state.

   The function schema stays complete either way:
   [:=> [:cat <this :schema> <this :sees>] <this :out>], both halves off this map."
  {:malli/schema [:function [:=> [:cat Id MapSchema] EventDef]
                  [:=> [:cat Id MapSchema [:or fn? :map]] EventDef]
                  [:=> [:cat Id MapSchema fn? [:maybe MapSchema]] EventDef]
                  [:=> [:cat Id MapSchema fn? [:maybe MapSchema] [:maybe :map]] EventDef]]
   :knowledge
   [{:id :a-handler-never-sees-the-state
     :kind :decision
     :says "A handler takes THE EVENT ALONE — (handler event) — or (handler event seen) where the event declares a {:sees} view. Never the state itself, and never what was not declared."
     :why "The reason is decoupling: one handler serves many events and many source states. The bigger payoff is THE CHECK: a handler with no state in it is a complete malli function on its own — [:=> [:cat <the event's schema> <the view>] <the event's :out>] — every half off the event definition and nothing from the graph, so :out becomes a claim testable generatively. (handler state event) would have welded the handler to one node's schema. What it does not forbid: the STEP may depend on the state as much as it likes."
     :from "the author, 2026-08-30"
     :when "2026-08-30"}
    {:id :a-handler-taking-the-state-was-turned-down
     :kind :rejected
     :says "(handler state event) was turned down."
     :why "It would have needed the source node's schema in the handler's signature, welding the handler to one node and making :out a claim nothing could test without a machine around it."
     :cites [:a-handler-never-sees-the-state]}
    {:id :accumulation-policy-is-refused
     :kind :rejected
     :says "A combining key that only grows — `:messages by conj` — stays refused. A combine that only grows is a mechanism with no policy."
     :why "What a task wants is the last n, or a summary, or one field from three steps back, and in the domain this library was built for THE PILE IS THE COST, context being metered. The accumulation question was the wrong question; who may SEE what is the right one, and a view answers it with the policy as ordinary code."
     :cites [:a-handler-never-sees-the-state :internal-visibility-is-declared-and-not-automatic]}
    {:id :internal-visibility-is-declared-and-not-automatic
     :kind :decision
     :says "It is the constructor of the machine who decides what is visible from inside, never the library automatically. The READ half is a view declared ON THE EVENT — {:sees <a map schema>} — so the handler names what it needs by SHAPE and stays reusable across every state that satisfies the view."
     :why "Too broad a data visibility from the inside brings security problems easily. Declaring the view on the event and not on the node keeps the original reason intact and is STRONGER reuse than `sees nothing`. The HOLD half — a node holds exactly what it declares — lives in the compiler, and the view check is only sound because it does."
     :from "the author, 2026-09-01: from OUTSIDE an observer sees every transition; from INSIDE, can a handler get at information? It is the constructor of the machine who decides"
     :when "2026-09-01"
     :cites [:a-handler-never-sees-the-state]}
    {:id :is-the-node-side-exposure-needed
     :kind :open
     :says "Is node-side EXPOSURE needed, or is the event-side view enough? A view is least privilege by the handler's own word: a careless handler declares {:sees [:map [:token :string]]} and is handed the token. The remedy is a handshake — the node declares what it exposes, the event what it needs, the check verifies one is within the other — and it is additive."
     :why "Not built because projection already bounds visibility by ABSENCE, which is the stronger guarantee and covers the case that matters most: a state that never held the secret cannot leak it. Exposure only helps where a state must HOLD something a handler in the same machine must not read. The bar is a real shape that has that."
     :cites [:internal-visibility-is-declared-and-not-automatic]}
    {:id :a-handler-answers-a-map-and-declares-it
     :kind :decision
     :says "A handler's return value is a MAP merged into the state, and the event DECLARES its schema as :out. Both halves are for the static check and no other reason: an opaque (fn [state] state') can never be checked, and merge(<from schema>, <declared out>) ⊆ <to schema> is decidable without running anything."
     :why "The declaration is optional per event; absent, subsumption says :undeclared rather than faulting. The cost as first stated — a merge cannot REMOVE a key — was paid off by projection: dropping a field is declaring one fewer. What it costs instead is that carrying a key across several states is explicit, which for a join is not a cost but the whole mechanism."
     :cites [:a-handler-never-sees-the-state]}
    {:id :an-event-given-only-a-schema-is-a-pure-lift
     :kind :decision
     :says "(event :brief [:map [:brief Brief]]) is the whole declaration: the handler answers exactly the keys the schema declares and the :out is that schema. :id and :instance are not liftable and it falls out rather than being arranged — they are not in the declared schema, so mu/keys does not name them."
     :why "The four-argument form said ONE FACT THREE TIMES — the schema, a (fn [e] {:k (:k e)}) per key, and an :out that is the schema again — and transcription is where a shape drifts from itself. The README's example lost four lines; the first consumer's events went from 13 lines to 5. A handler is a fn and options are a map, so the 3-arity takes either and says which by type, which is what let a report be declared without losing the short form."
     :from "the author, 2026-09-03: `the event's 4-arg constructor looks very redandunt.`"
     :when "2026-09-03"}
    {:id :a-pure-lift-does-not-compose-with-a-tag
     :kind :lesson
     :says "A pure lift does not compose with a discriminating tag: a tag is ROUTING information the target does not hold, so a lifting handler answers it and the closed patch schema refuses it — correctly. A guarded event therefore usually spells its handler out."
     :cites [:an-event-given-only-a-schema-is-a-pure-lift :an-event-is-the-only-way-a-transition-happens]}
    {:id :a-consumers-lint-cache-has-to-be-refreshed
     :kind :lesson
     :says "After an arity changes here, a consumer's clj-kondo remembers the old arities of a :local/root dependency and reports errors for correct code. `rm -rf .clj-kondo/.cache` in the consumer. Seen twice."
     :cites [:an-event-given-only-a-schema-is-a-pure-lift]}
    {:id :an-event-may-say-how-it-is-reported
     :kind :decision
     :says "{:report <fn> :reads <a map schema>} on an event, and nothing else added. :report is the function that goes and finds the fact; :reads is the view of the state it needs, projected and validated exactly as :sees is. AN EVENT WITH NO :report COMES FROM THE WORLD — which is what a park is — so the declaration IS the driver/world distinction, in data."
     :why "It arrived from a consumer: its driver carried a map of acts keyed by state, and the shape had no place to say it. This record had named the gap and dismissed it as `a label and not a feature, and nobody has asked for it`; what refuted the dismissal was every driver having to write that knowledge down a second time, somewhere the checker could not see. It is symmetric with what an event already had: :handler/:out/:sees say how an event LANDS, :report/:reads how it is FOUND, both halves off one declaration."
     :from "the author, 2026-09-04, of a consumer's driver: `it collects otherwise independent steps into a global map, which is an anti pattern — the integration point should not be spread, the FSM shape already did it`"
     :when "2026-09-04"
     :cites [:a-handler-belongs-to-the-event]
     :see [:robertluo.state-graph.shape/reports]}
    {:id :a-report-is-not-an-internal-event
     :kind :decision
     :says "A report does not reopen :a-handler-causes-nothing. The machine does not move itself: this is the shape telling a CALLER how an event would be found, and a caller choosing to ask. No queue, no run-to-completion, and the reduction is untouched — a driver that ignores every report still works."
     :cites [:an-event-may-say-how-it-is-reported :a-handler-causes-nothing]}
    {:id :the-report-cannot-go-in-the-handler
     :kind :rejected
     :says "Doing the reporting work inside the handler — proposed first — cannot work, and is the thing to understand before proposing it again."
     :why "A GUARD READS THE INCOMING EVENT: the compiler validates the guard against the payload and only then runs the handler, so a fact a branch depends on must be on the event when it ARRIVES. A handler computing a verdict computes it after the edge is chosen, and the only way back is two events for one observation — precisely the hidden transition guards removed. The producer has to be outside the machine; the only question was where it is DECLARED."
     :cites [:an-event-may-say-how-it-is-reported :a-guard-is-a-schema-over-the-event]}
    {:id :a-shape-is-a-function-of-its-env
     :kind :decision
     :says "A consumer writes (defn shape [env] ...) and the reports close over whatever they reach for — a model, a socket, a clock — as the shape is built, so nothing downstream carries an environment. The shape can still be built with NO env at all: (shape {}) checks, draws and fingerprints, because none of those runs a report."
     :why "It is :a-shape-is-code one level out — a closure was already licensed in a shape. And it is what makes every branch reachable without paying for it: whatever a report reaches for arrived as a value, so an ordinary function goes in its place. See explore."
     :from "the author, 2026-09-04"
     :when "2026-09-04"
     :cites [:a-shape-is-code :an-event-may-say-how-it-is-reported]}
    {:id :a-handler-causes-nothing
     :kind :decision
     :says "A handler may not cause another event. It answers a data map and that is all it does; a cascade is spelled as the caller feeding the next event. With no emission there are no internal events, no queue to drain and no run-to-completion, and the core stays the reduction the README promises."
     :why "A handler that raises is the classic source of self-inflicted disorder. It is a CONTRACT and not a guarantee: a handler doing I/O can publish to the very stream feeding this machine, and no schema catches it. An external event is the ultimate source of a transition — the world moves the machine. The lean `the STATE raises, a handler never does` was taken 2026-09-03 at the cheaper end, as a deterministic CONTINUATION rather than an event, so the cycle check exists and the queue still does not."
     :from "the author, 2026-09-03: `An external event is the ultimate source of a transition`; and `An internal conditional should generate an event to the event queue. However, in our current design, the machine does not own the event queue.`"
     :when "2026-09-03"
     :cites [:a-state-may-say-where-it-goes-when-it-completes :one-ordered-stream-per-instance]}
    {:id :a-handler-raising-events-was-turned-down
     :kind :rejected
     :says "A handler answering {:data {...} :raise [...]} — both a patch and events to raise — was turned down."
     :why "It undoes :a-handler-answers-a-map-and-declares-it: the answer stops being a map merged into the state, so :out no longer describes it and the static check loses its subject. Reuse does not need it: the state can raise what the handler never mentioned."
     :cites [:a-handler-causes-nothing :a-handler-answers-a-map-and-declares-it]}
    {:id :are-internal-events-wanted-at-all
     :kind :open
     :says "Are internal events wanted at all? Nothing is blocked meanwhile: the motivation is HANDLER REUSE rather than cascades, and the commonest reason to want one — `move on now that this is finished` — is what a completion transition now is."
     :why "Three things to settle before any of it: whether the machine may drive itself at all, since a caller triggering the next event by hand costs nothing and hides the flow from `check`, which is the whole trade; breadth-first or depth-first, which is observable in the history and cannot be left to whatever `into` happens to do; and how an audit trail tells what the world did from what the machine did. And an internal raise is a SECOND EVENT SOURCE where source-merging was pushed onto the caller precisely because the machine has no clock."
     :cites [:a-handler-causes-nothing :one-ordered-stream-per-instance]}]}
  ([id schema] (event id schema (lifting (m/schema schema)) schema nil))
  ;; A PURE LIFT MAY STILL HAVE OPTIONS. A handler is a fn and options are a map, so the
  ;; third argument says which it is with no ceremony — and without this, declaring a
  ;; :report on a lifting event would have cost it the short form and written its schema
  ;; out twice again, which is the whole thing the short form removed.
  ([id schema handler-or-opts]
   (if (map? handler-or-opts)
     (event id schema (lifting (m/schema schema)) schema handler-or-opts)
     (event id schema handler-or-opts nil nil)))
  ([id schema handler out] (event id schema handler out nil))
  ([id schema handler out opts]
   (cond-> {::kind :event :id id :schema (m/schema schema) :handler handler}
     (some? out) (assoc :out (m/schema out))
     (:sees opts) (assoc :sees (m/schema (:sees opts)))
     (:report opts) (assoc :report (:report opts))
     (:reads opts) (assoc :reads (m/schema (:reads opts))))))

(defn transition
  "An edge: from a state, on an event, to a state. Three keywords and no functions —
   what handles the event belongs to the event.

   {:when <a map schema>} GUARDS IT: the edge fires only for events that schema admits, so
   one event can lead two ways and the shape says which. The guard describes the event's
   PAYLOAD — what it carries, without :id and :instance, exactly as a state's schema
   describes the state without them.

     (transition :written :judged :implemented {:when [:map [:verdict [:= :green]]]})
     (transition :written :judged :fault       {:when [:map [:verdict [:= :red]]]})

   TWO GUARDED EDGES MUST BE PROVABLY DISJOINT or the shape is refused: determinism is this
   library's contract, and an ordered `first match wins` is not available to a graph whose
   out-edges are a SET. There is no :else — an event no guard admits fires no edge, which
   is `ignored`, and the reduction stays total."
  {:malli/schema [:function [:=> [:cat Id Id Id] TransDef]
                  [:=> [:cat Id Id Id [:maybe :map]] TransDef]]
   :knowledge
   [{:id :a-guard-is-a-schema-over-the-event
     :kind :decision
     :says "{:when <a map schema>} on a transition, and nothing else added. :when is to a transition what :sees is to an event — an optional map schema, declared where the thing it constrains lives. It replaces the old no-guards rule; determinism is not what was given up, it stays the contract and is now PROVEN rather than had for free."
     :why "What started it: a step of your own workflow that runs the code and then picks the edge with an `if` is a HIDDEN transition — the drawing shows both arrows with nothing saying which fires. With the target a function of [state, event-id] alone a data-dependent branch could not be in the shape at all. A schema and not a predicate because a schema is DATA — drawable, comparable, partially decidable — and an :fn carries a :description, so one expression is both the check and the label."
     :from "the author, 2026-09-03: `a hidden transition is something we want to avoid`"
     :when "2026-09-03"
     :see [:robertluo.state-graph.shape/disjoint :robertluo.state-graph.shape/accepted]}
    {:id :decidable-guards-branch-and-an-fn-guard-stands-alone
     :kind :rule
     :says "Three decidable levers separate two guards: a shared key whose value schemas are disjoint, a CLOSED schema not naming a key the other insists on, and numeric bounds that do not meet. Decidable guards branch; an :fn guard may only appear ALONE on its [from event], as a FILTER."
     :why "Family A of the guard survey — ordered candidates and first-match — is the one this graph cannot have, out-edges being a set, so determinism has to be proven rather than ordered. A lone :fn cannot threaten the lookup, having nothing to be ambiguous with."
     :cites [:a-guard-is-a-schema-over-the-event :ubergraph-out-edges-are-a-set :the-closed-lever-reaches-less-far-than-first-claimed]}
    {:id :there-is-no-else
     :kind :decision
     :says "There is no :else. No guard matching means no edge admits the event, which is `ignored` — legal and first-class, so the reduction stays total and the stream says :fired false. Coverage is therefore PUBLISHED and never faulted."
     :cites [:a-guard-is-a-schema-over-the-event]}
    {:id :a-guard-map-with-three-meanings-was-turned-down
     :kind :rejected
     :says "A guard spelled as a map with a dispatch key beside case->target pairs beside an :else was turned down on sight."
     :why "The tiers are not grammar, they are HOW MUCH THE CHECKER CAN PROVE, which is the same three answers `admits` already gives."
     :from "the author, 2026-09-03"
     :cites [:a-guard-is-a-schema-over-the-event]}
    {:id :a-guard-over-the-state-is-refused
     :kind :rejected
     :says "A guard over the STATE is not taken. A guard is over the CAUSE, and the cause is the event: the driver reports a FACT and the shape decides what the fact MEANS."
     :why "Turning `a fault string exists` into `go to :fault` inside a driver is precisely the hidden transition. A {:sees}-style guard over the state is a door, named and not designed."
     :cites [:a-guard-is-a-schema-over-the-event :a-completion-on-a-data-condition-is-refused]}
    {:id :the-guard-survey
     :kind :lesson
     :says "Surveyed 2026-09-03, three families of conditional transition. (A) ordered candidates and a predicate, first passing guard wins — UML, SCXML, XState, clj-statecharts, Spring. (B) pattern matching in host code, no graph to check — gen_statem, Akka, Rust statig. (C) determinize on the input value — Automat, table-driven lexers. This library is in C. Worth stealing from A is only that XState NAMES a guard so a visualizer draws EVENT [isGreen], Harel's own notation."
     :when "2026-09-03"
     :cites [:a-guard-is-a-schema-over-the-event]}
    {:id :document-order-first-match-is-unrepresentable
     :kind :lesson
     :says "Document-order first-match guards are unrepresentable here: ubergraph keeps out-edges in a set, so there is no edge order to recover, and a priority number would be order smuggled back in as data."
     :cites [:ubergraph-out-edges-are-a-set :a-guard-is-a-schema-over-the-event]}
    {:id :the-closed-lever-reaches-less-far-than-first-claimed
     :kind :lesson
     :says "The closed lever reaches a key the event schema does not declare AT ALL, and no further. `green means no :fault key` does not work when :fault is a key the event's own schema declares as optional: `accepted` merges the guard over that schema and [:fault {:optional true}] survives as genuinely satisfiable, so :unknown is correct and the shape is rightly refused."
     :why "Corrected by trying it in the first consumer, which needed a tag after all: its :judged carries {:verdict [:enum :green :red]}."
     :cites [:decidable-guards-branch-and-an-fn-guard-stands-alone]
     :see [:robertluo.state-graph.shape/accepted]}
    {:id :a-retry-budget-is-two-guarded-edges
     :kind :lesson
     :says "A retry budget works today as two guarded edges on disjoint numeric bounds — [:int {:max 8}] and [:int {:min 9}] over a count the driver reports on the event — so the stopping rule is in the shape and no driving loop needs a counter."
     :cites [:decidable-guards-branch-and-an-fn-guard-stands-alone]}
    {:id :should-a-transition-declare-its-effects-and-idempotence
     :kind :open
     :says "Should a transition declare its :effects and :idempotence? The concurrency licence proves REORDERING is safe and says nothing about RE-EXECUTION. Harmless today, a speculative take never re-running a handler; retry and replay would both need it, and it is the same class of declared-law-plus-checker as :combine/commutes."
     :cites [:a-combine-is-how-a-patch-lands]}]}
  ([from event to] (transition from event to nil))
  ([from event to opts]
   (cond-> {::kind :transition :from from :event event :to to}
     (:when opts) (assoc :when (m/schema (:when opts))))))

;;; ------------------------------------------------------- schemas, compared

(def ^{:knowledge
       [{:id :the-seven-primitive-types-are-pairwise-disjoint
         :kind :lesson
         :says "The seven primitive types are pairwise disjoint, checked and not assumed — every value of each validated against the other six, :int against :double included. That is what licenses `admits` to answer :no from a type difference alone, and `disjoint` inherited it."
         :see [:robertluo.state-graph.shape/disjoint]}]}
  primitive-types
  "Types no single value belongs to two of, so two schemas differing here are a PROOF and
   not a guess. Deliberately small: enough for the common mistake, and not a lattice of
   every type malli has. :double is in only because malli's :int rejects a double and its
   :double rejects an int, which was checked rather than assumed."
  #{:int :double :string :keyword :boolean :symbol :uuid})

(defn entries-of
  "{k {:optional? bool :schema S}} for a :map schema. m/children gives [k props child]
   triples with props nil where there are none."
  {:malli/schema [:=> [:cat MapSchema] :map]}
  [s]
  (into {} (for [[k props child] (m/children (m/schema s))]
             [k {:optional? (boolean (:optional props)) :schema child}])))

(defn combines-of
  "{k {:combine f :commutes? bool :schema S}} for a :map schema — how each key that
   declares one is APPLIED when a patch lands on it, and what it promises about that.

   A NAIVE MERGE IS WHAT THIS REPLACES, and it was the reason the concurrency licence was
   so narrow. `merge` is last-write-wins, so two patches touching one key were never
   licensed; and `merge` cannot express a change relative to what the state holds, which is
   what forces a {:sees} view — and a view makes a pair unlicensable from the other side.
   Both halves of Bernstein's condition traced back to one operation.

   THE COMBINE IS A CLOSURE AND NOT A NAMED OPERATION, deliberately: in real work merging
   is domain logic — keep the best-scoring implementation with its provenance, deduplicate
   review comments by line — and a fixed vocabulary of :+ and :max expresses none of it.
   That is allowed here where it is refused for a GUARD because the two are on opposite
   sides of one line: a guard decides WHERE THE MACHINE GOES, which is structural and must
   be decided from the guard's own shape, while a combine decides WHAT A VALUE IS, inside a
   state, exactly as a handler's body always has.

   WHAT CANNOT BE A CLOSURE IS THE PROMISE. No function yields its own algebra, so
   :combine/commutes is declared as DATA beside it, and it is the only thing `commutes`
   reads. It is checked and not trusted: `check/laws` refutes it by generation, and
   `compile` verifies it on the concrete values at the moment a licence is actually taken.

   IT IS DECLARED ON THE NODE and never on an event, because the same key must combine the
   same way however it arrives. Per-edge algebra would prove nothing."
  {:malli/schema [:=> [:cat MapSchema] :map]
   :knowledge
   [{:id :a-combine-is-how-a-patch-lands
     :kind :decision
     :says "{:combine f :combine/commutes true} on a map entry says how a patch lands on that key. A key with no combine REPLACES, which is what a merge always did, so nothing written before behaves differently."
     :why "A naive merge was the whole limit on the concurrency licence: last-write-wins is the only non-commutative thing in the apply phase, so a concurrently incremented counter was inexpressible — a relative change needs {:sees} and is refused read-write, an absolute set is refused write-write. Both halves of Bernstein traced back to one operation. Two increments under a merge give :n 1 where the serial answer is 2; under + both orders give 2."
     :from "the author, 2026-09-03: `in real life, merging is a domain/task related job.`"
     :when "2026-09-03"}
    {:id :a-combine-may-be-a-closure-where-a-guard-may-not
     :kind :decision
     :says "A combine is a CLOSURE where a guard must be a schema, and it is not a reversal: a guard decides WHERE THE MACHINE GOES, which is structural and must be decided from the guard's own shape, while a combine decides WHAT A VALUE IS, inside a state, exactly as a handler's body always has."
     :cites [:a-combine-is-how-a-patch-lands :a-guard-is-a-schema-over-the-event :a-shape-is-code]}
    {:id :a-fixed-combine-vocabulary-was-refused
     :kind :rejected
     :says "A small proven set of combines — :+ :max :min :union — whose algebra the library would know was the first proposal, and was refused by the author on exactly the right ground."
     :why "It does not survive contact: :max does not express `keep the highest-scoring implementation with its provenance`, and a review-comment merge deduplicating by line is nobody's :union. A vocabulary that covers no real merge buys a checker nothing."
     :cites [:a-combine-is-how-a-patch-lands]}
    {:id :the-promise-is-data-and-checked-at-two-strengths
     :kind :decision
     :says "No function yields its own algebra, so the law is declared beside the combine as DATA — :combine/commutes — and it is the only thing the licence reads. It is checked at two strengths: check's `laws` REFUTES it by generation, and the compiler VERIFIES it on the concrete values whenever the licence is actually taken, before either patch lands."
     :why "A declaration nothing checks is the repository's own named anti-pattern; a false promise is then a defect that stops the machine rather than an order-dependent flake."
     :cites [:a-combine-is-how-a-patch-lands]}
    {:id :three-nodes-declare-the-same-combine
     :kind :decision
     :says "Three nodes and not one decide whether a shared key may be written by both events of a pair: ta, tb and the join x, being every node a patch of the pair ever lands on. Each must declare the SAME combine and each must declare it commutative. It is declared on the NODE and never on an event, because the same key must combine the same way however it arrives."
     :why "The fold applies the first patch at ta or tb and the second at x, so three different functions would compose into two different answers and prove nothing. For a self-loop, where combines pay, all three are one node."
     :cites [:a-combine-is-how-a-patch-lands]}
    {:id :most-domain-merges-are-not-commutative
     :kind :lesson
     :says "Most domain merges are NOT commutative, and the author will not notice. Ties, timestamps, last-writer and provenance all break the law invisibly, and both of the first two combines written here were refuted by generation. So the licence widens less than it sounds."
     :cites [:the-promise-is-data-and-checked-at-two-strengths]}
    {:id :malli-keeps-arbitrary-entry-properties
     :kind :lesson
     :says "Malli keeps arbitrary entry properties and mu/merge carries them through, so {:combine f} on a map entry survives into `enter-schema`. m/children hands back [k props child], which `entries-of` had already destructured and merely thrown the props away. Checked before designing anything on it."
     :see [:robertluo.state-graph.shape/entries-of :robertluo.state-graph.shape/enter-schema]}]}
  [s]
  (into {} (for [[k props child] (m/children (m/schema s))
                 :when (or (contains? props :combine)
                           (contains? props :combine/commutes))]
             [k {:combine (:combine props)
                 :commutes? (boolean (:combine/commutes props))
                 :schema child}])))

(defn finite-values
  "The values this schema describes, where they are FINITE and can simply be tried, and
   nil where they are not. This is the lever that decides a guard against anything at all —
   `disjoint` uses it to separate two guards, and `check/coverage` to ask whether a set of
   them leaves a gap."
  {:malli/schema [:=> [:cat Schema] [:maybe [:set :any]]]}
  [s]
  (case (m/type (m/schema s))
    (:= :enum) (set (m/children (m/schema s)))
    nil))

(defn- bounds
  "[lo hi] for a numeric schema, each end [value exclusive?] or nil, and the whole nil
   where the schema is not numeric or says nothing. Malli spells a bound two ways — the
   :min/:max properties of :int and :double, and the comparator schemas, which carry it as
   their only child — and both are decidable, which is what keeps `attempts under three`
   from having to become a tag."
  [s]
  (let [p (m/properties s) c (first (m/children s))]
    (case (m/type s)
      (:int :double) (when (or (:min p) (:max p))
                       [(when-let [v (:min p)] [v false])
                        (when-let [v (:max p)] [v false])])
      :>  [[c true] nil]
      :>= [[c false] nil]
      :<  [nil [c true]]
      :<= [nil [c false]]
      nil)))

(defn- apart?
  "Does a's upper bound sit at or below b's lower bound, with at least one of them
   excluding the meeting point?"
  [[_ hi] [lo _]]
  (boolean
   (when (and hi lo)
     (let [[hv hx] hi [lv lx] lo]
       (and (number? hv) (number? lv)
            (or (< hv lv) (and (= hv lv) (or hx lx))))))))

(declare ^:private dis)

(defn- dis-map
  "ONE conflicting key is enough, which is the whole structural difference from
   subsumption: `admits` needs EVERY key of its target to hold, and this needs only one to
   be impossible. A key optional in BOTH conflicts with nothing, a value being free to
   leave it out.

   IT NEVER ANSWERS :no. Proving two map schemas OVERLAP means producing a value that
   satisfies both, and one shared key agreeing is not that — another key may still refuse."
  [a b]
  (let [ea (entries-of a) eb (entries-of b)
        insisted (fn [e] (for [[k v] e :when (not (:optional? v))] k))]
    (if (or
         ;; a shared key, insisted on by at least one side, whose children cannot both hold
         (some (fn [[k va]]
                 (when-let [vb (get eb k)]
                   (and (or (not (:optional? va)) (not (:optional? vb)))
                        (= :yes (dis (:schema va) (:schema vb))))))
               ea)
         ;; a CLOSED schema has no room for a key the other side insists on
         (and (:closed (m/properties a)) (some #(not (contains? ea %)) (insisted eb)))
         (and (:closed (m/properties b)) (some #(not (contains? eb %)) (insisted ea))))
      :yes
      :unknown)))

(defn- dis
  [a b]
  (let [at (m/type a) bt (m/type b)
        fa (finite-values a) fb (finite-values b)]
    (cond
      ;; a finite domain decides it against ANY schema, and a value that satisfies both is
      ;; a proof of overlap rather than a failure to prove separation
      fa (if (some #(m/validate b %) fa) :no :yes)
      fb (if (some #(m/validate a %) fb) :no :yes)
      (and (= :map at) (= :map bt)) (dis-map a b)
      (and (primitive-types at) (primitive-types bt) (not= at bt)) :yes
      :else (let [ba (bounds a) bb (bounds b)]
              (if (and ba bb (or (apart? ba bb) (apart? bb ba)))
                :yes
                :unknown)))))

(defn disjoint
  "Can NO value satisfy both schemas? :yes, :no, or :unknown.

   THE SIBLING OF check/admits, and partial for the same reason: it never lies, and
   :unknown is an answer rather than a failure. Same two levers — the primitive types, and
   a finite domain that can simply be TRIED — plus numeric bounds, which subsumption has no
   use for and a guard does.

   What it can PROVE:
   - a finite domain none of whose values the other schema accepts;
   - a shared key whose two child schemas cannot both hold, the map combinator being an OR
     where subsumption's is an AND;
   - a CLOSED schema with no room for a key the other side insists on;
   - two numeric ranges that do not meet.

   :no means PROVEN OVERLAP and comes only from a finite domain, where the value that
   satisfies both is the proof. Two map schemas are never proven to overlap here — that
   needs a value, not an argument."
  {:malli/schema [:=> [:cat Schema Schema] [:enum :yes :no :unknown]]
   :knowledge
   [{:id :where-a-check-lives-is-decided-by-when-it-must-answer
     :kind :decision
     :says "Where a check lives is decided by WHEN it must answer, not by what it resembles. `disjoint` could not live beside `admits`: the ambiguity check is REFERENTIAL — a shape whose determinism cannot be proven must not be constructible, so it has to answer before the graph exists — and check sits above shape."
     :why "So `primitive-types` and `entries-of` moved down into shape, and subsumption and disjointness are siblings a layer apart over one vocabulary."
     :see [:robertluo.state-graph.shape/primitive-types :robertluo.state-graph.shape/entries-of :robertluo.state-graph.shape/problems]}
    {:id :dis-map-never-answers-no
     :kind :lesson
     :says "Two MAP schemas are never proven to overlap here, so :ambiguous carries no witness. Proving overlap needs a VALUE that satisfies both, and one shared key agreeing is not one — another key may still refuse. The witness did land in check's `coverage`, where a probe constructs the value."
     :cites [:where-a-check-lives-is-decided-by-when-it-must-answer]}]}
  [a b]
  (dis (m/schema a) (m/schema b)))

(defn accepted
  "The schema of the events a transition FIRES ON: the event's own schema with the edge's
   :when merged over it, so a guard REFINES rather than replaces —
   (mu/merge [:map [:verdict [:enum :green :red]]] [:map [:verdict [:= :green]]]).

   The sibling of check/produced, and there for the same reason: `produced` is what a
   transition hands its target and this is what it takes, and both have to compose the way
   the step composes or a check is answering about something that never runs. Takes a
   transition as `transitions` reads one back, so both the referential check and the
   structural ones ask it the same question."
  {:malli/schema [:=> [:cat :map] [:maybe MapSchema]]}
  [t]
  (let [w (:when t)]
    (cond-> (:schema t) (and w (:schema t)) (mu/merge w))))

;;; -------------------------------------------------------------------- checks

(def ^:private def-schema
  {:state StateDef :event EventDef :transition TransDef})

;; The nested-machine check asks a child what its FIRST STATE would have to validate
;; against, and the two functions that answer live in the reading section below — where
;; they belong, being the vocabulary and not a check. Declared rather than moved, and
;; deliberately not reimplemented here: what the check asks has to be what runs.
(declare enter-schema initial-id final? states)

(defn- names
  "A transition as three plain keywords. A problem may not carry a handler or a
   compiled schema: an error nobody can print or compare is not data."
  [t]
  [(:from t) (:event t) (:to t)])

(defn completions
  "The COMPLETION TRANSITIONS a state definition declares, as a seq of
   {:outcome <the child's final state, or nil for any> :to <id> :yield <schema or nil>}.

   ONE READING OF `:done` AND `:yield`, so the constructor, the referential checks and the
   drawing cannot come to disagree about what a spelling means. A bare {:done <id>} answers
   ONE entry whose :outcome is nil — every way the child can finish goes there — and a map
   answers one entry per outcome. A state declaring neither answers nothing.

   IT TAKES A PART AND NOT A BUILT SHAPE, because `problems` has to answer about parts that
   may never become one. `continuations` is the same reading off the graph."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [s]
  (let [done (:done s)]
    (cond
      (map? done)  (vec (for [outcome (sort (keys done))
                              :let [{:keys [to yield]} (get done outcome)]]
                          {:outcome outcome :to to :yield yield}))
      (some? done) [{:to done :yield (:yield s)}]
      :else        [])))

(defn- on-entry?
  "Whether this state COMPLETES ON ENTRY — the only moment at which a :done continuation
   fires with no event having arrived.

   A state with no nested machine has no activity to finish, so finishing it IS arriving.
   One with a machine finishes when that child reaches a final state, which normally takes
   events — EXCEPT where the child's own first state is already final, and then the parent
   completes on entry too. That exception is not academic: it is precisely what makes a
   :done cycle through a nesting node infinite, so the cycle check has to know it."
  [s]
  (let [child (:machine s)]
    (or (nil? child)
        (and (uber/ubergraph? child) (final? child (initial-id child))))))

(defn problems
  "What is wrong with these parts, AS DATA — a vector of maps, empty when nothing is.

   Every check here is REFERENTIAL and answerable without the graph. The structural
   ones — a state nothing reaches, a dead end, a handler whose answer the target will
   not admit — need the graph and live above this.

   The parts are [:* :any] and stay that way: this must ACCEPT a malformed part in
   order to report it, so a tighter argument schema would refuse the very input the
   function exists to answer about."
  {:malli/schema [:=> [:cat [:* :any]] [:vector :map]]
   :knowledge
   [{:id :the-referential-faults
     :kind :decision
     :says "A fault is a map carrying :problem, the id it is about, :within [<host node> ...] where nested, and a :witness where something could construct one. REFERENTIAL, refused by the constructor: the malformed-part and duplicate-id family, :unknown-state, :unknown-event, :ambiguous, :reads-without-report, :unused-event, :initial, :reserved-declared, :combine-not-a-function, :law-without-combine, :machine-cannot-start, :seed-without-machine, :outcome-without-machine, :unknown-outcome, :yield-with-outcomes, :done-and-final, :done-with-edges, :machine-cannot-finish, :done-cycle, :yield-without-machine, :yield-without-done."
     :why "Every one is answerable from the parts alone, so it runs inside the constructor and a bad shape never exists. The structural faults need the built graph and are check's."
     :cites [:where-a-check-lives-is-decided-by-when-it-must-answer]}
    {:id :ambiguous-inverts-and-demands-proven-safety
     :kind :decision
     :says ":ambiguous is the one fault that demands PROVEN SAFETY rather than reporting a proven fault: two edges on one [from event] whose guards are not provably disjoint are refused. The asymmetry is principled — determinism is the CONTRACT, and a shape that cannot prove it is deterministic is not one."
     :cites [:a-guard-is-a-schema-over-the-event]}
    {:id :the-dead-event-check-became-a-construction-time-check
     :kind :decision
     :says ":unused-event is answered at construction, which is the only moment the catalogue is in hand. `An event no transition mentions is dead code` stopped being a graph query the day the catalogue was denormalised, there being no catalogue on the graph to be dead relative to."
     :cites [:the-event-catalogue-is-denormalised]}
    {:id :an-argument-that-must-accept-rubbish-keeps-any
     :kind :lesson
     :says "`problems` and `shape` take [:* :any] on purpose: they must ACCEPT a malformed part in order to REPORT it. A tighter schema would refuse it with ::m/invalid-input instead of the list of what is wrong, and only under instrumentation, so the diagnosis would be both worse and different between dev and production."
     :see [:robertluo.state-graph.shape/shape]}
    {:id :a-for-whose-body-is-a-cond-puts-nil-in-problems
     :kind :lesson
     :says "A `for` whose body is a `cond` puts nil in `problems`, and every shape with a combine was once refused with a vector of nils. This function is a concat of a dozen comprehensions, and the idiom is :when, never a cond body."}
    {:id :unknown-outcome-could-not-report-a-misspelling
     :kind :lesson
     :says "The :unknown-outcome check asked (final? child outcome), and `final?` reads an attribute off a NODE — ubergraph throws on one it does not hold — so an outcome naming no state of the child at all escaped as an IllegalArgumentException instead of the fault. Fixed by asking membership first, the idiom :unknown-state three lines above already used."
     :why "The suite missed it because a person writing a TEST writes a name they can see, and a person writing a SHAPE writes one they meant. The tutorial found it, 2026-09-05, because a tutorial writes the fault the way a reader would provoke it."
     :when "2026-09-05"}
    {:id :a-malformed-event-that-failed-every-guard-looked-like-a-miss
     :kind :lesson
     :says "Selection happens before conform!, so an event failing every guard looked like an ordinary miss, conflating a DEFECT with a legitimate one. A guard is a refinement of a schema the event must already satisfy, so where nothing matches the compiler conforms the event against the group's schema — a bad event throws and a well-formed one no guard wanted is still ignored."
     :cites [:there-is-no-else]}]}
  [& parts]
  (let [{:keys [state event transition]} (group-by ::kind parts)
        state-ids (set (map :id state))
        event-ids (set (map :id event))
        fired     (set (map :event transition))
        dupes     (fn [kind xs]
                    (for [[id n] (frequencies (map :id xs)) :when (< 1 n)]
                      {:problem :duplicate :kind kind :id id :count n}))]
    (vec
     (concat
      ;; 0 — a part that is not the thing it says it is. One check for a handler that
      ;; is not a fn, a schema that is not a map schema, and every missing key.
      (for [p parts
            :let [s (def-schema (::kind p))]
            :when (or (nil? s) (not (m/validate s p)))]
        {:problem :malformed :kind (::kind p) :id (:id p)})
      (dupes :state state)
      (dupes :event event)
      ;; 1, 2 — a transition naming something that is not there
      (for [t transition
            [k id] [[:from (:from t)] [:to (:to t)]]
            :when (not (state-ids id))]
        {:problem :unknown-state :in (names t) :key k :id id})
      (for [t transition :when (not (event-ids (:event t)))]
        {:problem :unknown-event :in (names t) :id (:event t)})
      ;; 3 — determinism, which is the CONTRACT and not a nicety. Two edges on one
      ;; [state, event] are legal where guards make them exclusive, and what makes them
      ;; safe is that no one event can fire both. So this is the one check that must prove
      ;; SAFETY rather than report a proven fault: not-provably-disjoint is a fault.
      ;; AN UNGUARDED EDGE BESIDE ANY OTHER IS THE SIMPLEST CASE OF IT and needs no special
      ;; handling — with no :when, `accepted` is the bare event schema, which is disjoint
      ;; from nothing. And an ordered `first match wins` is not the alternative: ubergraph
      ;; keeps out-edges in a SET, so there is no order to fall back on and the proof is
      ;; the whole of the safety.
      (let [schema-of (into {} (map (juxt :id :schema)) event)]
        (for [[[from ev] ts] (group-by (juxt :from :event) transition)
              :when (and (< 1 (count ts)) (m/validate MapSchema (schema-of ev)))
              [a b] (for [[i x] (map-indexed vector ts), y (drop (inc i) ts)] [x y])
              :let [fires #(accepted {:schema (schema-of ev) :when (:when %)})
                    verdict (disjoint (fires a) (fires b))]
              :when (not= :yes verdict)]
          {:problem :ambiguous :from from :event ev
           :to [(:to a) (:to b)] :verdict verdict}))
      ;; 3b — a report is what makes an event the DRIVER'S rather than the WORLD'S, and
      ;; :reads is the view it needs to write one. A :reads with no :report is a view
      ;; nothing will ever be handed — the same shape of mistake as an :out on an event
      ;; nothing fires, and answerable from the parts alone.
      (for [e event :when (and (:reads e) (not (:report e)))]
        {:problem :reads-without-report :id (:id e)})
      ;; 4 — the catalogue's only moment. This is what replaces the dead-event check,
      ;; which cannot be a graph query once the catalogue has been denormalised away.
      (for [id (sort (remove fired event-ids))]
        {:problem :unused-event :id id})
      ;; 5 — the reachability check above needs a root, so the shape has to know it
      (let [inits (filterv :initial state)]
        (when (not= 1 (count inits))
          [{:problem :initial :count (count inits) :ids (mapv :id inits)}]))
      ;; 6 — :id, :instance and :sub are the MACHINERY'S words, and neither a state nor
      ;; an EVENT may claim one. A state redeclaring :id would be describing something
      ;; written over it on every entry; an event's schema describes its PAYLOAD, what it
      ;; carries, and :id and :instance are not carried but ridden in — which is why the
      ;; step conforms an event against its schema with those keys taken off.
      (for [p (concat state event)
            :when (m/validate MapSchema (:schema p))
            k (mu/keys (:schema p))
            :when (#{:id :instance :sub} k)]
        {:problem :reserved-declared :id (:id p) :key k})
      ;; 6b — a combine and the promise it makes. Both are REFERENTIAL: whether the thing
      ;; declared is a function, and whether a law was declared with nothing to be a law
      ;; about. Whether the law is TRUE is a different question and not answerable here —
      ;; check/laws refutes it by generation and compile verifies it on the values.
      (for [p state
            :when (m/validate MapSchema (:schema p))
            [k {:keys [combine]}] (combines-of (:schema p))
            :when (and (some? combine) (not (ifn? combine)))]
        {:problem :combine-not-a-function :id (:id p) :key k})
      (for [p state
            :when (m/validate MapSchema (:schema p))
            [k {:keys [combine commutes?]}] (combines-of (:schema p))
            :when (and (nil? combine) commutes?)]
        {:problem :law-without-combine :id (:id p) :key k})
      ;; 7 — a nested machine must be able to START. Entering a node with one enters that
      ;; child at its own initial state with WHATEVER THE NODE SOWS, which is nothing at all
      ;; unless it declares a :seed — so a child whose first state insists on data a
      ;; seedless node cannot give it is a nesting that could never begin. Answerable from
      ;; the parts alone, which is why it is here and not in the structural checks.
      ;;   A SEEDED NODE IS NOT ANSWERABLE HERE and is deliberately left to `check/seeds`:
      ;;   whether one map schema guarantees another is SUBSUMPTION, which is the structural
      ;;   checker's question and is answered there in both directions — can this node
      ;;   provide the seed, and will the child take it.
      (for [s state
            :let [child (:machine s)]
            :when (and child (uber/ubergraph? child) (not (:seed s)))
            :let [id (initial-id child)]
            :when (not (m/validate (enter-schema child id) {:id id}))]
        {:problem :machine-cannot-start :id (:id s) :initial id})
      ;; A :seed needs something to sow INTO, exactly as a :yield needs something to
      ;; harvest FROM. Without a machine it is a declaration nothing ever reads.
      (for [s state :when (and (:seed s) (not (:machine s)))]
        {:problem :seed-without-machine :id (:id s)})
      ;; 8 — A COMPLETION TRANSITION, and what it may not be. The STRUCTURAL half of this
      ;; costs nothing at all: :done is a real EDGE, so `reachable`, `dead-ends`,
      ;; `finishable` and `traps` see it without being told. What is left is what only the
      ;; parts can answer.
      (for [s state, c (completions s) :when (not (state-ids (:to c)))]
        {:problem :unknown-state :in [(:id s) nil (:to c)] :key :done :id (:to c)})
      ;; AN OUTCOME IS ONE OF THE CHILD'S FINAL STATES and there is nothing else it could
      ;; be: the map is keyed by where the child STOPPED, so a key naming anything else is
      ;; a branch that can never be taken. Both halves are answerable from the parts, the
      ;; child being a built shape already.
      (for [s state :when (map? (:done s))
            :let [child (:machine s)]
            :when (not (and child (uber/ubergraph? child)))]
        {:problem :outcome-without-machine :id (:id s)})
      (for [s state :when (and (map? (:done s))
                               (:machine s) (uber/ubergraph? (:machine s)))
            :let [child-ids (set (states (:machine s)))]
            outcome (sort (keys (:done s)))
            ;; ASKED AS MEMBERSHIP FIRST, exactly as :unknown-state above is. `final?`
            ;; reads an attribute off a NODE and ubergraph THROWS on one it does not
            ;; hold — so an outcome naming no state at all, which is what a misspelling
            ;; looks like, would escape as an ubergraph error instead of the fault this
            ;; line exists to report. Not final and not there at all are one fault, the
            ;; branch being untakeable either way.
            :when (not (and (child-ids outcome) (final? (:machine s) outcome)))]
        {:problem :unknown-outcome :id (:id s) :outcome outcome})
      ;; A per-outcome :done carries each branch's own :yield, so one beside it is a
      ;; declaration nobody reads — and the two spellings disagreeing about what is
      ;; harvested is exactly the drift `completions` exists to make impossible.
      (for [s state :when (and (:yield s) (map? (:done s)))]
        {:problem :yield-with-outcomes :id (:id s)})
      ;; Completing and being FINAL are contradictory: a final state is where a machine
      ;; stops and :done says where it goes next.
      (for [s state :when (and (:done s) (:final s))]
        {:problem :done-and-final :id (:id s)})
      ;; A state that continues ON ENTRY can never be sitting there when an event arrives,
      ;; so its own out-edges are dead code — the same fault as :unused-event and reported
      ;; for the same reason. A NESTING node's edges are its ESCAPE and are not dead: it
      ;; sits there for as long as its child is unfinished, which is the whole point.
      (let [departs (set (map :from transition))]
        (for [s state :when (and (:done s) (on-entry? s) (departs (:id s)))]
          {:problem :done-with-edges :id (:id s)}))
      ;; A :done that can NEVER fire, because the child it waits on has no way to finish.
      (for [s state
            :let [child (:machine s)]
            :when (and (:done s) child (uber/ubergraph? child)
                       (not-any? #(final? child %) (states child)))]
        {:problem :machine-cannot-finish :id (:id s)})
      ;; A CYCLE AMONG ENTRY-FIRED CONTINUATIONS IS A PROVEN INFINITE LOOP, and being
      ;; proven is the whole reason it may be a fault: a completion transition is
      ;; UNCONDITIONAL, so the relation is a plain functional graph and a cycle in it is
      ;; not a suspicion about what might happen. A cycle THROUGH a nesting node whose
      ;; child needs events to finish is legal and is not reported — the events are what
      ;; break it.
      (let [chain (into {} (for [s state :when (and (:done s) (on-entry? s))]
                             ;; WHICH continuation an entry-completing state takes is
                             ;; decided: it has no machine, or its child's first state is
                             ;; already final and names the outcome. So the relation stays
                             ;; functional and a cycle in it is still a proof.
                             [(:id s) (let [child (:machine s)]
                                        (:to (first (filter #(or (nil? (:outcome %))
                                                                 (= (:outcome %)
                                                                    (and child (initial-id child))))
                                                            (completions s)))))]))]
        (for [id (sort (keys chain))
              :when (loop [at (chain id) seen #{}]
                      (cond (nil? at) false
                            (= at id) true
                            (seen at) false
                            :else (recur (chain at) (conj seen at))))]
          {:problem :done-cycle :id id}))
      ;; 9 — :yield needs something to harvest FROM and a moment to harvest ON. Without
      ;; the second it is a declaration nothing ever reads, and this library does not keep
      ;; those: completion is the only moment a child is guaranteed final.
      (for [s state, c (completions s) :when (and (:yield c) (not (:machine s)))]
        {:problem :yield-without-machine :id (:id s)})
      (for [s state :when (and (:yield s) (not (:done s)))]
        {:problem :yield-without-done :id (:id s)})))))

;;; ---------------------------------------------------------------------- shape

(defn shape
  "The parts, checked, as a graph. Throws when `problems` finds anything, carrying
   them in ex-data — ask `problems` first to look without throwing.

   [:* :any] for the same reason `problems` has it, and a stronger one: a tighter
   argument schema would refuse a malformed part with ::m/invalid-input instead of the
   list of what is wrong with it, and would do so ONLY under instrumentation — so the
   diagnosis would be worse and would differ between dev and production."
  {:malli/schema [:=> [:cat [:* :any]] Shape]
   :knowledge
   [{:id :a-shape-is-code
     :kind :decision
     :says "A state machine shape is CODE. You WRITE it as data; it does not ROUND-TRIP as data. It is built at namespace load, its handlers are real closures, its schemas are compiled once."
     :why "The README's `pure clojure data with convinient functions as constructors` means written as data, not stored as data. That kills every argument from EDN, from =, from storability — which were the only objections to the shape BEING an ubergraph, so it is one, with the checks and the drawing reading it directly and no parallel map to keep in sync. It settles persistence without a separate argument: what is stored is HISTORY, and it is what licenses a combine to be a closure."
     :from "the author, 2026-08-30"
     :when "2026-08-30"
     :cites [:ubergraph-is-equal-and-edn-for-value-attributes]}
    {:id :the-event-catalogue-is-denormalised
     :kind :decision
     :says "The event catalogue is an ARGUMENT to the constructor, which writes each event's schema, handler, :out, :sees, :report and :reads onto EVERY EDGE that fires it and refuses a shape whose edges disagree about one event. The graph remains the whole shape."
     :why "Forced by ubergraph rather than chosen: a graph holds nodes and edges and nothing else, so the catalogue has nowhere on the graph to live."
     :cites [:ubergraph-is-a-closed-map-and-fails-silently]
     :see [:robertluo.state-graph.shape/transitions]}
    {:id :a-bipartite-graph-was-killed
     :kind :rejected
     :says "Making the graph bipartite — state -> event -> state — was killed first, so nobody proposes it again. It is not merely awkward, it is WRONG: two transitions on :submit leaving different states would share one event node and FABRICATE paths the shape never declared, A -> submit -> D when only A -> submit -> B and C -> submit -> D were said."
     :cites [:the-event-catalogue-is-denormalised]}
    {:id :a-completion-is-an-edge-and-not-a-node-attribute
     :kind :decision
     :says "A completion transition is a real EDGE, marked {:done true}, carrying no :event, one edge per outcome, and nothing about it is left on the node. That absence of an :event is the whole distinction between an edge fired by an event and one fired by arriving."
     :why "The edge-or-attribute question was the whole design, settled by counting what each way costs: as an edge, `reachable`, `dead-ends`, `finishable` and `traps` all walk the graph and needed NOT ONE LINE; as an attribute, each would have had to learn about it or condemn correct shapes. The cost of the edge was one :when in `transitions`. Two places saying one thing is how a shape drifts from itself."
     :see [:robertluo.state-graph.shape/transitions :robertluo.state-graph.shape/continuations]}
    {:id :a-shared-catalogue-must-be-selected-from
     :kind :lesson
     :says "Sharing parts is free and complete — a vector of states and events assembles into two different machines by concat plus different transitions, and one child shape nests into two unrelated parents with nothing to alias. But a shared catalogue must be SELECTED FROM and not splatted in: the first assembly using fewer events than the catalogue holds is refused with :unused-event, and the check is right. A parts library wants to be a MAP KEYED BY ID."
     :why "Measured, checking the author's `most parts are shared, only the assembly differs` against the code. Whoever builds workflows out of parts should know it on day one rather than day three."
     :cites [:the-dead-event-check-became-a-construction-time-check]}]}
  [& parts]
  (when-let [ps (seq (apply problems parts))]
    (throw (ex-info "The shape has problems" {:problems (vec ps)})))
  (let [{:keys [state event transition]} (group-by ::kind parts)
        catalogue (into {} (map (juxt :id #(select-keys % [:schema :handler :out :sees :report :reads]))) event)]
    (-> (uber/multidigraph)
        (uber/add-nodes-with-attrs*
         (for [s state] [(:id s) (cond-> (select-keys s [:schema :initial :final :machine])
                                   (:seed s) (assoc :seed (m/schema (:seed s))))]))
        (uber/add-directed-edges*
         (for [t transition]
           [(:from t) (:to t)
            (cond-> (assoc (catalogue (:event t)) :event (:event t))
              (:when t) (assoc :when (:when t)))]))
        ;; A COMPLETION TRANSITION IS A REAL EDGE and not a node attribute, and that is
        ;; what buys the structural checks for nothing: `reachable`, `dead-ends`,
        ;; `finishable` and `traps` all WALK THE GRAPH, so a state reached only by
        ;; completing is reached, and a state whose only way out is completing is not a
        ;; dead end. Getting that from a node attribute would have meant teaching four
        ;; traversals about it.
        ;; IT CARRIES NO :event, and that absence is the whole distinction: `transitions`
        ;; reads it to leave these out, because an edge fired by an EVENT and an edge fired
        ;; by ARRIVING are different things to everything above here.
        ;; ONE EDGE PER OUTCOME, which is what makes the per-outcome form cost the
        ;; traversals nothing either: two ways for a child to finish are two arrows, and
        ;; `reachable`, `dead-ends`, `finishable` and `traps` walk them without being told
        ;; that a state can complete in more than one way.
        (uber/add-directed-edges*
         (for [s state, c (completions s)]
           [(:id s) (:to c) (cond-> {:done true}
                              (:outcome c) (assoc :outcome (:outcome c))
                              (:yield c) (assoc :yield (m/schema (:yield c))))])))))

;;; ------------------------------------------------------------------- reading

(defn states
  "The ids of every state in the shape."
  {:malli/schema [:=> [:cat Shape] [:sequential Id]]}
  [shape]
  (uber/nodes shape))

(defn initial-id
  "The state a run starts in. The shape knows the NODE; the starting data is still an
   argument to the reduction."
  {:malli/schema [:=> [:cat Shape] Id]}
  [shape]
  (first (filter #(uber/attr shape % :initial) (uber/nodes shape))))

(defn final?
  {:malli/schema [:=> [:cat Shape Id] :boolean]}
  [shape id]
  (boolean (uber/attr shape id :final)))

(defn machine
  "The machine NESTED in this node, or nil. A child is an ordinary Shape and is checked,
   compiled and drawn as one — which is what makes nesting cost so little."
  {:malli/schema [:=> [:cat Shape Id] [:maybe Shape]]}
  [shape id]
  (uber/attr shape id :machine))

(defn machines
  "{node id -> the machine nested in it}, for every node that has one. Empty for a flat
   shape, and the one thing a layer above needs to ask in order to recurse.

   NESTING CANNOT BE CIRCULAR and needs no check to say so: a shape is an immutable value
   built out of already-built children, so no shape can contain itself."
  {:malli/schema [:=> [:cat Shape] [:map-of Id Shape]]}
  [shape]
  (into {} (for [id (uber/nodes shape)
                 :let [m (machine shape id)]
                 :when m]
             [id m])))

(defn transitions
  "Every edge FIRED BY AN EVENT as a plain map — :from :event :to, and the event's
   :schema, :handler and :out denormalised onto it. The vocabulary everything above this
   reads a shape through, and why nothing above had to learn that the handler moved.

   A COMPLETION TRANSITION IS NOT ONE OF THESE, and leaving it out is not an omission. It
   carries no event, so there is no handler, no guard and no :out to denormalise — and
   every reader here is asking a question ABOUT EVENTS: what the index looks up, what
   `coverage` groups, what `commutes` reasons over. See `continuations`, which is where the
   other kind of edge is read."
  {:malli/schema [:=> [:cat Shape] [:sequential :map]]
   :knowledge
   [{:id :a-reading-layer-keeps-a-structural-change-local
     :kind :lesson
     :says "A reading layer between the graph and its consumers is what lets a structural change stay local. Moving the handler onto the event changed shape.clj AND NOTHING ELSE, because everything above reads a shape through this function; adding a whole new KIND of edge, the completion, touched it and nothing above it. Twice now."
     :cites [:a-handler-belongs-to-the-event :a-completion-is-an-edge-and-not-a-node-attribute]}]}
  [shape]
  (for [e (uber/edges shape)
        :when (uber/attr shape e :event)]
    (into {:from (uber/src e) :to (uber/dest e)} (uber/attrs shape e))))

(defn reports
  "{event-id -> {:report <fn>, :reads <schema>}} for every event a DRIVER produces —
   empty for a shape whose events all come from the world.

   WHAT IT IS FOR, and it is the one thing the shape could not say until now: whether an
   event comes from the DRIVER or from the WORLD. A state waiting on an event with no
   report is PARKED — somebody outside will say what happened — and a state waiting on one
   WITH a report is a state a driver can advance by itself. That distinction was drawn in
   prose and nowhere in data, so every driver written against this library had to write it
   down a second time, keyed by state, where nothing could check it.

   A REPORT IS NOT AN INTERNAL EVENT. The machine still does not move itself: this is the
   shape telling a caller HOW an event would be found, and a caller choosing to ask. The
   reduction is untouched and there is no queue."
  {:malli/schema [:=> [:cat Shape] [:map-of Id :map]]
   :knowledge
   [{:id :the-driver-world-distinction-is-data
     :kind :decision
     :says "Whether an event comes from the DRIVER or from the WORLD is read off the shape: an event with a :report is the driver's, one without is the world's, and a state waiting only on the latter is PARKED. It had been drawn in prose and nowhere in data, so every driver had written it down a second time keyed by state."
     :cites [:an-event-may-say-how-it-is-reported]}]}
  [shape]
  (into {} (for [t (transitions shape) :when (:report t)]
             [(:event t) (select-keys t [:report :reads])])))

(declare fingerprint continuations)

(defn- plain
  "A value with every UNREADABLE thing replaced by ::opaque, and every collection put in
   a deterministic order.

   WHY ERASING AND NOT RENDERING. `m/form` happily renders a closure as
   #object[user$fn__44837 0x3442b587 ...] — a hex address that differs every process — so a
   fingerprint over the printed form would change on every JVM start and be worth nothing to
   a transcript written yesterday. MEASURED: two builds of [:fn {...} (fn [v] ...)] have
   forms that are NOT =. So anything that is not a value becomes one marker.

   THE COST IS REAL AND IS THE SAME COST HANDLERS HAVE: a predicate's BODY is invisible, so
   changing what an :fn checks does not move the fingerprint. What is proven is the SHAPE of
   the schema and the presence of a predicate, not its meaning."
  [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)) x
    (map? x) (vec (sort-by pr-str (map (fn [[k v]] [(plain k) (plain v)]) x)))
    (set? x) (vec (sort-by pr-str (map plain x)))
    (sequential? x) (mapv plain x)
    :else ::opaque))

(defn canonical
  "The shape as ORDERED, READABLE DATA — what a fingerprint is taken over, and what to diff
   when two fingerprints disagree and you need to know why.

   EVERYTHING THAT IS DATA IS IN: node ids, the FORM of every schema, :initial and :final,
   every edge as [from event to] with its guard, :out, :sees and :reads, and every completion
   edge with its :yield. EVERYTHING THAT IS A CLOSURE IS IN ONLY AS ITS PRESENCE — a handler,
   a report and a combine are booleans here, because :a-shape-is-code means they cannot be
   anything else.

   A NESTED MACHINE IS ITS CHILD'S FINGERPRINT, so the recursion terminates and a change deep
   in a child still moves the parent.

   ORDERED BY PRINTED FORM, because ubergraph keeps nodes and out-edges in SETS and a
   fingerprint that depended on iteration order would not be one."
  {:malli/schema [:=> [:cat Shape] :map]
   :knowledge
   [{:id :closures-are-erased-and-not-rendered
     :kind :decision
     :says "Everything that is not a value becomes one marker, ::opaque, and a handler, a report and a combine are in only as their PRESENCE. It is the decision the fingerprint rests on."
     :why "m/form happily renders a closure as #object[user$fn__44837 0x3442b587 ...] — measured, two builds of [:fn {...} (fn [v] ...)] have forms that are not = — and a hex address differs every process, so a fingerprint over the printed form would be worthless to a transcript written yesterday. The cost is real and is the same cost handlers have: a predicate's BODY is invisible, so changing what an :fn checks does not move the fingerprint."
     :cites [:a-shape-has-a-derived-id]}]}
  [sh]
  {:states
   (vec (sort-by pr-str
                 (for [id (states sh)
                       :let [a (uber/attrs sh id)]]
                   (plain [id (cond-> {:schema (m/form (:schema a))
                                       :initial (boolean (:initial a))
                                       :final (boolean (:final a))
                                       :machine (some-> (:machine a) fingerprint)}
                                ;; ADDED ONLY WHERE THERE IS ONE, so that a shape which
                                ;; sows nothing fingerprints exactly as it did before seeds
                                ;; existed. A transcript written yesterday still names the
                                ;; machine that wrote it.
                                (:seed a) (assoc :seed (m/form (:seed a))))]))))
   :events
   (vec (sort-by pr-str
                 (for [t (transitions sh)]
                   (plain [(:from t) (:event t) (:to t)
                           {:schema (m/form (:schema t))
                            :when (some-> (:when t) m/form)
                            :out (some-> (:out t) m/form)
                            :sees (some-> (:sees t) m/form)
                            :reads (some-> (:reads t) m/form)
                            :handler (some? (:handler t))
                            :report (some? (:report t))}]))))
   :done
   (vec (sort-by pr-str
                 (for [[from cs] (continuations sh), c cs]
                   (plain [from (:to c) (cond-> {:yield (some-> (:yield c) m/form)}
                                          (:outcome c) (assoc :outcome (:outcome c)))]))))})

(defn fingerprint
  "A stable id for the SHAPE of this machine: SHA-256 over `canonical`, as hex.

   WHAT IT IS FOR. A transcript is a file of rows, and a row that cannot say which machine
   produced it is a row nobody can audit. `(hash shape)` will not do — MEASURED: two
   structurally identical shapes are neither = nor equal-hashed, because their handlers are
   distinct closures and their schemas distinct compiled objects, so it changes on every
   namespace load. This is derived from the shape's DATA and is the same in every process.

   IT IS DERIVED AND NOT DECLARED, which is the whole reason to have it: nobody can forget to
   bump it. What it does NOT carry is a NAME — that is a fact about the job rather than about
   the graph, and it belongs to whoever owns the job.

   WHAT IT PROVES AND WHAT IT DOES NOT, and this must be read before trusting one: it proves
   THE GRAPH MATCHED — the same states, schemas, events, guards and targets. It does not prove
   the same CODE ran. Change what a handler returns without changing its :out, or change what
   an :fn predicate checks, and the fingerprint is unmoved. See `plain`."
  {:malli/schema [:=> [:cat Shape] :string]
   :knowledge
   [{:id :a-shape-has-a-derived-id
     :kind :decision
     :says "A shape has a stable identity: `canonical` is the ordered, readable form and `fingerprint` is SHA-256 over its printed representation. Everything that is DATA goes in — node ids, schema forms, :initial and :final, every edge as [from event to] with its guard, :out, :sees and :reads, every completion edge with its :yield and :outcome — ordered by printed form because ubergraph keeps nodes and edges in sets. A nested machine is its child's fingerprint."
     :why "A transcript row that cannot say which machine produced it is a row nobody can audit. It is DERIVED and not declared, which is the whole reason to have one rather than a version number: nobody can forget to bump it."
     :from "the author, 2026-09-04: `to make sure the transcript log file correspond to a FSM, we may need a stable id for the FSM.`"
     :when "2026-09-04"
     :cites [:closures-are-erased-and-not-rendered :ubergraph-out-edges-are-a-set]
     :see [:robertluo.state-graph.shape/canonical]}
    {:id :hash-shape-is-not-an-id
     :kind :rejected
     :says "(hash shape) is not an identity, measured: two structurally identical shapes built separately in one process are neither = nor equal-hashed, their handlers being distinct closures and their schemas distinct compiled objects. It changes on every namespace load."
     :cites [:a-shape-has-a-derived-id :a-shape-is-code]}
    {:id :the-fingerprint-proves-the-graph-and-not-the-code
     :kind :decision
     :says "What a fingerprint proves is THE GRAPH MATCHED — the same states, schemas, events, guards and targets — and not that the same code ran. Change what a handler returns without changing its :out, or change what an :fn predicate checks, and it does not move. It has to be said wherever a fingerprint is used."
     :cites [:a-shape-has-a-derived-id :closures-are-erased-and-not-rendered]}
    {:id :the-env-does-not-move-the-fingerprint
     :kind :lesson
     :says "A shape built as a function of its env fingerprints the same in every env, the env being closed over in reports that are erased. Measured on the first consumer: (shape {}) and (shape {:writer ... :repl ...}) are one fingerprint. Right — it is the same machine — and it means the fingerprint does not say WHERE it ran; that belongs in the log's context beside the name."
     :cites [:a-shape-is-a-function-of-its-env :the-fingerprint-proves-the-graph-and-not-the-code]}
    {:id :the-fingerprint-carries-no-name
     :kind :decision
     :says "A fingerprint carries no NAME. What a machine is called is a fact about the JOB rather than about the graph, and belongs to whoever owns the job — so identity is two-part and only half of it is the library's."
     :cites [:a-shape-has-a-derived-id]}
    {:id :mid-flight-shape-versioning-stays-open
     :kind :open
     :says "A fingerprint on every transcript row answers `which shape produced this` for a FINISHED run, which is the audit case and the one asked for. An instance in flight across a shape change is still open, and is left open deliberately."
     :cites [:a-shape-has-a-derived-id]}]}
  [sh]
  (let [bs (.digest (java.security.MessageDigest/getInstance "SHA-256")
                    (.getBytes (pr-str (canonical sh)) "UTF-8"))]
    (apply str (map #(format "%02x" %) bs))))

(defn continuations
  "{from -> [{:outcome <id or absent>, :to <id>, :yield <schema>} ...]} for every COMPLETION
   TRANSITION — where a state goes when it COMPLETES, with no event and no handler. Empty
   for a shape that declares none, which is every shape written before this existed.

   A VECTOR PER STATE, because a state may say where each OUTCOME goes: an entry with no
   :outcome is the unconditional form and there is then exactly one of them, and an entry
   WITH one is taken when the child stopped in that final state. Either way it is a lookup
   on an id and never a search — nothing is guarded, so there is still no ambiguity to prove
   away, which is the difference between this and a guard.

   ORDERED, because ubergraph keeps out-edges in a SET and a runtime that depended on
   iteration order would not be one.

   THE EDGE IS THE ONLY RECORD OF IT. `state` takes :done, :yield and :seed, the constructor
   turns the first two into edges, and nothing about them is left behind on the node: two
   places saying one thing is how a shape drifts from itself."
  {:malli/schema [:=> [:cat Shape] [:map-of Id [:vector :map]]]}
  [shape]
  (->> (for [e (uber/edges shape)
             :when (uber/attr shape e :done)
             :let [y (uber/attr shape e :yield)
                   o (uber/attr shape e :outcome)]]
         [(uber/src e) (cond-> {:to (uber/dest e)} o (assoc :outcome o) y (assoc :yield y))])
       (reduce (fn [m [from c]] (update m from (fnil conj []) c)) {})
       (into {} (map (fn [[from cs]] [from (vec (sort-by pr-str cs))])))))

(defn seed
  "The map schema a state sows its nested machine with, or nil where it sows nothing.

   THE MIRROR OF `continuations`' :yield, and read off the NODE where that is read off the
   edge — a seed is about entering this state, which is not a transition anywhere."
  {:malli/schema [:=> [:cat Shape Id] [:maybe MapSchema]]}
  [shape id]
  (uber/attr shape id :seed))

(defn combines
  "{k {:combine f :commutes? bool :schema S}} for a NODE — what its own schema declares
   about how a patch lands on each key. Empty for a node that declares none, which is
   every node written before this existed: a key with no combine REPLACES, which is what
   `merge` always did. See `combines-of`."
  {:malli/schema [:=> [:cat Shape Id] :map]}
  [shape id]
  (combines-of (uber/attr shape id :schema)))

(defn enter-schema
  "What a state is validated against ON ENTER: its own schema with :id written in, and
   :instance permitted. DERIVED and never written by hand, so entering also checks that
   the machine landed where it thought it did.

   :instance is OPTIONAL because one machine reduced over one seq needs no name for
   itself — that is the README's own headline use and it should cost nothing. It is the
   async layer, routing between many, that will insist on it."
  {:malli/schema [:=> [:cat Shape Id] MapSchema]}
  [shape id]
  (mu/merge (cond-> [:map [:id [:= id]] [:instance {:optional true} Instance]]
              (machine shape id) (conj [:sub [:map [:id Id]]]))
            (uber/attr shape id :schema)))

(defn patch-schema
  "What a HANDLER may answer for a state: the state's own schema, EVERY KEY OPTIONAL and
   the map CLOSED.

   OPTIONAL because a handler answers a PATCH and not a state — it says what changed, and
   what it does not mention the state it is changing already holds.

   CLOSED because a key this state does not declare is a key the machine THROWS AWAY. The
   merge is projected onto `mu/keys` of the enter-schema, so an undeclared key never
   reaches the state whatever the state's own `:closed` says — and a handler computing
   something that silently evaporates is a defect, not a style. Closing it here is what
   turns that from a shrug into a refusal.

   AND IT IS WHY IDENTITY NEEDS NO SPECIAL CASE. A state schema describes the map WITHOUT
   :id, :instance and :sub, so a handler answering any of the three is answering a key the
   state does not declare, and this refuses it for the same reason it refuses a typo. An
   event is the only way a transition happens; a handler that names where it lands is
   asking for one it was not given."
  {:malli/schema [:=> [:cat Shape Id] MapSchema]
   :knowledge
   [{:id :an-event-is-the-only-way-a-transition-happens
     :kind :decision
     :says "A handler answers a PATCH, conformed against the target's own schema with every key optional and the map CLOSED. Optional because a handler says what changed; closed because a key the target does not declare EVAPORATES, and closing turns a shrug into a refusal. Identity then needs no special case: naming :id, :instance or :sub is answering an undeclared key, refused by the rule that refuses a typo."
     :why "What was already true is that a handler could not move the machine, the step writing :id after the merge. What was wrong with it was SILENCE — a handler answering :id was overwritten without a word, a convention the code quietly repaired. Four tests had asserted the silence; each now asserts the refusal. The check sits after :out and before :enter: :out is what a handler PROMISES and is optional, :answer is what the target ADMITS and is not, :enter keeps the one thing only a whole state can be wrong about — a required key nobody supplied."
     :from "the author, 2026-09-03: `In a FSM, a state can only transit by an event, so inside a machine, the only way of doing transition is to emit an event. And this hidden transition has to be illegal.` and `the event's returned data should match the state schema`"
     :when "2026-09-03"
     :cites [:a-state-has-an-id :the-schema-describes-the-map-without-the-machinery-keys]}]}
  [shape id]
  (-> (uber/attr shape id :schema)
      (mu/optional-keys)
      (mu/update-properties assoc :closed true)))

(defn explain
  "m/explain as PLAIN DATA — one map per error, and forms rather than compiled Schema
   objects, which nobody can read, print or compare. nil when the value is fine.

   The offending child is resolved with (mu/get-in root (:path error)), NOT with malli's
   own (:schema error): for a MISSING KEY the latter is the whole enclosing map, which
   answers a question nobody asked. Verified over a missing key, a wrong-typed key and a
   nested one — mu/get-in is right in all three, (:schema error) only in the second.

   :type is carried where malli gives one, since that is what tells a missing key apart
   from a key whose value is legitimately nil.

   Beware a schema holding a [:fn ...]: m/form emits the fn object."
  {:malli/schema [:=> [:cat Schema :any] [:maybe [:vector :map]]]
   :knowledge
   [{:id :malli-names-the-wrong-schema-for-a-missing-key
     :kind :lesson
     :says "(:schema error) is the WHOLE ENCLOSING MAP when a key is absent, and the offending child only when a present value is wrong. (mu/get-in root (:path error)) is right in both, which is why this keeps the root schema. :type :malli.core/missing-key is the only thing telling a missing key from one whose value is legitimately nil."
     :why "Verified over a missing key, a wrong-typed key and a nested one; it cost a test before it was understood."}]}
  [schema value]
  (let [root (m/schema schema)]
    (some->> (:errors (m/explain root value))
             (mapv (fn [{:keys [in value path type]}]
                     (cond-> {:in in :value value
                              :schema (some-> (mu/get-in root path) m/form)}
                       type (assoc :type type)))))))
