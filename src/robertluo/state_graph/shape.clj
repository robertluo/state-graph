(ns robertluo.state-graph.shape
  "THE BOTTOM: a state machine's shape, which is a graph.

   A STATE is a node, shaped by a malli schema and validated on enter. An EVENT is
   shaped by a malli schema too. A TRANSITION is an edge carrying the event that fires
   it and the handler that answers a change. Two events may join one pair of states,
   so the graph is a MULTI-digraph and a plain digraph would silently keep one of them.

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

(def StateDef
  "A node. :schema describes the map WITHOUT its :id — what a state is called is the
   shape's to say, not the user's."
  [:map [::kind [:= :state]] [:id Id] [:schema MapSchema]
        [:initial {:optional true} :boolean]
        [:final {:optional true} :boolean]])

(def EventDef
  "A catalogue entry. It is consumed at construction and not kept: its schema is
   written onto every edge that fires it."
  [:map [::kind [:= :event]] [:id Id] [:schema MapSchema]])

(def TransDef
  "An edge. :handler takes THE EVENT ALONE and answers a map, which is merged into the
   state; :out declares the schema of that answer, which is what makes the handler a
   complete [:=> [:cat <event schema>] <out>] and the declaration a testable claim."
  [:map [::kind [:= :transition]] [:from Id] [:event Id] [:to Id] [:handler fn?]
        [:out {:optional true} MapSchema]])

(def Shape
  "The graph. Its innards are ubergraph's business, so what is guarded is what goes in."
  [:fn uber/ubergraph?])

;;; -------------------------------------------------------------- constructors

(defn state
  "A state: an id, the malli schema of its DATA, and optionally {:initial true} or
   {:final true}. The schema form is compiled here, so what a shape stores is always
   a compiled schema and MapSchema is honest rather than aspirational."
  {:malli/schema [:function [:=> [:cat Id MapSchema] StateDef]
                            [:=> [:cat Id MapSchema [:maybe :map]] StateDef]]}
  ([id schema] (state id schema nil))
  ([id schema opts] (into {::kind :state :id id :schema (m/schema schema)} opts)))

(defn event
  "An event: an id and the malli schema of its DATA. The :id rides in the value at
   runtime for the same reason a state's does — the step function matches an edge on it."
  {:malli/schema [:=> [:cat Id MapSchema] EventDef]}
  [id schema]
  {::kind :event :id id :schema (m/schema schema)})

(defn transition
  "An edge: from a state, on an event, to a state, by a handler — and optionally the
   schema of what that handler answers."
  {:malli/schema [:function [:=> [:cat Id Id Id fn?] TransDef]
                            [:=> [:cat Id Id Id fn? [:maybe MapSchema]] TransDef]]}
  ([from event to handler] (transition from event to handler nil))
  ([from event to handler out]
   (cond-> {::kind :transition :from from :event event :to to :handler handler}
     (some? out) (assoc :out (m/schema out)))))

;;; -------------------------------------------------------------------- checks

(def ^:private def-schema
  {:state StateDef :event EventDef :transition TransDef})

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
      ;; 6 — :id is the shape's word. A state redeclaring it would be describing
      ;; something the shape overwrites on every entry.
      (for [s state :when (some #{:id} (mu/keys (:schema s)))]
        {:problem :id-declared :id (:id s)})))))

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
        catalogue (into {} (map (juxt :id :schema)) event)]
    (-> (uber/multidigraph)
        (uber/add-nodes-with-attrs*
         (for [s state] [(:id s) (select-keys s [:schema :initial :final])]))
        (uber/add-directed-edges*
         (for [t transition]
           [(:from t) (:to t)
            (assoc (select-keys t [:event :handler :out])
                   :schema (catalogue (:event t)))])))))

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

(defn transitions
  "Every edge as a plain map — :from :event :to :handler :out and :schema, the event's.
   The vocabulary everything above this reads a shape through."
  {:malli/schema [:=> [:cat Shape] [:sequential :map]]}
  [shape]
  (for [e (uber/edges shape)]
    (into {:from (uber/src e) :to (uber/dest e)} (uber/attrs shape e))))

(defn enter-schema
  "What a state is validated against ON ENTER: its own schema with its :id written in.
   DERIVED and never written by hand, so entering also checks that the machine landed
   where it thought it did."
  {:malli/schema [:=> [:cat Shape Id] MapSchema]}
  [shape id]
  (mu/merge [:map [:id [:= id]]] (uber/attr shape id :schema)))

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
