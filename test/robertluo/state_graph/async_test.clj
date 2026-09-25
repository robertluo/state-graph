(ns robertluo.state-graph.async-test
  (:require [clojure.core.async :as ca]
            [clojure.core.async.impl.protocols :as p]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph.async :as a]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.compile :as c]
            [robertluo.state-graph.shape :as shape]
            [robertluo.state-graph.test-support :as ts]))

(use-fixtures :once ts/instrumented)

(def ^:private patience 5000)

(defn- wait
  "What `ch` delivers, or ::timeout. BOUNDED, so a machine that hangs fails a test instead of
   hanging the suite — which matters more here than anywhere else in this project, channels
   being the one thing that can wait for ever."
  [ch]
  (let [[v port] (ca/alts!! [ch (ca/timeout patience)])]
    (if (= port ch) v ::timeout)))

(defn- collect
  "Every state a machine produced, and what its :done delivered. :states first, because
   backpressure is real."
  [{:keys [states done]}]
  {:states (wait (ca/into [] states))
   :done   (wait done)})

(defn- fed
  "A channel carrying exactly these events, then closed. BUFFERED, so the puts complete
   with nobody consuming yet and a test can arrange everything before it starts reading."
  [events]
  (let [in (ca/chan (max 1 (count events)))]
    (ca/onto-chan!! in events)
    in))

(defn- hand!
  "Put `event` on an UNBUFFERED channel and answer only once the machine has TAKEN it —
   true, or false where it did not within the patience. What makes a race decided by the
   test and not by a clock: once the second event of a pair is taken, the machine has
   already chosen."
  [in event]
  (let [[v port] (ca/alts!! [[in event] (ca/timeout patience)])]
    (and (= port in) v)))

(defn- now
  "A value already available, as a handler under a/context may answer one."
  [x]
  (doto (ca/promise-chan) (ca/put! x)))

;;; --------------------------------------------------------------------- drive

(deftest an-event-stream-becomes-a-state-stream
  (let [sh (ts/counter)
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                              (fed [{:id :start :seed 0} {:id :set :to 7} {:id :stop}])))]
    (is (= [{:id :running :n 0} {:id :running :n 7} {:id :done :n 7}] (:states got)))
    (is (= {:id :done :n 7} (:done got)) ":done carries the last state, not merely nil")))

(deftest a-handler-may-answer-later
  ;; What a/context is FOR: the handler answers a channel, so the step answers one, and the
  ;; reduction is unbothered. a/blocking runs the same shape SYNCHRONOUSLY by taking from
  ;; it — two Contexts, one step, and the core knowing nothing of either.
  (let [sh (shape/shape
            (shape/state :a [:map] {:initial true})
            (shape/state :b [:map [:n :int]] {:final true})
            (shape/event :go [:map] (fn [_] (now {:n 7})) [:map [:n :int]])
            (shape/transition :a :go :b))
        step (c/compile sh a/context)]
    (testing "under this Context the step itself answers a channel"
      (is (satisfies? p/ReadPort (step (c/initial sh {}) {:id :go}))))
    (testing "and the same shape run synchronously through a/blocking takes from it instead"
      (is (= {:id :b :n 7} ((c/compile sh a/blocking) (c/initial sh {}) {:id :go}))))
    (is (= {:states [{:id :b :n 7}] :done {:id :b :n 7}}
           (collect (a/drive step (c/initial sh {}) (fed [{:id :go}])))))))

(deftest a-handler-may-fail-by-delivering-an-exception
  ;; A channel carries no error of its own, so a handler that fails LATER delivers the
  ;; exception, and it reaches :done as the same object — under the stream door and under
  ;; a/blocking alike, where it is thrown.
  (let [boom (ex-info "the model refused" {:reason :quota})
        sh (shape/shape
            (shape/state :a [:map] {:initial true})
            (shape/state :b [:map [:n :int]] {:final true})
            (shape/event :go [:map] (fn [_] (now boom)) [:map [:n :int]])
            (shape/transition :a :go :b))]
    (is (identical? boom (:done (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                                                  (fed [{:id :go}])))))
        "the SAME exception, so nothing it carried was lost on the way")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the model refused"
                          ((c/compile sh a/blocking) (c/initial sh {}) {:id :go})))))

(deftest an-event-nobody-handled-still-answers-a-state
  ;; The reduction stays total, so there is one state per event and a consumer counting
  ;; them is not misled about how much arrived.
  (let [sh (ts/counter)
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                              (fed [{:id :stop} {:id :start :seed 3}])))]
    (is (= [{:id :idle} {:id :running :n 3}] (:states got))
        ":stop from :idle changed nothing, and said so by answering the state unchanged")))

(deftest a-defect-reaches-done-and-closes-the-states
  ;; A crossing that does not hold is a DEFECT, so it belongs on :done — as the exception
  ;; compile threw, with everything it said. And :states MUST close, or a consumer waits
  ;; for ever on a machine that has already stopped — which is the worst failure a stream
  ;; layer has available to it.
  (let [sh (shape/shape
            (shape/state :a [:map] {:initial true})
            (shape/state :b [:map [:n :int]] {:final true})
            (shape/event :go [:map] (constantly {:n "seven"}) [:map [:n :int]])
            (shape/transition :a :go :b))
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {}) (fed [{:id :go}])))]
    (is (= [] (:states got)) "closed, and closed EMPTY — the bad state was never a state")
    (is (instance? clojure.lang.ExceptionInfo (:done got)))
    (is (= {:from :a :event :go :to :b :crossing :out} (select-keys (ex-data (:done got))
                                                                    [:from :event :to :crossing]))
        "and nothing compile said about it was thrown away")))

;;; ----------------------------------------------------------------------- fan

(deftest fan-gives-every-instance-its-own-reduction
  (let [sh (ts/counter)
        got (collect (a/fan (c/compile sh a/context) (fn [k] (c/initial sh k {}))
                            (fed [{:id :start :seed 1   :instance "a"}
                                  {:id :start :seed 100 :instance "b"}
                                  {:id :set   :to 5     :instance "a"}
                                  {:id :stop            :instance "b"}
                                  {:id :stop            :instance "a"}])))]
    (testing "one state per event and NONE LOST, which is the s/connect race exactly:
              the first version forwarded each machine's states through s/connect, and
              closing the shared output after every machine reported done dropped whatever
              was still in a pipeline"
      (is (= 5 (count (:states got)))))

    (is (= {"a" [[:running 1] [:running 5] [:done 5]]
            "b" [[:running 100] [:done 100]]}
           (into {} (map (fn [[k v]] [k (mapv (juxt :id :n) v)]))
                 (group-by :instance (:states got))))
        "each machine reduced its OWN events and none of anybody else's")

    (testing ":done IS KEYED BY INSTANCE and waits for every machine, so a state in flight
              when the events run out is still a state"
      (is (= {"a" {:id :done :instance "a" :n 5}
              "b" {:id :done :instance "b" :n 100}}
             (:done got))))))

(deftest fan-answers-an-empty-done-where-no-event-ever-arrived
  ;; There is no machine until an event names one, so there is nothing to report on.
  (let [sh (ts/counter)]
    (is (= {:states [] :done {}}
           (collect (a/fan (c/compile sh a/context) (fn [k] (c/initial sh k {})) (fed [])))))))

(deftest an-unnamed-event-is-its-own-machine
  ;; A caller who never names anything still works, and the partition is the one machine.
  (let [sh (ts/counter)
        got (collect (a/fan (c/compile sh a/context) (fn [k] (c/initial sh k {}))
                            (fed [{:id :start :seed 2} {:id :stop}])))]
    (is (= [{:id :running :n 2} {:id :done :n 2}] (:states got))
        "and no :instance key appears, because nobody supplied one")))

(deftest a-stopped-machine-stops-the-fan
  ;; An event routed to a machine that has already stopped on a defect is not waited on for
  ;; ever: the fan delivers that machine's exception and closes the results.
  (let [sh (shape/shape
            (shape/state :a [:map] {:initial true})
            (shape/state :b [:map [:n :int]] {:final true})
            (shape/event :go [:map] (constantly {:n "seven"}) [:map [:n :int]])
            (shape/transition :a :go :b)
            (shape/transition :b :go :b))
        got (collect (a/fan (c/compile sh a/context) (fn [k] (c/initial sh k {}))
                            (fed [{:id :go :instance "x"} {:id :go :instance "x"}
                                  {:id :go :instance "x"}])))]
    (is (vector? (:states got)) "the results closed")
    (is (= :out (:crossing (ex-data (:done got))))
        "and :done is the defect that stopped the machine, as compile threw it")))

;;; -------------------------------------------------------------------- result

(deftest what-goes-on-the-output-is-the-callers-to-decide
  ;; The injection that lets a layer above put something richer than a state: whether an
  ;; event FIRED is shape knowledge, and this layer has none — so it is handed a maker and
  ;; asks no questions. It sees the state applied TO, the event, and the state that came back.
  (let [sh (ts/counter)
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                              (fed [{:id :start :seed 1} {:id :stop}])
                              (fn [state event state'] [(:id state) (:id event) (:id state')])))]
    (is (= [[:idle :start :running] [:running :stop :done]] (:states got)))
    (is (= {:id :done :n 1} (:done got))
        ":done is the final STATE and never a result — the state is the accumulator")))

;;; --------------------------------------------------------------- the ordering

(deftest ^:integration serialisation-survives-a-slow-handler
  ;; The ordering guarantee, and it needs a handler that really is slower — hence
  ;; ^:integration, this being the one test here that depends on a clock. The ASSERTION is
  ;; about order rather than timing, so it does not care how slow: if the reduction were
  ;; not serialised the fast event could overtake, and nothing takes from `events` while a
  ;; handler is still in flight.
  (let [sh (shape/shape
            ;; :mark IS DECLARED, because a node holds what it declares and this one holds a
            ;; mark. Under a bare [:map] the projection on entry would drop it — which is the
            ;; whole point of projecting, and this fixture was simply under-declared before.
            (shape/state :s [:map [:mark {:optional true} :keyword]] {:initial true})
            (shape/event :slow [:map] (fn [_] (ca/thread (Thread/sleep 150) {:mark :slow}))
                         [:map [:mark :keyword]])
            (shape/event :fast [:map] (constantly {:mark :fast}) [:map [:mark :keyword]])
            (shape/transition :s :slow :s)
            (shape/transition :s :fast :s))
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                              (fed [{:id :slow} {:id :fast}])))]
    (is (= [:slow :fast] (mapv :mark (:states got)))
        "the slow one still landed first")))

;;; -------------------------------------------------------------- the licence

(defn- join
  "Two branches and a join. :eval is fed first, and the test decides which of the two
   patches the machine sees land first — see `cranked`."
  []
  (shape/shape
   (shape/state :verifying [:map] {:initial true})
   (shape/state :evaled    [:map [:eval :int]])
   (shape/state :tested    [:map [:test :int]])
   (shape/state :complete  [:map [:eval :int] [:test :int]] {:final true})
   (shape/event :eval [:map] (constantly {:eval 1}) [:map [:eval :int]])
   (shape/event :test [:map] (constantly {:test 2}) [:map [:test :int]])
   (shape/transition :verifying :eval :evaled)
   (shape/transition :verifying :test :tested)
   (shape/transition :tested    :eval :complete)
   (shape/transition :evaled    :test :complete)))

(defn- licensed
  "What a layer that knows the shape hands down: the two phases and somebody else's proof.
   Built here rather than mocked, so this asserts the real `check/commuting` value."
  [sh ph]
  {:patch (:patch ph) :apply (:apply ph) :agree (:agree ph)
   :pairs (check/commuting sh)})

(defn- scripted
  "Drive a licensed machine whose handlers finish WHEN THE TEST SAYS, by a script, and
   collect. A step of the script is one of

     [:event e]        hand the machine an event
     [:land i]         land the patch of the i-th licensed :patch call, as the real one computes it
     [:land i value]   land `value` in its place — an exception, or a patch the target refuses
     [:close]          close the events

   and :acks says, per step, whether the machine TOOK what was handed within the patience.

   THE LICENCE'S :patch ANSWERS AN UNBUFFERED CHANNEL per call, and the test puts on it
   the patch the real one computes — so each delivery is a RENDEZVOUS with the very take
   that decides the race, and returns only once the machine has made it. A gate opened
   after the events are taken is not enough under core.async: the machine runs on another
   thread, and may look at the two handlers only after BOTH have answered, when the order
   they finished in can no longer be seen. See :a-rendezvous-decides-a-race-a-gate-does-not.

   The patches are computed from `initial`, which is where both of a pair are selected, and
   the i-th call is the i-th event the licensed branch patches."
  [sh initial script]
  (let [ph      (c/phases sh a/context)
        events  (for [[op x] script :when (= op :event)] x)
        patches (mapv #(let [p ((:patch ph) initial %)]
                         (if (satisfies? p/ReadPort p) (wait p) p))
                      events)
        chans   (vec (repeatedly (count events) ca/chan))
        calls   (atom -1)
        in      (ca/chan)
        m       (a/drive (:step ph) initial in a/state-only
                         (assoc (licensed sh ph) :patch (fn [_ _] (chans (swap! calls inc)))))
        ;; DRAINED WHILE THE SCRIPT RUNS: the output is unbuffered, so a machine whose
        ;; rows nobody reads takes no further event — see
        ;; :consume-states-or-done-may-never-resolve.
        rows    (ca/into [] (:states m))
        acks    (mapv (fn [[op x & v]]
                        (case op
                          :event (hand! in x)
                          :land  (hand! (chans x) (if (seq v) (first v) (patches x)))
                          :close (do (ca/close! in) true)))
                      script)]
    (ca/close! in)
    {:states (wait rows) :done (wait (:done m)) :acks acks}))

(defn- cranked
  "`scripted`, for the common case: hand every event, then land the patches in `order`."
  [sh initial events order]
  (scripted sh initial (concat (map (fn [e] [:event e]) events)
                               (map (fn [i] [:land i]) order))))

(deftest a-licensed-pair-is-applied-in-completion-order
  ;; THE WHOLE POINT OF THE SPLIT, and deterministic without a clock: :eval is fed first
  ;; and :test's patch lands first, so the machine applies :test to :verifying while :eval
  ;; is still in flight, which one step doing both halves at once could not express.
  (let [sh  (join)
        got (cranked sh (c/initial sh {}) [{:id :eval} {:id :test}] [1 0])]
    (is (= {:verifying #{#{:eval :test}}} (check/commuting sh))
        "the pair really is licensed, so the branch under test is the one taken")
    (is (every? true? (:acks got))
        "both events were taken while neither had landed, and each patch by the machine")
    (is (= [{:id :tested :test 2} {:id :complete :eval 1 :test 2}] (:states got))
        ":test landed first though :eval arrived first, and :eval's patch — computed in
         :verifying, where its target was :evaled — landed in :complete instead")
    (is (= {:id :complete :eval 1 :test 2} (:done got)))))

(deftest the-licence-changes-the-order-and-never-the-destination
  ;; The same shape and the same events, driven both ways. Serialised it goes through
  ;; :evaled; licensed with :test landing first it goes through :tested. TWO ROUTES, ONE
  ;; DESTINATION — which is what `commutes` proved before anything ran, and the reason the
  ;; row order may be traded away at all.
  (let [sh     (join)
        serial (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                                 (fed [{:id :eval} {:id :test}])))
        got    (cranked sh (c/initial sh {}) [{:id :eval} {:id :test}] [1 0])]
    (is (= [{:id :evaled :eval 1} {:id :complete :eval 1 :test 2}] (:states serial))
        "with no licence, strictly the order it was fed")
    (is (= [{:id :tested :test 2} {:id :complete :eval 1 :test 2}] (:states got))
        "licensed with :test landing first, it goes through :tested instead")
    (is (= (:done serial) (:done got))
        "and both doors end in the same state, by different routes")))

(deftest an-unlicensed-pair-is-never-run-at-once
  ;; The licence is a set lookup of somebody else's proof and nothing is inferred here.
  ;; `counter` has no commuting pair at all — :set and :stop in :running do NOT commute —
  ;; so passing a licence built from it changes nothing that happens.
  (let [sh (ts/counter)
        ph (c/phases sh a/context)]
    (is (= {} (:pairs (licensed sh ph))) "nothing to license")
    (is (= [{:id :running :n 0} {:id :running :n 7} {:id :done :n 7}]
           (:states (collect (a/drive (:step ph) (c/initial sh {})
                                      (fed [{:id :start :seed 0} {:id :set :to 7} {:id :stop}])
                                      a/state-only (licensed sh ph)))))
        "so a licence with no pair in it drives exactly as no licence does")))

(deftest ^:integration two-licensed-handlers-cost-one-of-them
  ;; THE PAYOFF, and it needs a real clock — hence ^:integration, following the one other
  ;; test here that depends on one. Two handlers of 300ms each, on a pair proven to be
  ;; order-indifferent: serialised that is ~600ms and licensed it is ~300ms. The bound is
  ;; loose because the assertion is about which of the two regimes is running, not about
  ;; how fast this machine is.
  (let [slow (fn [k] (fn [_] (ca/thread (Thread/sleep 300) {k 1})))
        sh (shape/shape
            (shape/state :verifying [:map] {:initial true})
            (shape/state :evaled    [:map [:eval :int]])
            (shape/state :tested    [:map [:test :int]])
            (shape/state :complete  [:map [:eval :int] [:test :int]] {:final true})
            (shape/event :eval [:map] (slow :eval) [:map [:eval :int]])
            (shape/event :test [:map] (slow :test) [:map [:test :int]])
            (shape/transition :verifying :eval :evaled)
            (shape/transition :verifying :test :tested)
            (shape/transition :tested    :eval :complete)
            (shape/transition :evaled    :test :complete))
        ph (c/phases sh a/context)
        run (fn [licence]
              (let [t0 (System/currentTimeMillis)
                    got (collect (a/drive (:step ph) (c/initial sh {})
                                          (fed [{:id :eval} {:id :test}])
                                          a/state-only licence))]
                [(- (System/currentTimeMillis) t0) (:done got)]))
        [serial-ms serial-end]   (run nil)
        [conc-ms   conc-end]     (run (licensed sh ph))]
    (is (= {:id :complete :eval 1 :test 1} serial-end conc-end)
        "both regimes land in the same state, which is what makes the trade legal")
    (is (>= serial-ms 600) "serialised, the two handlers cost both of them")
    (is (< conc-ms 500) "licensed, they cost one")))

;;; ------------------------------------------------------- the combines at once

(deftest two-events-writing-one-key-can-now-run-at-once
  ;; WHAT THE COMBINE BOUGHT, end to end. Under a naive merge :offer-a and :offer-b could
  ;; never be licensed — they write the same key, and which landed second decided the
  ;; answer. With a commutative combine on the node they are licensed, and the result is
  ;; the better offer whichever handler finished first.
  (let [sh (shape/shape
            (shape/state :choosing
                         [:map [:best {:optional true
                                       :combine ts/better
                                       :combine/commutes true} ts/Impl]]
                         {:initial true})
            (shape/event :offer-a [:map]
                         (constantly {:best {:score 5 :by "a"}}) [:map [:best ts/Impl]])
            (shape/event :offer-b [:map]
                         (constantly {:best {:score 9 :by "b"}}) [:map [:best ts/Impl]])
            (shape/transition :choosing :offer-a :choosing)
            (shape/transition :choosing :offer-b :choosing))
        got (cranked sh (c/initial sh {}) [{:id :offer-a} {:id :offer-b}] [1 0])]
    (is (contains? (get (check/commuting sh) :choosing) #{:offer-a :offer-b})
        "one key, two writers, licensed")
    (is (= [{:id :choosing :best {:score 9 :by "b"}}
            {:id :choosing :best {:score 9 :by "b"}}] (:states got))
        ":offer-b landed first and :offer-a's lower score did not displace it")
    (is (= {:id :choosing :best {:score 9 :by "b"}} (:done got)))))

(deftest a-false-promise-stops-the-machine-rather-than-flaking
  ;; The whole reason :agree is not optional. A combine declared commutative that is not
  ;; would otherwise produce an order-dependent answer silently — the one failure this
  ;; library refuses. It is a DEFECT, so it reaches :done and closes the results, and it
  ;; does so BEFORE either patch lands, which is why :states is empty.
  (let [sticky (fn [x y] (if (= "pinned" (:by x)) x (ts/better x y)))
        sh (shape/shape
            (shape/state :s [:map [:best {:combine sticky :combine/commutes true} ts/Impl]]
                         {:initial true})
            (shape/event :p [:map]
                         (constantly {:best {:score 5 :by "pinned"}}) [:map [:best ts/Impl]])
            (shape/event :q [:map]
                         (constantly {:best {:score 9 :by "z"}}) [:map [:best ts/Impl]])
            (shape/transition :s :p :s)
            (shape/transition :s :q :s))
        got (cranked sh (c/initial sh {:best {:score 0 :by "m"}}) [{:id :p} {:id :q}] [1 0])]
    (is (= [] (:states got)) "closed, and closed EMPTY — the check runs before either patch lands")
    (is (= :combine (:crossing (ex-data (:done got))))
        "and :done is the exception :agree threw, with everything it said")))

;;; ---------------------------------------------------------- the fan-out licence

(deftest two-of-ONE-event-run-at-once-where-the-accumulator-commutes
  ;; THE FAN-OUT, and what the licence was refusing until 2026-09-03. Two reports of the
  ;; same kind, and neither can land until BOTH patches are in — which is what makes :agree
  ;; a genuine pre-condition. Landed out of order on purpose, so the assertion is about the
  ;; ANSWER and never about a clock.
  (let [reports (atom [#{1} #{2}])
        sh (shape/shape
            (shape/state :gathering
                         [:map [:seen {:combine into :combine/commutes true} [:set :int]]]
                         {:initial true})
            (shape/event :found [:map]
                         (fn [_] (let [[r & more] @reports] (reset! reports more) {:seen r}))
                         [:map [:seen [:set :int]]])
            (shape/transition :gathering :found :gathering))
        got (cranked sh (c/initial sh {:seen #{}}) [{:id :found} {:id :found}] [1 0])]
    (is (= {:gathering #{#{:found}}} (check/commuting sh))
        "a SINGLETON licence, which is the fan-out one")
    (is (= #{1 2} (:seen (:done got)))
        "set union, so which worker finished first is not in the answer")
    (is (= 2 (count (:states got))) "one row per event, still")))

;;; ------------------------------------------------------- every way to fail
;;
;; A bug in a go block is the hardest kind in this library to find after the fact: an
;; exception that escapes one is printed on another thread and the machine simply stops.
;; So every path by which a machine can stop is asserted here, each by the channel it
;; stops on, and each asserts that :states CLOSED and :done DELIVERED — a timeout on
;; either is the failure these tests exist to catch.

(defn- two-state
  "A to b on :go, whose handler is `handler`."
  [handler]
  (shape/shape
   (shape/state :a [:map] {:initial true})
   (shape/state :b [:map [:n :int]] {:final true})
   (shape/event :go [:map] handler [:map [:n :int]])
   (shape/transition :a :go :b)))

(deftest a-handler-channel-closed-empty-is-a-defect
  ;; A handler that answered NOTHING — its channel closed with no value — would otherwise
  ;; hand nil on to fail one seam later with a worse diagnosis.
  (let [sh (two-state (fn [_] (doto (ca/chan) ca/close!)))
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {}) (fed [{:id :go}])))]
    (is (= [] (:states got)))
    (is (= :channel (:crossing (ex-data (:done got)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed without answering"
                          ((c/compile sh a/blocking) (c/initial sh {}) {:id :go}))
        "and a/blocking says the same thing, by throwing")))

(deftest a-late-answer-is-checked-as-strictly-as-a-prompt-one
  ;; The :out check runs INSIDE a go block when the answer came on a channel, so its throw
  ;; is the one the boundary in `then` exists for.
  (let [sh (two-state (fn [_] (ca/go {:n "seven"})))
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {}) (fed [{:id :go}])))]
    (is (= [] (:states got)))
    (is (= {:from :a :event :go :to :b :crossing :out}
           (select-keys (ex-data (:done got)) [:from :event :to :crossing])))))

(deftest blocking-leaves-a-plain-answer-alone
  (let [sh (two-state (constantly {:n 7}))]
    (is (= ((c/compile sh) (c/initial sh {}) {:id :go})
           ((c/compile sh a/blocking) (c/initial sh {}) {:id :go})
           {:id :b :n 7}))))

(deftest a-result-maker-that-fails-stops-the-machine
  ;; `result` is the caller's and runs inside the pump: throwing, or answering nil — which
  ;; no channel will carry — is a defect like any other.
  (let [sh (ts/counter)
        boom (ex-info "no row" {})
        drive-with (fn [result] (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                                                  (fed [{:id :start :seed 0}]) result)))]
    (let [got (drive-with (fn [& _] (throw boom)))]
      (is (= [] (:states got)))
      (is (identical? boom (:done got))))
    (let [got (drive-with (constantly nil))]
      (is (= [] (:states got)))
      (is (instance? IllegalArgumentException (:done got))))))

;;; ------------------------------------------------ every branch of the licence

(deftest a-pair-ready-at-once-lands-in-arrival-order
  ;; Both handlers answer at once, so by the time the machine looks the order they finished
  ;; in cannot be seen — and :priority then keeps the order they ARRIVED in, which is the
  ;; row order a consumer would have got with no licence at all.
  (let [sh (join)
        ph (c/phases sh a/context)
        got (collect (a/drive (:step ph) (c/initial sh {}) (fed [{:id :eval} {:id :test}])
                              a/state-only (licensed sh ph)))]
    (is (= [:evaled :complete] (mapv :id (:states got))))))

(deftest a-pair-landing-in-arrival-order-is-applied-in-it
  ;; The other side of the race `a-licensed-pair-is-applied-in-completion-order` decides.
  (let [sh (join)
        got (cranked sh (c/initial sh {}) [{:id :eval} {:id :test}] [0 1])]
    (is (every? true? (:acks got)))
    (is (= [{:id :evaled :eval 1} {:id :complete :eval 1 :test 2}] (:states got)))))

(deftest an-event-that-arrives-first-but-is-no-pair-is-held-and-not-lost
  ;; A second :eval is not licensed beside the first. It won the race, so it is HELD and
  ;; applied next, to the state the first one produced — and nothing is taken twice.
  (let [sh (join)
        got (scripted sh (c/initial sh {})
                      [[:event {:id :eval}] [:event {:id :eval}] [:land 0] [:event {:id :test}]])]
    (is (every? true? (:acks got)))
    (is (= [{:id :evaled :eval 1} {:id :evaled :eval 1} {:id :complete :eval 1 :test 2}]
           (:states got))
        "the held :eval was applied in :evaled, which ignores it, and :test after it")))

(deftest the-events-running-out-mid-handler-lose-nothing
  ;; The race can be won by the events CLOSING: the handler in flight still lands.
  (let [sh (join)
        got (scripted sh (c/initial sh {}) [[:event {:id :eval}] [:close] [:land 0]])]
    (is (every? true? (:acks got)))
    (is (= [{:id :evaled :eval 1}] (:states got)))
    (is (= {:id :evaled :eval 1} (:done got)))))

(deftest a-pair-whose-handler-fails-stops-before-either-lands
  (let [sh (join)
        boom (ex-info "eval crashed" {})
        got (scripted sh (c/initial sh {})
                      [[:event {:id :eval}] [:event {:id :test}] [:land 1] [:land 0 boom]])]
    (is (every? true? (:acks got)))
    (is (= [] (:states got)) "neither landed — :test's patch waited for :eval's")
    (is (identical? boom (:done got)))))

(deftest a-pair-whose-first-patch-its-target-refuses-stops-there
  (let [sh (join)
        got (scripted sh (c/initial sh {})
                      [[:event {:id :eval}] [:event {:id :test}]
                       [:land 1 {:answer {:test "two"} :depth 0}] [:land 0]])]
    (is (every? true? (:acks got)))
    (is (= [] (:states got)))
    (is (= :answer (:crossing (ex-data (:done got)))))))

(deftest a-malformed-event-in-a-licensed-state-is-a-defect
  ;; Where a state has a licensed pair the event goes to :patch directly, whose event check
  ;; throws BEFORE any channel exists — the pump's own boundary is what catches it.
  (let [sh (shape/shape
            (shape/state :verifying [:map] {:initial true})
            (shape/state :evaled    [:map [:eval :int]])
            (shape/state :tested    [:map [:test :int]])
            (shape/state :complete  [:map [:eval :int] [:test :int]] {:final true})
            (shape/event :eval [:map] (constantly {:eval 1}) [:map [:eval :int]])
            (shape/event :test [:map [:by :string]] (constantly {:test 2}) [:map [:test :int]])
            (shape/transition :verifying :eval :evaled)
            (shape/transition :verifying :test :tested)
            (shape/transition :tested    :eval :complete)
            (shape/transition :evaled    :test :complete))
        ph (c/phases sh a/context)
        got (collect (a/drive (:step ph) (c/initial sh {}) (fed [{:id :test}])
                              a/state-only (licensed sh ph)))]
    (is (seq (:verifying (check/commuting sh))) "the licensed branch is the one taken")
    (is (= [] (:states got)))
    (is (= :event (:crossing (ex-data (:done got)))))))

(deftest a-machine-goes-on-after-a-pair
  ;; After a pair lands the machine is in a state with a licence again, and a third event
  ;; takes the single-handler side of the race.
  (let [reports (atom [#{1} #{2} #{3}])
        sh (shape/shape
            (shape/state :gathering
                         [:map [:seen {:combine into :combine/commutes true} [:set :int]]]
                         {:initial true})
            (shape/event :found [:map]
                         (fn [_] (let [[r & more] @reports] (reset! reports more) {:seen r}))
                         [:map [:seen [:set :int]]])
            (shape/transition :gathering :found :gathering))
        got (scripted sh (c/initial sh {:seen #{}})
                      [[:event {:id :found}] [:event {:id :found}] [:land 1] [:land 0]
                       [:event {:id :found}] [:land 2]])]
    (is (every? true? (:acks got)))
    (is (= 3 (count (:states got))))
    (is (= #{1 2 3} (:seen (:done got))))))

;;; --------------------------------------------------------- every way a fan stops

(deftest a-machine-failing-on-its-last-event-fails-the-fan
  ;; The other road to a stopped machine: nothing more is routed to it, and the fan finds
  ;; the defect only when it waits for every machine at the end.
  (let [sh (two-state (constantly {:n "seven"}))
        got (collect (a/fan (c/compile sh a/context) (fn [k] (c/initial sh k {}))
                            (fed [{:id :go :instance "x"}])))]
    (is (= [] (:states got)))
    (is (= :out (:crossing (ex-data (:done got)))))))

(deftest a-first-state-that-cannot-be-made-fails-the-fan
  ;; `initial-of` is the caller's and runs inside the fan's own go block — a first state
  ;; its schema refuses, for one. This HUNG for ever before the fan had a boundary.
  (let [sh (ts/counter)
        boom (ex-info "no first state" {})
        got (collect (a/fan (c/compile sh a/context) (fn [_] (throw boom))
                            (fed [{:id :start :seed 0}])))]
    (is (= [] (:states got)))
    (is (identical? boom (:done got)))))

;;; ------------------------------------------ whatever a handler answers, generated

(def ^:private modes
  "Every way a handler can answer under a/context: at once, on a ready channel, from a go
   block, from a real thread — or by delivering an exception."
  [:plain :now :go :thread :fails])

(defn- answering
  [mode boom]
  (case mode
    :plain  (constantly {})
    :now    (fn [_] (now {}))
    :go     (fn [_] (ca/go {}))
    :thread (fn [_] (ca/thread {}))
    :fails  (fn [_] (ca/go boom))))

(defn- rewired
  "The generated parts with every event's handler answering as `mode-of` says, and failing
   with its own exception from `booms`."
  [parts mode-of booms]
  (map (fn [p]
         (if (= :event (:robertluo.state-graph.shape/kind p))
           (shape/event (:id p) [:map] (answering (mode-of (:id p)) (booms (:id p))))
           p))
       parts))

(def ^:private gen-modes
  (gen/fmap #(zipmap [:e0 :e1 :e2 :e3] %) (gen/vector (gen/elements modes) 4)))

(def ^:private booms
  (into {} (for [id [:e0 :e1 :e2 :e3]] [id (ex-info "handler failed" {:event id})])))

(defspec serialised-the-states-are-the-reduction-s-whatever-a-handler-answers 60
  ;; An INDEPENDENT reference: the same shape with every handler answering {} at once,
  ;; reduced. Serialised, a machine must emit exactly the states that reduction passes
  ;; through, however late each answer came — and where a handler FAILS, exactly the
  ;; prefix before the first failing event that FIRED, with that very exception on :done.
  ;; `admits?` on the reference says which events fire, so no machine is consulted twice.
  (prop/for-all [parts ts/gen-shape
                 mode-of gen-modes
                 events (gen/vector ts/gen-event 0 8)]
    (let [ref     (apply shape/shape parts)
          sh      (apply shape/shape (rewired parts #(mode-of % :plain) booms))
          idx     (c/index ref)
          before  (vec (reductions (c/compile ref) (c/initial ref {}) events))
          fail-at (first (keep-indexed (fn [i e]
                                         (when (and (= :fails (mode-of (:id e)))
                                                    (c/admits? idx (before i) e))
                                           i))
                                       events))
          got     (collect (a/drive (c/compile sh a/context) (c/initial sh {}) (fed events)))]
      (if fail-at
        (and (= (subvec before 1 (inc fail-at)) (:states got))
             (identical? (booms (:id (events fail-at))) (:done got)))
        (and (= (rest before) (:states got))
             (= (peek before) (:done got)))))))

(defspec licensed-the-machine-ends-where-the-reduction-does 60
  ;; Every pair gen-shape's empty answers make licensable IS licensed, so handlers of mixed
  ;; timing race for real here. The row ORDER may then differ from the reduction's — that
  ;; is the licence's price — but never the number of rows or where the machine ends.
  (prop/for-all [parts ts/gen-shape
                 mode-of (gen/fmap #(update-vals % (fn [m] (if (= :fails m) :go m))) gen-modes)
                 events (gen/vector ts/gen-event 0 8)]
    (let [ref (apply shape/shape parts)
          sh  (apply shape/shape (rewired parts #(mode-of % :plain) booms))
          ph  (c/phases sh a/context)
          got (collect (a/drive (:step ph) (c/initial sh {}) (fed events)
                                a/state-only (licensed sh ph)))]
      (and (= (count events) (count (:states got)))
           (= (reduce (c/compile ref) (c/initial ref {}) events) (:done got))))))

(defspec fanned-each-instance-is-its-own-reduction 60
  ;; Routing under mixed timing: each instance's rows, in order, are the reduction over its
  ;; OWN events, and :done holds where each ended. Rows of different instances interleave
  ;; however they like.
  (prop/for-all [parts ts/gen-shape
                 mode-of (gen/fmap #(update-vals % (fn [m] (if (= :fails m) :thread m))) gen-modes)
                 events (gen/vector (gen/fmap (fn [[e k]] (cond-> e k (assoc :instance k)))
                                              (gen/tuple ts/gen-event (gen/elements [nil "a" "b"])))
                                    0 10)]
    (let [ref  (apply shape/shape parts)
          sh   (apply shape/shape (rewired parts #(mode-of % :plain) booms))
          step (c/compile ref)
          by   (group-by :instance events)
          got  (collect (a/fan (c/compile sh a/context) (fn [k] (c/initial sh k {})) (fed events)))]
      (and (= (into {} (for [[k es] by] [k (reduce step (c/initial ref k {}) es)]))
              (:done got))
           (= (into {} (for [[k es] by] [k (rest (reductions step (c/initial ref k {}) es))]))
              (group-by :instance (:states got)))))))
