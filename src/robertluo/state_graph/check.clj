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
   schema, the handler's DECLARED answer merged over it, and the target's :id written
   in last — which is the order `compile` does it in, so what is checked is what runs.

   nil where the edge declared no :out. That declaration is what this check is FOR:
   without it there is nothing to say about a closure."
  {:malli/schema [:=> [:cat shape/Shape :map] [:maybe shape/MapSchema]]}
  [sh {:keys [from to out]}]
  (when out
    (-> (mu/merge (uber/attr sh from :schema) out)
        (mu/assoc :id [:= to]))))

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
            (-> v (dissoc :verdict) (assoc :problem :target-refuses)))))))

;;; ---------------------------------------------------------------------- drawing

(defn- node-label
  [sh id]
  (str (name id)
       (when (= id (shape/initial-id sh)) " ▸")
       (when (shape/final? sh id) " ◼")
       "\n" (pr-str (m/form (uber/attr sh id :schema)))))

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

(defn draw!
  "The shape as a picture, through ubergraph and graphviz.

   :save {:filename f :format :dot} writes the GRAPHVIZ SOURCE and needs no graphviz
   installed — it is a spit. Every other format shells out to `dot`, and no :save at
   all opens a viewer. This is an effect and never a test: what a drawing is for is a
   person looking at it."
  {:malli/schema [:function [:=> [:cat shape/Shape] :any]
                            [:=> [:cat shape/Shape :map] :any]]}
  ([sh] (draw! sh {}))
  ([sh opts] (uber/viz-graph (labelled sh) opts)))
