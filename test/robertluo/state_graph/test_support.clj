(ns robertluo.state-graph.test-support
  "What every suite needs and no source namespace should have to know.

   Not named <ns>-test, so kaocha does not load it as a suite."
  (:require [clojure.test.check.generators :as gen]
            [malli.instrument :as mi]
            [robertluo.state-graph.shape :as shape]))

(def namespaces
  "Every namespace whose :malli/schema metadata the fixture collects. THE FACADE IS IN HERE
   and contributes exactly one schema — `run`, the one function it really adds; its
   re-exports carry none on purpose and are guarded by the vars they delegate to."
  '[robertluo.state-graph.shape robertluo.state-graph.compile
    robertluo.state-graph.check robertluo.state-graph.async
    robertluo.state-graph])

(defn instrumented
  "A fixture that makes the :malli/schema metadata actually do something. mi/collect!
   is a MACRO reading *ns*, so in a test file it would collect the TEST; clj-collect!
   is the plain function underneath it and takes {:ns [...]} as a value."
  [f]
  (mi/clj-collect! {:ns namespaces})
  (mi/instrument!)
  (f)
  (mi/unstrument!))

(def gen-shape
  "A WELL-FORMED shape, as the parts it is built from.

   Every state schema is [:map] and every event schema is [:map], on purpose: these
   generate the STRUCTURAL properties, where what is being asked is about the graph and
   the lookup. A schema that could reject something would make a failure ambiguous
   between the two, and what schemas enforce is asserted by example instead.

   Every event is fired by at least one transition and no two transitions share a
   [from event], so `problems` has nothing to find — which is itself the first property.

   gen/let in test.check 1.1.1 does NOT support :let bindings, hence bind and fmap."
  (gen/bind
   (gen/tuple (gen/choose 2 5) (gen/choose 1 4))
   (fn [[n-states n-events]]
     (let [sids (mapv #(keyword (str "s" %)) (range n-states))
           eids (mapv #(keyword (str "e" %)) (range n-events))]
       (gen/fmap
        (fn [groups]
          (concat (map-indexed (fn [i s] (shape/state s [:map] (when (zero? i) {:initial true}))) sids)
                  (map (fn [e] (shape/event e [:map] (constantly {}))) eids)
                  (apply concat groups)))
        (apply gen/tuple
               (for [e eids]
                 (gen/bind
                  (gen/not-empty (gen/set (gen/elements sids)))
                  (fn [froms]
                    (gen/fmap
                     (fn [tos] (mapv (fn [f t] (shape/transition f e t)) froms tos))
                     (gen/vector (gen/elements sids) (count froms))))))))))))

(def gen-event
  "An event to feed a generated shape — mostly ones it knows, sometimes ones it does
   not, because what an unadmitted event does is half of what `compile` promises."
  (gen/fmap (fn [id] {:id id})
            (gen/elements [:e0 :e1 :e2 :e3 :e4 :e5 :no-such-event])))

(defn parts-of
  "The parts of one kind, out of a generated shape."
  [kind parts]
  (filter #(= kind (:robertluo.state-graph.shape/kind %)) parts))

(def gen-map-schema
  "A small map schema. The keys come from a POOL OF THREE so that two generated
   schemas actually overlap — two schemas sharing no keys agree about nothing and
   would make the subsumption property vacuous — and an entry is sometimes optional,
   which is the case `admits` has to get right and the one a naive checker gets wrong.
   Deduplicated by key: [:map [:a :int] [:a :string]] is not a schema."
  (gen/fmap
   (fn [entries]
     (into [:map]
           (map (fn [[k opt t]] (if opt [k {:optional true} t] [k t])))
           (vals (into {} (map (fn [e] [(first e) e])) entries))))
   (gen/vector (gen/tuple (gen/elements [:a :b :c])
                          gen/boolean
                          (gen/elements [:int :string :keyword :boolean]))
               0 4)))

(defn broken
  "A shape with one of everything wrong, and every fault STRUCTURAL — it passes
   shape/problems, which is the point. :island-a and :island-b reach each other and
   nothing else, so they are the case a `no in-edge` check would miss."
  []
  (shape/shape
   (shape/state :idle [:map] {:initial true})
   (shape/state :running [:map [:n :int]])
   (shape/state :done [:map [:n :int]] {:final true})
   (shape/state :trap [:map [:n :int]])
   (shape/state :typed [:map [:n :int]])
   (shape/state :island-a [:map])
   (shape/state :island-b [:map])
   (shape/event :go   [:map] (constantly {:n 0})       [:map [:n :int]])
   (shape/event :stop [:map] (constantly {})           [:map])
   (shape/event :oops [:map] (constantly {})           [:map])
   (shape/event :bad  [:map] (constantly {:n "seven"}) [:map [:n :string]])
   (shape/event :hop  [:map] (constantly {})           [:map])
   (shape/transition :idle :go :running)
   (shape/transition :running :stop :done)
   (shape/transition :running :oops :trap)
   (shape/transition :running :bad :typed)
   (shape/transition :island-a :hop :island-b)))

(defn trapped
  "A shape whose ONLY fault is a trap, and that is the whole point of it: :limbo and
   :retrying are reachable, both have somewhere to go, and no sequence of events from
   either ever reaches :done. Before `traps` existed, check/problems answered [] here."
  []
  (shape/shape
   (shape/state :idle [:map] {:initial true})
   (shape/state :running [:map])
   (shape/state :done [:map] {:final true})
   (shape/state :limbo [:map])
   (shape/state :retrying [:map])
   (shape/event :go    [:map] (constantly {}))
   (shape/event :stop  [:map] (constantly {}))
   (shape/event :oops  [:map] (constantly {}))
   (shape/event :retry [:map] (constantly {}))
   (shape/event :back  [:map] (constantly {}))
   (shape/transition :idle :go :running)
   (shape/transition :running :stop :done)
   (shape/transition :running :oops :limbo)
   (shape/transition :limbo :retry :retrying)
   (shape/transition :retrying :back :limbo)))

(defn endless
  "A machine that was never meant to finish: no :final anywhere, and a cycle so that
   nothing is a dead end either. `traps` has to stay SILENT here, and that exception is
   why the check went unbuilt until it was asked for."
  []
  (shape/shape
   (shape/state :awake [:map] {:initial true})
   (shape/state :asleep [:map])
   (shape/event :sleep [:map] (constantly {}))
   (shape/event :wake  [:map] (constantly {}))
   (shape/transition :awake :sleep :asleep)
   (shape/transition :asleep :wake :awake)))

(defn form
  "The one fixture here holding a pair that COMMUTES — none of the others has one, and
   that zero is what :confluence-was-measured-not-guessed records. :name and :email are
   self-loops on :filling writing DISJOINT keys, so no completion order can be observed.
   The rest are the other verdicts: :both overlaps them, :touch declares no :out, and
   :submit leaves with no way back."
  []
  (shape/shape
   ;; :name and :email are DECLARED here, because a node holds what it declares and these
   ;; events write them. Before the merge was projected on entry a bare [:map] kept them
   ;; anyway, which made this fixture demonstrate a write the runtime silently undid.
   (shape/state :filling [:map [:name {:optional true} :string]
                               [:email {:optional true} :string]] {:initial true})
   (shape/state :submitted [:map [:name {:optional true} :string]
                                 [:email {:optional true} :string]] {:final true})
   (shape/event :name   [:map [:v :string]] (fn [e] {:name (:v e)})  [:map [:name :string]])
   (shape/event :email  [:map [:v :string]] (fn [e] {:email (:v e)}) [:map [:email :string]])
   (shape/event :both   [:map [:v :string]] (fn [e] {:name (:v e) :email (:v e)})
                [:map [:name :string] [:email :string]])
   (shape/event :touch  [:map] (constantly {}))
   (shape/event :submit [:map] (constantly {}))
   (shape/transition :filling :name :filling)
   (shape/transition :filling :email :filling)
   (shape/transition :filling :both :filling)
   (shape/transition :filling :touch :filling)
   (shape/transition :filling :submit :submitted)))

(defn join
  "A JOIN, spelled the only way this library has: the PRODUCT of two independent events,
   so :complete is reachable only once both :eval and :test have been handled and either
   ORDER gets there. The intermediate states are the join's progress and their schemas say
   so — which is projection working for us rather than against.

   THE POINT OF IT HERE is that the pair is licensed while being NOTHING LIKE self-loops:
   `form` has the trivial diamond where ta = tb = x = s, and this has the real one, four
   distinct nodes and two routes that rejoin. `commutes` implements the general diamond and
   this is the fixture that says so.

   Handlers are pure lifts, so a caller feeds {:id :eval :eval {...}} and the patch is that
   key. The two events write DISJOINT keys and read nothing, which is Bernstein's condition."
  []
  (let [R [:map [:ok :boolean]]]
    (shape/shape
     (shape/state :verifying [:map] {:initial true})
     (shape/state :evaled    [:map [:eval R]])
     (shape/state :tested    [:map [:test R]])
     (shape/state :complete  [:map [:eval R] [:test R]] {:final true})
     (shape/event :eval [:map [:eval R]])
     (shape/event :test [:map [:test R]])
     (shape/transition :verifying :eval :evaled)
     (shape/transition :verifying :test :tested)
     (shape/transition :tested    :eval :complete)
     (shape/transition :evaled    :test :complete))))

(def Impl
  "What a fan-out offers back."
  [:map [:score :int] [:by :string]])

(defn better
  "A TOTAL order, ties broken on :by — which is the whole difference between a combine
   that is commutative and one that merely looks it. With `>=` on :score alone a tie has no
   canonical winner, so the answer depends on which patch arrived first, and `check/laws`
   refutes it in a few dozen samples."
  [a b]
  (if (pos? (compare [(:score a) (:by a)] [(:score b) (:by b)])) a b))

(defn fanning
  "FAN OUT AND TAKE THE BEST — what a combine is for, and what a naive merge made
   inexpressible. :offer-a and :offer-b write THE SAME key, so under last-write-wins the
   pair could never be licensed however independent the work was. With a commutative
   combine declared on the node, which patch landed second stops being observable.

   Note the combine is declared on :choosing, the node the offers land on, and NOT on
   :chosen. It is the data owner's declaration and there is nothing to combine on the way
   out."
  []
  (shape/shape
   (shape/state :choosing
                [:map [:best {:optional true :combine better :combine/commutes true} Impl]]
                {:initial true})
   (shape/state :chosen [:map [:best {:optional true} Impl]] {:final true})
   (shape/event :offer-a [:map [:best Impl]])
   (shape/event :offer-b [:map [:best Impl]])
   (shape/event :settle  [:map])
   (shape/transition :choosing :offer-a :choosing)
   (shape/transition :choosing :offer-b :choosing)
   (shape/transition :choosing :settle :chosen)))

(defn counter
  "The canonical example shape, where the schemas DO bite: a counter whose :n the
   events carry, since a handler never sees the state it is changing."
  []
  (shape/shape
   (shape/state :idle [:map] {:initial true})
   (shape/state :running [:map [:n :int]])
   (shape/state :done [:map [:n :int]] {:final true})
   (shape/event :start [:map [:seed :int]] (fn [e] {:n (:seed e)}) [:map [:n :int]])
   (shape/event :set   [:map [:to :int]]   (fn [e] {:n (:to e)})   [:map [:n :int]])
   (shape/event :stop  [:map]              (fn [_] {}))
   (shape/transition :idle :start :running)
   (shape/transition :running :set :running)
   (shape/transition :running :stop :done)))
