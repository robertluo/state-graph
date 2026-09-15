(ns robertluo.state-graph.check-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.compile :as c]
            [robertluo.state-graph.shape :as shape]
            [robertluo.state-graph.test-support :as ts])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(use-fixtures :once ts/instrumented)

;;; ----------------------------------------------------------------- properties

(defspec a-reduction-never-leaves-the-reachable-set 100
  ;; The invariant that ties the STATIC analysis to what actually runs, and the only
  ;; one that can catch either half being wrong: `reachable` walks the graph, the step
  ;; function does a lookup, and they are written independently. If a reduction can
  ;; reach a state the traversal says it cannot, one of the two is lying.
  (prop/for-all [parts ts/gen-shape
                 events (gen/vector ts/gen-event 0 20)]
    (let [g (apply shape/shape parts)
          r (check/reachable g)]
      (every? #(contains? r (:id %))
              (reductions (c/compile g) (c/initial g {}) events)))))

(defspec a-yes-from-admits-is-never-a-lie 200
  ;; SOUNDNESS, and it is checked the only honest way — by GENERATION against the
  ;; structural analysis, which is a genuinely independent second opinion. A checker
  ;; that says :no where it should say :unknown merely nags; one that says :yes where
  ;; it should say :no HIDES A BUG, so this is the direction worth paying for.
  (prop/for-all [target ts/gen-map-schema
                 produced ts/gen-map-schema]
    (or (not= :yes (check/admits target produced))
        (every? #(m/validate target %) (mg/sample produced {:size 25})))))

(defspec admits-is-reflexive 100
  (prop/for-all [s ts/gen-map-schema]
    (= :yes (check/admits s s))))

;;; ------------------------------------------------------------------- the graph

(deftest unreachable-finds-an-island-and-not-merely-a-loner
  ;; :island-a and :island-b have edges and would both survive a `no in-edge` check —
  ;; :island-b has an in-edge. Only traversal from the root finds them.
  (let [g (ts/broken)]
    (is (= #{:island-a :island-b} (set (check/unreachable g))))
    (is (= #{:idle :running :done :trap :typed} (check/reachable g)))))

(deftest a-dead-end-is-not-an-ending
  (let [g (ts/broken)]
    (is (= #{:trap :typed :island-b} (set (check/dead-ends g))))
    (is (not (contains? (set (check/dead-ends g)) :done))
        ":done has no way out either, but it is :final and that is what :final says")))

(deftest a-trap-is-what-a-cycle-hides
  ;; The argument for this check, put as a test: on this shape BOTH other structural
  ;; checks are entirely silent, and before `traps` existed check/problems answered [].
  (let [g (ts/trapped)]
    (is (= #{:limbo :retrying} (set (check/traps g))))
    (is (= #{:idle :running :done} (check/finishable g)))
    (testing "and neither of the others can see it"
      (is (empty? (check/unreachable g)) "a forward traversal gets there")
      (is (empty? (check/dead-ends g))
          "both have somewhere to go — going nowhere and going nowhere USEFUL differ"))
    (testing "so it is the only thing problems reports"
      (is (= [{:problem :trap :id :limbo} {:problem :trap :id :retrying}]
             (check/problems g))))))

(deftest a-machine-that-never-ends-is-not-broken
  ;; The exception that kept this unbuilt. With no :final declared, `can it still
  ;; finish` is not a question about this machine, so the check stays quiet rather than
  ;; condemning every state it has.
  (let [g (ts/endless)]
    (is (empty? (check/traps g)))
    (is (= #{:awake :asleep} (check/finishable g)) "every state, vacuously")
    (is (empty? (check/problems g)))))

(deftest a-dead-end-is-a-trap-reported-as-the-sharper-fault
  ;; `traps` is TOTAL, so :trap and :typed are both in it — neither has a way to :done.
  ;; But :dead-end says more about them, so problems names each state once and names it
  ;; that. The accessor is honest; problems is what filters.
  (let [g (ts/broken)]
    (is (= #{:trap :typed} (set (check/traps g))))
    (is (empty? (filter #(= :trap (:problem %)) (check/problems g)))
        "every trap here is already a dead end, and is reported as that instead")))

;;; ------------------------------------------------------------------ subsumption

(deftest admits-answers-what-it-can-prove-and-declines-the-rest
  (testing "proven yes"
    (is (= :yes (check/admits [:map [:n :int]] [:map [:n :int]])))
    (is (= :yes (check/admits [:map [:n {:optional true} :int]] [:map])))
    (is (= :yes (check/admits :any [:map [:n :int]])))
    (is (= :yes (check/admits [:maybe :int] :int)))
    (testing "a finite set of values is decidable — just try them"
      (is (= :yes (check/admits :keyword [:enum :a :b])))
      (is (= :yes (check/admits [:map [:id :keyword]] [:map [:id [:= :done]]])))))

  (testing "proven no"
    (is (= :no (check/admits [:map [:n :int]] [:map]))
        "a required key the produced value has not got — the common bug by a distance")
    (is (= :no (check/admits [:map [:n :int]] [:map [:n :string]])))
    (is (= :no (check/admits [:map [:n :int]] [:map [:n {:optional true} :int]]))
        "optional where the target insists: it MAY be absent, so it is not admitted")
    (is (= :no (check/admits :int [:enum :a 1])) "one member is enough to refuse")
    (is (= :no (check/admits [:map {:closed true} [:n :int]]
                             [:map {:closed true} [:n :int] [:x :int]]))))

  (testing "declines to answer, which is an answer and not a failure"
    (is (= :unknown (check/admits [:vector :int] [:sequential :int])))
    (is (= :unknown (check/admits [:map {:closed true} [:n :int]] [:map [:n :int]]))
        "an open produced may carry keys a closed target would refuse — unprovable")))

(deftest subsumption-reports-its-own-coverage
  (let [by-event (into {} (map (juxt :event :verdict)) (check/subsumption (ts/counter)))]
    (is (= :yes (:start by-event)))
    (is (= :undeclared (:stop by-event))
        "the edge named no :out, so there was nothing to check — not a fault, a gap"))
  (is (= :no (->> (check/subsumption (ts/broken))
                  (filter #(= :bad (:event %))) first :verdict))))

;;; ------------------------------------------------------------------ confluence

(deftest confluence-licenses-only-what-it-can-prove
  (let [v (into {} (map (juxt :pair :verdict)) (check/confluence (ts/form)))]
    (testing "yes: both self-loops, both :out declared, key sets disjoint"
      (is (= :yes (v [:email :name]))))

    (testing "unknown: the diamond closes, the patches cannot be shown to commute"
      (is (= :unknown (v [:both :name])) "their :out both write :name")
      (is (= :unknown (v [:both :email])) "and both write :email")
      (is (= :unknown (v [:name :touch])) ":touch declared no :out at all")
      (testing "a shared key is NOT reported as a proof of conflict, because the two
                values might coincide and nothing here can know"
        (is (not= :no (v [:both :name])))))

    (testing "no: the diamond fails, so completion order decides where the machine ends
              up — :submit leaves :filling and there is no way back"
      (is (= :no (v [:name :submit])))
      (is (= :no (v [:submit :touch]))))))

(deftest a-nested-machine-makes-a-pair-unknowable
  ;; MEASURED, and it was a live unsoundness rather than a gap: INNER FIRST means a child
  ;; sees an event before this shape's own edges do, so a nesting node's own self-loops
  ;; describe a diamond that never runs. Before this, the pair below was licensed as
  ;; commuting while the CHILD's own confluence proved it :no — and the two orders landed
  ;; in visibly different states. Nothing had noticed because `drive` serialised whatever
  ;; the licence said; it became load-bearing the day the runtime took it.
  (let [child (shape/shape
               (shape/state :k1 [:map] {:initial true})
               (shape/state :ka [:map [:a :int]] {:final true})
               (shape/state :kb [:map [:b :int]] {:final true})
               (shape/event :a [:map [:a :int]])
               (shape/event :b [:map [:b :int]])
               (shape/transition :k1 :a :ka)
               (shape/transition :k1 :b :kb))
        host (shape/shape
              (shape/state :p [:map [:a {:optional true} :int] [:b {:optional true} :int]]
                           {:initial true :machine child})
              (shape/state :out [:map] {:final true})
              (shape/event :a [:map [:a :int]])
              (shape/event :b [:map [:b :int]])
              (shape/event :fin [:map])
              (shape/transition :p :a :p)
              (shape/transition :p :b :p)
              (shape/transition :p :fin :out))]
    (is (= [{:pair [:a :a] :verdict :no}
            {:pair [:a :b] :verdict :no}
            {:pair [:b :b] :verdict :no}]
           (map #(select-keys % [:pair :verdict]) (check/confluence child)))
        "the child proves its own pair is order-dependent")
    (is (every? #{:unknown} (map :verdict (remove :within (check/confluence host))))
        "so the host may claim nothing about any pair pending where that child lives")
    (is (= #{:no} (set (map :verdict (filter :within (check/confluence host)))))
        "and it PUBLISHES the child's own verdicts rather than hiding them — the answer is
         about the machine, not about one layer of it")
    (is (= {} (check/commuting host))
        "and licenses nothing, which is the only safe answer")
    (testing "the two orders really do differ, which is what makes :unknown necessary"
      (let [step (c/compile host)
            s0 (c/initial host {})
            ab (reduce step s0 [{:id :a :a 1} {:id :b :b 2}])
            ba (reduce step s0 [{:id :b :b 2} {:id :a :a 1}])]
        (is (not= ab ba))))))

(deftest a-general-diamond-is-licensed-and-not-only-a-self-loop
  ;; `form` has the TRIVIAL diamond, ta = tb = x = s. `join` has the real one: four
  ;; distinct nodes and two routes that rejoin, which is what `commutes` implements and
  ;; what a self-loop shortcut would have refused to see.
  (let [j (ts/join)]
    (is (= [{:in :evaled :pair [:test :test] :verdict :no}
            {:in :tested :pair [:eval :eval] :verdict :no}
            {:in :verifying :pair [:eval :eval] :verdict :no}
            {:in :verifying :pair [:eval :test] :verdict :yes}
            {:in :verifying :pair [:test :test] :verdict :no}]
           (vec (check/confluence j)))
        "and the diagonals are all :no, a join's arms being the opposite of a fan-out —
         one :eval takes you somewhere that does not admit a second")
    (is (= {:verifying #{#{:eval :test}}} (check/commuting j)))
    (testing "and the proof holds when run: both orders land in one identical state"
      (let [step (c/compile j)
            s0 (c/initial j {})
            e {:id :eval :eval {:ok true}} t {:id :test :test {:ok false}}]
        (is (= (reduce step s0 [e t]) (reduce step s0 [t e])))))))

(deftest commuting-is-plain-data-the-async-layer-can-hold
  ;; Why it is a VALUE and not a closure: the async layer is handed this the way it is
  ;; handed a compiled step, so it still knows nothing of shapes — and a person can print
  ;; it, which a closure would not allow.
  (is (= {:filling #{#{:name :email}}} (check/commuting (ts/form))))

  (testing "and the shapes that started this argument measure ZERO, which is the finding
            and not a gap in the fixtures"
    (is (= {} (check/commuting (ts/counter))))
    (is (= {} (check/commuting (ts/trapped))))
    (is (= {} (check/commuting (ts/broken)))))

  (testing "none of it reaches problems — a pair that cannot be concurrent is not a
            fault, it is a pair that waits, and waiting is the default"
    (is (empty? (check/problems (ts/form))))))

;;; --------------------------------------------------------------------- problems

(deftest structural-problems-are-data-and-only-the-proven-ones
  (let [ps (check/problems (ts/broken))]
    (is (= #{:unreachable :dead-end :target-refuses} (set (map :problem ps))))
    (is (= {:from :running :event :bad :to :typed :problem :target-refuses}
           (first (filter #(= :target-refuses (:problem %)) ps))))
    (is (empty? (check/problems (ts/counter)))
        "a sound shape has nothing structurally wrong, and every :undeclared and
         :unknown stays out of it — a checker that cries about what it could not work
         out is a checker people turn off")))

;;; ---------------------------------------------------------------------- drawing

(deftest ^:integration draw-renders-a-picture
  ;; The only test of the RENDERING path, as opposed to the source: it shells out to
  ;; `dot`, which is why graphviz is in devenv.nix. A machine without it fails here and
  ;; nowhere else — the :dot test above needs nothing.
  (let [f (str (Files/createTempFile "state-graph" ".png" (into-array FileAttribute [])))]
    (try
      (check/draw! (ts/counter) {:save {:filename f :format :png}})
      (let [magic (with-open [in (io/input-stream f)]
                    (let [buf (byte-array 4)] (.read in buf) (vec buf)))]
        ;; asserted on the MAGIC BYTES, because a file existing proves only that
        ;; something wrote one — these prove graphviz actually drew the thing
        (is (= [-119 80 78 71] magic) "0x89 P N G")
        (is (< 1000 (.length (File. f))) "three states do not render in a few bytes"))
      (finally (.delete (File. f))))))

;;; ---------------------------------------------------------------- nesting

(deftest a-nested-machine-is-checked-as-an-ordinary-shape
  ;; Most of why nesting cost so little: every structural check is about ONE graph, and a
  ;; child is one. :within is a PATH, because nesting nests.
  (let [kid (ts/trapped)
        g (shape/shape
           (shape/state :p [:map] {:initial true :machine kid})
           (shape/state :q [:map] {:final true})
           (shape/event :e [:map] (constantly {}) [:map])
           (shape/transition :p :e :q))]
    (is (empty? (check/problems (shape/shape
                                 (shape/state :p [:map] {:initial true :machine (ts/counter)})
                                 (shape/state :q [:map] {:final true})
                                 (shape/event :e [:map] (constantly {}) [:map])
                                 (shape/transition :p :e :q))))
        "a sound child says nothing")
    (is (= [{:problem :trap :id :limbo :within [:p]}
            {:problem :trap :id :retrying :within [:p]}]
           (check/problems g))
        "and a child's own faults are reported under the node that hosts it")))

;;; ---------------------------------------------------------- declared views

(deftest views-answers-whether-a-state-can-provide-what-a-handler-asks-to-see
  ;; `admits` again, with the view as the target and the source node's schema as what is
  ;; produced — and sound ONLY because a node now holds exactly what it declares.
  (let [g (shape/shape
           (shape/state :rich [:map [:goal :string] [:token :string]] {:initial true})
           (shape/state :thin [:map [:goal {:optional true} :string]])
           (shape/state :end  [:map] {:final true})
           (shape/event :ok      [:map] (fn [_ _] {}) [:map] {:sees [:map [:goal :string]]})
           (shape/event :maybe   [:map] (fn [_ _] {}) [:map] {:sees [:map [:goal :string]]})
           (shape/event :nothing [:map] (constantly {}) [:map])
           (shape/transition :rich :ok      :thin)
           (shape/transition :thin :maybe   :end)
           (shape/transition :rich :nothing :end))]
    (is (= #{{:from :rich :event :ok      :verdict :yes}
             {:from :thin :event :maybe   :verdict :no}
             {:from :rich :event :nothing :verdict :undeclared}}
           (set (check/views g)))
        "an OPTIONAL key is :no — a view a handler is handed cannot rest on a maybe")
    (is (= [{:from :thin :event :maybe :problem :view-unavailable}]
           (filterv #(= :view-unavailable (:problem %)) (check/problems g)))
        "and only the proven one is a fault")))

(deftest a-view-makes-a-read-write-hazard-that-write-sets-cannot-see
  ;; BERNSTEIN, not disjoint writes, and this test is the reason the condition changed. :sum
  ;; reads :n and writes :total; :set writes :n. The WRITE sets are disjoint, so the pair was
  ;; licensed until a handler could read — and the two orders answer differently, which is a
  ;; flake nobody chose rather than a race anybody wanted.
  (let [g (shape/shape
           (shape/state :s [:map [:n :int] [:total :int]] {:initial true})
           (shape/event :sum [:map] (fn [_ seen] {:total (* 2 (:n seen))})
                        [:map [:total :int]] {:sees [:map [:n :int]]})
           (shape/event :set [:map [:to :int]] (fn [e] {:n (:to e)}) [:map [:n :int]])
           (shape/transition :s :sum :s)
           (shape/transition :s :set :s))
        step (c/compile g)
        init (c/initial g {:n 1 :total 0})]
    (is (= [{:in :s :pair [:set :set] :verdict :unknown}
            {:in :s :pair [:set :sum] :verdict :unknown}
            {:in :s :pair [:sum :sum] :verdict :unknown}]
           (check/confluence g))
        ":unknown and not :no — an overlap is not a PROOF that the values differ. The
         diagonals are :unknown for the same reason each writes what it writes with no
         combine to say how it lands")
    (is (= {} (check/commuting g))
        "so no licence, which is what matters: only :yes licenses anything")
    (is (not= (reduce step init [{:id :sum} {:id :set :to 9}])
              (reduce step init [{:id :set :to 9} {:id :sum}]))
        "and the order really is observable — :total 2 one way, 18 the other")))

;;; ----------------------------------------------------------------------- guards

(defn- guarded
  "Two guards on one event, and a third state whose way out is one guard only — so this
   fixture has both an exhaustive branch and a deliberate FILTER in it."
  []
  (shape/shape
   (shape/state :written     [:map] {:initial true})
   (shape/state :implemented [:map] {:final true})
   (shape/state :faulted     [:map])
   (shape/event :judged [:map [:verdict [:enum :green :red]]] (constantly {}) [:map])
   (shape/transition :written :judged :implemented {:when [:map [:verdict [:= :green]]]})
   (shape/transition :written :judged :faulted     {:when [:map [:verdict [:= :red]]]})
   (shape/transition :faulted :judged :written     {:when [:map [:verdict [:= :green]]]})))

(deftest coverage-publishes-a-gap-and-never-faults-one
  ;; `admits` and `disjoint` run against a PROBE — the event's schema with one key pinned
  ;; to one value of a finite domain. Two structural checks off one subsumption function,
  ;; which is what `views` did first.
  (let [g (guarded)
        verdict (fn [from] (->> (check/coverage g) (filter #(= from (:from %))) first))]
    (is (= {:from :written :event :judged :verdict :yes} (verdict :written))
        "every value of the verdict enum reaches an edge")
    (is (= {:from :faulted :event :judged :verdict :no :witness {:verdict :red}}
           (verdict :faulted))
        "and where one reaches none, the value that proves it is published")

    (testing "A GAP IS NOT A FAULT. An event no guard admits fires no edge, which is
              `ignored` — legal, and exactly what a lone guard used as a filter is for"
      (is (= [] (check/problems g))))))

(deftest an-unguarded-edge-covers-whatever-the-others-refuse
  (let [g (shape/shape
           (shape/state :a [:map] {:initial true})
           (shape/state :b [:map])
           (shape/state :c [:map] {:final true})
           (shape/event :go [:map [:v [:enum :x :y :z]]] (constantly {}) [:map])
           (shape/transition :a :go :b {:when [:map [:v [:= :x]]]})
           (shape/transition :b :go :c))]
    (is (= [{:from :a :event :go :verdict :no :witness {:v :y}}
            {:from :b :event :go :verdict :yes}]
           (vec (check/coverage g))))))

(deftest a-guarded-pair-is-not-reasoned-about-for-concurrency
  ;; Where a guard decides the target, `both admitted` stops being a fact about the shape
  ;; and starts depending on the events themselves, so the diamond cannot be looked up.
  ;; :unknown is the honest answer; it costs only a licence that was never taken.
  (let [g (shape/shape
           (shape/state :idle [:map] {:initial true})
           (shape/state :done [:map] {:final true})
           (shape/event :tick [:map] (constantly {}) [:map])
           (shape/event :go   [:map [:v [:enum :x :y]]] (constantly {}) [:map])
           (shape/transition :idle :tick :idle)
           (shape/transition :idle :go :done {:when [:map [:v [:= :x]]]})
           (shape/transition :idle :go :idle {:when [:map [:v [:= :y]]]}))]
    (is (= [{:in :idle :pair [:go :go] :verdict :unknown}
            {:in :idle :pair [:go :tick] :verdict :unknown}
            {:in :idle :pair [:tick :tick] :verdict :yes}]
           (vec (check/confluence g)))
        "the guarded event appears in the listing, because leaving it out would be a
         quiet gap in what this publishes — and so does the DIAGONAL, two of one event
         being the fan-out case")
    (is (not (contains? (get (check/commuting g) :idle) #{:go :tick}))
        "nothing is licensed off an :unknown")
    (is (= {:idle #{#{:tick}}} (check/commuting g))
        "and :tick with ITSELF is licensed, its :out writing nothing at all — two empty
         patches commute however many of them there are")))

(deftest a-guard-is-drawn-on-the-arrow
  ;; Harel's own notation, event [guard], and a guard here is a SCHEMA — so it can be read
  ;; rather than merely named. That is what makes the branch visible in the picture at all,
  ;; which is the whole reason a guard is data and not a closure.
  (let [src (check/dot (guarded))]
    (is (str/includes? src "judged [verdict=:green]"))
    (is (str/includes? src "judged [verdict=:red]"))
    (is (not (str/includes? src "$eval")) "a closure in a picture is the failure mode")))

;;; ------------------------------------------------------------------ the laws

(deftest a-commutative-combine-licenses-a-shared-key
  ;; THE WIDENING, and the reason the naive merge was the real limit. :offer-a and :offer-b
  ;; write THE SAME key, so under last-write-wins this pair could never be licensed however
  ;; independent the work behind it was. It is licensed now.
  (let [f (ts/fanning)]
    (is (= {:choosing #{#{:offer-a :offer-b} #{:offer-a} #{:offer-b}}} (check/commuting f))
        "and each offer with ITSELF, a SINGLETON in the same set-of-sets — which is the
         fan-out licence: n workers sending n events of one id")
    (testing "and take the combine's promise away and the licence goes with it"
      (let [plain (shape/shape
                   (shape/state :choosing [:map [:best {:optional true} ts/Impl]]
                                {:initial true})
                   (shape/state :chosen [:map [:best {:optional true} ts/Impl]] {:final true})
                   (shape/event :offer-a [:map [:best ts/Impl]])
                   (shape/event :offer-b [:map [:best ts/Impl]])
                   (shape/event :settle [:map])
                   (shape/transition :choosing :offer-a :choosing)
                   (shape/transition :choosing :offer-b :choosing)
                   (shape/transition :choosing :settle :chosen))]
        (is (= {} (check/commuting plain))
            "a shared key with no commutative combine is exactly as unlicensable as before")))))

(deftest laws-refutes-and-never-proves
  ;; Generation can REFUTE a law and cannot prove one, so the verdicts are :no with a
  ;; witness or :unknown, and never :yes. Both laws are worth having and they catch
  ;; different mistakes.
  (testing ":commutes — a tie with no canonical winner leaks argument order"
    (let [ties (fn [a b] (if (>= (:score a) (:score b)) a b))
          bad (shape/shape
               (shape/state :s [:map [:best {:combine ties :combine/commutes true} ts/Impl]]
                            {:initial true})
               (shape/event :p [:map [:best ts/Impl]])
               (shape/transition :s :p :s))
          v (first (filter (comp #{:commutes} :law) (check/laws bad)))]
      (is (= :no (:verdict v)))
      (is (some? (:witness v)) "and it hands over the values that disagree")
      (is (apply not= (:answers v)))))
  (testing ":closed — a combine that changes the type would make every edge into the state a lie"
    (let [v (first (check/laws (shape/shape
                                (shape/state :s [:map [:n {:combine str} :int]]
                                             {:initial true})
                                (shape/event :p [:map [:n :int]])
                                (shape/transition :s :p :s))))]
      (is (= [:closed :no] [(:law v) (:verdict v)]))
      (is (string? (:answer v)) "str answered a string where an :int was declared")))
  (testing "an honest combine is :unknown, which is all generation can honestly say"
    (is (every? #{:unknown} (map :verdict (check/laws (ts/fanning))))))
  (testing "and it is SEEDED, so a check that answers differently each call is not one"
    (is (= (check/laws (ts/fanning)) (check/laws (ts/fanning))))))

(deftest generation-cannot-reach-every-violation
  ;; THE MEASUREMENT THAT DECIDED THE DESIGN, kept as a test because it is the reason
  ;; `compile` verifies the same claim at runtime. A plausible domain rule — a pinned choice
  ;; wins outright — is NOT commutative, and no amount of generated data finds it, because
  ;; malli will not invent the string "pinned".
  (let [sticky (fn [a b] (if (= "pinned" (:by a)) a (ts/better a b)))
        liar (shape/shape
              (shape/state :s [:map [:best {:combine sticky :combine/commutes true} ts/Impl]]
                           {:initial true})
              (shape/event :p [:map [:best ts/Impl]])
              (shape/event :q [:map [:best ts/Impl]])
              (shape/transition :s :p :s)
              (shape/transition :s :q :s))]
    (is (every? #{:unknown} (map :verdict (check/laws liar {:samples 14})))
        "2,744 triples and nothing found")
    (is (= {:s #{#{:p :q} #{:p} #{:q}}} (check/commuting liar))
        "so the pair IS licensed on a false promise — which is what the runtime check is for")
    (testing "and the promise really is false"
      (let [s {:score 0 :by "m"} a {:score 5 :by "pinned"} b {:score 9 :by "z"}]
        (is (not= (sticky (sticky s a) b) (sticky (sticky s b) a)))))))

;;; ------------------------------------------------------ a completion transition

(deftest the-structural-checks-see-a-completion-transition-for-nothing
  ;; THE WHOLE ARGUMENT FOR MAKING IT AN EDGE. None of these four learned anything about
  ;; :done; they walk the graph, and the arrow is in the graph.
  (let [sh (shape/shape (shape/state :a [:map] {:initial true})
                        (shape/state :b [:map] {:done :c})
                        (shape/state :c [:map] {:final true})
                        (shape/event :go [:map])
                        (shape/transition :a :go :b))]
    (testing ":c is reached only by completing, and `reachable` reaches it"
      (is (= #{:a :b :c} (set (check/reachable sh))))
      (is (empty? (check/unreachable sh))))
    (testing ":b's only way out is completing, so it is neither a dead end nor a trap"
      (is (empty? (check/dead-ends sh)))
      (is (empty? (check/traps sh))))
    (is (empty? (check/problems sh)))))

(deftest a-completion-transition-is-subsumption-checked-and-is-never-undeclared
  ;; THE ONE WAY THIS IS STRONGER THAN AN EVENT EDGE: an event edge can only be checked
  ;; where the event declared an :out, because what a closure answers is otherwise
  ;; unknowable. A completion carries no closure, so what arrives is the state itself.
  (let [sh (shape/shape (shape/state :a [:map [:n :int]] {:initial true :done :b})
                        (shape/state :b [:map [:n :int]] {:final true}))
        v (first (filter :done (check/subsumption sh)))]
    (is (= {:from :a :to :b :done true :verdict :yes} v))
    (is (not-any? #{:undeclared} (map :verdict (filter :done (check/subsumption sh))))))
  (testing "a target that insists on a key the source cannot have is a PROVEN fault"
    (let [sh (shape/shape (shape/state :a [:map] {:initial true :done :b})
                          (shape/state :b [:map [:needed :int]] {:final true}))]
      (is (= [{:from :a :to :b :done true :problem :target-refuses}]
             (check/problems sh))))))

(deftest a-yield-is-admits-for-the-third-time
  ;; `views` was the second. Three structural checks off one subsumption function is the
  ;; argument for having written it.
  (let [child (fn [schema]
                (shape/shape (shape/state :d1 [:map] {:initial true})
                             (shape/state :d2 schema {:final true})
                             (shape/event :fin schema)
                             (shape/transition :d1 :fin :d2)))
        verdict (fn [child-schema yield]
                  (let [sh (shape/shape
                            (shape/state :p [:map] {:initial true :machine (child child-schema)
                                                    :done :z :yield yield})
                            (shape/state :z yield {:final true}))]
                    (:verdict (first (check/yields sh)))))]
    (is (= :yes (verdict [:map [:r :string]] [:map [:r :string]]))
        "the child finishes with the key and more")
    (is (= :no (verdict [:map [:other :int]] [:map [:r :string]]))
        "the child never has it")
    (is (= :no (verdict [:map [:r :int]] [:map [:r :string]]))
        "the child has it at the wrong type")
    (is (= :no (verdict [:map [:r {:optional true} :string]] [:map [:r :string]]))
        "AND ONLY OPTIONALLY IS ALSO :no — a yield cannot rest on a maybe")))

(deftest every-final-state-of-the-child-is-asked-and-not-just-one
  ;; A child may finish in ANY of its finals, and a yield resting on only some is a yield
  ;; that is sometimes not there.
  (let [child (shape/shape (shape/state :d1 [:map] {:initial true})
                           (shape/state :ok  [:map [:r :string]] {:final true})
                           (shape/state :bad [:map] {:final true})
                           (shape/event :win  [:map [:r :string]])
                           (shape/event :lose [:map])
                           (shape/transition :d1 :win :ok)
                           (shape/transition :d1 :lose :bad))
        sh (shape/shape (shape/state :p [:map] {:initial true :machine child
                                                :done :z :yield [:map [:r :string]]})
                        (shape/state :z [:map [:r :string]] {:final true}))]
    (is (= [{:from :p :final :bad :verdict :no}
            {:from :p :final :ok :verdict :yes}]
           (vec (check/yields sh))))
    (is (= [{:from :p :final :bad :problem :yield-unavailable}]
           (filter (comp #{:yield-unavailable} :problem) (check/problems sh))))))

(deftest a-PER-OUTCOME-yield-is-asked-about-its-OWN-final-state-and-no-other
  ;; NOT A RELAXATION OF THE RULE ABOVE — the same rule read where it applies. That branch
  ;; is taken only when the child stopped THERE, so that is the only state the yield has to
  ;; rest on, and naming the outcome makes the check SHARPER rather than looser: the very
  ;; shape condemned above is fine once each way of finishing says where it goes.
  (let [child (shape/shape (shape/state :d1 [:map] {:initial true})
                           (shape/state :ok  [:map [:r :string]] {:final true})
                           (shape/state :bad [:map] {:final true})
                           (shape/event :win  [:map [:r :string]])
                           (shape/event :lose [:map])
                           (shape/transition :d1 :win :ok)
                           (shape/transition :d1 :lose :bad))
        sh (shape/shape (shape/state :p [:map] {:initial true :machine child
                                                :done {:ok  {:to :z :yield [:map [:r :string]]}
                                                       :bad {:to :nothing}}})
                        (shape/state :z [:map [:r :string]] {:final true})
                        (shape/state :nothing [:map] {:final true}))]
    (is (= [{:from :p :final :ok :verdict :yes}] (vec (check/yields sh))))
    (is (empty? (check/problems sh)))))

(deftest seeds-is-admits-in-BOTH-directions-because-a-seed-has-two-sides
  ;; `yields` READ BACKWARDS. One carries child -> parent at completion, this one parent ->
  ;; child at entry, and either side of the crossing can be wrong: a node asked to sow what
  ;; it does not hold, and a child that will not take what it is sown.
  (let [child (fn [schema] (shape/shape (shape/state :c1 schema {:initial true})
                                        (shape/state :c2 [:map] {:final true})
                                        (shape/event :fin [:map] (constantly {}) [:map])
                                        (shape/transition :c1 :fin :c2)))
        sh (fn [node seed child-schema]
             (shape/shape (shape/state :a [:map] {:initial true})
                          (shape/state :b node {:machine (child child-schema) :seed seed})
                          (shape/event :go [:map] (constantly {}) [:map])
                          (shape/transition :a :go :b)))
        v  (fn [& args] (select-keys (first (check/seeds (apply sh args))) [:provides :accepts]))]
    (is (= {:provides :yes :accepts :yes}
           (v [:map [:job :string]] [:map [:job :string]] [:map [:job :string]])))
    (is (= {:provides :no :accepts :yes}
           (v [:map] [:map [:job :string]] [:map [:job :string]]))
        "the node was asked to sow a key it does not hold")
    (is (= {:provides :no :accepts :yes}
           (v [:map [:job {:optional true} :string]] [:map [:job :string]] [:map [:job :string]]))
        "AND ONLY OPTIONALLY IS ALSO :no — a child is no better off with a maybe than a
         handler is")
    (is (= {:provides :yes :accepts :no}
           (v [:map [:job :string]] [:map [:job :string]] [:map [:job :int]]))
        "the child will not take it at that type")
    ;; `:b` is a dead end in these fixtures and says so; what is asserted is the seed.
    (let [seedy (fn [s] (filter (comp #{:seed-unavailable :seed-refused} :problem)
                                (check/problems s)))]
      (is (= [{:problem :seed-unavailable :from :b}]
             (seedy (sh [:map] [:map [:job :string]] [:map [:job :string]])))
          "and the proven half is a fault, exactly as :yield-unavailable is")
      (is (= [{:problem :seed-refused :from :b :initial :c1}]
             (seedy (sh [:map [:job :string]] [:map [:job :string]] [:map [:job :int]])))))))

(deftest a-continuing-JOIN-NODE-keeps-its-licence
  ;; AND THAT MATTERS RATHER THAN BEING A NICETY. x is where both orders arrive — the
  ;; diamond having proved they arrive at the SAME x — and a continuation is a pure
  ;; function of the state, so both orders continue identically. A join's own :complete is
  ;; exactly where a :done belongs, and refusing it would lose the licence precisely where
  ;; it was won.
  (let [R [:map [:ok :boolean]]
        joined (shape/shape
                (shape/state :verifying [:map] {:initial true})
                (shape/state :evaled [:map [:eval R]])
                (shape/state :tested [:map [:test R]])
                (shape/state :complete [:map [:eval R] [:test R]] {:done :shipped})
                (shape/state :shipped [:map [:eval R] [:test R]] {:final true})
                (shape/event :eval [:map [:eval R]])
                (shape/event :test [:map [:test R]])
                (shape/transition :verifying :eval :evaled)
                (shape/transition :verifying :test :tested)
                (shape/transition :evaled :test :complete)
                (shape/transition :tested :eval :complete))]
    (is (empty? (check/problems joined)))
    (is (= {:verifying #{#{:eval :test}}} (check/commuting joined)))
    (is (= {:verifying #{#{:eval :test}}} (check/commuting (ts/join)))
        "and it is the same licence the join had without one")))

(deftest a-plain-state-can-never-both-continue-and-be-in-a-diamond
  ;; WHICH IS WHY `commutes` NEEDS NO TEST OF ITS OWN FOR THIS. A state in a diamond needs
  ;; out-edges, and a plain state that both continues and has out-edges is refused before
  ;; it exists — so the licence's own guard is IMPLIED by this fault. Asserted here because
  ;; that implication is the thing `commutes` is resting on, and it spans two namespaces.
  (is (= [:done-with-edges]
         (map :problem
              (shape/problems (shape/state :s [:map] {:initial true})
                              (shape/state :ta [:map] {:done :x})
                              (shape/state :tb [:map])
                              (shape/state :x [:map] {:final true})
                              (shape/event :a [:map]) (shape/event :b [:map])
                              (shape/transition :s :a :ta)
                              (shape/transition :s :b :tb)
                              (shape/transition :ta :b :x)
                              (shape/transition :tb :a :x))))))

(deftest a-completion-transition-is-drawn-DASHED-and-UNLABELLED
  ;; UML's own notation for it, and honest here for a better reason: there is no event to
  ;; name, and a :yield is about the DATA rather than about where the machine goes.
  (let [d (check/dot (ts/shipping))]
    (is (re-find #"dashed" d))
    (is (not (re-find #"\$eval" d)) "no closure ever reaches a picture")))

(deftest a-PER-OUTCOME-completion-is-labelled-with-the-child-s-final-state
  ;; By the same test :a-node-is-labelled-by-its-id sets. Two dashed arrows leaving one node
  ;; are two different STRUCTURAL facts, and a drawing that cannot tell them apart is showing
  ;; a machine that does not exist. What it names is a node of the CHILD, so it is written
  ;; the way a guard is — in brackets, the condition on an otherwise causeless arrow.
  (let [d (check/dot (ts/refining))]
    (is (re-find #"\[done\]" d))
    (is (re-find #"\[stuck\]" d))
    (is (re-find #"dashed" d))))

;;; ---------------------------------------------------------- the fan-out licence

(deftest two-of-ONE-event-may-commute-which-is-the-fan-out-case
  ;; THE DIAGONAL, and it is not a degenerate case. n workers feeding one accumulating
  ;; state send n events of a SINGLE id, and an async handler makes two of them pending at
  ;; once exactly as it does for two ids. It was refused outright before, on the ground that
  ;; two events of one id write one set of keys and so conflict by construction — true under
  ;; a merge, and untrue of a key that declares a COMMUTATIVE COMBINE.
  (let [g (ts/gathering)]
    (is (= [{:in :gathering :pair [:found :found] :verdict :yes}
            {:in :gathering :pair [:found :stop] :verdict :no}
            {:in :gathering :pair [:stop :stop] :verdict :no}]
           (vec (check/confluence g))))
    (is (= {:gathering #{#{:found}}} (check/commuting g))
        "a SINGLETON in the same set-of-sets, so a runtime asks one question for both kinds")
    (testing "`commutes` needed no change for it, which is what says the condition was
              always right: with a = b the two events share a handler, an :out and a target,
              so the diamond closes wherever the target admits the event again"
      (is (= :no (:verdict (first (filter #(= [:stop :stop] (:pair %))
                                          (check/confluence g)))))
          ":stop leaves :gathering, so a second :stop meets a state with no edge for it"))))

(deftest the-fan-out-accumulator-must-be-COMMUTATIVE-and-a-vector-is-not
  ;; THE TRAP EVERY USER OF THIS WILL MEET, and `laws` is what catches it — measured, not
  ;; guessed: `into` on a VECTOR is order-dependent, so which worker reported first is
  ;; visible in the answer. Set union is not. This is :what-the-combine-taught's own warning
  ;; landing on the obvious first attempt at a join.
  (let [vec-shape (fn [schema]
                    (shape/shape
                     (shape/state :s [:map [:xs {:combine into :combine/commutes true} schema]]
                                  {:initial true})
                     (shape/event :add [:map [:xs schema]])
                     (shape/transition :s :add :s)))]
    (is (= [:no] (->> (check/laws (vec-shape [:vector :int]))
                      (filter #(= :commutes (:law %))) (map :verdict)))
        "a vector accumulator is REFUTED, and the witness is two patches in both orders")
    (is (= [:unknown] (->> (check/laws (vec-shape [:set :int]))
                           (filter #(= :commutes (:law %))) (map :verdict)))
        "a set accumulator survives — :unknown being the best generation can ever say")
    (testing "BOTH are licensed statically, and that is not a bug — `commutes` reads the
              DECLARATION, a claim about a closure that no static check can settle. `laws`
              is the development aid and compile's :agree is the enforcement, which is the
              three-way argument :what-the-combine-taught made and this is a fourth witness
              to it"
      (is (= {:s #{#{:add}}} (check/commuting (vec-shape [:set :int]))))
      (is (= {:s #{#{:add}}} (check/commuting (vec-shape [:vector :int])))))))

(deftest a-report-asking-for-what-the-state-cannot-give-is-a-proven-fault
  ;; `admits` FOR THE FOURTH TIME — `views` was the second and `yields` the third —
  ;; with the read as the TARGET and the source node's schema as what is PRODUCED.
  ;; A driver runs a report in the state that AWAITS the event, so that state is
  ;; what must provide the keys.
  (let [reported (fn [reads]
                   (shape/event :go [:map [:verdict :keyword]]
                                (fn [e] (select-keys e [:verdict])) nil
                                {:reads reads :report (fn [_] {:verdict :green})}))
        sh (fn [holds reads]
             (shape/shape (shape/state :a holds {:initial true})
                          (shape/state :z [:map [:verdict :keyword]] {:final true})
                          (reported reads)
                          (shape/transition :a :go :z)))]
    (testing "the state holds what the report reads"
      (is (= [{:from :a :event :go :verdict :yes}]
             (vec (check/readings (sh [:map [:n :int]] [:map [:n :int]])))))
      (is (= [] (check/problems (sh [:map [:n :int]] [:map [:n :int]])))))

    (testing "it does not, and that is PROVEN rather than suspected"
      (is (= [{:from :a :event :go :verdict :no}]
             (vec (check/readings (sh [:map] [:map [:n :int]])))))
      (is (= [{:from :a :event :go :problem :reads-unavailable}]
             (check/problems (sh [:map] [:map [:n :int]])))))

    (testing "it only MIGHT, which is also :no — a report cannot rest on a maybe any
              more than a handler's view can"
      (is (= [{:from :a :event :go :verdict :no}]
             (vec (check/readings (sh [:map [:n {:optional true} :int]] [:map [:n :int]]))))))

    (testing "an event with no report comes from the WORLD, so there is nothing to check"
      (let [world (shape/shape (shape/state :a [:map] {:initial true})
                               (shape/state :z [:map] {:final true})
                               (shape/event :go [:map])
                               (shape/transition :a :go :z))]
        (is (= [{:from :a :event :go :verdict :undeclared}] (vec (check/readings world))))
        (is (= {} (shape/reports world)))))))

;;; ------------------------------------------------------- who can move a machine

(def ^:private fork-child
  (shape/shape (shape/state :s [:map] {:initial true})
               (shape/state :x [:map] {:final true})
               (shape/state :y [:map] {:final true})
               (shape/event :one [:map] {:reads [:map] :report (constantly {})})
               (shape/event :two [:map] {:reads [:map] :report (constantly {})})
               (shape/transition :s :one :x)
               (shape/transition :s :two :y)))

(deftest a-published-check-answers-about-the-machine-and-not-one-layer-of-it
  ;; THE INCONSISTENCY THE CRANK FOUND. `problems` has recursed since nesting landed and
  ;; nothing else did, so the same question got two answers depending on which door you
  ;; asked through: `readings` said a child was fine while `problems` reported a PROVEN
  ;; :reads-unavailable in it.
  (let [child (shape/shape (shape/state :s [:map] {:initial true})
                           (shape/state :x [:map] {:final true})
                           (shape/event :go [:map]
                                        {:reads [:map [:nowhere :string]]
                                         :report (constantly {})})
                           (shape/transition :s :go :x))
        host  (shape/shape (shape/state :in  [:map] {:initial true :machine child :done :out})
                           (shape/state :out [:map] {:final true}))]
    (is (= [{:from :s :event :go :verdict :no :within [:in]}] (check/readings host)))
    (is (= [{:from :s :event :go :problem :reads-unavailable :within [:in]}]
           (check/problems host))
        "and reported ONCE — `problems` takes only its own from a check that now recurses")))

(deftest the-licence-is-one-machine-s-and-not-its-children-s
  ;; A wrong :yes here is an order-dependent flake, so `commuting` must not fold a child's
  ;; pairs into a parent state that happens to share its name. `confluence` recurses; the
  ;; licence takes the outermost only.
  (let [host (shape/shape (shape/state :s   [:map] {:initial true :machine fork-child :done :out})
                          (shape/state :out [:map] {:final true}))]
    (is (seq (filter :within (check/confluence host))) "the child's pairs are published")
    (is (= {} (check/commuting host)) "and none of them is licensed here")))

(deftest who-can-move-each-state
  ;; THE STATIC COUNTERPART OF `drive/awaiting`, which is the same question asked of a
  ;; RUNNING machine. Asking it of the graph is what `could this ever have worked` means
  ;; for the door that finds its own events.
  (let [m (shape/shape (shape/state :asked [:map] {:initial true})
                       (shape/state :coded [:map [:code :string]])
                       (shape/state :lawed [:map [:law :string]])
                       (shape/state :both  [:map [:code :string] [:law :string]] {:final true})
                       (shape/event :write [:map [:code :string]]
                                    {:reads [:map] :report (constantly {:code "c"})})
                       (shape/event :draft [:map [:law :string]]
                                    {:reads [:map] :report (constantly {:law "l"})})
                       (shape/transition :asked :write :coded)
                       (shape/transition :asked :draft :lawed)
                       (shape/transition :coded :draft :both)
                       (shape/transition :lawed :write :both))]
    (is (= {:asked :join :coded :driver :lawed :driver :both :final}
           (into {} (map (juxt :id :verdict)) (check/driving m)))))

  (testing "a park is a legitimate verdict and not a fault — the world supplies it"
    (let [p (shape/shape (shape/state :a [:map] {:initial true})
                         (shape/state :z [:map] {:final true})
                         (shape/event :approve [:map])
                         (shape/transition :a :approve :z))]
      (is (= :world (:verdict (first (check/driving p)))))
      (is (= [] (check/problems p)))))

  (testing "a state offering a driver's event beside a person's escape is :driver —
            it awaits two and reports one"
    (let [e (shape/shape (shape/state :running [:map] {:initial true})
                         (shape/state :done    [:map] {:final true})
                         (shape/state :aborted [:map] {:final true})
                         (shape/event :tick    [:map] {:reads [:map] :report (constantly {})})
                         (shape/event :abandon [:map])
                         (shape/transition :running :tick    :done)
                         (shape/transition :running :abandon :aborted))
          v (first (filter #(= :running (:id %)) (check/driving e)))]
      (is (= {:awaits #{:tick :abandon} :reports #{:tick} :verdict :driver}
             (select-keys v [:awaits :reports :verdict])))))

  (testing "AND THE ONE WORTH LOOKING FOR: a fork a driver cannot settle, three levels in.
            Nothing else says so before you run it — `problems` calls this shape fine."
    (let [host (shape/shape (shape/state :in  [:map] {:initial true :machine fork-child :done :out})
                            (shape/state :out [:map] {:final true}))]
      (is (= [] (check/problems host)))
      (is (= {:id :s :awaits #{:one :two} :reports #{:one :two} :verdict :fork :within [:in]}
             (first (filter #(= :fork (:verdict %)) (check/driving host))))))))
