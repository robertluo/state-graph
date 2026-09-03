(ns robertluo.state-graph.check
  "WHAT THE GRAPH BUYS, and the whole reason for not writing another FSM library: a
   shape that is a graph can be LOOKED AT and CHECKED WITHOUT RUNNING ANYTHING.

   Both live here because they answer the same question by different means — an
   unreachable state is obvious in a picture and invisible in a map literal.

   Nothing here is on the runtime path: `compile` does not require it, and an
   application shipping a working shape never has to. These are for the person
   writing the machine, and they are the checks no other FSM library has.

   Requires the shape, ubergraph and malli."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
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

;; `primitive-types` and `entries-of` live in `shape` and not here. They are pure malli
;; reasoning with no graph in them, and the REFERENTIAL checks need them: `disjoint`, which
;; proves two guards on one [state, event] can never both fire, has to answer before the
;; shape exists. So subsumption and disjointness are siblings a layer apart — one asked
;; about the built graph, one answerable from the parts — reading the same vocabulary.

(declare ^:private sub)

(defn- sub-map
  [target produced]
  (let [t (shape/entries-of target)
        p (shape/entries-of produced)
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
      (and (shape/primitive-types tt) (shape/primitive-types pt)) :no
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
   - a value whose type cannot be the wanted one (see shape/primitive-types);
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

(defn views
   "Every declared VIEW and whether the state it reads can provide it — one verdict per edge
   whose event declares :sees, as PLAIN DATA. :undeclared where an event declares none.

   IT IS `admits` AGAIN, with the view as the TARGET and the source node's schema as what is
   PRODUCED, and it needed no new machinery for the same reason nesting needed none: the
   question `does this schema guarantee that one` was already answered here.

   SOUND ONLY BECAUSE A NODE HOLDS WHAT IT DECLARES. Before the merge was projected on entry,
   a node's schema was a LOWER BOUND on what it held — a key could arrive from a state three
   transitions back — so :no would have proven nothing and this check would have condemned
   shapes that run. See :internal-visibility-is-declared-and-not-automatic.

   A source that only OPTIONALLY has the key is :no, and that is right: a view a handler is
   handed cannot rest on a maybe."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]}
  [sh]
  (for [{:keys [from event sees]} (shape/transitions sh)]
    {:from from :event event
     :verdict (if sees
                (admits sees (shape/enter-schema sh from))
                :undeclared)}))

(defn- domains
  "{k #{v}} for every key an event INSISTS on whose values are FINITE — the only keys a
   coverage question can be decided on. An OPTIONAL key is no use: an event may leave it
   out, and no probe on it would say anything about that event."
  [s]
  (into {} (for [[k e] (shape/entries-of s)
                 :let [f (shape/finite-values (:schema e))]
                 :when (and f (not (:optional? e)))]
             [k f])))

(defn- covers
  "The verdict for one group of edges sharing a source and an event."
  [ts]
  (let [schema (:schema (first ts))
        guards (mapv shape/accepted ts)
        ;; AN UNGUARDED EDGE TAKES EVERYTHING THE OTHERS REFUSE, so there is nothing to
        ;; decide and no finite key needed to decide it on. Without this the general path
        ;; answers :unknown for the commonest group there is — one plain edge whose event
        ;; carries nothing to pin.
        probe  (fn [k v] (mu/merge schema [:map [k [:= v]]]))
        per-key
        (for [[k dom] (domains schema)]
          (let [answers (for [v (sort-by str dom)
                              :let [p (probe k v)]]
                          (cond
                            (some #(= :yes (admits % p)) guards) :covered
                            (every? #(= :yes (shape/disjoint % p)) guards) [:gap v]
                            :else :unknown))]
            (cond
              (every? #{:covered} answers) {:verdict :yes}
              (some vector? answers) {:verdict :no
                                      :witness {k (second (first (filter vector? answers)))}}
              :else nil)))]
    (or (when (some (complement :when) ts) {:verdict :yes})
        (first (filter (comp #{:yes} :verdict) per-key))
        (first (filter (comp #{:no} :verdict) per-key))
        {:verdict :unknown})))

(defn coverage
  "Do the guards on a [state, event] leave a GAP? One verdict per group of edges sharing a
   source and an event, as PLAIN DATA — and NEVER A FAULT, which is the point of it.

   IT IS `admits` AND `disjoint` RUN AGAINST A PROBE — the event's schema with one key
   pinned to one value of a finite domain. Where some guard admits every event matching the
   probe, that value is covered; where EVERY guard is disjoint from it, that value can
   reach no edge at all and is a PROVEN GAP, published with the witness that shows it.
   Two structural checks off one subsumption function, which is what `views` did first.

   :yes where an edge in the group is UNGUARDED — it takes everything the others refuse —
   or where every value of some insisted-on finite key reaches an edge. :no with a
   :witness where some value reaches none. :unknown where no finite key decides it.

   AND A GAP IS NOT A FAULT, which is why nothing here reaches `problems`. An event no
   guard admits fires no edge, and that is `ignored` — legal, first-class, and exactly what
   a lone guard used as a FILTER is for. Reporting it as a fault would make `problems`
   publish a suspicion, which it has never done."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]}
  [sh]
  (for [[[from ev] ts] (sort-by key (group-by (juxt :from :event) (shape/transitions sh)))]
    (into {:from from :event ev} (covers ts))))

;;; ------------------------------------------------------------------ confluence

(defn- targets
  "{[state-id event-id] -> target-id}. What compile's index is, with everything the
   step needs at runtime left out.

   GUARDED EDGES ARE LEFT OUT TOO, because with a guard there is no `the` target: which
   way the machine goes depends on the event and not on the shape alone, so there is
   nothing here to look up. `commutes` refuses to reason about such a pair rather than
   reading a target that would be one of two."
  [sh]
  (into {} (comp (remove :when) (map (juxt (juxt :from :event) :to)))
        (shape/transitions sh)))

(defn- guarded
  "The events some edge guards. A pair involving one of them is :unknown to `commutes`."
  [sh]
  (into #{} (comp (filter :when) (map :event)) (shape/transitions sh)))

(defn- declared-out
  "{event-id -> its :out, or nil}. The event's and not the edge's, so one entry serves
   every edge that fires it — see shape/EventDef."
  [sh]
  (into {} (map (juxt :event :out)) (shape/transitions sh)))

(defn- declared-sees
  "{event-id -> its :sees, or nil} — what each event's handler READS. Same denormalisation
   as `declared-out`, and needed for the same question: two patches commute only if neither
   reads what the other writes."
  [sh]
  (into {} (map (juxt :event :sees)) (shape/transitions sh)))

(defn- nesting
  "The states that NEST a machine. A pair pending in one of them cannot be reasoned about
   from these edges at all, because INNER FIRST means the child sees an event before this
   shape's own edges do — so the diamond the edges describe is not the diamond that runs.

   MEASURED, and it was a live unsoundness rather than a gap: a node whose child admits
   both events had its two own self-loops licensed as commuting while the child's own
   `confluence` proved that pair :no, and the two orders landed in visibly different
   states. Nothing had noticed because `drive` serialised regardless; the licence became
   load-bearing the day the runtime took it."
  [sh]
  (set (keys (shape/machines sh))))

(defn- combining
  "{node-id {k {:combine f :commutes? bool :schema S}}} — what each node declares about
   how a patch lands on its keys. Empty maps for a shape that declares none."
  [sh]
  (into {} (for [id (shape/states sh)] [id (shape/combines sh id)])))

(defn- combines-commutatively?
  "Whether key k may be written by BOTH events of a candidate pair.

   THE THREE NODES ARE ta, tb AND x, which are every node a patch of this pair ever lands
   on: the first event's target, the second's, and the join they rejoin at. Each must
   declare the SAME combine for k and each must declare it commutative — three nodes
   because the fold applies the first patch at ta or tb and the second at x, so three
   different functions would compose into two different answers and prove nothing. For a
   SELF-LOOP, where combines actually pay, all three are one node and this is one lookup.

   A KEY DECLARED ON ONLY SOME OF THEM IS REFUSED, `:commutes?` of nil being false."
  [comb nodes k]
  (let [ds (map #(get-in comb [% k]) nodes)]
    (and (every? :commutes? ds)
         (apply = (map :combine ds)))))

(defn- commutes
  "The verdict for two events pending in ONE state: :yes, :no or :unknown, and like
   `admits` it never lies.

   THE PATCH CONDITION IS BERNSTEIN'S, not merely disjoint writes, and it stopped being
   merely disjoint writes the day a handler could READ — see
   :internal-visibility-is-declared-and-not-automatic. Two patches commute only if neither
   writes what the other writes AND neither READS what the other WRITES: a handler that
   computed :total from the :n another handler is changing has a patch that goes stale, and
   the write sets alone cannot see it. Measured: before this, a pair whose writes were
   {:total} and {:n} was licensed while one of them read :n, and the two orders answered
   :total 2 and :total 18."
  [tgt guards nests comb out sees s a b]
  (let [ta (tgt [s a]), tb (tgt [s b])]
    (cond
      ;; A NESTED MACHINE IS NOT REASONED ABOUT HERE, and it has to be asked FIRST — of
      ;; where the pair is pending AND of where either event would leave it, since those
      ;; are the three states an event of this pair is ever routed from. INNER FIRST means
      ;; a child would take the event before the edge being read here, so neither :yes nor
      ;; :no is about what runs. The state a pair ENDS in may nest freely: entering a
      ;; nesting node seeds the child's own first state either way round.
      (some nests [s ta tb]) :unknown
      ;; A GUARDED EVENT IS NOT REASONED ABOUT HERE. Where a guard decides the target,
      ;; `both admitted` stops being a fact about the shape — it depends on the events
      ;; themselves — so the diamond cannot be looked up at all. :unknown is the honest
      ;; answer, and it costs only a licence that was never taken anyway.
      (or (guards a) (guards b)) :unknown
      (not (and ta tb))
      ;; not both admitted here, so they are not a concurrent pair at all
      :no
      :else
      (let [x1 (tgt [ta b]), x2 (tgt [tb a])
            oa (out a), ob (out b)
            ;; an event with no view reads nothing, so a shape with no views is unaffected
            reads (fn [e] (if-let [v (sees e)] (set (mu/keys v)) #{}))
            writes (fn [o] (set (mu/keys o)))]
        (cond
          ;; THE DIAMOND. Either return edge missing, or the two routes landing in
          ;; different nodes, is a PROOF that completion order is observable.
          (not (and x1 x2 (= x1 x2))) :no
          ;; the diamond closes, and now it is only about the patches
          (not (and oa ob)) :unknown
          ;; WRITE-WRITE, AND A SHARED KEY IS NO LONGER THE END OF IT. Under a naive merge
          ;; two patches touching one key could never be licensed, last-write-wins being
          ;; the whole of how a patch landed. A key that declares a COMMUTATIVE COMBINE is
          ;; different: which patch landed second stops being observable in that key. The
          ;; declaration is a claim about a closure and is not proven here — `laws` refutes
          ;; it by generation and `compile`'s :agree verifies it on the concrete values
          ;; whenever the licence is actually taken.
          (not-every? (partial combines-commutatively? comb [ta tb x1])
                      (filter (writes ob) (writes oa)))
          :unknown
          ;; READ-WRITE IS UNTOUCHED BY A COMBINE, and cannot be helped by one: a handler
          ;; that READ the key computed from a value the other event changes, so its patch
          ;; is stale whatever lands it. The way to a concurrent accumulation is a combine
          ;; INSTEAD of a view — answer from the event alone and let the node combine.
          (some (writes ob) (reads a)) :unknown
          (some (writes oa) (reads b)) :unknown
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
   - :unknown wherever a NESTED MACHINE could take either event — the state the pair is
     pending in, or the state either event would leave it in. The edges read here are not
     what runs there, so no verdict off them would be about the right diamond.
   - :yes where the diamond closes and BERNSTEIN'S CONDITIONS hold on the patches: neither
     event writes what the other writes, and neither READS what the other writes. Disjoint
     writes alone was the condition until a handler could read a declared view, and it was
     then unsound — a patch computed from what the other event changes goes stale.

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
        guards (guarded sh)
        nests (nesting sh)
        comb (combining sh)
        out (declared-out sh)
        sees (declared-sees sh)
        ;; read from the edges and not from `targets`, so a GUARDED event still appears in
        ;; the listing — as :unknown, which is coverage, where leaving it out would be a
        ;; quiet gap in what this publishes.
        here (fn [s] (sort (distinct (for [t (shape/transitions sh)
                                           :when (= s (:from t))]
                                       (:event t)))))]
    (for [s (sort (shape/states sh))
          :let [es (here s)]
          a es b es
          :when (neg? (compare a b))]
      {:in s :pair [a b] :verdict (commutes tgt guards nests comb out sees s a b)})))

(def ^:private law-samples
  "How many values a law is tried on. 12 is 144 pairs and 1,728 triples, which costs
   milliseconds and is enough to catch the mistakes that are about VALUES rather than about
   rare ones — a tie-break, an argument-order leak."
  12)

(defn- refute
  "A COUNTEREXAMPLE OR nil. A counterexample is a PROOF; its absence is not."
  [law f vs schema]
  (case law
    :closed
    (first (for [a vs b vs
                 :let [r (f a b)]
                 :when (not (m/validate schema r))]
             {:witness [a b] :answer r}))
    :commutes
    (first (for [held vs a vs b vs
                 :let [ab (f (f held a) b) ba (f (f held b) a)]
                 :when (not= ab ba)]
             {:witness {:held held :patches [a b]} :answers [ab ba]}))))

(defn laws
  "One verdict per LAW per COMBINING KEY — whether the algebra a node declares about a
   combine actually holds, tried by GENERATION from the key's own schema.

   IT NEVER ANSWERS :yes, and that is the honest part. Generation can REFUTE a law and
   cannot prove one, so a counterexample is :no with a witness and everything else is
   :unknown. MEASURED, and the reason `compile` verifies the same claim at runtime: a
   plausible domain rule — a pinned choice wins outright — survived 27,000 generated
   triples here and is not commutative.

   THE TWO LAWS:
   - :closed  is checked for EVERY combine, declared or not, and is the one generation
              settles well, being about TYPES rather than about values: f of two values of
              the key's schema must answer a value of that schema. It has to hold or the
              static subsumption check is wrong — `produced` composes the declared :out
              over the source's schema and knows nothing of a combine, so a combine that
              changed the type would make every edge into that state a lie.
   - :commutes is checked only where {:combine/commutes true} is declared, and is the law
              the licence rests on. It is LEFT-COMMUTATIVITY over triples — f(f(s,a),b) =
              f(f(s,b),a) — and not commutativity of the binary operation, because that is
              the shape the fold has. Testing the binary law instead is a real mistake and
              was made here first.

   IT IS NOT PART OF `problems`, deliberately. `problems` is static, cheap and runs
   nothing; this runs the author's own function a couple of thousand times. Mixing them
   would make `problems` a test runner. Seeded, so it answers the same thing twice.

   A key whose schema malli cannot generate THROWS, which is malli's answer and not one to
   work around: give that schema a :gen/gen. The runtime check holds either way."
  {:malli/schema [:function [:=> [:cat shape/Shape] [:sequential :map]]
                            [:=> [:cat shape/Shape [:maybe :map]] [:sequential :map]]]}
  ([sh] (laws sh nil))
  ([sh opts]
   (let [{:keys [samples seed]} (merge {:samples law-samples :seed 1} opts)]
     (for [id (sort (shape/states sh))
           [k {:keys [combine commutes? schema]}] (sort-by key (shape/combines sh id))
           :when combine
           :let [vs (mg/sample schema {:size samples :seed seed})]
           law (cond-> [:closed] commutes? (conj :commutes))
           :let [bad (refute law combine vs schema)]]
       (merge {:in id :key k :law law :verdict (if bad :no :unknown)} bad)))))

(defn commuting
  "{state-id #{#{event-a event-b}}} — only the pairs PROVEN to commute, as plain data a
   runtime layer can look up and a person can print.

   This is the whole of what the async layer needs from a shape, and it is why that layer
   still knows nothing of shapes: it is handed this VALUE, the same way it is handed a
   compiled step. A state with no such pair is absent rather than empty.

   A PAIR THAT CANNOT BE CONCURRENT IS NOT A FAULT, so none of this reaches `problems`.
   It is a pair that has to wait, and waiting is the default.

   THIS IS NOW LOAD-BEARING AND WAS ONCE DECORATIVE. `sg/run` hands it to the async layer
   as the LICENCE, so a wrong :yes here is an order-dependent flake and not merely an
   unused claim — which is what makes every :unknown above worth its caution."
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
          ;; A handler asking to see what the state it reads cannot provide. PROVEN, like
          ;; every other fault here — and provable only because a node now holds exactly
          ;; what it declares.
          (for [{:keys [verdict] :as v} (views sh) :when (= :no verdict)]
            (-> v (dissoc :verdict) (assoc :problem :view-unavailable)))
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
  "What a person reads on a node: its name, and a marker for initial, final and nesting.

   THE SCHEMA IS NOT IN HERE, and it used to be. What a drawing is FOR is the structure —
   `an unreachable state is obvious in a picture and invisible in a map literal` — and a
   schema is exactly the part of a shape that a map literal DOES show. So it was the least
   useful thing in the label and the only thing that did not scale: a consumer whose states
   accumulate a vocabulary produced a 1,183-character label, at which point `dot -Tpng`
   prints `graph is too large for cairo-renderer bitmaps` and writes a ZERO-BYTE FILE.
   A label that breaks the drawing is worse than no label. Read the shape for the schemas;
   they are right there, and `problems` answers what they imply.

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
         (when child (str " ⊞ " (count (shape/states child)) " states")))))

(defn- guard-label
  "A guard, short enough to sit on an arrow. Harel's own notation is event [guard], and a
   guard here is a SCHEMA, so it can be read rather than named: `verdict=:green` where the
   key is pinned to one value, `verdict∈[:green :red]` where it is a small set, and the
   schema form otherwise. A :description wins over all three, being what the author wrote.

   TRUNCATED, and not as a nicety: a 1,183-character label once made `dot -Tpng` print a
   warning and write a ZERO-BYTE file. See :a-node-is-labelled-by-its-id."
  [w]
  (let [t (or (:description (m/properties w))
              (str/join ", "
                        (for [[k e] (shape/entries-of w)
                              :let [f (shape/finite-values (:schema e))]]
                          (cond
                            (= 1 (count f)) (str (name k) "=" (pr-str (first f)))
                            (seq f) (str (name k) "∈" (pr-str (vec (sort-by str f))))
                            :else (str (name k) " " (pr-str (m/form (:schema e))))))))]
    (cond-> t (< 40 (count t)) (-> (subs 0 39) (str "…")))))

(defn- edge-label
  [sh e]
  (let [w (uber/attr sh e :when)]
    (str (name (uber/attr sh e :event))
         (when w (str " [" (guard-label w) "]")))))

(defn labelled
  "The shape with its attributes replaced by things a person can read. ubergraph's own
   :auto-label pprints the whole attribute map, which here is a COMPILED malli schema
   and a CLOSURE — neither of which is a label."
  {:malli/schema [:=> [:cat shape/Shape] shape/Shape]}
  [sh]
  (reduce (fn [g e]
            (uber/set-attrs g e {:label (edge-label sh e)}))
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
