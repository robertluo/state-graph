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
   the machinery writes and a handler may not."
  [:map [::kind [:= :state]] [:id Id] [:schema MapSchema]
        [:initial {:optional true} :boolean]
        [:final {:optional true} :boolean]
        [:machine {:optional true} Shape]])

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
        [:sees {:optional true} MapSchema]])

(def TransDef
  "An edge: which event moves the machine from where to where, and nothing else. What
   handles the event is the EVENT's to say — see EventDef. The TARGET is still the
   graph's, because A -submit-> B beside C -submit-> D is what a multidigraph is for."
  [:map [::kind [:= :transition]] [:from Id] [:event Id] [:to Id]])

;;; -------------------------------------------------------------- constructors

(defn state
  "A state: an id, the malli schema of its DATA, and optionally {:initial true} or
   {:final true}. The schema form is compiled here, so what a shape stores is always
   a compiled schema and MapSchema is honest rather than aspirational."
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
                            [:=> [:cat Id MapSchema fn?] EventDef]
                            [:=> [:cat Id MapSchema fn? [:maybe MapSchema]] EventDef]
                            [:=> [:cat Id MapSchema fn? [:maybe MapSchema] [:maybe :map]] EventDef]]}
  ([id schema] (event id schema (lifting (m/schema schema)) schema nil))
  ([id schema handler] (event id schema handler nil nil))
  ([id schema handler out] (event id schema handler out nil))
  ([id schema handler out opts]
   (cond-> {::kind :event :id id :schema (m/schema schema) :handler handler}
     (some? out) (assoc :out (m/schema out))
     (:sees opts) (assoc :sees (m/schema (:sees opts))))))

(defn transition
  "An edge: from a state, on an event, to a state. Three keywords and no functions —
   what handles the event belongs to the event."
  {:malli/schema [:=> [:cat Id Id Id] TransDef]}
  [from event to]
  {::kind :transition :from from :event event :to to})

;;; -------------------------------------------------------------------- checks

(def ^:private def-schema
  {:state StateDef :event EventDef :transition TransDef})

;; The nested-machine check asks a child what its FIRST STATE would have to validate
;; against, and the two functions that answer live in the reading section below — where
;; they belong, being the vocabulary and not a check. Declared rather than moved, and
;; deliberately not reimplemented here: what the check asks has to be what runs.
(declare enter-schema initial-id)

(defn- names
  "A transition as three plain keywords. A problem may not carry a handler or a
   compiled schema: an error nobody can print or compare is not data."
  [t]
  [(:from t) (:event t) (:to t)])

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
      ;; 3 — determinism. Without it `compile` is a search and no check is answerable.
      (for [[[from ev] ts] (group-by (juxt :from :event) transition) :when (< 1 (count ts))]
        {:problem :ambiguous :from from :event ev :count (count ts)})
      ;; 4 — the catalogue's only moment. This is what replaces the dead-event check,
      ;; which cannot be a graph query once the catalogue has been denormalised away.
      (for [id (sort (remove fired event-ids))]
        {:problem :unused-event :id id})
      ;; 5 — the reachability check above needs a root, so the shape has to know it
      (let [inits (filterv :initial state)]
        (when (not= 1 (count inits))
          [{:problem :initial :count (count inits) :ids (mapv :id inits)}]))
      ;; 6 — :id and :instance are the MACHINERY'S words, not a state's. A state
      ;; redeclaring either would be describing something written over it on every
      ;; entry: :id by the edge, :instance by whoever started the run.
      (for [s state
            k (mu/keys (:schema s))
            :when (#{:id :instance :sub} k)]
        {:problem :reserved-declared :id (:id s) :key k})
      ;; 7 — a nested machine must be able to START. Entering a node with one enters that
      ;; child at its own initial state with NO data, so a child whose first state insists
      ;; on some is a nesting that could never begin. Answerable from the parts alone,
      ;; which is why it is here and not in the structural checks.
      (for [s state
            :let [child (:machine s)]
            :when (and child (uber/ubergraph? child))
            :let [id (initial-id child)]
            :when (not (m/validate (enter-schema child id) {:id id}))]
        {:problem :machine-cannot-start :id (:id s) :initial id})))))

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
        catalogue (into {} (map (juxt :id #(select-keys % [:schema :handler :out :sees]))) event)]
    (-> (uber/multidigraph)
        (uber/add-nodes-with-attrs*
         (for [s state] [(:id s) (select-keys s [:schema :initial :final :machine])]))
        (uber/add-directed-edges*
         (for [t transition]
           [(:from t) (:to t)
            (assoc (catalogue (:event t)) :event (:event t))])))))

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
  "Every edge as a plain map — :from :event :to, and the event's :schema, :handler and
   :out denormalised onto it. The vocabulary everything above this reads a shape through,
   and why nothing above had to learn that the handler moved."
  {:malli/schema [:=> [:cat Shape] [:sequential :map]]}
  [shape]
  (for [e (uber/edges shape)]
    (into {:from (uber/src e) :to (uber/dest e)} (uber/attrs shape e))))

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
