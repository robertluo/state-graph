(ns robertluo.state-graph.drive
  "THE REPORT-DRIVEN DOOR: turn the crank yourself, one event at a time.

  THE THIRD DOOR, AND THE ONE `:report` WAS MISSING. An event may declare how it
  is FOUND — {:report f :reads <a view>} — and until now nothing in this library
  consumed that declaration, so every application that wanted it wrote the same
  driver again: what does this state await, which of those can I produce, what
  does the report read, apply it, go round. That loop is a fact about a SHAPE and
  not about anybody's application — it is `:report`, `:done`, nesting and
  `confluence`, all of them ours — and writing it twice is what said so.

  WHERE IT SITS BESIDE THE OTHER TWO:

    the reduction — (reduce (compile sh) (initial sh {}) events). Events come from
                    wherever the caller has them. Nothing here.
    the stream    — (run sh {} events). Events arrive from a SOURCE, many machines
                    at once, and the caller feeds it.
    this one      — (drive sh []). Events are FOUND, by asking the shape which one
                    this state awaits and running the report that declares how.
                    One machine, synchronous, and it stops on its own.

  A RUN IS THE VECTOR OF EVENTS, which is the whole data model here. Where it got
  to is a reduction over it, so resuming is replaying, rewinding is a PREFIX, and
  a turn can be looked at, thrown away or taken again. Nothing mutates and nothing
  is stored — `:on` is how a caller writes down what happened.

  AND `async/drive` IS NOT THIS. That one serialises ONE machine over a manifold
  stream and is the async layer's own; this one finds the events. They share a
  word because both mean `keep going`, and nothing else."
  (:require [malli.core :as m]
            [malli.util :as mu]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.compile :as compile]
            [robertluo.state-graph.shape :as shape]))

;;; ------------------------------------------------------------------ the shapes

(def Run
  "A run: the events that happened, in the order they happened."
  [:sequential compile/Event])

(def Turn
  "WHY A RUN IS NOT MOVING AND WHO CAN MOVE IT — what `awaiting` answers, and the
   whole driving rule as one value.

     {:at :implemented :awaits #{}         :final true}               over
     {:at :written :awaits #{:judged} :from :driver :event :judged}   `step` will do it
     {:at :written ... :held true}                                    parked, this run's choice
     {:at :signed  :awaits #{:approve} :from :world}                  parked, the machine's
     {:at :asked ... :from :driver :events [:draft :write]}           a join, proven either way

   :within IS THE PATH OF HOSTS where the run is inside a nested machine, outermost
   first, and :at is then the CHILD's node. A driver reports into the machine that
   is actually running, which is the innermost one that has anything to say.

   THE TWO PARKS ARE DIFFERENT AND THIS IS WHERE THEY ARE TOLD APART. :from :world
   is the SHAPE's — an event nobody but the world can supply, and it is the same on
   every run of that shape. :held is THIS RUN's, and it moves nothing in the graph
   at all, which is why supervising a run does not change its fingerprint."
  [:map
   [:at shape/Id]
   [:awaits [:set shape/Id]]
   [:within {:optional true} [:vector shape/Id]]
   [:final  {:optional true} :boolean]
   [:from   {:optional true} [:enum :driver :world]]
   [:event  {:optional true} shape/Id]
   [:events {:optional true} [:vector shape/Id]]
   [:held   {:optional true} :boolean]])

(def Options
  "WHAT A DRIVER IS GIVEN, and every one of them is a FUNCTION OR A VALUE rather
   than a setting this library then has to understand.

     :data       what the first state carries. Default {}
     :instance   the name of this run, written onto every state and event
     :context    handed to `compile` — {:ignored f} where a miss must not be silent
     :permitted  what THIS TURN may report: a set of event ids, or any predicate
                 over one. Default everything, which is a run nobody is watching
     :on         called with a Transition after each event is applied. The place a
                 caller writes a transcript, and the only reason this door needs to
                 know that histories exist
     :reports    given a seq of thunks, answers a seq of their results. Default runs
                 them in order; a caller with a stream library passes one that runs
                 them AT ONCE, which is what makes a proven join cost one report
                 rather than two"
  [:map
   [:data      {:optional true} :map]
   [:instance  {:optional true} shape/Instance]
   [:context   {:optional true} compile/Context]
   [:permitted {:optional true} ifn?]
   [:on        {:optional true} ifn?]
   [:reports   {:optional true} ifn?]])

(def Applied
  "WHAT `:on` IS TOLD: one event, applied, and where it took the machine.

   IT IS EVERYTHING AND ONLY WHAT A ROW NEEDS — which machine is the caller's to
   know, but what happened is not recoverable from the run alone once a state has
   been projected. :within is the path of hosts where the event landed inside a
   nested machine."
  [:map
   [:event compile/Event]
   [:from shape/Id]
   [:to shape/Id]
   [:state compile/State]
   [:within {:optional true} [:vector shape/Id]]])

;;; ------------------------------------------------------------------ asking the shape

(defn awaits
  "The event ids `id` has an edge for — what that state is WAITING TO BE TOLD.

  IT IS NOT THE DRIVING RULE, and the difference is the whole of why `awaiting`
  exists: this counts every event the state admits, and a driver can produce only
  the ones the shape gave a `:report`. A state awaiting one from a driver and one
  from a person awaits TWO and is perfectly drivable."
  {:malli/schema [:=> [:cat shape/Shape shape/Id] [:set shape/Id]]}
  [sh id]
  (into #{} (comp (filter #(= id (:from %))) (map :event)) (shape/transitions sh)))

(def ^:private stepper
  "The shape compiled, remembered. A shape is a value and a reduction happens once
   a turn, so compiling it twice is work nobody asked for."
  (memoize (fn [sh context] (if context (compile/compile sh context) (compile/compile sh)))))

(def ^:private commuting
  "`check/confluence` as a lookup, remembered — {[state #{a b}] verdict}. A static
   check is not something to recompute once a turn."
  (memoize
   (fn [sh]
     ;; THIS MACHINE'S OWN PAIRS. `check/confluence` recurses into nested children now, and
     ;; a lookup keyed by state id would merge a child's pair into a parent state that
     ;; happens to share its name. Each level is asked about its OWN shape, here and in
     ;; `check/commuting`, for the same reason.
     (into {} (for [{:keys [in pair verdict within]} (check/confluence sh)
                    :when (empty? within)]
                [[in (set pair)] verdict])))))

(defn- confluent?
  "Whether every DISTINCT pair among `ids` is proven to land in the same state either
   way round, in state `id`.

   THIS IS WHAT LICENSES A DRIVER TO CHOOSE. Two reportable events out of one state
   is a fork, and a driver that picked one at random would be inventing an order the
   shape never promised — unless the shape PROVES the order cannot be observed, which
   is exactly what `confluence` answers. A pair of the SAME event is not asked about:
   nothing is being chosen between."
  [sh id ids]
  (let [index (commuting sh)]
    (every? (fn [[a b]] (= :yes (get index [id #{a b}])))
            (for [a ids b ids :when (neg? (compare a b))] [a b]))))

(defn- levels
  "The machine and the state at every depth the run is currently in, OUTERMOST
   FIRST, each with the path of hosts above it.

   A nesting node carries its child's state under :sub, so following :sub as deep as
   it goes is following the machine that is actually running."
  [sh state]
  (loop [sh sh, st state, path [], acc []]
    (let [acc (conj acc [sh st path])]
      (if-let [child (and (:sub st) (shape/machine sh (:id st)))]
        (recur child (:sub st) (conj path (:id st)) acc)
        acc))))

(defn- turn-at
  "What a driver can do at ONE level, or nil where it can do nothing."
  [sh st path permitted]
  (let [awaited (awaits sh (:id st))
        can     (into #{} (filter (set (keys (shape/reports sh)))) awaited)
        base    (cond-> {:at (:id st) :awaits awaited}
                  (seq path) (assoc :within path))]
    (when (seq can)
      (let [allowed (into (sorted-set) (filter permitted) can)]
        (cond
          (empty? allowed)              (assoc base :from :driver :held true
                                               :event (first (sort can)))
          (= 1 (count allowed))         (assoc base :from :driver :event (first allowed))
          (confluent? sh (:id st) allowed) (assoc base :from :driver :events (vec allowed))
          :else                         (assoc base :from :world))))))

(defn- turn
  "The Turn, and the level it is about — `[turn shape state path]`. Private because
   `step` needs the level to project a report's `:reads`, and a caller does not."
  [sh state permitted]
  (let [ls (levels sh state)]
    (or (some (fn [[s st path]]
                (when-let [t (turn-at s st path permitted)] [t s st path]))
              (reverse ls))
        ;; INNER FIRST, and nothing anywhere can be reported: the run is waiting where
        ;; it actually is, which is the innermost machine.
        (let [[s st path] (last ls)]
          [(cond-> {:at (:id st) :awaits (awaits s (:id st))}
             (seq path) (assoc :within path)
             (shape/final? sh (:id state)) (assoc :final true)
             (not (shape/final? sh (:id state))) (assoc :from :world))
           s st path]))))

;;; ------------------------------------------------------------------ the door

(defn where
  "Where `events` got to: the state, with everything it holds.

  A RESUMED RUN AND A FRESH ONE ARE THE SAME REDUCTION over a vector that differs
  only in length, so there is no second code path for carrying on. And this runs NO
  REPORTS — replaying a run costs arithmetic on maps and never a second call to
  whatever the reports reach."
  {:malli/schema [:function
                  [:=> [:cat shape/Shape Run] compile/State]
                  [:=> [:cat shape/Shape Run Options] compile/State]]}
  ([sh events] (where sh events {}))
  ([sh events {:keys [data instance context]}]
   (reduce (stepper sh context)
           (if instance
             (compile/initial sh instance (or data {}))
             (compile/initial sh (or data {})))
           events)))

(defn awaiting
  "Why `events` is not moving and WHO CAN MOVE IT — a `Turn`, derived from the shape
  rather than written down beside it.

  IT COUNTS WHAT CAN BE REPORTED, NOT WHAT IS AWAITED, and it asks the INNERMOST
  running machine first, a child's own vocabulary deciding before its host's."
  {:malli/schema [:function
                  [:=> [:cat shape/Shape Run] Turn]
                  [:=> [:cat shape/Shape Run Options] Turn]]}
  ([sh events] (awaiting sh events {}))
  ([sh events opts]
   (first (turn sh (where sh events opts) (or (:permitted opts) any?)))))

(defn advance
  "One event applied to a run: the run it grows into, and `:on` told what happened.

  THE DOOR SOMEBODY ELSE COMES IN BY. `step` finds the event the shape says a driver
  can find; this takes one it is HANDED — an approval, an amendment, a decision only
  the world can make — and is otherwise the same turn. Both end here, so there is one
  place a run grows and one place a history is told.

  AN EVENT THE STATE DOES NOT ADMIT IS THE COMPILER'S BUSINESS AND NOT THIS
  FUNCTION'S: by default it answers the state unchanged, and a caller who needs a
  miss to be loud passes {:ignored f} as the :context."
  {:malli/schema [:function
                  [:=> [:cat shape/Shape Run compile/Event] Run]
                  [:=> [:cat shape/Shape Run compile/Event Options] Run]]}
  ([sh events event] (advance sh events event {}))
  ([sh events event opts]
   (let [state  (where sh events opts)
         landed ((stepper sh (:context opts)) state event)]
     (when-let [on (:on opts)]
       (let [path (nth (last (levels sh state)) 2)]
         (on (cond-> {:event event :from (:id state) :to (:id landed) :state landed}
               (seq path) (assoc :within path)))))
     (conj (vec events) event))))

(defn- reported
  "One event, as the shape says it would be found: the report run against the view
   it declares, with the event's own id put on afterwards.

   THE REPORT ANSWERS A PAYLOAD AND THIS PUTS THE `:id` ON, so a report cannot name
   the wrong event — which event is being reported is the shape's to say."
  [sh st id]
  (let [{:keys [report reads]} (get (shape/reports sh) id)
        seen (if reads (select-keys st (mu/keys reads)) st)]
    (when-let [wrong (and reads (m/explain reads seen))]
      (throw (ex-info "The state cannot provide what this report reads"
                      {:at (:id st) :event id :explain (dissoc wrong :schema)})))
    (fn [] (assoc (report seen) :id id))))

(defn step
  "One turn: find what the state `events` lands in is waiting to be told, go and
  find it out, and answer the run with the event saying it.

  A RUN THAT IS OVER, PARKED on an event only the world can report, or HELD by
  `:permitted` comes back UNCHANGED — which is what makes a loop over this safe to
  write, and `awaiting` says which of the three it was.

  A PROVEN JOIN IS TAKEN WHOLE. Where several events are reportable and the shape
  proves the order of them cannot be observed, all of them are found — through
  `:reports`, so a caller who hands over a concurrent one pays for the slowest
  rather than the sum — and applied in a fixed order the shape has already said
  makes no difference. Where it is NOT proven, this stops and says :from :world,
  because choosing would be inventing an order nobody promised.

  IT IS NOT `(compile sh)`. That answers the next STATE from a state and an event
  you already have; this finds the event."
  {:malli/schema [:function
                  [:=> [:cat shape/Shape Run] Run]
                  [:=> [:cat shape/Shape Run Options] Run]]}
  ([sh events] (step sh events {}))
  ([sh events opts]
   (let [state (where sh events opts)
         [t s st] (turn sh state (or (:permitted opts) any?))
         ids   (cond (:held t) nil
                     (:event t) [(:event t)]
                     :else (:events t))]
     (if (empty? ids)
       (vec events)
       (let [run  (or (:reports opts) (fn [thunks] (mapv #(%) thunks)))
             evts (run (mapv #(reported s st %) ids))]
         (reduce (fn [es e] (advance sh es e opts)) (vec events) evts))))))

(defn drive
  "Turn the crank until the machine stops moving, and answer the run it got to.

  IT NEEDS NO COUNTER, and that is the only reason a loop belongs in a library at
  all: the stopping rule is IN THE SHAPE — a final state, a park on the world, a
  hold this run declared — so the loop's whole job is noticing that `step` answered
  what it was given. A budget, a retry limit, a give-up rule are EDGES, where they
  can be drawn and checked, and not numbers in here.

  DRIVING FROM [] RUNS THE WHOLE MACHINE, and driving from a run carries it on.
  There is no second code path for resuming, because carrying on is what this
  already is."
  {:malli/schema [:function
                  [:=> [:cat shape/Shape Run] Run]
                  [:=> [:cat shape/Shape Run Options] Run]]}
  ([sh events] (drive sh events {}))
  ([sh events opts]
   (loop [es (vec events)]
     (let [next (step sh es opts)]
       (if (= next es) es (recur next))))))
