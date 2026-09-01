(ns robertluo.state-graph.check
  "WHAT THE GRAPH BUYS, and the whole reason for not writing another FSM library: a
   shape that is a graph can be LOOKED AT and CHECKED WITHOUT RUNNING ANYTHING.

   Both live here because they answer the same question by different means — an
   unreachable state is obvious in a picture and invisible in a map literal.

   Nothing here is on the runtime path: `compile` does not require it, and an
   application shipping a working shape never has to. These are for the person
   writing the machine, and they are the checks no other FSM library has.

   Requires the shape, ubergraph and malli."
  (:require [malli.core :as m]
            [malli.util :as mu]
            [robertluo.state-graph.shape :as shape]
            [ubergraph.alg :as alg]
            [ubergraph.core :as uber]))

;;; ------------------------------------------------------------------ the graph

(defn reachable
  "The states a run can actually get to, from the one it starts in. This is why the
   shape has to know its :initial — reachability needs a root."
  {:malli/schema [:=> [:cat shape/Shape] [:set shape/Id]]}
  [sh]
  (set (alg/pre-traverse sh (shape/initial-id sh))))

(defn unreachable
  "States the shape declares and no run can ever be in. Not `has no in-edge`, which
   misses a whole island of states that only reach each other."
  {:malli/schema [:=> [:cat shape/Shape] [:set shape/Id]]}
  [sh]
  (into (sorted-set) (remove (reachable sh)) (shape/states sh)))

(defn dead-ends
  "States with nowhere to go that nobody said were an ending. A state that is :final
   is not one — that is what :final is for."
  {:malli/schema [:=> [:cat shape/Shape] [:set shape/Id]]}
  [sh]
  (into (sorted-set)
        (comp (remove #(seq (uber/out-edges sh %)))
              (remove #(shape/final? sh %)))
        (shape/states sh)))

(defn finishable
  "The states from which a run can still reach AN ENDING — a :final, or somewhere a
   :final is reachable from. The same argument `reachable` makes, made BACKWARDS: a
   traversal of the transposed graph from every :final.

   EVERY state when the shape declares no :final at all, because a machine that was
   never meant to terminate is not a broken one. That is not a special case bolted on;
   it is what `can still finish` means where finishing is not a thing this machine does."
  {:malli/schema [:=> [:cat shape/Shape] [:set shape/Id]]}
  [sh]
  (let [finals (filter #(shape/final? sh %) (shape/states sh))]
    (if (empty? finals)
      (set (shape/states sh))
      (let [back (uber/transpose sh)]
        (into #{} (mapcat #(alg/pre-traverse back %)) finals)))))

(defn traps
  "Reachable states from which NO ENDING can be reached. The machine stays alive, goes
   on accepting events, and can never legitimately finish.

   THE CASE A CYCLE HIDES, and the one both other structural checks walk straight past.
   `unreachable` does not see it, because a forward traversal gets there. `dead-ends`
   does not see it, because a trap HAS out-edges — going nowhere and going nowhere
   USEFUL are different faults. A dead end is a trap of size one; two states that only
   bounce off each other are the smallest interesting one, and nothing before this
   reported them at all.

   TOTAL, so a dead end is in here too: the accessor is honest and `problems` is what
   filters, exactly as `subsumption` publishes every verdict. Empty where the shape
   declares no :final, by way of `finishable`."
  {:malli/schema [:=> [:cat shape/Shape] [:set shape/Id]]}
  [sh]
  (into (sorted-set) (remove (finishable sh)) (reachable sh)))

;;; ------------------------------------------------------------------ subsumption

(def ^:private disjoint-types
  "Types no single value belongs to two of, so a produced one and a wanted one that
   differ here is a PROOF and not a guess. Deliberately small: enough to catch the
   common mistake — a handler answering a string where the target wants an int — and
   not a lattice of every type malli has. :double is in only because malli's :int
   rejects a double and its :double rejects an int, which was checked rather than
   assumed."
  #{:int :double :string :keyword :boolean :symbol :uuid})

(declare ^:private sub)

(defn- entries-of
  "{k {:optional? bool :schema S}} for a :map schema. m/children gives [k props child]
   triples with props nil where there are none."
  [s]
  (into {} (for [[k props child] (m/children s)]
             [k {:optional? (boolean (:optional props)) :schema child}])))

(defn- sub-map
  [target produced]
  (let [t (entries-of target)
        p (entries-of produced)
        closed? (:closed (m/properties target))
        verdicts
        (concat
         (for [[k {:keys [optional? schema]}] t]
           (if-let [pe (get p k)]
             (if (and (:optional? pe) (not optional?))
               ;; produced MAY leave it out where the target insists on it
               :no
               (sub schema (:schema pe)))
             (if optional? :yes :no)))
         (when closed?
           (concat
            (for [k (keys p) :when (not (contains? t k))] :no)
            ;; an OPEN produced may carry keys nobody declared, and a closed target
            ;; would refuse them — unprovable either way
            (when-not (:closed (m/properties produced)) [:unknown]))))]
    (cond (some #{:no} verdicts) :no
          (some #{:unknown} verdicts) :unknown
          :else :yes)))

(defn- sub
  [target produced]
  (let [tt (m/type target), pt (m/type produced)]
    (cond
      ;; a sufficient condition and not a necessary one, which is all it is used for.
      ;; Beware a form holding a regex: (= #"a" #"a") is false, so this answers
      ;; :unknown there rather than lying.
      (= (m/form target) (m/form produced)) :yes
      (= :any tt) :yes
      (= :maybe tt) (sub (first (m/children target)) produced)
      ;; a single value, or a finite set of them, is DECIDABLE — just try it
      (= := pt) (if (m/validate target (first (m/children produced))) :yes :no)
      (= :enum pt) (if (every? #(m/validate target %) (m/children produced)) :yes :no)
      (and (= :map tt) (= :map pt)) (sub-map target produced)
      (and (disjoint-types tt) (disjoint-types pt)) :no
      :else :unknown)))

(defn admits
  "Does `target` admit EVERY value `produced` describes? :yes, :no, or :unknown.

   PARTIAL ON PURPOSE, and it never lies. Malli has no subsumption — m/validate
   answers about a VALUE, and there is no `is schema A admitted by schema B` — so this
   is written here, structurally over :map entries, and it declines to answer wherever
   it cannot prove one. :unknown is an answer and not a failure: a partial checker
   nobody has to second-guess is worth more than a total one that guesses.

   What it can PROVE:
   - a required key of the target that the produced value may not have — the common
     bug by a distance, a handler that forgot to set something;
   - a value whose type cannot be the wanted one (see disjoint-types);
   - a [:= v] or an [:enum ...], where the values are finite and can simply be tried."
  {:malli/schema [:=> [:cat shape/Schema shape/Schema] [:enum :yes :no :unknown]]}
  [target produced]
  (sub (m/schema target) (m/schema produced)))

(defn produced
  "The schema of what a transition actually hands its target: the source state's own
   schema, the handler's DECLARED answer merged over it, then the machinery's own keys —
   the target's :id, and :sub where the target nests a machine — written in last, which is
   the order `compile` does it in, so what is checked is what runs.

   :sub HAS TO BE HERE, and leaving it out was a real fault for as long as nesting existed
   without it: the target's enter-schema REQUIRES :sub, no handler may write it, and the
   step assocs the child's first state on entry. A check that did not know that condemned
   every edge into a nested node as :target-refuses.

   nil where the edge declared no :out. That declaration is what this check is FOR:
   without it there is nothing to say about a closure."
  {:malli/schema [:=> [:cat shape/Shape :map] [:maybe shape/MapSchema]]}
  [sh {:keys [from to out]}]
  (when out
    (cond-> (-> (mu/merge (uber/attr sh from :schema) out)
                (mu/assoc :id [:= to]))
      (shape/machine sh to) (mu/assoc :sub [:map [:id shape/Id]]))))

(defn subsumption
  "One verdict per transition — :yes, :no, :unknown, or :undeclared where the edge
   named no :out. The last two are not faults; they are the CHECK'S OWN COVERAGE, and
   worth reading as such."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]}
  [sh]
  (for [t (shape/transitions sh)]
    (assoc (select-keys t [:from :event :to])
           :verdict (if-let [p (produced sh t)]
                      (admits (shape/enter-schema sh (:to t)) p)
                      :undeclared))))

;;; ------------------------------------------------------------------ confluence

(defn- targets
  "{[state-id event-id] -> target-id}. What compile's index is, with everything the
   step needs at runtime left out."
  [sh]
  (into {} (map (juxt (juxt :from :event) :to)) (shape/transitions sh)))

(defn- declared-out
  "{event-id -> its :out, or nil}. The event's and not the edge's, so one entry serves
   every edge that fires it — see shape/EventDef."
  [sh]
  (into {} (map (juxt :event :out)) (shape/transitions sh)))

(defn- commutes
  "The verdict for two events pending in ONE state: :yes, :no or :unknown, and like
   `admits` it never lies."
  [tgt out s a b]
  (let [ta (tgt [s a]), tb (tgt [s b])]
    (if-not (and ta tb)
      ;; not both admitted here, so they are not a concurrent pair at all
      :no
      (let [x1 (tgt [ta b]), x2 (tgt [tb a])
            oa (out a), ob (out b)]
        (cond
          ;; THE DIAMOND. Either return edge missing, or the two routes landing in
          ;; different nodes, is a PROOF that completion order is observable.
          (not (and x1 x2 (= x1 x2))) :no
          ;; the diamond closes, and now it is only about the patches
          (not (and oa ob)) :unknown
          (some (set (mu/keys ob)) (mu/keys oa)) :unknown
          :else :yes)))))

(defn confluence
  "One verdict per pair of events that can be PENDING AT ONCE in one state — :yes, :no
   or :unknown — and, like `subsumption`, it publishes every one of them so the check's
   own COVERAGE is readable rather than merely its complaints.

   THE QUESTION IT ANSWERS is the async layer's only hard one. A handler may answer a
   deferred, so a second event can arrive while the first is in flight, and a machine is
   in one state at a time. Applying them IN ORDER OF COMPLETION is sound exactly where
   the order cannot be observed.

   `BOTH ADMITTED` IS NOT THAT CONDITION, and it is the tempting wrong answer. Both
   admitted means each is individually legal here, not that they COMMUTE:
   idle -start-> running beside idle -cancel-> cancelled has both legal, and if start
   lands first the cancel meets a state with no cancel edge and is DISCARDED.

   WHAT IS PROVEN, each from a lookup and never a traversal:
   - :no where THE DIAMOND FAILS — [s a] -> ta, [s b] -> tb, and either [ta b] or [tb a]
     missing, or the two landing in different nodes. Completion order is then observable
     in where the machine ends up, which is as observable as it gets.
   - :unknown where the diamond closes but the patches cannot be shown to commute:
     an event with no :out declared, or two whose :out share a key. Sharing a key is not
     a PROOF of conflict — the values might coincide — so it is not reported as one.
   - :yes where the diamond closes and the :out key sets are DISJOINT, so the merges
     commute whatever the values are.

   The intermediate states need no check of their own: if [ta b] is an edge at all then
   `subsumption` has already asked whether ta admits what b produces.

   SELF-LOOPS ARE WHERE THIS PAYS, though nothing here is special-cased for them. Both
   events self-loops means ta = tb = x = s and the diamond closes trivially; measured
   over this project's fixtures, a pair where either event LEAVES the state has never
   once closed. Which is unsurprising: different events going to different places is
   what a state machine is for."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]}
  [sh]
  (let [tgt (targets sh)
        out (declared-out sh)
        here (fn [s] (sort (for [[[f e] _] tgt :when (= f s)] e)))]
    (for [s (sort (shape/states sh))
          :let [es (here s)]
          a es b es
          :when (neg? (compare a b))]
      {:in s :pair [a b] :verdict (commutes tgt out s a b)})))

(defn commuting
  "{state-id #{#{event-a event-b}}} — only the pairs PROVEN to commute, as plain data a
   runtime layer can look up and a person can print.

   This is the whole of what the async layer needs from a shape, and it is why that layer
   still knows nothing of shapes: it is handed this VALUE, the same way it is handed a
   compiled step. A state with no such pair is absent rather than empty.

   A PAIR THAT CANNOT BE CONCURRENT IS NOT A FAULT, so none of this reaches `problems`.
   It is a pair that has to wait, and waiting is the default."
  {:malli/schema [:=> [:cat shape/Shape] [:map-of shape/Id [:set [:set shape/Id]]]]}
  [sh]
  (reduce (fn [m {:keys [in pair verdict]}]
            (cond-> m (= :yes verdict) (update in (fnil conj #{}) (set pair))))
          {}
          (confluence sh)))

;;; --------------------------------------------------------------------- problems

(defn problems
  "What is STRUCTURALLY wrong with a built shape, as data — the checks that need the
   graph, where shape/problems is the referential ones that need only the parts.

   Only PROVEN faults. An :unknown subsumption is not reported: a checker that cries
   about what it could not work out is a checker people turn off — and a machine with no
   :final declared is not condemned for having no way to finish, see `finishable`."
  {:malli/schema [:=> [:cat shape/Shape] [:vector :map]]}
  [sh]
  (let [ends (dead-ends sh)]
    (vec (concat
          (for [id (unreachable sh)] {:problem :unreachable :id id})
          (for [id ends] {:problem :dead-end :id id})
          ;; A DEAD END IS A TRAP, and :dead-end is the sharper diagnosis of the two,
          ;; so each state is named once and named by the more specific fault. `traps`
          ;; itself stays total; this is where the filtering belongs.
          (for [id (traps sh) :when (not (ends id))] {:problem :trap :id id})
          (for [{:keys [verdict] :as v} (subsumption sh) :when (= :no verdict)]
            (-> v (dissoc :verdict) (assoc :problem :target-refuses)))
          ;; A NESTED MACHINE IS CHECKED AS AN ORDINARY SHAPE, which is most of why
          ;; nesting cost so little: every check above is about one graph, and a child is
          ;; one. :within names the path of nodes it was found under, so a fault three
          ;; machines deep still says where it lives — and it is a PATH rather than a node
          ;; because nesting nests.
          (for [[id child] (shape/machines sh)
                p (problems child)]
            (assoc p :within (into [id] (:within p))))))))

;;; ---------------------------------------------------------------------- drawing

(defn- node-label
  "What a person reads on a node: its name, a marker for initial and final, and its schema.

   A NODE THAT NESTS A MACHINE SAYS SO AND DOES NOT DRAW IT. ubergraph's viz-graph builds
   its own element list out of nodes and edges, with no way to hand it a graphviz CLUSTER,
   so a child inside its parent's box is not available without generating the dot ourselves
   or rewriting the child's — both worse than the honest alternative, which is that the
   parent marks the node and the child is drawn by asking it for its own picture."
  [sh id]
  (let [child (shape/machine sh id)]
    (str (name id)
         (when (= id (shape/initial-id sh)) " ▸")
         (when (shape/final? sh id) " ◼")
         (when child (str " ⊞ " (count (shape/states child)) " states"))
         "\n" (pr-str (m/form (uber/attr sh id :schema))))))

(defn labelled
  "The shape with its attributes replaced by things a person can read. ubergraph's own
   :auto-label pprints the whole attribute map, which here is a COMPILED malli schema
   and a CLOSURE — neither of which is a label."
  {:malli/schema [:=> [:cat shape/Shape] shape/Shape]}
  [sh]
  (reduce (fn [g e]
            (uber/set-attrs g e {:label (name (uber/attr sh e :event))}))
          (reduce (fn [g id]
                    (uber/set-attrs g id (cond-> {:label (node-label sh id)}
                                           (shape/final? sh id) (assoc :shape :doublecircle))))
                  sh (shape/states sh))
          (uber/edges sh)))

(defn dot
  "The shape as GRAPHVIZ SOURCE, as a string — the drawing as DATA, where `draw!` is the
   drawing as an effect. Both label the graph the same way, so what this answers is exactly
   what `draw!` would render.

   WHAT IT IS FOR: anything that renders a diagram itself rather than shelling out. A
   notebook, a web page, a docs build — all of them want the source and none of them wants a
   file. It needs no graphviz installed, being a `spit` and not a `dot`.

   HOW, and it is worth writing down because ubergraph gives no other way: viz-graph THREADS
   the source through a cond-> whose :dot branch is (#(spit filename %)), so the value it
   answers is spit's nil and the string is only ever written OUT. But `spit` calls
   clojure.java.io/writer on what it is handed, and that accepts a java.io.Writer — so a
   StringWriter catches the source in memory. Verified. It needs no `finally` either: spit
   closes the writer it made, and closing a StringWriter is a no-op that keeps the buffer."
  {:malli/schema [:=> [:cat shape/Shape] :string]}
  [sh]
  (let [w (java.io.StringWriter.)]
    (uber/viz-graph (labelled sh) {:save {:filename w :format :dot}})
    (str w)))

(defn draw!
  "The shape as a picture, through ubergraph and graphviz.

   :save {:filename f :format :dot} writes the GRAPHVIZ SOURCE and needs no graphviz
   installed — it is a spit. Every other format shells out to `dot`, and no :save at
   all opens a viewer. This is an effect and never a test: what a drawing is for is a
   person looking at it.

   IT ANSWERS NOTHING USEFUL, ubergraph's own return being spit's nil for :dot and a
   viewer's for the rest. Somebody who wants the source as a VALUE wants `dot`."
  {:malli/schema [:function [:=> [:cat shape/Shape] :any]
                            [:=> [:cat shape/Shape :map] :any]]}
  ([sh] (draw! sh {}))
  ([sh opts] (uber/viz-graph (labelled sh) opts)))
