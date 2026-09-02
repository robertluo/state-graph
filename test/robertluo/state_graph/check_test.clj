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
            [robertluo.state-graph.test-support :as ts]
            [ubergraph.core :as uber])
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

(deftest labelled-replaces-what-nobody-can-read
  (let [g (check/labelled (ts/counter))]
    ;; The NAME and the markers, and no schema: a drawing is for the structure, and
    ;; the schema is the part of a shape a map literal already shows. It also did not
    ;; scale — see `node-label`.
    (is (= "idle ▸" (uber/attr g :idle :label)))
    (is (every? #(contains? (uber/attrs g %) :label)
                (concat (shape/states g) (uber/edges g))))))

(deftest dot-answers-graphviz-source
  ;; IN THE FAST LOOP, and it used to be ^:integration: `dot` catches the source in a
  ;; StringWriter, so there is no file, nothing to release and no graphviz — where the same
  ;; assertions through `draw!` needed a temp file and a `finally`. The rendering path is
  ;; what still needs both, and it is the test below.
  (let [src (check/dot (ts/counter))]
    (is (str/starts-with? src "digraph"))
    (doseq [n ["idle" "running" "done" "start" "set" "stop"]]
      (is (str/includes? src n) (str "the drawing names " n)))
    (is (str/includes? src "doublecircle") ":done is final and the picture says so")
    (is (not (str/includes? src "$eval")) "no closure reached the label")))

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

(deftest a-node-that-nests-a-machine-says-so-in-the-picture
  ;; It does not DRAW the child: viz-graph builds its own element list and cannot be handed
  ;; a graphviz cluster, so the parent marks the node and the child is asked for its own
  ;; picture. The marker is what stops a nested machine being invisible.
  (let [g (shape/shape
           (shape/state :p [:map] {:initial true :machine (ts/counter)})
           (shape/state :q [:map] {:final true})
           (shape/event :e [:map] (constantly {}) [:map])
           (shape/transition :p :e :q))]
    (is (str/includes? (check/dot g) "⊞ 3 states"))))

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
    (is (= [{:in :s :pair [:set :sum] :verdict :unknown}] (check/confluence g))
        ":unknown and not :no — an overlap is not a PROOF that the values differ")
    (is (= {} (check/commuting g))
        "so no licence, which is what matters: only :yes licenses anything")
    (is (not= (reduce step init [{:id :sum} {:id :set :to 9}])
              (reduce step init [{:id :set :to 9} {:id :sum}]))
        "and the order really is observable — :total 2 one way, 18 the other")))
