(ns robertluo.state-graph.check
  "WHAT THE GRAPH BUYS, and the whole reason for not writing another FSM library: a
   shape that is a graph can be LOOKED AT and CHECKED WITHOUT RUNNING ANYTHING.

   Both live here because they answer the same question by different means — an
   unreachable state is obvious in a picture and invisible in a map literal.

   Nothing here is on the runtime path: `compile` does not require it, and an
   application shipping a working shape never has to. These are for the person
   writing the machine, and they are the checks no other FSM library has.

   Requires the shape, ubergraph and malli."
  {:knowledge
   [{:id :what-the-graph-buys
     :kind :decision
     :says "The whole argument for not writing another FSM library: a shape that is a graph can be DRAWN, so a person sees the machine rather than reads it; CHECKED STATICALLY, which is the part that pays; and STORED, so history is queryable in the same shape as the definition."
     :why "The check worth building first is the one no other FSM library has: a transition whose handler cannot produce a value the target's schema admits is a bug findable WITHOUT RUNNING ANYTHING. That is what malli on the nodes is for."
     :cites [:the-shape-is-a-graph]}
    {:id :two-kinds-of-check-and-two-places-for-them
     :kind :decision
     :says "shape/problems is REFERENTIAL — answerable from the parts alone, so it runs inside the constructor and a bad shape never exists. This namespace is STRUCTURAL — it needs the built graph, so it is separate and opt-in, off the runtime path: the compiler does not require it, and an application shipping a working shape never loads a graph algorithm."
     :why "The drawing is in here too, and it belongs: it answers the same question by different means."
     :cites [:where-a-check-lives-is-decided-by-when-it-must-answer]
     :see [:robertluo.state-graph.shape/problems :robertluo.state-graph.check/problems]}
    {:id :a-published-check-answers-about-the-machine
     :kind :decision
     :says "A published check answers about the MACHINE and not about one layer of it: every check answering MAPS recurses into nested children and carries :within, the path of nodes it was found under. The checks answering a SET OF IDS — reachable, traps, dead-ends, finishable — are about ONE graph and stay there, because two machines may name a state the same and a set has nowhere to say which it meant."
     :why "Measured 2026-09-04: `problems` had recursed since nesting landed and NOTHING ELSE DID. Asked of a shape whose child had a proven :reads-unavailable, `readings` answered () while `problems` reported it with :within [:inner] — the same question, two doors, two answers, silently, and the complete answers were only on the complaint path."
     :from "the author, 2026-09-04: `the 2 findings look like state-graph library's gaps`"
     :when "2026-09-04"
     :cites [:a-machine-can-nest-in-a-node]}
    {:id :a-new-capability-is-not-local
     :kind :lesson
     :says "The lesson this project keeps relearning, three for three: a new capability is NOT LOCAL, and the place to look is whatever OTHER check reasoned about the same thing. Views made `commutes` unsound because it compared write sets only; the phase split found the licence decorative; the crank found every check but `problems` flat over a nested child. Each was found by asking `does this actually solve the problem it was built for`, and no test caught any of them."
     :cites [:a-published-check-answers-about-the-machine :adding-a-read-made-commutes-unsound :the-licence-was-unsound-and-nothing-had-noticed]}
    {:id :dependency-test-check
     :kind :decision
     :says "test.check 1.1.1 is in :deps and not :dev on purpose — generative tests are the unit suite here, not an extra — and `laws` loads it through malli.generator, so the facade does too. Nothing new is on the classpath for it; what changed is what is loaded."
     :see [:robertluo.state-graph.check/laws]}]}
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.util :as mu]
            [robertluo.state-graph.shape :as shape]
            [ubergraph.alg :as alg]
            [ubergraph.core :as uber]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]))

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
  {:malli/schema [:=> [:cat shape/Shape] [:set shape/Id]]
   :knowledge
   [{:id :reachability-is-a-traversal-from-the-root
     :kind :decision
     :says "Unreachable is decided by a traversal from :initial and not by `has no in-edge`: two states that reach only each other both have in-edges and are both unreachable. The suite has exactly that island in it, because the weaker check passes it."
     :cites [:what-the-graph-buys]}]}
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
  {:malli/schema [:=> [:cat shape/Shape] [:set shape/Id]]
   :knowledge
   [{:id :the-exception-lives-in-finishable
     :kind :decision
     :says "Where a shape declares no :final at all, EVERY state is finishable, vacuously — so a machine never meant to terminate is silent of its own accord, and `traps` needs no special case. That is what `can still finish` means where finishing is not a thing this machine does."
     :cites [:a-trap-is-what-a-cycle-hides]}]}
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
  {:malli/schema [:=> [:cat shape/Shape] [:set shape/Id]]
   :knowledge
   [{:id :a-trap-is-what-a-cycle-hides
     :kind :decision
     :says "`traps` answers the REACHABLE states from which no ending can be reached. It is `reachable` run backwards over the transposed graph from every :final, which is why it was cheap."
     :why "Both other structural checks walk straight past it: a forward traversal gets there, and a trap HAS out-edges — going nowhere and going nowhere USEFUL are different faults. A dead end is a trap of size one; two states bouncing off each other are the smallest interesting one, and `problems` answered [] on exactly that fixture before this existed."
     :cites [:reachability-is-a-traversal-from-the-root :the-exception-lives-in-finishable]}
    {:id :traps-is-total-and-problems-filters
     :kind :decision
     :says "`traps` is total, so a dead end is in it too, and `problems` is what filters — each state is named once and by the sharper fault, :dead-end. The pattern `subsumption` set: the accessor publishes everything and the fault list is a projection of it."
     :cites [:a-trap-is-what-a-cycle-hides]}]}
  [sh]
  (into (sorted-set) (remove (finishable sh)) (reachable sh)))

;;; ------------------------------------------------------------------ subsumption

(defn- inside
  "The same question asked of every machine nested in this one, each answer carrying the
   PATH of nodes it was found under, outermost first.

   A PUBLISHED CHECK ANSWERS ABOUT THE MACHINE AND NOT ABOUT ONE LAYER OF IT, and until
   2026-09-04 only `problems` did. Every other check stopped at the outer graph and said
   nothing, silently: `readings` answered () for a shape whose child had a PROVEN
   :reads-unavailable that `problems` reported. Two doors onto one question with two
   different answers, and the driver is what found it — see :a-published-check-answers-
   about-the-machine.

   THE LINE IS THE ANSWER'S TYPE. A check answering MAPS carries :within and recurses;
   one answering a SET OF IDS — `reachable`, `traps`, `dead-ends`, `finishable` — is about
   ONE graph and stays there, because two machines may name a state the same and a set has
   nowhere to say which one it meant. `problems` bridges them by recursing itself."
  [sh f]
  (for [[id child] (shape/machines sh)
        answer (f child)]
    (assoc answer :within (into [id] (:within answer)))))

(defn- own
  "Only this machine's own answers, out of a check that now recurses — for the two callers
   that must not see a child's: `problems`, which recurses itself and would otherwise
   report a nested fault twice, and `commuting`, whose answer is the LICENCE for one
   machine's step and would otherwise merge a child's pairs into a parent state that
   happens to share its name."
  [answers]
  (filter #(empty? (:within %)) answers))

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
  {:malli/schema [:=> [:cat shape/Schema shape/Schema] [:enum :yes :no :unknown]]
   :knowledge
   [{:id :a-partial-subsumption-checker
     :kind :decision
     :says "`admits` answers :yes, :no or :unknown, and IT NEVER LIES. Malli has no subsumption — m/validate answers about a value, and nothing asks whether schema A is admitted by schema B — so it is written here, structurally over :map entries, and declines to answer wherever it cannot prove one."
     :why "What it can prove, each decidable rather than heuristic: a REQUIRED key the produced value may not have, the common bug by a distance; a value whose TYPE cannot be the wanted one, over seven primitives verified pairwise disjoint; and a [:= v] or an [:enum ...], where the values are finite and can simply be tried."
     :cites [:the-seven-primitive-types-are-pairwise-disjoint]}
    {:id :unknown-is-an-answer-and-not-a-failure
     :kind :rule
     :says ":unknown is an answer and not a failure, and `problems` reports only PROVEN faults. A checker that cries about what it could not work out is a checker people turn off; publishing every verdict, :unknown and :undeclared included, makes the check's own coverage readable, which is better than pretending to be total."
     :cites [:a-partial-subsumption-checker]}
    {:id :soundness-is-tested-by-generation
     :kind :decision
     :says "Where `admits` says :yes, values generated from the produced schema must all validate against the target — a genuinely independent second opinion. That direction is the one worth paying for: a checker saying :no where it should say :unknown merely nags, one saying :yes where it should say :no HIDES A BUG."
     :cites [:a-partial-subsumption-checker]}]}
  [target produced]
  (sub (m/schema target) (m/schema produced)))

(defn- produced
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
  {:knowledge
   [{:id :the-subsumption-check-had-to-learn-about-sub
     :kind :lesson
     :says "Until this composed :sub the way the compiler does, nesting was broken in a way only the check could see: a nesting target's enter-schema REQUIRES :sub, no handler may write it, and the step assocs the child's first state on entry, so every edge into a nested node was condemned :target-refuses. Found one minute after nesting first worked."
     :cites [:what-is-checked-must-be-what-runs :sub-is-the-machinerys]}]}
  [sh {:keys [from to out]}]
  (when out
    (cond-> (-> (mu/merge (uber/attr sh from :schema) out)
                (mu/assoc :id [:= to]))
      (shape/machine sh to) (mu/assoc :sub [:map [:id shape/Id]]))))

(defn- continued
  "The schema of what a COMPLETION TRANSITION hands its target: the source state's own
   schema, the :yield merged over it, and the machinery's own keys written in last — the
   same order and for the same reason as `produced`, which is its sibling.

   NEVER :undeclared, and that is the one way this is STRONGER than `produced`. An event
   edge can only be checked where the event declared an :out, because what a CLOSURE
   answers is otherwise unknowable — and a completion transition carries no closure at all.
   What arrives is the state itself, so its schema is known exactly.

   :sub IS NOT CARRIED. A child left behind would ride into a state that never declared it,
   so `arrive` drops it and re-seeds — and this starts from the node's OWN schema, which
   never held it, so the two agree without either mentioning the other."
  {:knowledge
   [{:id :a-completion-is-never-undeclared
     :kind :decision
     :says "A completion transition is checked HARDER than an event edge and is never :undeclared: it carries no closure, so what arrives is the state itself and its schema is known exactly. :sub is not carried, `arrive` dropping and re-seeding it, and this starts from the node's own schema, which never held it — so the two agree without either mentioning the other."
     :cites [:what-is-checked-must-be-what-runs :a-completion-is-an-edge-and-not-a-node-attribute]}]}
  [sh from {:keys [to yield]}]
  (let [base (cond-> (uber/attr sh from :schema) yield (mu/merge yield))]
    (cond-> (mu/assoc base :id [:= to])
      (shape/machine sh to) (mu/assoc :sub [:map [:id shape/Id]]))))

(defn subsumption
  "One verdict per transition — :yes, :no, :unknown, or :undeclared where the edge
   named no :out. The last two are not faults; they are the CHECK'S OWN COVERAGE, and
   worth reading as such.

   A COMPLETION TRANSITION IS IN HERE TOO, marked {:done true} and carrying no :event,
   because it is a way a state is entered and this check is about what a target will
   admit. See `continued` for why none of them is ever :undeclared."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]
   :knowledge
   [{:id :an-edge-with-no-out-is-undeclared-and-not-a-fault
     :kind :decision
     :says "An edge whose event declared no :out is :undeclared and not a fault. That declaration is what the whole check is FOR; without it there is nothing to say about a closure, and the check degrades to the generative one — generate an event, run the handler, validate the answer — which is this project's testing style anyway."
     :cites [:a-handler-answers-a-map-and-declares-it :unknown-is-an-answer-and-not-a-failure]}
    {:id :the-out-is-the-events-and-that-is-the-friction
     :kind :lesson
     :says "The first consumer's real friction: an event leading to two targets needing DIFFERENT data — :fault requires a fault string, :implemented does not — shares one :out, so it must be weak enough for the green edge and then cannot prove the red one. :target-refuses, on a correct shape. The way out is to declare NO :out on the guarded event and let the runtime :answer and :enter crossings enforce it; this then says :undeclared, which is coverage rather than a fault."
     :when "2026-09-03"
     :cites [:two-edges-share-one-out :an-edge-with-no-out-is-undeclared-and-not-a-fault]}
    {:id :weakening-a-schema-to-please-a-checker-is-refused
     :kind :rejected
     :says "Weakening the target's own schema to {:optional true} buys a :yes back and is weakening a schema to please a checker. Named so nobody takes that road."
     :cites [:the-out-is-the-events-and-that-is-the-friction]}]}
  [sh]
  (concat
   (for [t (shape/transitions sh)]
     (assoc (select-keys t [:from :event :to])
            :verdict (if-let [p (produced sh t)]
                       (admits (shape/enter-schema sh (:to t)) p)
                       :undeclared)))
   (for [[from cs] (shape/continuations sh), c cs]
     (cond-> {:from from :to (:to c) :done true
              :verdict (admits (shape/enter-schema sh (:to c)) (continued sh from c))}
       (:outcome c) (assoc :outcome (:outcome c))))
   (inside sh subsumption)))

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
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]
   :knowledge
   [{:id :views-is-admits-again
     :kind :decision
     :says "The view check is `admits` with the view as TARGET and the source node's schema as what is PRODUCED, and it needed no new machinery for the same reason nesting needed none. A source that only OPTIONALLY has the key is :no, and that is right: a view a handler is handed cannot rest on a maybe."
     :cites [:a-partial-subsumption-checker :internal-visibility-is-declared-and-not-automatic :the-view-check-is-only-sound-under-projection]}]}
  [sh]
  (concat
   (for [{:keys [from event sees]} (shape/transitions sh)]
     {:from from :event event
      :verdict (if sees
                 (admits sees (shape/enter-schema sh from))
                 :undeclared)})
   (inside sh views)))

(defn readings
  "Every declared :reads and whether the state a driver would report FROM can provide it —
   one verdict per edge whose event declares one, as PLAIN DATA. :undeclared where an event
   has no report, which means the WORLD supplies it and there is nothing to check.

   IT IS `admits` FOR THE FOURTH TIME — `views` was the second and `yields` the third — with
   the read as the TARGET and the source node's schema as what is PRODUCED. A driver runs a
   report in the state that AWAITS the event, so that state is what must provide the keys.

   SOUND FOR THE SAME REASON `views` IS: a node holds exactly what it declares, so :no is a
   proof rather than a guess. And a source that only OPTIONALLY has the key is :no — a report
   handed a view cannot rest on a maybe any more than a handler can."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]
   :knowledge
   [{:id :readings-is-admits-for-the-fourth-time
     :kind :decision
     :says "The :reads check is `admits` with the read as TARGET and the source node's schema as PRODUCED — `views` was the second and `yields` the third. A driver runs a report in the state that AWAITS the event, so that state is what must provide the keys. :undeclared where there is no report, which means the world supplies the event and there is nothing to check."
     :cites [:views-is-admits-again :an-event-may-say-how-it-is-reported]}]}
  [sh]
  (concat
   (for [{:keys [from event reads report]} (shape/transitions sh)]
     {:from from :event event
      :verdict (if (and report reads)
                 (admits reads (shape/enter-schema sh from))
                 :undeclared)})
   (inside sh readings)))

(defn yields
  "Every declared :yield and whether the child it harvests from can PROVIDE it — one
   verdict per final state of the nested machine, as plain data.

   IT IS `admits` FOR THE THIRD TIME, with the yield as the TARGET and the child's own final
   state as what is PRODUCED; `views` was the second. Three structural checks off one
   subsumption function is the argument for having written it.

   EVERY FINAL STATE AND NOT JUST ONE for an UNCONDITIONAL completion, because a child may
   finish in any of them and a yield that rests on only some is a yield that is sometimes
   not there. A PER-OUTCOME completion is asked about its OWN final state and no other,
   which is not a relaxation but the same rule read where it applies: that branch is taken
   only when the child stopped there, so that is the only state the yield has to rest on.
   Naming the outcome is what makes the check sharper rather than looser.

   SOUND ONLY BECAUSE A YIELD IS HARVESTED AT COMPLETION. Were it taken on an ordinary
   escape the child could be in ANY state, so :no would prove nothing and this would condemn
   shapes that run — the same dependency `views` has on projection, and the same lesson: a
   read check is only ever as sound as the moment it reads at."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]
   :knowledge
   [{:id :yields-is-admits-for-the-third-time
     :kind :decision
     :says "The yield check is `admits` with the yield as TARGET and the child's own final state as PRODUCED. EVERY final state is asked for an unconditional completion, a child being free to finish in any of them, and a yield resting on only some is a yield that is sometimes not there. A per-outcome completion is asked about its OWN final state and no other — sharper, not looser."
     :cites [:views-is-admits-again :yield-is-harvested-at-completion-only :done-may-say-where-each-outcome-goes]}]}
  [sh]
  (concat
   (for [[from cs] (shape/continuations sh)
         {:keys [yield outcome]} cs
         :when yield
         :let [child (shape/machine sh from)]
         id (if outcome
              [outcome]
              (sort (filter #(shape/final? child %) (shape/states child))))]
     {:from from :final id
      :verdict (admits yield (shape/enter-schema child id))})
   (inside sh yields)))

(defn seeds
  "Every declared :seed and whether it can actually be sown — TWO verdicts per seeded node,
   as plain data, because a seed is a crossing with two sides and either can be wrong.

   :provides  can this node GIVE the seed? `admits` with the seed as the TARGET and the
              node's own schema as what is produced — the same question `views` asks of a
              :sees and `readings` of a :reads, which is `admits` for the fifth and sixth
              time and is the argument for having written it once.
   :accepts   will the child TAKE it? `admits` with the child's first state as the target
              and the seed, plus the :id the machinery writes, as what is produced.

   IT IS `yields` READ BACKWARDS, and deliberately shaped like it: one carries parent ->
   child at entry and the other child -> parent at completion, so the checks are mirror
   images and neither needed machinery the other did not.

   SOUND FOR THE REASON EVERY READ CHECK HERE IS SOUND: a node holds exactly what it
   declares, and the seed is taken off the node's own PROJECTED value — so :no is a proof.
   A node that only OPTIONALLY has the key is :no, a child handed a seed being no better
   off with a maybe than a handler is."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]
   :knowledge
   [{:id :seeds-is-admits-in-both-directions
     :kind :decision
     :says "A seed is a crossing with two sides and either can be wrong, so this is `admits` for the fifth and sixth time: :provides — can this node give the seed, with the node's own schema as produced — and :accepts — will the child take it, with the child's first state as the target. It is `yields` read backwards, and deliberately shaped like it."
     :cites [:views-is-admits-again :a-node-may-sow-its-child :sown-off-the-projected-value]}]}
  [sh]
  (concat
   (for [id (sort (shape/states sh))
         :let [seed (shape/seed sh id), child (shape/machine sh id)]
         :when (and seed child)
         :let [first-id (shape/initial-id child)]]
     {:from id :initial first-id
      :provides (admits seed (shape/enter-schema sh id))
      :accepts (admits (shape/enter-schema child first-id)
                       (mu/assoc seed :id [:= first-id]))})
   (inside sh seeds)))

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
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]
   :knowledge
   [{:id :coverage-is-published-and-never-faulted
     :kind :decision
     :says "Whether the guards on a [state, event] leave a GAP is published and never faulted. A gap means no edge admits the event, which is `ignored` — legal, first-class, and exactly what a lone guard used as a filter is for. Reporting it as a fault would make `problems` publish a SUSPICION, which it has never done."
     :cites [:there-is-no-else :unknown-is-an-answer-and-not-a-failure]}
    {:id :the-witness-landed-in-coverage
     :kind :lesson
     :says "The witness the guard design promised for :ambiguous is not there — two map schemas are never proven to overlap — but it did land here, where the probe pins one key to one value so a value is in hand. Same idea, and it works only where something CONSTRUCTS the value."
     :cites [:dis-map-never-answers-no]}]}
  [sh]
  (concat
   (for [[[from ev] ts] (sort-by key (group-by (juxt :from :event) (shape/transitions sh)))]
     (into {:from from :event ev} (covers ts)))
   (inside sh coverage)))

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

(defn- continuing
  "The states that declare a COMPLETION TRANSITION. A pair pending in one — or leaving into
   one — cannot be reasoned about from the edges alone, for the same reason a nesting node
   cannot: the state the second patch is applied to is not the state the edge names.

   THE JOIN NODE MAY CONTINUE FREELY, and that matters rather than being a nicety. x is
   where both orders arrive, the diamond having proved they arrive at the SAME x, and a
   continuation is a pure function of the state — so both orders continue identically. A
   join's own :complete state is exactly where a :done belongs, and refusing it would lose
   the licence precisely where it was won."
  [sh]
  (set (keys (shape/continuations sh))))

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
  [tgt guards nests conts comb out sees s a b]
  (let [ta (tgt [s a]), tb (tgt [s b])]
    (cond
      ;; A NESTED MACHINE IS NOT REASONED ABOUT HERE, and it has to be asked FIRST — of
      ;; where the pair is pending AND of where either event would leave it, since those
      ;; are the three states an event of this pair is ever routed from. INNER FIRST means
      ;; a child would take the event before the edge being read here, so neither :yes nor
      ;; :no is about what runs. The state a pair ENDS in may nest freely: entering a
      ;; nesting node seeds the child's own first state either way round.
      (some nests [s ta tb]) :unknown
      ;; A COMPLETION TRANSITION IS REFUSED THE SAME WAY AND FOR THE SAME REASON: if ta
      ;; continues, the second patch is applied where ta CONTINUED TO and not at ta, so
      ;; (tgt [ta b]) is not the lookup that runs. `continuing` says why x is exempt.
      ;;
      ;; IMPLIED TODAY AND STATED HERE ANYWAY. No shape `shape` will build can reach this:
      ;; s, ta and tb all need out-edges to be in a diamond, and a PLAIN state that both
      ;; continues and has out-edges is refused as :done-with-edges, while a NESTING one is
      ;; already caught a line above. So this is the licence's own statement of its own
      ;; condition, and not a second reading of somebody else's fault — the coupling it
      ;; would otherwise rest on spans two namespaces, and the licence is load-bearing.
      (some conts [s ta tb]) :unknown
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

(defn- confluent
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
   - :unknown wherever a COMPLETION TRANSITION leaves one of those same three states, for
     the same reason: the second patch would be applied where the first one CONTINUED TO.
     The state the pair ENDS in may continue freely — see `continuing`.
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
        conts (continuing sh)
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
          ;; THE DIAGONAL IS IN HERE, and a = b is not a degenerate case but the FAN-OUT one:
          ;; n workers feeding one accumulating state send n events of ONE id, and an async
          ;; handler makes two of them pending at once exactly as it does for two ids. Leaving
          ;; the diagonal out was a QUIET GAP in what this publishes — the same fault this
          ;; function avoids for a guarded event by reading the edges rather than `targets`.
          ;;
          ;; `commutes` NEEDED NO CHANGE FOR IT, which is what says the condition was right
          ;; all along. With a = b the two events share a handler, an :out and a target, so
          ;; ta = tb and the diamond closes wherever the target admits the event again; the
          ;; write-write test then covers EVERY key the :out writes, so the pair is licensed
          ;; only where all of them declare a commutative combine.
          :when (not (pos? (compare a b)))]
      {:in s :pair [a b] :verdict (commutes tgt guards nests conts comb out sees s a b)})))

(defn confluence
  "Every state's concurrent pairs, THIS MACHINE'S AND ITS CHILDREN'S — see `confluent`
   for the verdicts and why each is what it is. A child's answers carry :within.

   `commuting` TAKES ONLY THIS MACHINE'S, and that is not a detail: it is the licence a
   runtime layer looks up by state id, and a child may name a state whatever it likes."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]
   :knowledge
   [{:id :two-events-in-flight-at-once
     :kind :decision
     :says "SERIALISE BY DEFAULT, and take concurrency only where the shape PROVES the order of completion cannot be observed. A handler may answer a deferred, so a second event can arrive while the first is in flight; where the state admits only the first the second WAITS, and where it admits both they may be applied in order of completion — but only when they commute."
     :from "the author, 2026-08-31, correcting :parallel-is-across-instances: every argument there was about events arriving one at a time and none touched two events PENDING in one state"
     :when "2026-08-31"
     :cites [:parallel-is-across-instances]}
    {:id :both-admitted-is-not-the-condition
     :kind :rejected
     :says "`Both admitted` is not the condition, and it was the first analysis's mistake. Both admitted means each is INDIVIDUALLY legal there, not that they commute: idle -start-> running beside idle -cancel-> cancelled — if start completes first the machine is in running, which has no cancel edge, so the cancel is SILENTLY DISCARDED and the caller believes they cancelled. A flake, not a race anybody chose."
     :cites [:two-events-in-flight-at-once]}
    {:id :the-condition-is-confluence-plus-bernstein
     :kind :decision
     :says "The condition is CONFLUENCE — the diamond [s a]->ta, [s b]->tb, [ta b]->x, [tb a]->x with one x — plus BERNSTEIN'S conditions on the patches: neither writes what the other writes, and neither READS what the other writes. The intermediate states need no check of their own: if [ta b] is an edge at all, subsumption has already asked whether ta admits what b produces."
     :why "Measured: writes of {:total} and {:n} are disjoint while :sum reads :n, and the two orders answer :total 2 and :total 18. The write-write half is no longer absolute — a key declaring a COMMUTATIVE COMBINE is licensed — and the read half is untouched and cannot be helped by one."
     :cites [:two-events-in-flight-at-once :a-combine-is-how-a-patch-lands :the-patch-is-never-stale-only-the-admission-is]}
    {:id :adding-a-read-made-commutes-unsound
     :kind :lesson
     :says "Adding a declared view made this check UNSOUND, because it compared WRITE sets only: a handler that computed :total from the :n another handler is changing has a patch that goes stale, and write sets alone cannot see it. Found by asking whether the check still solved the problem it was built for, two entries away from the change."
     :cites [:the-condition-is-confluence-plus-bernstein]}
    {:id :the-licence-was-unsound-under-nesting
     :kind :lesson
     :says "INNER FIRST means a child sees an event before the parent's own edges do, so a nesting node's self-loops describe a diamond that never runs: measured, a node whose child admitted both events had its self-loops licensed :yes while the child's own confluence proved that pair :no, and the two orders landed in visibly different states. `commutes` answers :unknown wherever a nested machine could take either event — where the pair is pending, or either state it would leave it in; the state a pair ENDS in may nest freely."
     :why "It was decorative for two days and nothing noticed, because `drive` serialised whatever it said. The licence became load-bearing the day the runtime took it."
     :cites [:the-condition-is-confluence-plus-bernstein :inner-first]}
    {:id :the-licence-was-unsound-and-nothing-had-noticed
     :kind :lesson
     :says "The licence was unsound and nothing had noticed, found by ASKING rather than by a test, and harmless for exactly as long as `drive` ignored it. The habit worth keeping: before resting anything on a check, ask what it was reasoning about — a check that is decorative is a check nobody has tested against reality."
     :cites [:the-licence-was-unsound-under-nesting]}
    {:id :a-completion-refuses-the-licence-around-it
     :kind :decision
     :says "A completion transition leaving s, ta or tb makes the pair :unknown, for the same reason nesting does: if ta continues, the second patch is applied where ta CONTINUED TO and not at ta, so the lookup that would run is not the one read here. THE JOIN NODE x IS EXEMPT, and that matters: both orders were proved to arrive at the same x and a continuation is a pure function of the state, so a join's own :complete is exactly where a :done belongs."
     :why "Implied today and stated anyway — no shape the constructor builds can reach it, :done-with-edges refusing a plain state that both continues and has out-edges — because the argument spans two namespaces and the licence is load-bearing."
     :cites [:the-condition-is-confluence-plus-bernstein :a-state-may-say-where-it-goes-when-it-completes]}
    {:id :the-diagonal-is-the-fan-out
     :kind :decision
     :says "The pair may be two of ONE event, and the diagonal is asked about since 2026-09-03: n workers feeding one accumulating state send n events of a single id, and an async handler makes two of them pending exactly as it does two ids. Licensed only where every key the :out writes declares a commutative combine. `commutes` needed NO CHANGE to say so — the whole change was (neg? (compare a b)) becoming (not (pos? ...)) — which is what says the condition was right all along."
     :why "Measured: two 300ms reports went 613ms to 305ms. An event that writes nothing commutes with itself, the write-write filter being empty."
     :when "2026-09-03"
     :cites [:the-condition-is-confluence-plus-bernstein :a-combine-is-how-a-patch-lands]}
    {:id :only-ever-two-in-flight
     :kind :decision
     :says "Only ever TWO events in flight. `commuting` is a pairwise relation on ONE state; a third would need the licence re-established at each intermediate state, and inventing that in the runtime would be taking more than was proven. The speculative take is one event deep for the same reason."
     :cites [:the-condition-is-confluence-plus-bernstein]}
    {:id :no-independence-is-declared
     :kind :rejected
     :says "An author-asserted independence between events was turned down twice over: the shape ALREADY says which events are self-loops, so nothing needs asserting; and independence is STATE-RELATIVE, so a global claim would be refuted somewhere in most real shapes. Dependency is the default and needs no saying."
     :cites [:two-events-in-flight-at-once]}
    {:id :confluence-was-measured-not-guessed
     :kind :lesson
     :says "ONE HUNDRED PER CENT of the concurrent-candidate pairs in this project's early fixtures fail confluence — set-then-stop lands :running then :done, stop-then-set lands :done and silently discards the :set — so `commute by default` would have been wrong in every case there was, silently and order-dependently. The finding is structural: different events take you to different places, and that is what a state machine is FOR. What those fixtures had none of was a JOIN."
     :cites [:both-admitted-is-not-the-condition :a-join-is-the-product-and-the-licence]}
    {:id :a-join-is-the-product-and-the-licence
     :kind :decision
     :says "A JOIN is the product construction and needed no new grammar: :complete declares both keys REQUIRED and two events reach it by two routes through intermediate states that declare what has arrived so far, at the DFA's own cost of 2^n states — 4 at n=2, 8 at n=3. Projection is why it works: the state name is the join's progress and the schema says so. And confluence PROVES it: the n=3 lattice is 8 states, 12 edges, problems [], confluence {:yes 6}, all six permutations landing in one identical state. A join is what a commuting pair IS."
     :why "Parking needed nothing and already worked — a state waiting for :approve is a state with an :approve edge, and an event it does not admit comes back :fired false. So the feature was the runtime taking the licence, and only wall-clock had been lost."
     :from "the author, 2026-09-03, asked for two features: park on a state waiting for an external signal, and transit only after two independent events"
     :when "2026-09-03"
     :cites [:the-condition-is-confluence-plus-bernstein :a-node-holds-what-it-declares :a-handler-answers-a-map-and-declares-it :orthogonal-regions-are-out :an-event-may-say-how-it-is-reported]}
    {:id :a-join-helper-was-not-added
     :kind :rejected
     :says "A join is a PARTS ASSEMBLY and not a construct, so no `join` helper was added: the n=3 lattice was generated by a twenty-line function over the powerset. If one is ever wanted it belongs in whoever writes the workflows."
     :cites [:a-join-is-the-product-and-the-licence :a-shared-catalogue-must-be-selected-from]}
    {:id :a-join-guard-over-the-state-was-turned-down
     :kind :rejected
     :says "A {:join <schema>} plus {:done <target>} continuation — accumulate on self-loops and move on when the state satisfies a schema — was turned down. It is a GUARD OVER THE STATE wearing a different hat."
     :cites [:a-join-is-the-product-and-the-licence :a-guard-over-the-state-is-refused]}
    {:id :history-order-stops-matching-arrival-order
     :kind :decision
     :says "The cost of taking the licence, accepted by the author: where concurrency is taken, HISTORY ORDER STOPS MATCHING ARRIVAL ORDER, and an audit trail has to represent that honestly rather than pretend to a sequence that did not happen."
     :cites [:two-events-in-flight-at-once]}]}
  [sh]
  (concat (confluent sh) (inside sh confluence)))

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
                            [:=> [:cat shape/Shape [:maybe :map]] [:sequential :map]]]
   :knowledge
   [{:id :the-law-is-left-commutativity
     :kind :decision
     :says "The law the licence rests on is LEFT-COMMUTATIVITY over (state, patch, patch) triples — f(f(s,a),b) = f(f(s,b),a) — because that is the shape the fold has, and not commutativity of the binary operation. The second law is :closed and is not optional: f of two values of the key's schema must answer a value of that schema, or `produced`, which knows nothing of a combine, makes every edge into that state a lie."
     :cites [:the-promise-is-data-and-checked-at-two-strengths]}
    {:id :the-binary-law-was-the-wrong-law
     :kind :lesson
     :says "The law tested first was the wrong law: a function can be left-commutative in the fold and not commutative as a binary operation, the fold always putting the STATE first, so a rule that only ever discards the second argument is consistent in both orders. Two of three attempted counterexamples were not counterexamples for exactly that reason."
     :cites [:the-law-is-left-commutativity]}
    {:id :the-prototype-refuted-my-own-sound-example
     :kind :lesson
     :says "`best-of` written as (if (>= score-a score-b) a b) was offered as the correct example and generation broke it in forty samples: a TIE has no canonical winner, and it took a TOTAL order — (compare [score by]) — to make the law hold. If the person proposing the mechanism gets it wrong in the first example, the mechanism needs a checker and not a docstring."
     :cites [:the-promise-is-data-and-checked-at-two-strengths]}
    {:id :generation-cannot-reach-every-violation
     :kind :lesson
     :says "A plausible domain rule — `a pinned choice wins outright` — is not left-commutative, and 27,000 generated triples found nothing, malli having no reason to invent the string `pinned`. So this never answers :yes: generation can REFUTE a law and cannot prove one, and the runtime check on the concrete values is the enforcement."
     :cites [:the-law-is-left-commutativity :agree-verifies-the-law-on-the-concrete-values]}
    {:id :laws-is-not-part-of-problems
     :kind :decision
     :says "`laws` is deliberately not part of `problems`. `problems` is static, cheap and runs nothing; this runs the author's own function a couple of thousand times, and mixing them would make a static check a test runner. Seeded, so it answers the same thing twice; an :fn schema with no :gen/gen throws no-generator, which is malli's answer and not one to work around."
     :cites [:the-promise-is-data-and-checked-at-two-strengths]}
    {:id :the-accumulator-must-be-a-set
     :kind :lesson
     :says "`into` on a VECTOR is order-dependent, so the obvious spelling of a join accumulator is not commutative — which worker reported first is visible in the answer — and this refuted it in forty samples. Set union works, and so does a map keyed by the item. Both are fixtures, and the trap is in the README and the tutorial because everyone meets it first."
     :cites [:most-domain-merges-are-not-commutative :the-diagonal-is-the-fan-out]}]}
  ([sh] (laws sh nil))
  ([sh opts]
   (let [{:keys [samples seed]} (merge {:samples law-samples :seed 1} opts)]
     (concat
      (for [id (sort (shape/states sh))
            [k {:keys [combine commutes? schema]}] (sort-by key (shape/combines sh id))
            :when combine
            :let [vs (mg/sample schema {:size samples :seed seed})]
            law (cond-> [:closed] commutes? (conj :commutes))
            :let [bad (refute law combine vs schema)]]
        (merge {:in id :key k :law law :verdict (if bad :no :unknown)} bad))
      (inside sh #(laws % opts))))))

(defn driving
  "WHO CAN MOVE EACH STATE — one verdict per state, as plain data, and never a fault.

   THE STATIC COUNTERPART OF `drive/awaiting`, which asks the same question of a RUNNING
   machine. This asks it of the graph, before anything has run, which is this library's
   whole position applied to the door that finds its own events: `could this ever have
   worked`, for a driver.

   THE VERDICTS:
   - :final   nobody is asked anything
   - :driver  exactly one event here carries a `:report`, so a driver knows what to go and
              find out. This is every state of a workflow that runs itself
   - :world   none of the events here carries one — a PARK, and a legitimate one: somebody
              outside says what happened. It is also what a machine driven by a stream
              looks like everywhere
   - :join    several carry one AND every pair among them is PROVEN confluent, so a driver
              may take them all and the order cannot be observed
   - :fork    several carry one and the shape does NOT prove the order irrelevant. A driver
              must stop here: choosing would invent an order nobody promised. THIS IS THE
              ONE WORTH LOOKING FOR — a shape that will park for ever at a state you meant
              to be automatic, and nothing else says so before you run it

   IT IS NOT A FAULT, and :fork is why the line is where it is. A shape may perfectly well
   want the world to choose between two reportable events; what would be wrong is a driver
   choosing for it. `:ambiguous` is a fault because two guards on one [from event] is
   nondeterminism IN THE MACHINE; this is a question about who produces an event, and the
   machine is deterministic either way.

   A NESTING NODE IS MARKED {:machine true} and its verdict is about its own ESCAPES. Read
   it together with the child's entries, which carry :within — the crank asks the innermost
   machine first, and a nesting node whose child is running is not where the next event
   comes from."
  {:malli/schema [:=> [:cat shape/Shape] [:sequential :map]]
   :knowledge
   [{:id :driving-is-the-static-half-of-awaiting
     :kind :decision
     :says "One verdict per state about who can move it — :final, :driver, :world, :join, :fork — asked of the GRAPH before anything runs, where drive's `awaiting` asks the same question of a running machine. A generative property asserts the two answers agree for every state a driven run lands in; neither is derived from the other, so it is the only thing that can catch one drifting."
     :why "The crank discovers at runtime that a state offers two reportable events it cannot choose between, and parks. Nothing said so beforehand — `problems` called such a shape fine — which for a library whose argument is that a graph can be checked before it runs was a hole in the newest door."
     :when "2026-09-04"
     :cites [:the-driver-world-distinction-is-data :a-join-is-the-product-and-the-licence]}
    {:id :a-fork-is-published-and-never-faulted
     :kind :decision
     :says ":fork — several events reportable and the shape does not prove the order irrelevant — is the verdict worth looking for, a shape that will park for ever at a state you meant to be automatic. It is published and never faulted, where :ambiguous went the other way: two guards on one [from event] is nondeterminism IN THE MACHINE, while two reportable events is a question about who produces an event, and a shape may perfectly well want the world to choose. What would be wrong is a driver choosing for it."
     :cites [:driving-is-the-static-half-of-awaiting :ambiguous-inverts-and-demands-proven-safety]}]}
  [sh]
  (let [reported (set (keys (shape/reports sh)))
        pairs    (into {} (for [{:keys [in pair verdict]} (own (confluence sh))]
                            [[in (set pair)] verdict]))
        proven?  (fn [id ids]
                   (every? (fn [[a b]] (= :yes (get pairs [id #{a b}])))
                           (for [a ids b ids :when (neg? (compare a b))] [a b])))]
    (concat
     (for [id (sort (shape/states sh))
           :let [awaited (into #{} (comp (filter #(= id (:from %))) (map :event))
                               (shape/transitions sh))
                 can (into (sorted-set) (filter reported) awaited)]]
       (cond-> {:id id :awaits awaited :reports can
                :verdict (cond
                           (shape/final? sh id)  :final
                           (empty? can)          :world
                           (= 1 (count can))     :driver
                           (proven? id can)      :join
                           :else                 :fork)}
         (shape/machine sh id) (assoc :machine true)))
     (inside sh driving))))

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
  {:malli/schema [:=> [:cat shape/Shape] [:map-of shape/Id [:set [:set shape/Id]]]]
   :knowledge
   [{:id :the-licence-is-one-machines
     :kind :decision
     :says "`commuting` takes only THIS machine's pairs and not its children's, and that is sharper than tidiness: it is a lookup keyed by STATE ID that the stream door hands down as the licence, so a child's pair would merge into a parent state that happens to share its name and license a concurrency nothing proved. A wrong :yes there is an order-dependent flake. The crank's own lookup filters the same way, asking each LEVEL about its own shape."
     :cites [:a-published-check-answers-about-the-machine :the-licence-was-unsound-under-nesting]}]}
  [sh]
  (reduce (fn [m {:keys [in pair verdict]}]
            (cond-> m (= :yes verdict) (update in (fnil conj #{}) (set pair))))
          {}
          ;; THIS MACHINE'S OWN PAIRS AND NOT ITS CHILDREN'S. `confluence` recurses now,
          ;; and a licence keyed by state id would merge a child's pair into a parent state
          ;; that happens to share its name — a wrong :yes here is an order-dependent flake.
          ;; A child's concurrency is the COMPILER's business inside one step, which is
          ;; also why `nesting` makes every pair around such a node :unknown.
          (own (confluence sh))))

;;; --------------------------------------------------------------------- problems

(defn problems
  "What is STRUCTURALLY wrong with a built shape, as data — the checks that need the
   graph, where shape/problems is the referential ones that need only the parts.

   Only PROVEN faults. An :unknown subsumption is not reported: a checker that cries
   about what it could not work out is a checker people turn off — and a machine with no
   :final declared is not condemned for having no way to finish, see `finishable`."
  {:malli/schema [:=> [:cat shape/Shape] [:vector :map]]
   :knowledge
   [{:id :the-structural-faults
     :kind :decision
     :says "STRUCTURAL faults, reported here and needing the built graph: :unreachable, :dead-end, :trap, :target-refuses, :view-unavailable, :reads-unavailable, :yield-unavailable, :seed-unavailable, :seed-refused. PUBLISHED AND NEVER FAULTED, being coverage rather than fault: subsumption, views, readings, yields, seeds, coverage, confluence, commuting, laws, driving."
     :cites [:two-kinds-of-check-and-two-places-for-them :the-referential-faults :unknown-is-an-answer-and-not-a-failure]}
    {:id :problems-takes-own-from-the-checks-that-recurse
     :kind :decision
     :says "`problems` recurses into nested children itself and takes only its OWN answers from the checks it derives faults from, or every nested fault would be reported twice — once from the child's verdict and once from this recursion. Asserted. The id-set checks cannot recurse, having nowhere to say which machine they meant, so the recursion here is what covers them."
     :cites [:a-published-check-answers-about-the-machine]}]}
  [sh]
  (let [ends (dead-ends sh)]
    (vec (concat
          (for [id (unreachable sh)] {:problem :unreachable :id id})
          (for [id ends] {:problem :dead-end :id id})
          ;; A DEAD END IS A TRAP, and :dead-end is the sharper diagnosis of the two,
          ;; so each state is named once and named by the more specific fault. `traps`
          ;; itself stays total; this is where the filtering belongs.
          (for [id (traps sh) :when (not (ends id))] {:problem :trap :id id})
          (for [{:keys [verdict] :as v} (own (subsumption sh)) :when (= :no verdict)]
            (-> v (dissoc :verdict) (assoc :problem :target-refuses)))
          ;; A handler asking to see what the state it reads cannot provide. PROVEN, like
          ;; every other fault here — and provable only because a node now holds exactly
          ;; what it declares.
          (for [{:keys [verdict] :as v} (own (views sh)) :when (= :no verdict)]
            (-> v (dissoc :verdict) (assoc :problem :view-unavailable)))
          ;; A DRIVER asked to report an event from a state that cannot give it what the
          ;; report reads. Same proof as :view-unavailable and the same reason it holds —
          ;; a node holds exactly what it declares — but about the half that PRODUCES an
          ;; event rather than the half that applies one.
          (for [{:keys [verdict] :as v} (own (readings sh)) :when (= :no verdict)]
            (-> v (dissoc :verdict) (assoc :problem :reads-unavailable)))
          ;; A parent asking to harvest what its child cannot finish with. PROVEN, like
          ;; every other fault here, and provable only because a yield is taken at
          ;; COMPLETION — the one moment the child is guaranteed to be in a final state.
          (for [{:keys [verdict] :as v} (own (yields sh)) :when (= :no verdict)]
            (-> v (dissoc :verdict) (assoc :problem :yield-unavailable)))
          ;; AND THE SAME CROSSING READ THE OTHER WAY. A parent asking to sow what it does
          ;; not hold, and a child that will not take what it is sown — two faults off one
          ;; declaration, because a seed has two sides and either can be wrong. The second
          ;; is `:machine-cannot-start` proven where the parts alone could not prove it:
          ;; a seedless node is answered referentially, a seeded one needs subsumption.
          (for [{:keys [provides] :as v} (own (seeds sh)) :when (= :no provides)]
            {:problem :seed-unavailable :from (:from v)})
          (for [{:keys [accepts] :as v} (own (seeds sh)) :when (= :no accepts)]
            {:problem :seed-refused :from (:from v) :initial (:initial v)})
          ;; A NESTED MACHINE IS CHECKED AS AN ORDINARY SHAPE, which is most of why
          ;; nesting cost so little: every check above is about one graph, and a child is
          ;; one. :within names the path of nodes it was found under, so a fault three
          ;; machines deep still says where it lives — and it is a PATH rather than a node
          ;; because nesting nests.
          ;;
          ;; THE `own` ABOVE IS WHY THIS STILL READS RIGHT. Those checks recurse on their
          ;; own account now, so taking their whole answer here would report every nested
          ;; fault twice — once from the child's verdict and once from this recursion. The
          ;; id-SET checks cannot recurse, having nowhere to say which machine they meant,
          ;; so this line is what covers them and it stays.
          (for [[id child] (shape/machines sh)
                p (problems child)]
            (assoc p :within (into [id] (:within p))))))))

;;; ---------------------------------------------------------------------- drawing

(defn- node-label*
  "What a person reads on a node: its structural markers — ▸ initial, ◼ final, ⊞ n for a
   node nesting a machine of n states — followed by its id. THE SCHEMA IS NOT IN HERE: see
   :a-node-is-labelled-by-its-id. This differs from any prior node-label only in shape —
   markers first, name last, plain data out — because the caller of `labelled` gets a map,
   never a graph value, never a schema, never a closure."
  [sh id]
  (let [child (shape/machine sh id)]
    (str/join " "
              (remove nil?
                      [(when (= id (shape/initial-id sh)) "▸")
                       (when (shape/final? sh id) "◼")
                       (when child (str "⊞ " (count (shape/states child))))
                       (name id)]))))

(defn- guard-label
  "A guard, short enough to sit on an arrow. Harel's own notation is event [guard], and a
   guard here is a SCHEMA, so it can be read rather than named: `verdict=:green` where the
   key is pinned to one value, `verdict∈[:green :red]` where it is a small set, and the
   schema form otherwise. A :description wins over all three, being what the author wrote.

   TRUNCATED, and not as a nicety: a 1,183-character label once made `dot -Tpng` print a
   warning and write a ZERO-BYTE file."
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
  "What a person reads on an arrow: Harel's own event [guard].

   AN UNCONDITIONAL COMPLETION TRANSITION IS UNLABELLED — there is no event to name, arriving
   being the whole of its cause. A PER-OUTCOME ONE IS LABELLED WITH THE CHILD'S FINAL STATE,
   in brackets, the same way a guard is: two dashed arrows leaving one node are two different
   structural facts, and a drawing that cannot tell them apart is showing a machine that does
   not exist."
  [sh e]
  (if-let [ev (uber/attr sh e :event)]
    (let [w (uber/attr sh e :when)]
      (str (name ev) (when w (str " [" (guard-label w) "]"))))
    (if-let [o (uber/attr sh e :outcome)] (str "[" (name o) "]") "")))

(defn labelled
  "The shape's DRAWING AS DATA: one entry per node and one per edge, each carrying only what
   a person reads — plain keywords, strings and numbers, and no schema, no closure and no
   graph-library value anywhere in it. A node's label is its id plus its structural markers:
   ▸ for the initial one, ◼ for a final one, ⊞ n for a node nesting a machine of n states,
   which is marked here and drawn by asking the child for its own picture. An edge's label
   is Harel's own notation, EVENT [guard], with the guard read off the schema and truncated
   to fit; a completion edge is marked :done, unlabelled where unconditional and labelled
   with the child's final state where the completion is per-outcome. `dot` renders this to
   graphviz source and `draw!` to a picture, and anything else that draws — a notebook, a
   web page, a docs build — can read it directly."
  {:malli/schema
   [:=> {:registry
         {"Shape" shape/Shape
          "Drawing" [:map
                     [:nodes [:vector [:schema [:ref "Node"]]]]
                     [:edges [:vector [:schema [:ref "Edge"]]]]]
          "Node" [:map
                  [:id :keyword]
                  [:label :string]
                  [:nested {:optional true} [:int {:min 0}]]]
          "Edge" [:map
                  [:from :keyword]
                  [:to :keyword]
                  [:label :string]
                  [:done :boolean]]}}
    [:cat [:schema [:ref "Shape"]]]
    [:schema [:ref "Drawing"]]] :knowledge [{:id :a-node-is-labelled-by-its-id
     :kind :decision
     :says "Labels are the name and the structural markers — ▸ initial, ◼ final, ⊞ n states for a nesting node, a guard on an arrow — and nothing else. The schema is not in the label, and it used to be."
     :why "What a drawing is FOR is structure — an unreachable state is obvious in a picture and invisible in a map literal — and a schema is precisely the part of a shape a map literal DOES show. Measured on the first real consumer: twelve labels, the longest 1,183 characters, a dot source of 10,408, and dot -Tpng printed `graph is too large for cairo-renderer bitmaps`, scaled, and wrote a ZERO-BYTE FILE. After: 1,007 characters of dot and a 120KB PNG. All markers kept are STRUCTURAL, which is the test for anything wanting into a label."
     :from "the author, 2026-09-02: `should not each state just be represented by the :id?`"
     :when "2026-09-02"
     :cites [:what-the-graph-buys]}
    {:id :the-drawing-is-harels
     :kind :decision
     :says "An arrow reads EVENT [guard], Harel's own notation, with the guard read off the schema — verdict=:green where a key is pinned, verdict∈[...] for a small set, the :description where the author wrote one, truncated to fit. A completion is DASHED and unlabelled where unconditional, [<the child's final state>] where per-outcome: two dashed arrows leaving one node are two structural facts, and a picture that cannot tell them apart shows a machine that does not exist."
     :cites [:a-node-is-labelled-by-its-id :done-may-say-where-each-outcome-goes]}
    {:id :a-schema-back-in-the-label-was-not-built
     :kind :rejected
     :says "An option to put the schema back into the label was not built. Nobody has asked, the shape is right there to read, and `problems` answers what the schemas imply better than a picture of them ever did."
     :cites [:a-node-is-labelled-by-its-id]}
    {:id :graphviz-clusters-are-not-reachable-through-viz-graph
     :kind :lesson
     :says "ubergraph's viz-graph builds its own dorothy element list with no hook for a graphviz CLUSTER, so a nested child is not drawn inside its parent. The alternatives were copying ubergraph's private dotid and sanitize-attrs, or rewriting the child's dot to prefix every node id — a small and fragile compiler. Instead the parent MARKS the node and the child is asked for its own picture."
     :cites [:a-node-is-labelled-by-its-id]}]}
  [sh]
  (let [nodes (mapv (fn [id]
                       (let [child (shape/machine sh id)
                             base {:id id :label (node-label* sh id)}]
                         (if child
                           (assoc base :nested (count (shape/states child)))
                           base)))
                     (shape/states sh))
        edges (mapv (fn [e]
                       {:from (uber/src e)
                        :to (uber/dest e)
                        :label (edge-label sh e)
                        :done (boolean (uber/attr sh e :done))})
                     (uber/edges sh))]
    {:nodes nodes :edges edges}))

(defn- dot-escape
  [s]
  (str/escape (str s) {\" "\\\"" \\ "\\\\"}))

(defn- dot-quote
  [s]
  (str "\"" (dot-escape s) "\""))

(defn- node-stmt
  [{:keys [id label]}]
  (str "  " (dot-quote (name id)) " [label=" (dot-quote label) "];"))

(defn- edge-stmt
  [{:keys [from to label done]}]
  (str "  " (dot-quote (name from)) " -> " (dot-quote (name to))
       " [label=" (dot-quote label) (when done ", style=dashed") "];"))

(defn dot
  "The shape as GRAPHVIZ SOURCE, as a string — the drawing as DATA, where `draw!` is the
   drawing as an effect. Both label the graph the same way, so what this answers is exactly
   what `draw!` would render.

   WHAT IT IS FOR: anything that renders a diagram itself rather than shelling out. A
   notebook, a web page, a docs build — all of them want the source and none of them wants a
   file. It needs no graphviz installed, being a `spit` and not a `dot`.

   HOW: this is built directly from `labelled`'s plain drawing — one node statement per
   entry of :nodes carrying its :label, one edge statement per entry of :edges from :from to
   :to carrying its :label, dashed where :done is true — with every id and label quoted for
   graphviz. It calls no ubergraph rendering function, so it needs no graph value, no writer
   and no file: it is a pure function of the shape, and the labels are exactly `labelled`'s."
  {:malli/schema [:=> [:cat shape/Shape] :string]
   :knowledge
   [{:id :dot-arrived-from-a-consumer
     :kind :decision
     :says "`dot` answers the drawing as DATA where `draw!` is the drawing as an effect, and it arrived from a consumer — the tutorial could not be written without it, and `draw!` alone cannot serve a renderer that is not graphviz. A consumer's need is the only good reason to widen a facade."
     :why "It paid twice: the notebook's helper went from eleven lines to four, and the graphviz-source test stopped needing a file and left the integration suite for the fast loop."}
    {:id :viz-graph-answers-nothing-useful
     :kind :lesson
     :says "viz-graph threads the dot string through a cond-> whose :dot branch is (#(spit filename %)), so its value is spit's nil and the source is only ever written OUT. The way to it as a value: `spit` calls clojure.java.io/writer on what it is handed and accepts any java.io.Writer, so a StringWriter catches the source in memory and needs no finally. Its :auto-label pprints the whole attribute map, which here holds a compiled schema and a closure."
     :cites [:dot-arrived-from-a-consumer]}
    {:id :dot-built-from-labelled-directly
     :kind :lesson
     :says "ubergraph's viz-graph threads the dot string through a cond-> whose :dot branch is (#(spit filename %)), so its value is spit's nil, and it takes a graph value that this shape no longer carries once `labelled` reduced it to plain data. Building the graphviz text directly from :nodes and :edges needs no writer, no graph value and no ubergraph rendering call at all — just quoting for graphviz syntax."
     :cites [:dot-arrived-from-a-consumer]
     :supersedes [:viz-graph-answers-nothing-useful]}]}
  [sh]
  (let [{:keys [nodes edges]} (labelled sh)]
    (str "digraph {\n"
         (str/join "\n" (map node-stmt nodes))
         "\n"
         (str/join "\n" (map edge-stmt edges))
         "\n}\n")))

(defn draw!
  "The shape as a picture, through graphviz — an EFFECT and never a test: what a drawing is for is a
   person looking at it, and it answers nothing useful. :save {:filename f :format :dot} writes the
   graphviz source `dot` answers and needs nothing installed — it is a spit. Any other :format shells
   out to `dot -T<format> -o f` with the source on stdin and writes f. No :save at all renders a PNG
   into a temporary file and opens it through java.awt.Desktop, where the JVM has one.

   NOTHING IS SWALLOWED: where graphviz leaves no usable file — it can write a zero-byte file and
   exit 0 — or where no desktop can open one, it throws an ex-info carrying the file, the format and
   graphviz's stderr, and what `dot` refuses it refuses too, because a drawing that silently did not
   appear is the one failure a person cannot see."
  {:malli/schema [:=> {:registry {"Shape" shape/Shape}} [:cat [:schema [:ref "Shape"]] [:? :map]] :any] :knowledge [{:id :dot-can-write-a-zero-byte-file-and-exit-0
     :kind :lesson
     :says "`dot` can write a ZERO-BYTE FILE and exit 0 on an oversized graph, after a warning that looks survivable and is not. CHECK THE FILE AND NOT THE EXIT CODE. SVG rendered the same graph fine, which made it look like a graphviz quirk rather than a label problem."
     :cites [:a-node-is-labelled-by-its-id]}
    {:id :two-tests-two-requirements
     :kind :lesson
     :says ":format :dot is a spit and needs NOTHING installed; :format :png shells out, and that test is the only thing proving the RENDERING path — asserted on the PNG magic bytes, because a file existing proves only that something wrote one. Verified both ways: outside the devenv the render test errors and the source test passes; inside, both pass. graphviz is in the shared devenv because a drawing nobody can look at is not worth having."
     :cites [:dot-can-write-a-zero-byte-file-and-exit-0]}
    {:id :check-the-drawing-as-a-real-png
     :kind :lesson
     :says "Check a drawing as a real PNG and not as dot source. The completion transitions were checked that way — dashed unlabelled arrows beside a solid labelled `cancel` for the abort — which is the distinction visible at a glance and the argument for drawing at all. And assert that no $eval reached a label: a closure in a picture is the failure mode."
     :cites [:the-drawing-is-harels]}
    {:id :draw-built-from-dot-directly
     :kind :decision
     :says "`draw!` renders `dot`'s own graphviz source rather than building any text of its own, so there is exactly one declaration of the drawing. :save {:format :dot} is a plain spit of that source; any other :save format pipes the source into `dot -T<format> -o f` on stdin and then checks the FILE — its existence and its size — never the process's exit code, because dot can print a warning, exit 0, and still write nothing. With no :save at all it falls back to ubergraph's own viewer on the underlying graph, exactly as before, and answers nothing useful either way."
     :cites [:dot-can-write-a-zero-byte-file-and-exit-0 :dot-arrived-from-a-consumer]}
    {:id :draw-swallows-nothing
     :kind :decision
     :says "`draw!` with no :save renders a PNG into a temporary file and opens it through java.awt.Desktop, and throws where the JVM has no desktop; every other path throws where graphviz left no usable file. Nothing is caught anywhere in it. The old no-:save arity called ubergraph's viewer inside a try that swallowed every exception into nil — the one bare try/catch in this library, against its own rule — and on macOS, which has no xlib viewer, it did nothing and said nothing."
     :why "A drawing that silently did not appear is the one failure a person cannot see, and this function exists for a person to look at. Found by review rule (2) on 2026-09-15 after the landing's gate — suite and lint — had passed it: a gate checks what a test can see, and a swallowed exception is what no test sees."
     :from "the author, 2026-09-15, agreeing the review's recommendation that the four defects the landing left come first"
     :when "2026-09-15"
     :cites [:draw-built-from-dot-directly :dot-can-write-a-zero-byte-file-and-exit-0]}
    {:id :a-goal-s-sentence-held-nothing-and-an-example-did
     :kind :lesson
     :says "Rewritten by robertluo.coder's authoring machine from a brief whose :goal said `no try/catch anywhere: what fails, throws`. Its first answer wrapped `dot` in a try that swallowed every exception into nil — the defect the brief existed to remove, back in a different place — and passed its suite, because no test fed it a value `dot` refuses. The second brief carried one more example: a non-shape thrown out of `draw!` as it is thrown out of `dot`. The second answer holds no try. A sentence in a goal is words the writer may not weigh; an example is what the machine holds an answer to."
     :when "2026-09-15"
     :cites [:draw-swallows-nothing]}]}
  ([sh] (draw! sh {}))
  ([sh opts]
   (if-let [{:keys [filename format]} (:save opts)]
     (let [src (dot sh)]
       (if (= format :dot)
         (spit filename src)
         (let [{:keys [exit err]} (shell/sh "dot" (str "-T" (name format)) "-o" filename :in src)
               f (io/file filename)]
           (when-not (and (.exists f) (pos? (.length f)))
             (throw (ex-info "dot produced no usable output"
                             {:exit exit :err err :format format :filename filename}))))))
     (let [src (dot sh)
           tmp (java.io.File/createTempFile "draw" ".png")
           filename (.getAbsolutePath tmp)
           {:keys [exit err]} (shell/sh "dot" "-Tpng" "-o" filename :in src)
           f (io/file filename)]
       (when-not (and (.exists f) (pos? (.length f)))
         (throw (ex-info "dot produced no usable output"
                         {:exit exit :err err :format :png :filename filename})))
       (if (java.awt.Desktop/isDesktopSupported)
         (.open (java.awt.Desktop/getDesktop) f)
         (throw (ex-info "no desktop available to open drawing" {:filename filename})))))))
