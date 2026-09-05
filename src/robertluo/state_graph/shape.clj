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
  (:require [malli.core :as m]
            [malli.util :as mu]
            [ubergraph.core :as uber]))

;;; ---------------------------------------------------------------- vocabulary

(def Id
  "What names a state or an event."
  :keyword)

(def Instance
  "What names a RUN of the machine — the third identity here, and it gets a third name.
   :id on a state says which NODE it is in and :id on an event says its TYPE; this says
   which machine either belongs to. See the README: `a lifecycle of an INSTANCE of the
   FSM can be seen as a reduction on a seq of events`, which is where the word comes from.

   Deliberately loose — a uuid, an order number, a string — because what an instance is
   called is the caller's business and never this library's. Not nil, though: a partition
   key that may be nil is a bug waiting for the second machine."
  some?)

(def Schema
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

(def StateDef
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

(def EventDef
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

(def TransDef
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
                  [:=> [:cat Id MapSchema [:maybe :map]] StateDef]]}
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
                  [:=> [:cat Id MapSchema fn? [:maybe MapSchema] [:maybe :map]] EventDef]]}
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
                  [:=> [:cat Id Id Id [:maybe :map]] TransDef]]}
  ([from event to] (transition from event to nil))
  ([from event to opts]
   (cond-> {::kind :transition :from from :event event :to to}
     (:when opts) (assoc :when (m/schema (:when opts))))))

;;; ------------------------------------------------------- schemas, compared

(def primitive-types
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
  {:malli/schema [:=> [:cat MapSchema] :map]}
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
  {:malli/schema [:=> [:cat Schema Schema] [:enum :yes :no :unknown]]}
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
  {:malli/schema [:=> [:cat [:* :any]] [:vector :map]]}
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
  {:malli/schema [:=> [:cat [:* :any]] Shape]}
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
  {:malli/schema [:=> [:cat Shape] [:sequential :map]]}
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
  {:malli/schema [:=> [:cat Shape] [:map-of Id :map]]}
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
  {:malli/schema [:=> [:cat Shape] :map]}
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
  {:malli/schema [:=> [:cat Shape] :string]}
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
  {:malli/schema [:=> [:cat Shape Id] MapSchema]}
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
  {:malli/schema [:=> [:cat Schema :any] [:maybe [:vector :map]]]}
  [schema value]
  (let [root (m/schema schema)]
    (some->> (:errors (m/explain root value))
             (mapv (fn [{:keys [in value path type]}]
                     (cond-> {:in in :value value
                              :schema (some-> (mu/get-in root path) m/form)}
                       type (assoc :type type)))))))
