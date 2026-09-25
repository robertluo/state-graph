(ns robertluo.state-graph.crank-test
  "THE CRANK: the door that FINDS its own events.

  What is asserted here is the driving RULE — which event a state can be told, who
  can supply it, and when the machine stops — over shapes built here and over shapes
  test.check invents. Nothing opens a file, a socket or a clock: a report is an
  ordinary function, so the whole of this is the fast suite."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph :as sg]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.crank :as sut]
            [robertluo.state-graph.test-support :as ts]))

(use-fixtures :once ts/instrumented)

;;; ---------------------------------------------------------------- the fixtures

(def parked
  "A machine that cannot move itself: its one event has no `:report`, so somebody
   outside has to say."
  (sg/shape (sg/state :waiting  [:map] {:initial true})
            (sg/state :approved [:map] {:final true})
            (sg/event :approve [:map])
            (sg/transition :waiting :approve :approved)))

(def escapable
  "A state a driver can advance AND a person can escape from — two events awaited
   and one of them reportable."
  (sg/shape (sg/state :running [:map] {:initial true})
            (sg/state :done    [:map] {:final true})
            (sg/state :aborted [:map] {:final true})
            (sg/event :tick    [:map] {:reads [:map] :report (constantly {})})
            (sg/event :abandon [:map])
            (sg/transition :running :tick    :done)
            (sg/transition :running :abandon :aborted)))

(def joined
  "TWO BRANCHES AND A JOIN, the product construction: both events are reportable out
   of :asked, and the two orders land in the identical state — which `confluence`
   proves and the crank then relies on."
  (sg/shape (sg/state :asked [:map] {:initial true})
            (sg/state :coded [:map [:code :string]])
            (sg/state :lawed [:map [:law :string]])
            (sg/state :both  [:map [:code :string] [:law :string]] {:final true})
            (sg/event :write [:map [:code :string]] {:reads [:map] :report (constantly {:code "c"})})
            (sg/event :draft [:map [:law :string]]  {:reads [:map] :report (constantly {:law "l"})})
            (sg/transition :asked :write :coded)
            (sg/transition :asked :draft :lawed)
            (sg/transition :coded :draft :both)
            (sg/transition :lawed :write :both)))

(def forked
  "TWO REPORTABLE EVENTS THAT GO DIFFERENT PLACES — a fork nobody proved, which is
  not the same thing as a join and must not be driven like one."
  (sg/shape (sg/state :s [:map] {:initial true})
            (sg/state :x [:map] {:final true})
            (sg/state :y [:map] {:final true})
            (sg/event :one [:map] {:reads [:map] :report (constantly {})})
            (sg/event :two [:map] {:reads [:map] :report (constantly {})})
            (sg/transition :s :one :x)
            (sg/transition :s :two :y)))

(def child
  (sg/shape (sg/state :drafting [:map] {:initial true})
            (sg/state :drafted  [:map [:law :string]] {:final true})
            (sg/event :propose [:map [:law :string]] {:reads [:map] :report (constantly {:law "l"})})
            (sg/transition :drafting :propose :drafted)))

(def nested
  "A NODE THAT IS A WHOLE MACHINE, which is what keeps a sub-problem's states out of
  its host's. The crank has to report into the machine that is actually running."
  (sg/shape (sg/state :lawing [:map] {:initial true :machine child
                                      :done :ready :yield [:map [:law :string]]})
            (sg/state :ready [:map [:law :string]] {:final true})))

;;; ---------------------------------------------------------------- the driving rule

(deftest what-a-state-awaits-is-not-what-a-driver-can-do
  ;; THE CORRECTION THAT MADE PARKING WORK. Counting what a state AWAITS stops the
  ;; first machine that offers a person a way out; what drives is how many of those
  ;; the shape gave a `:report`, and there is only ever one of those or the shape is
  ;; asking the world.
  (is (= #{:tick :abandon} (sut/awaits escapable :running)))
  (is (= {:at :running :awaits #{:tick :abandon} :from :driver :event :tick}
         (sut/awaiting escapable [])))
  (is (= [{:id :tick}] (sut/step escapable []))))

(deftest a-run-stops-for-one-of-three-reasons-and-says-which
  (testing "over"
    (is (= {:at :approved :awaits #{} :final true}
           (sut/awaiting parked (sut/advance parked [] {:id :approve})))))

  (testing "the MACHINE's park — an event only the world can supply, the same on
            every run of this shape"
    (is (= {:at :waiting :awaits #{:approve} :from :world} (sut/awaiting parked [])))
    (is (= [] (sut/step parked [])) "and a driver does not invent one"))

  (testing "THIS RUN's hold, which is not in the graph at all"
    (is (= {:at :running :awaits #{:tick :abandon} :from :driver :event :tick :held true}
           (sut/awaiting escapable [] {:permitted #{}})))
    (is (= [] (sut/step escapable [] {:permitted #{}})))
    (is (= [{:id :tick}] (sut/step escapable [] {:permitted #{:tick}})))))

(deftest a-fork-nobody-proved-is-the-world-s-to-settle
  ;; The crank may choose only where the shape has said the choice cannot be seen.
  ;; Anywhere else, picking one would be inventing an order nobody promised.
  (is (= {:at :s :awaits #{:one :two} :from :world} (sut/awaiting forked [])))
  (is (= [] (sut/step forked []))))

(deftest a-proven-join-is-taken-whole
  (is (= {:at :asked :awaits #{:write :draft} :from :driver :events [:draft :write]}
         (sut/awaiting joined [])))

  (testing "both in one turn, and the run ends where either order would have"
    (let [run (sut/step joined [])]
      (is (= [:draft :write] (mapv :id run)))
      (is (= {:id :both :code "c" :law "l"} (sut/where joined run)))))

  (testing "and the reports go through `:reports`, which is where a caller who wants
            them at once puts that"
    (let [n (atom 0)]
      (sut/step joined [] {:reports (fn [thunks] (swap! n + (count thunks)) (mapv #(%) thunks))})
      (is (= 2 @n)))))

(deftest the-crank-reports-into-the-machine-that-is-running
  ;; A NESTED NODE IS NOT A LEAF. Its host has no edge for the child's events, so a
  ;; driver reading only the host's out-edges sees a state that awaits nothing and is
  ;; not final — and parks for ever on a machine that was ready to go.
  (is (= {:at :drafting :awaits #{:propose} :within [:lawing] :from :driver :event :propose}
         (sut/awaiting nested [])))

  (testing "and one turn finishes the child, which completes the host"
    (let [run (sut/drive nested [])]
      (is (= [:propose] (mapv :id run)))
      (is (= {:id :ready :law "l"} (sut/where nested run)))))

  (testing "what `:on` is told carries the path, so a history can say where it happened"
    (let [rows (atom [])]
      (sut/drive nested [] {:on #(swap! rows conj (select-keys % [:from :to :within]))})
      (is (= [{:from :lawing :to :ready :within [:lawing]}] @rows)))))

(deftest a-person-comes-in-through-advance
  (let [rows (atom [])
        run  (sut/advance parked [] {:id :approve} {:on #(swap! rows conj %)})]
    (is (= [{:id :approve}] run))
    (is (= :approved (:id (sut/where parked run))))
    (is (= [[:waiting :approved]] (mapv (juxt :from :to) @rows))
        "and it is told, which is what makes an approval auditable")))

(deftest driving-stops-and-carries-on
  (is (= [:draft :write] (mapv :id (sg/drive joined []))))
  (is (= [] (sg/drive parked [])) "a park stops the loop as surely as an ending")
  (testing "carrying on is the same reduction, so there is no second code path"
    (is (= (sg/drive joined []) (sg/drive joined (sg/step joined []))))))

;;; ---------------------------------------------------------------- the properties

(defn- cranked
  "n turns of the crank, bounded — a generated shape may well be a cycle every event
   of which is reportable, which is an infinite machine and correctly so."
  [sh n]
  (nth (iterate #(sut/step sh %) []) n))

(defspec the-crank-stops-only-for-a-reason-the-shape-gives 100
  ;; THE ONE THAT IS NEVER VACUOUS, and the whole contract: a run that is not moving
  ;; is over, waiting on the world, or held — never merely stuck. A crank that gave up
  ;; on a state it did not understand would fail this and nothing else would notice.
  (prop/for-all [parts ts/gen-driven-shape
                 n (gen/choose 0 8)]
    (let [sh  (apply sg/shape parts)
          run (cranked sh n)]
      (or (not= run (sut/step sh run))
          (let [t (sut/awaiting sh run)]
            (boolean (or (:final t) (= :world (:from t)) (:held t))))))))

(defspec the-crank-never-applies-an-event-the-state-does-not-admit 100
  ;; INDEPENDENT OF HOW IT CHOSE: whatever the crank decided to report, the machine
  ;; had an edge for it where it was applied. This is what a driver reporting into the
  ;; wrong machine breaks, and it is checked against the GRAPH rather than against the
  ;; driver's own idea of what it was doing.
  (prop/for-all [parts ts/gen-driven-shape
                 n (gen/choose 0 8)]
    (let [sh  (apply sg/shape parts)
          run (cranked sh n)]
      (every? (fn [i]
                (let [before (sut/where sh (subvec run 0 i))]
                  (contains? (sut/awaits sh (:id before)) (:id (nth run i)))))
              (range (count run))))))

(defspec the-crank-has-no-memory 100
  ;; Which is what makes resuming a parked run the same thing as never having stopped:
  ;; n turns and then m is n+m turns, because a turn is a function of the run alone.
  (prop/for-all [parts ts/gen-driven-shape
                 n (gen/choose 0 4)
                 m (gen/choose 0 4)]
    (let [sh (apply sg/shape parts)]
      (= (cranked sh (+ n m))
         (nth (iterate #(sut/step sh %) (cranked sh n)) m)))))

(defspec what-the-crank-does-is-what-the-shape-said-it-would 100
  ;; THE TWO DOORS ONTO ONE QUESTION AGREE. `check/driving` answers who can move a state
  ;; from the GRAPH, before anything runs; `awaiting` answers it of a RUNNING machine. They
  ;; are written separately and neither is derived from the other, so this is the only thing
  ;; that can catch one drifting from the other — and a static check nobody can rely on is a
  ;; static check nobody runs.
  (prop/for-all [parts ts/gen-driven-shape
                 n (gen/choose 0 6)]
    (let [sh   (apply sg/shape parts)
          run  (cranked sh n)
          t    (sut/awaiting sh run)
          said (:verdict (first (filter #(= (:id %) (:at t)) (check/driving sh))))]
      (= (case said
           (:driver :join) :driver
           (:world :fork)  :world
           :final          :final)
         (cond (:final t)                :final
               (= :driver (:from t))     :driver
               :else                     :world)))))
