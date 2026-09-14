(ns robertluo.state-graph.explore
  "COVER THE GRAPH BY ACTUALLY RUNNING IT — the runtime counterpart of `check`.

  Everything in `check` answers `could this ever have worked` from the graph alone.
  This answers `did it` — by DRIVING, for real, through the handlers and the guards
  and the schema at every crossing, with the seams of the machine replaced by
  ordinary functions.

  THE TECHNIQUE IS ONE SENTENCE. A SHAPE IS A FUNCTION OF ITS ENV, so whatever a
  report reaches for — a model, a database, a socket, a clock — arrived as a value
  in that env, and a plain function goes in its place. Give the same constructor a
  set of alternative envs and every branch is reachable on purpose, at no cost and
  with no flake. A branch a live run reaches once a week is a branch nobody tests.

  `covering` is the whole of it: a constructor, a base env, and the alternatives to
  vary. It drives one run per combination and answers which transitions were taken.

  WHAT IT ADDS OVER DRIVING BY HAND is not the driving — it is the ACCOUNTING, and
  the accounting has a subtlety that made it worth writing down. Some transitions
  CANNOT be taken by any driver however you fake the world:

    :no-report        the event carries no `:report`, so only the world supplies it.
                      An approval, an amendment, a person's decision
    :join-order       the state is a proven `:join`, so the crank takes its events
                      ALL AT ONCE in one order — and the intermediate states of the
                      other orders are never entered. Exactly the property that
                      makes a join safe is what makes half its diamond undrivable
    :unvisited-state  nothing entered the state this edge leaves from, so the reason
                      is upstream of here and this edge is not the thing to fix

  Subtract those three and what is left is `:gaps` — a transition a driver COULD
  have taken and your alternatives never produced a payload for. THAT is the number
  to drive to zero, and it is the only one that means you have missed something.

  A THROW PROPAGATES AND IS NOT COLLECTED. Driving enforces the event's schema, the
  guards, the target state's schema and a loud miss; anything that goes wrong is a
  defect in the machine, and stopping on it with the exception intact is more useful
  than a tally. Bisect by passing fewer alternatives.

  ONE MACHINE, THE OUTERMOST. A nesting node's child has its own transitions and its
  own state ids, and two machines may name a state `:done` — so this stays with one
  graph, for the reason :a-published-check-answers-about-the-machine gives for
  `reachable`, `traps` and `dead-ends`. What a child took is answered under
  `:nested`, keyed by `:within`, and is not scored.

  A COMPLETION TRANSITION IS SCORED TOO, and it had to be once one could BRANCH. While
  a `:done` was a single unconditional target there was nothing to cover — the edge was
  taken exactly when its state was entered — but a `:done` keyed by the child's final
  state is a fork, and a fork no run took is precisely what this exists to name. It
  fires no event, so it is never reported and is RECONSTRUCTED instead, which
  :a-state-may-say-where-it-goes-when-it-completes says an auditor holding the shape
  can do: a row says where the whole machine ended up, so a host it is no longer
  sitting in has completed, by the branch that child's own final state names."
  {:knowledge
   [{:id :cover-the-graph-by-running-it
     :kind :decision
     :says "Cover the graph by actually running it — the runtime counterpart of `check`. A shape is a function of its env, so whatever a report reaches for arrived as a value and an ordinary function goes in its place; give one constructor a set of alternative envs and every branch is reachable on purpose. `covering` drives one run per combination and answers which transitions were taken."
     :why "The technique was already possible; what was missing was the ACCOUNTING for having done it. Measured on the first consumer's machine: fourteen states, twenty-two transitions, three agents and a REPL replaced by constants — 19 of 22 covered, 24 runs, gaps empty, and the three uncovered exactly one of each kind. The first attempt covered 14 with three gaps, all the way back from a failure; the fix was to vary :rounds, a plain number in the env and not a function — a budget that is an edge is covered by substituting a constant."
     :from "the author of ../coder, 2026-09-05: `the ability of fully cover the graph using this technique is very important and general, I think maybe it worth a seat in src` and `we can substitute not only LLM functions, but any functions in system ... our check does its work statically, while this can check in the runtime` — runtime meaning by actual executing"
     :when "2026-09-05"
     :cites [:a-shape-is-a-function-of-its-env :what-the-graph-buys]}
    {:id :one-fixed-env-per-run
     :kind :decision
     :says "ONE FIXED ENV PER RUN. To take an edge you need an env that produces its payload, not a particular history, so TRANSITION coverage does not need a fake that answers differently on its third call. Reach for a stateful fake when the thing under test is a SEQUENCE; reach for this when it is a GRAPH."
     :cites [:cover-the-graph-by-running-it]}
    {:id :a-stateful-fake-was-turned-down-as-the-default
     :kind :rejected
     :says "A fake that answers differently on its third call — a recovery, tests failing and then passing — reaches paths a fixed one cannot, and was turned down as the default: it makes the run count unpredictable and the failure hard to read."
     :cites [:one-fixed-env-per-run]}
    {:id :a-throw-propagates-and-is-not-collected
     :kind :decision
     :says "A throw propagates and is not collected. Driving already enforces the event's schema, the guards, the target's schema and a loud miss, so anything that goes wrong is a defect in the machine, and stopping on it with the exception intact beats a tally. There is no try in this library's src and this did not become the first one. Bisect by passing fewer alternatives."
     :cites [:cover-the-graph-by-running-it]}
    {:id :covering-is-a-sibling-of-drive-and-not-on-the-facade
     :kind :decision
     :says "A sibling of drive, above it in the arrow, requiring it and check and shape. It is NOT in `check`, which never touches the runtime path — this one runs the machine — and it is NOT on the facade, for the reason coverage, confluence, commuting, subsumption and views are not."
     :cites [:cover-the-graph-by-running-it :two-kinds-of-check-and-two-places-for-them]}
    {:id :one-machine-the-outermost
     :kind :decision
     :says "One machine, the outermost, for the reason the id-set checks stay with one graph: two machines may name a state :done, and a report keyed by state id has nowhere to say which it meant. A child's transitions are counted under :nested by :within and are not scored."
     :cites [:a-published-check-answers-about-the-machine]}
    {:id :a-completion-transition-is-scored-too
     :kind :decision
     :says "A completion transition is scored too, and it had to be once one could BRANCH: while a :done was a single unconditional target there was nothing to cover, but a :done keyed by the child's final state is a fork, and a fork no run took is precisely what this exists to name. It fires no event, so it is RECONSTRUCTED — a row says where the whole machine ended up, so a host it is no longer sitting in has completed, and which branch is read off the target. Exact wherever the branches go different places; two outcomes completing to one target are a merge this counts both of."
     :when "2026-09-05"
     :cites [:done-may-say-where-each-outcome-goes :cover-the-graph-by-running-it]}
    {:id :covering-had-a-hole-the-outcomes-opened
     :kind :lesson
     :says "Found by writing a consumer test that asserted a branch the tool could not see: a completion fires no event, so it was never scored — invisible while unconditional, a silent gap once it could fork. And an edge into a state that completes on entry had scored as uncovered, a LATENT inaccuracy no fixture had. The temptation is the thing to note: the fix nearly made was to weaken the assertion. A tool blind to a branch tempts you to weaken the test until it matches the tool."
     :cites [:a-completion-transition-is-scored-too]}
    {:id :a-completion-is-never-no-report
     :kind :decision
     :says "A completion is never :no-report, and the ordering of `why` says so: that reason is about an event only the WORLD can supply, and ARRIVING is not something anybody supplies. Asked as membership in the completion set rather than by the source state, a nesting node's own edges being its escape and perfectly ordinary events."
     :cites [:a-completion-transition-is-scored-too]}]}
  (:require [robertluo.state-graph.check :as check]
            [robertluo.state-graph.drive :as drive]
            [robertluo.state-graph.shape :as shape]))

(def ^{:knowledge
       [{:id :a-step-cap-where-drive-has-none
         :kind :decision
         :says "A step cap where `drive` has none. `drive` needs no counter because the stopping rule is an edge — but exploration is exactly where you find out that yours is not, and hanging the suite is a poor way to report an infinite loop."
         :cites [:drive-needs-no-counter]}]}
  default-steps
  "How many turns one run may take before this stops asking.

  A SHAPE WHOSE STOPPING RULE IS AN EDGE NEEDS NO CAP — `drive` has none for exactly
  that reason, the budget being a guard and not a number in a loop. This has one
  anyway, because exploration is where you find out that yours ISN'T: a machine that
  loops for ever is the defect being looked for, and hanging the suite is a poor way
  to report it."
  200)

(def Alternatives
  "What to vary, and over what: an env key to the values it may take.

  A SEQUENCE PER KEY AND NOT A SET, because order is what makes a report readable —
  the first combination is the first value of each, so the run people read first is
  the one they wrote first."
  [:map-of :any [:sequential :any]])

(def Covering
  "What `covering` answers.

  `:covered` and `:gaps` are the two numbers. Everything else is there to explain
  them, which is the difference between a coverage report and a scoreboard."
  [:map
   [:of :int]
   [:covered :int]
   [:runs :int]
   [:visited [:set :any]]
   [:uncovered [:vector [:map [:transition [:tuple :any :any :any]] [:why :keyword]]]]
   [:gaps [:vector [:tuple :any :any :any]]]
   [:nested [:map-of :any :int]]])

(defn- unhandled
  "An event no edge out of this state would admit.

  LOUD HERE AND NOT SILENT, which is the opposite of the library's default and right
  for this one caller. `compile` answers such an event with the state UNCHANGED,
  because a machine reduced over somebody else's stream meets events it does not
  care about. There is no such stream here: every event was REPORTED by the shape
  itself, so a miss is a gap between guards that `check/coverage` would have named,
  arriving late."
  [state event]
  (throw (ex-info "No edge out of this state admits that event"
                  {:at (:id state) :took (:id event)})))

(defn- envs
  "`env` crossed with every combination of `alternatives`.

  A REDUCTION AND NOT A `for`, so the number of keys is data rather than syntax."
  [env alternatives]
  (reduce (fn [acc [k vs]] (vec (for [e acc v vs] (assoc e k v))))
          [env]
          alternatives))

(defn- entry-chain
  "The completion edges an ENTRY-FIRED continuation took, walking from where an edge
   landed to where the machine actually ended up — or nil where the walk does not get
   there.

   A state that completes on entry has no child, so it has ONE unconditional way out
   and the walk is deterministic, which is the same argument :done-cycle rests on."
  [conts to at]
  (loop [a to acc []]
    (cond
      (= a at) acc
      :else (if-let [c (first (filter (comp nil? :outcome) (conts a)))]
              (recur (:to c) (conj acc [a nil (:to c)]))
              nil))))

(defn- travelled
  "THE EDGES ONE REPORTED TRANSITION ACTUALLY CROSSED: the event's own, and every
   COMPLETION that carried the machine on from where it landed.

   A ROW SAYS WHERE THE MACHINE ENDED UP and not where the edge pointed — a state that
   completes on entry is passed straight through inside the same step, and the
   intermediate hops are deliberately not published. They are a PURE FUNCTION of the
   shape and the state, so an auditor holding the shape can reconstruct them, and this
   is the auditor: the edge is the declared target the chain from which reaches `to`."
  [sh conts from event to]
  (or (some (fn [t] (when-let [chain (entry-chain conts t to)]
                      (into [[from event t]] chain)))
            (for [x (shape/transitions sh)
                  :when (and (= from (:from x)) (= event (:event x)))]
              (:to x)))
      [[from event to]]))

(defn- driven!
  "Drive one shape to a standstill, telling `on` about every transition, and stop
  after `steps` turns whatever happens.

  NOT `drive/drive`, and only for the cap. Everything else here is that function."
  [sh on steps]
  (loop [run [] n steps]
    (let [next (drive/step sh run {:context {:ignored unhandled} :on on})]
      (if (or (= next run) (zero? n)) run (recur next (dec n))))))

(defn covering
  "Drive `constructor`'s machine once for every combination of `alternatives` over
  `env`, and answer which of its transitions were actually taken.

    (covering machine/shape
              {:brief brief :rounds 1 :writer (constantly \"(defn answer [] 1)\")}
              {:judge [{:at :code} {:at :test} {:at :both}]
               :repl  [(answering-green) (answering-red)]})
    ;=> {:of 22 :covered 19 :runs 6
    ;    :gaps []
    ;    :uncovered [{:transition [:both :amend :both]   :why :no-report}
    ;                {:transition [:asked :write :coded] :why :join-order}
    ;                {:transition [:coded :propose :both] :why :unvisited-state}]}

  `constructor` is `(fn [env] shape)` — the function a task already writes, since A
  SHAPE IS A FUNCTION OF ITS ENV. `env` is the part that does not vary and
  `alternatives` is the part that does; one run happens per combination, so the cost
  is the PRODUCT of the alternatives and not an exponential search over turns.

  ONE FIXED ENV PER RUN, DELIBERATELY. A fake that answers differently on its third
  call can reach a path a fixed one cannot — but it makes the run count unpredictable
  and the failure hard to read, and TRANSITION coverage does not need it: to take an
  edge you need one env that produces its payload, not a particular history. Reach
  for a stateful fake when you are testing a SEQUENCE; reach for this when you are
  testing a GRAPH.

  THE CONSTRUCTOR IS ALSO CALLED ONCE ON `env` ALONE, to ask the structural questions
  — its transitions, which events carry a `:report`, which states are joins. That
  call never DRIVES, so an env missing a key the alternatives supply is fine: a
  report that would close over nil is built and never run.

  WHAT `:gaps` MEANS is in the namespace docstring, and it is the only number that
  means you have missed something. An empty `:gaps` says every transition a driver
  can take was taken."
  {:malli/schema [:function
                  [:=> [:cat ifn? :map Alternatives] Covering]
                  [:=> [:cat ifn? :map Alternatives [:map [:steps {:optional true} :int]]]
                   Covering]]
   :knowledge
   [{:id :three-kinds-of-transition-no-driver-can-take
     :kind :decision
     :says "Three kinds of transition cannot be taken by any driver however you fake the world, and calling them failures would cry wolf on every real machine: :no-report, the event is the world's; :join-order, the state is a proven join, so the crank takes its events at once in ONE order and the other orderings' halfway states are never entered; :unvisited-state, the reason is upstream. Subtract those and the residue is :gaps — an edge a driver COULD have taken and your alternatives never produced a payload for — the only number that means you missed something."
     :why "The :join-order row is the one worth the entry: exactly the property that makes a join safe is what makes half its diamond undrivable. Confluence proves the order cannot be observed, so the crank picks one; a report that let you fix it would be inviting you to depend on which order it picked, the one thing the library promises you may not observe. The explore test asserts that one of the two orderings is skipped and never which."
     :cites [:cover-the-graph-by-running-it :a-proven-join-is-taken-whole :the-driver-world-distinction-is-data]}]}
  ([constructor env alternatives] (covering constructor env alternatives {}))
  ([constructor env alternatives {:keys [steps] :or {steps default-steps}}]
   (let [taken  (atom #{})
         nested (atom {})
         sh     (constructor env)
         seen   (atom #{(shape/initial-id sh)})
         conts  (shape/continuations sh)
         on     (fn [{:keys [event from to within]}]
                  (if (seq within)
                    (do (swap! nested update within (fnil inc 0))
                        ;; A CHILD FINISHING COMPLETES ITS HOST, and the row says so by
                        ;; moving the OUTERMOST state: `from` is where the whole machine
                        ;; was and `to` is where it is now. WHICH BRANCH was taken is
                        ;; read off the TARGET, the child's own final state having been
                        ;; dropped on the way out — which is exact wherever the branches
                        ;; go different places, and that is what branching is for. Two
                        ;; outcomes completing to ONE target are a merge, and this counts
                        ;; both: the shape has made them indistinguishable in a history.
                        (when (not= from to)
                          (doseq [c (conts from) :when (= to (:to c))]
                            (swap! taken conj [from (:outcome c) to]))
                          (swap! seen into [from to])))
                    (doseq [[a e b] (travelled sh conts from (:id event) to)]
                      (swap! taken conj [a e b])
                      (swap! seen into [a b]))))
         runs   (envs env alternatives)]
     (doseq [e runs]
       (driven! (constructor e) on steps))
     (let [done      (set (for [[from cs] conts, c cs] [from (:outcome c) (:to c)]))
           all       (into (mapv (juxt :from :event :to) (shape/transitions sh)) done)
           reports   (set (keys (shape/reports sh)))
           joins     (set (for [{:keys [id verdict]} (check/driving sh)
                                :when (= :join verdict)]
                            id))
           why       (fn [[from event _ :as t]]
                       (cond (not (@seen from)) :unvisited-state
                             ;; A COMPLETION HAS NO EVENT AND IS STILL COVERABLE, so
                             ;; it is asked about before :no-report — which is about an
                             ;; event only the WORLD can supply, and arriving is not
                             ;; something anybody supplies. Asked as MEMBERSHIP and not
                             ;; by the source state, a nesting node's own edges being
                             ;; its escape and perfectly ordinary events.
                             (done t)              :gap
                             (not (reports event)) :no-report
                             (joins from)          :join-order
                             :else                 :gap))
           uncovered (sort (remove @taken all))]
       {:of        (count all)
        :covered   (count (filter @taken all))
        :runs      (count runs)
        :visited   @seen
        :uncovered (mapv (fn [t] {:transition (vec t) :why (why t)}) uncovered)
        :gaps      (mapv vec (filter #(= :gap (why %)) uncovered))
        :nested    @nested}))))
