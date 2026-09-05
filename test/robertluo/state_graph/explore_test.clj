(ns robertluo.state-graph.explore-test
  "Covering a graph by running it.

  THE FIXTURE HAS ONE OF EACH THING THAT CANNOT BE DRIVEN, which is the whole
  reason this namespace exists rather than a `count` at a call site: a proven
  JOIN, whose other ordering no crank takes; a WORLD event, which no driver
  reports; and a state reachable only through the ordering that is skipped. A
  report that called those three a failure would cry wolf on every real machine."
  (:require
   [clojure.test :refer [deftest is testing]]
   [robertluo.state-graph :as sg]
   [robertluo.state-graph.check :as check]
   [robertluo.state-graph.explore :as sut]
   [robertluo.state-graph.shape :as shape]))

;; ── The machine under exploration ──────────────────────────────
;;
;; :start -:begin-> :ready ==:a/:b==> :both -:check-> :done or :broken,
;; and :broken -:retry-> :ready or :given-up on a budget that is an EDGE.
;;
;; `:a` writes :x and `:b` writes :y, neither reading the other, so `:ready` is a
;; PROVEN join and the crank takes both in one turn. `:note` carries no `:report`.

(defn machine
  "A SHAPE IS A FUNCTION OF ITS ENV — which is the property the whole technique
  rests on, so the fixture has to have it. `:verdict`, `:x`, `:y` and `:budget`
  are the seams, and the last is a plain number: what may be substituted is not
  only a function."
  [env]
  (let [budget (get env :budget 1)
        ready  [:map [:round :int]]
        did-a  (conj ready [:x :int])
        did-b  (conj ready [:y :int])
        both   (conj ready [:x :int] [:y :int])]
    (sg/shape
     (sg/state :start    [:map] {:initial true})
     (sg/state :ready    ready)
     (sg/state :did-a    did-a)
     (sg/state :did-b    did-b)
     (sg/state :both     both)
     (sg/state :broken   both)
     (sg/state :done     both {:final true})
     (sg/state :given-up both {:final true})

     (sg/event :begin [:map [:round :int]]
               {:reads [:map] :report (fn [_] {:round 1})})
     (sg/event :a [:map [:x :int]]
               {:reads [:map] :report (fn [_] {:x ((:x env))})})
     (sg/event :b [:map [:y :int]]
               {:reads [:map] :report (fn [_] {:y ((:y env))})})
     ;; ROUTING INFORMATION NO STATE HOLDS, so it cannot be a pure lift.
     (sg/event :check [:map [:ok :boolean]] (constantly {}) nil
               {:reads both :report (fn [_] {:ok ((:verdict env))})})
     (sg/event :retry [:map [:round :int]]
               {:reads [:map [:round :int]] :report (fn [{:keys [round]}] {:round (inc round)})})
     ;; NO :report — only the world supplies it.
     (sg/event :note [:map])

     (sg/transition :start :begin :ready)
     (sg/transition :ready :a :did-a)
     (sg/transition :ready :b :did-b)
     (sg/transition :did-a :b :both)
     (sg/transition :did-b :a :both)
     (sg/transition :both :note :both)
     (sg/transition :both :check :done   {:when [:map [:ok [:= true]]]})
     (sg/transition :both :check :broken {:when [:map [:ok [:= false]]]})
     (sg/transition :broken :retry :ready
                    {:when [:map {:description "under budget"} [:round [:int {:max budget}]]]})
     (sg/transition :broken :retry :given-up
                    {:when [:map {:description "out of rounds"} [:round [:int {:min (inc budget)}]]]}))))

(def fixed
  "The seams that do not vary."
  {:x (constantly 1) :y (constantly 2)})

(deftest the-fixture-is-what-it-claims
  ;; ONLY ASSERT WHAT CAN FAIL — and what could fail here is the fixture drifting
  ;; until it no longer has the three shapes the report is supposed to tell apart.
  (let [sh (machine fixed)]
    (is (= [] (sg/problems sh)))
    (is (= :join (some (fn [{:keys [id verdict]}] (when (= :ready id) verdict))
                       (check/driving sh)))
        "a proven join, so the crank takes :a and :b in ONE turn and in one order")
    (is (not (contains? (set (keys (shape/reports sh))) :note))
        "and :note is the world's")))

(deftest covering-drives-and-counts-what-it-took
  (let [report (sut/covering machine fixed
                             {:verdict [(constantly true) (constantly false)]
                              :budget  [1 2]})]
    (is (= 10 (:of report)))
    (is (= 4 (:runs report)) "one run per combination — the PRODUCT, not a search")

    (testing "NOTHING IS LEFT THAT A DRIVER COULD HAVE TAKEN, which is the only
              number that means you missed something"
      (is (= [] (:gaps report))))

    (testing "and the three it did not take are the three that cannot be driven,
              one of each kind"
      (is (= {:no-report 1 :join-order 1 :unvisited-state 1}
             (frequencies (map :why (:uncovered report)))))
      (is (= 7 (:covered report))))

    (let [why (into {} (map (juxt :why :transition)) (:uncovered report))]
      (testing "the world's event is named exactly"
        (is (= [:both :note :both] (:no-report why))))

      (testing "WHICH ordering the crank skips is what `confluence` PROVES you may
                not observe, so this asserts that one of the two is skipped and
                never which — a test that pinned it would be asserting the thing
                the library promises is unobservable"
        (is (contains? #{[:ready :a :did-a] [:ready :b :did-b]} (:join-order why)))
        (is (contains? #{[:did-a :b :both] [:did-b :a :both]} (:unvisited-state why)))

        (testing "and the halfway state it skipped is the one it did not enter"
          (let [skipped (first (:unvisited-state why))]
            (is (not (contains? (:visited report) skipped)))
            (is (= #{:start :ready :both :broken :done :given-up}
                   (disj (:visited report) :did-a :did-b)))
            (is (= 1 (count (filter (:visited report) [:did-a :did-b])))
                "exactly one halfway state, because a join takes one order")))))))

(deftest a-gap-is-an-alternative-you-did-not-vary
  ;; THE REPORT EARNS ITS KEEP HERE. Hold the budget still and the way back from a
  ;; failure is never taken — and it is NOT called undrivable, because a driver
  ;; plainly could take it. That is the distinction the whole namespace is for.
  (let [report (sut/covering machine (assoc fixed :budget 1)
                             {:verdict [(constantly true) (constantly false)]})]
    (is (= [[:broken :retry :ready]] (:gaps report)))
    (is (= 2 (:runs report)))

    (testing "and varying the very thing it named closes it — a plain number, not
              a function, which is what `substitute anything in the env` means"
      (is (= [] (:gaps (sut/covering machine fixed
                                     {:verdict [(constantly true) (constantly false)]
                                      :budget  [1 2]})))))))

(deftest a-miss-is-loud-and-a-runaway-is-bounded
  ;; TWO WAYS A MACHINE MISBEHAVES UNDER EXPLORATION, and neither may be silent.
  (testing "a guard nothing satisfies is a gap between guards, and it THROWS —
            `compile` answers such an event with the state unchanged, which would
            make the crank spin here for ever in silence"
    (let [gappy (fn [env]
                  (sg/shape
                   (sg/state :start [:map] {:initial true})
                   (sg/state :done  [:map [:n :int]] {:final true})
                   (sg/event :go [:map [:n :int]]
                             {:reads [:map] :report (fn [_] {:n ((:n env))})})
                   (sg/transition :start :go :done {:when [:map [:n [:= 1]]]})))]
      (is (= [] (:gaps (sut/covering gappy {:n (constantly 1)} {}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No edge out of this state"
                            (sut/covering gappy {:n (constantly 2)} {})))))

  (testing "and a machine whose stopping rule is NOT an edge is bounded rather
            than left to hang the suite — exploration is where you find that out"
    (let [forever (fn [_]
                    (sg/shape
                     (sg/state :start [:map] {:initial true})
                     (sg/state :going [:map [:n :int]])
                     (sg/event :begin [:map [:n :int]] {:reads [:map] :report (fn [_] {:n 0})})
                     (sg/event :again [:map [:n :int]]
                               {:reads [:map [:n :int]] :report (fn [{:keys [n]}] {:n (inc n)})})
                     (sg/transition :start :begin :going)
                     (sg/transition :going :again :going)))]
      (is (= [] (:gaps (sut/covering forever {} {} {:steps 5})))
          "five turns and it stops asking, having taken both edges"))))

;;; -------------------------------------------------- a completion is scored too

(defn- outcomes
  "A host whose child can finish TWO WAYS, and drivable — one event carrying a
  verdict and two guarded edges, which is the only shape a crank can take a fork by.
  `:work` is the seam, so both ways are one `constantly` apart."
  [env]
  (let [worker (shape/shape
                (shape/state :idle  [:map [:job :string]] {:initial true})
                (shape/state :done  [:map [:job :string] [:answer :string]] {:final true})
                (shape/state :stuck [:map [:job :string]] {:final true})
                (shape/event :tried [:map [:outcome [:enum :ok :stuck]]
                                     [:answer {:optional true} :string]]
                             (fn [e] (select-keys e [:answer])) nil
                             {:reads  [:map [:job :string]]
                              :report (fn [{:keys [job]}] ((:work env) job))})
                (shape/transition :idle :tried :done  {:when [:map [:outcome [:= :ok]]]})
                (shape/transition :idle :tried :stuck {:when [:map [:outcome [:= :stuck]]]}))]
    (shape/shape
     (shape/state :start [:map] {:initial true})
     (shape/state :working [:map [:job :string] [:answer {:optional true} :string]]
                  {:machine worker
                   :seed    [:map [:job :string]]
                   :done    {:done  {:to :kept :yield [:map [:answer :string]]}
                             :stuck {:to :lost}}})
     (shape/state :kept [:map [:job :string] [:answer :string]] {:final true})
     (shape/state :lost [:map [:job :string] [:answer {:optional true} :string]] {:final true})
     (shape/event :begin [:map [:job :string]]
                  {:reads [:map] :report (fn [_] {:job "a job"})})
     (shape/transition :start :begin :working))))

(deftest a-branching-completion-is-covered-or-it-is-a-GAP
  ;; IT HAD TO BE SCORED ONCE ONE COULD BRANCH. While a :done was a single unconditional
  ;; target there was nothing to cover — the edge was taken exactly when its state was
  ;; entered — but a :done keyed by the child's final state is a FORK, and a fork no run
  ;; took is precisely what this namespace exists to name. It fires no event, so it is
  ;; never reported and is RECONSTRUCTED: a row says where the whole machine ended up,
  ;; so a host it is no longer sitting in has completed, by the branch that child's own
  ;; final state names.
  (let [ok    (constantly {:outcome :ok :answer "v"})
        stuck (constantly {:outcome :stuck})
        both  (sut/covering outcomes {} {:work [ok stuck]})
        one   (sut/covering outcomes {:work ok} {})]
    (testing "the two completions are IN the count, beside the one event edge"
      (is (= 3 (:of both)))
      (is (= 3 (:covered both)))
      (is (= [] (:gaps both))))
    (testing "and the branch no run took is a GAP — not :no-report, which is about an
              event only the WORLD can supply, and ARRIVING is not something anybody
              supplies"
      (is (= [[:working :stuck :lost]] (:gaps one)))
      (is (= 2 (:covered one))))
    (testing "the state only that branch reaches was never entered, and says so"
      (is (contains? (:visited both) :lost))
      (is (not (contains? (:visited one) :lost))))))
