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
  `:nested`, keyed by `:within`, and is not scored."
  (:require [robertluo.state-graph.check :as check]
            [robertluo.state-graph.drive :as drive]
            [robertluo.state-graph.shape :as shape]))

(def default-steps
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
                   Covering]]}
  ([constructor env alternatives] (covering constructor env alternatives {}))
  ([constructor env alternatives {:keys [steps] :or {steps default-steps}}]
   (let [taken  (atom #{})
         nested (atom {})
         sh     (constructor env)
         seen   (atom #{(shape/initial-id sh)})
         on     (fn [{:keys [event from to within]}]
                  (if (seq within)
                    (swap! nested update within (fnil inc 0))
                    (do (swap! taken conj [from (:id event) to])
                        (swap! seen into [from to]))))
         runs   (envs env alternatives)]
     (doseq [e runs]
       (driven! (constructor e) on steps))
     (let [all       (mapv (juxt :from :event :to) (shape/transitions sh))
           reports   (set (keys (shape/reports sh)))
           joins     (set (for [{:keys [id verdict]} (check/driving sh)
                                :when (= :join verdict)]
                            id))
           why       (fn [[from event _]]
                       (cond (not (reports event))  :no-report
                             (joins from)           :join-order
                             (not (@seen from))     :unvisited-state
                             :else                  :gap))
           uncovered (sort (remove @taken all))]
       {:of        (count all)
        :covered   (count (filter @taken all))
        :runs      (count runs)
        :visited   @seen
        :uncovered (mapv (fn [t] {:transition (vec t) :why (why t)}) uncovered)
        :gaps      (mapv vec (filter #(= :gap (why %)) uncovered))
        :nested    @nested}))))
