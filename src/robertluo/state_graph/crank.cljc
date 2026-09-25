(ns robertluo.state-graph.crank
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

  AND `async/drive` IS NOT THIS. That one serialises ONE machine over a channel
  of events and is the async layer's own; this one finds the events. They share a
  word because both mean `keep going`, and nothing else."
  {:knowledge
   [{:id :the-crank-is-the-door-report-was-missing
     :kind :decision
     :says "The third door: `drive` and one turn of it, `step`, with `awaits`, `awaiting`, `where` and `advance` beside them. Synchronous and one machine, which keeps the division the other two doors had — the compiler is the pure core, async is the concurrent default, and this is the one that FINDS events rather than being fed them."
     :why "A declaration with nothing in the library consuming it is half a feature, and that was :report: the shape could say how an event is FOUND and nothing here ever went and found one, so every application wrote the same forty lines — what does this state await, which of those did the shape give a :report, what view does that report read, run it, put the event id on, apply it, go round. That loop is :report, :done, nesting and confluence, all of them this library's own, and the way we found out is that it got written twice."
     :from "the author, 2026-09-04: `again, drive and step, if you have to live with them, add them to the state-graph api` and `park is a general ability, not something every workflow needs to implement by itself`"
     :when "2026-09-04"
     :cites [:an-event-may-say-how-it-is-reported :the-driver-world-distinction-is-data]}
    {:id :a-declaration-nothing-consumes-is-half-a-feature
     :kind :lesson
     :says ":report was declared on 2026-09-04 and the argument for stopping there — `the shape telling a caller HOW an event would be found, and a caller choosing to ask` — was right about the SEMANTICS and wrong about the SURFACE. Measured twice before the loop was moved: the first consumer shrank by 160 lines and no longer required the facade at all."
     :cites [:the-crank-is-the-door-report-was-missing]}
    {:id :a-run-is-the-vector-of-events
     :kind :decision
     :says "A run is the vector of events that happened, in order, and that is the whole data model here. Where it got to is a reduction over it, so resuming is replaying, rewinding is a PREFIX, and a turn can be looked at, thrown away or taken again. Nothing mutates and nothing is stored; :on is how a caller writes down what happened."
     :cites [:compilation-and-lifecycle]}
    {:id :a-concurrent-crank-was-not-taken
     :kind :rejected
     :says "A concurrent crank was not built. :reports is an injection, so the library neither depends on manifold at this layer nor decides how many threads anybody has; a caller with a stream library hands one that runs the thunks at once. The concurrent door is still `run`."
     :cites [:the-crank-is-the-door-report-was-missing :inject-a-function-and-never-thread-options]}
    {:id :a-budget-in-drive-was-not-taken
     :kind :rejected
     :says "A budget, a retry limit or a give-up rule inside `drive` was not taken. Those are EDGES, where they can be drawn and checked; `drive` needs no counter because the stopping rule is in the shape, which is the only reason a loop belongs in a library at all."
     :cites [:the-crank-is-the-door-report-was-missing :a-retry-budget-is-two-guarded-edges]}]}
  (:require [malli.core :as m]
            [malli.util :as mu]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.compiler :as compile]
            [robertluo.state-graph.shapes :as shape]
            [malli.generator :as mg]))

;;; ------------------------------------------------------------------ the shapes

(def Run
  "A run: the events that happened, in the order they happened."
  [:sequential compile/Event])

(def ^{:knowledge
       [{:id :the-two-parks-are-different
         :kind :decision
         :says "Two parks, told apart here. :from :world is the SHAPE'S — an event only a person can supply, the same on every run of that shape. :held is THIS RUN'S — the caller's :permitted said not yet — and it moves nothing in the graph, which is why supervising a run does not change its fingerprint: a workflow watched and a workflow left alone are the same machine."
         :cites [:the-driver-world-distinction-is-data :the-fingerprint-carries-no-name]}]}
  Turn
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

(def ^{:knowledge
       [{:id :everything-else-is-injected
         :kind :decision
         :says "Everything a driver is given is a function or a value and never a setting this library has to understand: :permitted (what this turn may report), :on (told each applied event, which is how a caller writes a transcript), :context (handed to the compiler), :reports (given the thunks, so a caller with a stream library pays for the slowest rather than the sum)."
         :cites [:inject-a-function-and-never-thread-options :the-crank-is-the-door-report-was-missing]}]}
  Options
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
                  [:=> [:cat shape/Shape Run Options] Turn]]
   :knowledge
   [{:id :count-what-can-be-reported-not-what-is-awaited
     :kind :decision
     :says "The driving rule counts how many events CAN BE REPORTED, never how many are awaited, and `awaiting` is that rule as one value: :final, :from :world, :held, :from :driver with one event, or :from :driver with several a join proves."
     :why "The first driver's rule — one out-edge and it drives, none and it is final, several and the world chooses — was wrong the moment a state offered a driver's event BESIDE a person's escape: an interruptible step awaits two, one of them reportable, and the run stopped dead on a shape whose `problems` was []."
     :cites [:the-crank-is-the-door-report-was-missing :the-driver-world-distinction-is-data]
     :see [:robertluo.state-graph.crank/awaits]}
    {:id :discovery-recurses-into-a-live-child
     :kind :decision
     :says "The crank follows :sub as deep as it goes and asks the INNERMOST machine first, which is inner-first in the one place it had not yet been applied. :within on the answer is the path of hosts, and :on carries it too, so a history can say where inside a machine something happened."
     :why "The asymmetry is surprising: the compiler handles a nested child completely — an event routes inward, the child's vocabulary decides, the completion fires and the yield is harvested in one step — but DISCOVERY did not. A nesting node has no edge for its child's events, so a driver reading the host's out-edges saw a state that awaits nothing and is not final, and parked for ever on a machine that was ready to go."
     :cites [:inner-first :count-what-can-be-reported-not-what-is-awaited]}]}
  ([sh events] (awaiting sh events {}))
  ([sh events opts]
   (first (turn sh (where sh events opts) (or (:permitted opts) any?)))))

(defn reorder-agrees
  "Run two events out of ONE state BOTH WAYS through the compiled machine and answer where each order landed and whether the two landings are identical. It is the independent witness the concurrency licence has never had: it recomputes nothing `confluence` computes — it holds the two concrete events, applies a then b, applies b then a, and compares the resulting states — so a pair the checker calls :yes whose two orders differ is an unsound licence and this says so. A pair called :no or :unknown is merely never taken and may disagree freely. It RUNS the machine, so like `laws` it is not part of `problems`; it is what the generative soundness property over `confluence` and the `commuting` licence is written on."
  {:malli/schema [:=> {:registry {"Shape" shape/Shape, "State" [:map [:id :keyword]], "Event" [:map [:id :keyword]], "Agreement" [:map [:a-then-b [:schema [:ref "State"]]] [:b-then-a [:schema [:ref "State"]]] [:agree :boolean]]}} [:cat [:schema [:ref "Shape"]] [:schema [:ref "State"]] [:schema [:ref "Event"]] [:schema [:ref "Event"]]] [:schema [:ref "Agreement"]]] :knowledge [{:id :the-witness-runs-the-machine-and-recomputes-nothing
     :kind :decision
     :says "`reorder-agrees` is the independent witness the concurrency licence never had: it compiles the machine and steps a pair of events out of one state both ways, comparing where each order landed, and recomputes nothing `confluence` computes — so a pair the checker calls :yes whose two orders differ is an unsound licence caught by RUNNING rather than by asking. It lives here and not beside `confluence` because drive is the lowest namespace that sees `compile`; a witness placed in check rebuilt stepping from the edges, without guards or handlers, and passed its examples."
     :why "Written 2026-09-15 by robertluo.coder's authoring machine from a brief its candidate machine wrote off this record's own three confessions that the licence had been unsound and nothing had noticed; the property over generated shapes it is written for is still owed."
     :cites [:the-licence-was-unsound-and-nothing-had-noticed]}]}
  [shape state a b]
  (let [step (compile/compile shape)
        a-then-b (step (step state a) b)
        b-then-a (step (step state b) a)]
    {:a-then-b a-then-b
     :b-then-a b-then-a
     :agree (= a-then-b b-then-a)}))

(defn- owning-shape
  "The shape (this one or one of its nested machines, recursively) that actually
  declares `state-id` as one of its own states."
  [shape state-id]
  (or (when (some #{state-id} (shape/states shape)) shape)
      (some #(owning-shape % state-id) (vals (shape/machines shape)))))

(defn- gen-event-for
  "Generate one event map for `event-id` firing from `state-id` in `shape` (or one
  of its nested machines)."
  [top-shape state-id event-id]
  (let [shape (owning-shape top-shape state-id)
        trans (first (filter #(and (= (:from %) state-id) (= (:event %) event-id))
                              (shape/transitions shape)))
        schema (shape/accepted trans)]
    (assoc (mg/generate schema) :id event-id)))

(defn- gen-state-for
  "Generate one state map for `state-id` in `shape` (or one of its nested machines)."
  [top-shape state-id]
  (let [shape (owning-shape top-shape state-id)
        schema (shape/enter-schema shape state-id)]
    (assoc (mg/generate schema) :id state-id)))

(defn- pair-events
  "The two event ids a commuting pair names — the same one twice where the pair
  is a self-pair (one event proven to commute with itself), since a set of one
  collapses the duplicate."
  [pair]
  (let [es (vec pair)]
    (if (= 1 (count es))
      [(first es) (first es)]
      es)))

(defn licence-agrees
  "Whether the concurrency licence is SOUND for a shape, by running it: for every state
  `check/commuting` names and every pair of events it licenses there, a state generated
  from that state's schema and one event generated from each of the pair's event schemas
  are applied both ways through `drive/reorder-agrees`, `samples` times each, and every
  disagreement is answered — the state id, the pair, and the two landings. Answers how
  many pairs were licensed in all and the disagreements found; {:licensed n :disagreeing []}
  is a sound licence, and :licensed 0 is a shape the licence names no pair in. It RUNS
  the machine and recomputes nothing the checker computes, so a :yes whose two orders
  differ is caught here and nowhere else."
  {:malli/schema [:=> [:cat shape/Shape [:int {:min 1}]] [:map [:licensed [:int {:min 0}]] [:disagreeing [:vector :map]]]] :knowledge [{:id :the-licence-held-on-forty-generated-shapes
     :kind :lesson
     :says "`licence-agrees` runs every pair `commuting` licenses, in every state it names, both ways through `reorder-agrees` over a generated state and two generated events, and answers the disagreements; `licence-agrees-never-disagrees` asks it of forty shapes from `ts/gen-shape` and none disagreed, 2026-09-15 — the first time the licence, unsound three times before and each time found by asking, was held to a property. And a licence that names `add` with itself is right: two events whose combine commutes commute."
     :why "Written by robertluo.coder's authoring machine in state-graph's own JVM, the only one that sees this test tree. Its first run was abandoned on an example of the author's that said the summing shape licenses nothing; the machine answered one, and the example was the one that was wrong."
     :cites [:the-witness-runs-the-machine-and-recomputes-nothing :the-licence-was-unsound-and-nothing-had-noticed]}]}
  [shape samples]
  (let [commute-map (check/commuting shape)]
    (reduce
     (fn [acc [state-id pairs]]
       (reduce
        (fn [acc pair]
          (let [[e1 e2] (pair-events pair)
                disagreements
                (keep
                 (fn [_]
                   (let [st (gen-state-for shape state-id)
                         ea (gen-event-for shape state-id e1)
                         eb (gen-event-for shape state-id e2)
                         {:keys [agree a-then-b b-then-a]} (reorder-agrees shape st ea eb)]
                     (when-not agree
                       {:state state-id :pair pair :a-then-b a-then-b :b-then-a b-then-a})))
                 (range samples))]
            (-> acc
                (update :licensed inc)
                (update :disagreeing into disagreements))))
        acc
        pairs))
     {:licensed 0 :disagreeing []}
     commute-map)))

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
                  [:=> [:cat shape/Shape Run Options] Run]]
   :knowledge
   [{:id :a-proven-join-is-taken-whole
     :kind :decision
     :says "Two reportable events out of one state is a fork, and a driver that picked one would be inventing an order the shape never promised — unless every distinct pair among them is :yes in `confluence`, in which case all of them are found in one turn through :reports and applied in a fixed order the shape has said makes no difference. Anything else is :from :world and the caller settles it. A pair of the SAME event is not asked about: nothing is being chosen between."
     :why "It is the second place a static check is load-bearing at runtime — the stream door taking the licence was the first — and it is the answer to whether two writers may start in parallel: the shape says whether they may, and :reports is where a caller puts the concurrency."
     :cites [:a-join-is-the-product-and-the-licence :the-licence-is-one-machines :everything-else-is-injected]}]}
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
                  [:=> [:cat shape/Shape Run Options] Run]]
   :knowledge
   [{:id :drive-needs-no-counter
     :kind :decision
     :says "`drive` needs no counter: the stopping rule is IN THE SHAPE — a final state, a park on the world, a hold this run declared — so the loop's whole job is noticing that `step` answered what it was given. Driving from [] runs the whole machine and driving from a run carries it on; there is no second code path for resuming."
     :cites [:a-budget-in-drive-was-not-taken :a-run-is-the-vector-of-events]}]}
  ([sh events] (drive sh events {}))
  ([sh events opts]
   (loop [es (vec events)]
     (let [next (step sh es opts)]
       (if (= next es) es (recur next))))))
