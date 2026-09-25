(ns robertluo.state-graph.test-support
  "What every suite needs and no source namespace should have to know.

   Not named <ns>-test, so kaocha does not load it as a suite."
  {:knowledge
   [{:id :tests-live-in-test-and-not-in-the-source
     :kind :rule
     :says "Tests live in test/, one <ns>_test.clj per source namespace, ordinary clojure.test run by kaocha in two suites over one tree — unit, and ^:integration for anything that opens a file, a socket or a real clock. Generative tests ARE the unit suite here, which is why test.check is in :deps and not :dev."
     :cites [:a-generative-test-needs-an-independent-invariant :dependency-test-check]}
    {:id :everything-a-test-opens-is-released-in-a-finally
     :kind :rule
     :says "Everything a test opens is released in a `finally`. `finally` is release and is NOT the forbidden try/catch. Nothing here opens a database any more, so what this governs is files and streams."}
    {:id :every-deref-in-a-stream-test-is-bounded
     :kind :rule
     :says "Every deref of a machine in a stream test is BOUNDED, or a hang becomes a hung suite — a bounded deref is the only honest one, so a machine that hangs FAILS. And a test that derefs :done before draining :states hangs, backpressure being real."
     :cites [:consume-states-or-done-may-never-resolve]}
    {:id :kaocha-ignores-a-focus-meta-nobody-carries
     :kind :lesson
     :says "With no ^:integration test in the tree, the gate silently RUNS THE UNIT TESTS and a bare run does everything twice. Resolved once there was one; kaocha is in :dev, so the runner is clojure -M:dev:test and never clojure -M:test."}
    {:id :gen-let-does-not-support-let-bindings
     :kind :lesson
     :says "gen/let in test.check 1.1.1 does not support :let bindings — the symbol does not resolve, and the failure arrives as `Unable to resolve symbol` from inside the generator. Use gen/bind and gen/fmap. And mg/sample takes {:size n} as the COUNT, not as test.check's generator size, which matters in a property looking for a counterexample."}
    {:id :the-instrument-count-caught-a-stale-repl
     :kind :lesson
     :says "The instrument count still said 14 after `check` was written, because `namespaces` here had been edited on disk and not reloaded, so nine new fns were never collected. The number is a smoke alarm for the fixture AND for the REPL: two ways of counting that agree — (count (mi/instrument!)) against an ns-publics count of fns carrying a :malli/schema — is what says no public function was added without a schema, and a disagreement has twice meant a stale REPL."}
    {:id :the-count-is-a-repl-habit-and-not-an-assertion
     :kind :open
     :says "`instrumented` collects and instruments and returns nothing, and no test counts anything. Making the instrument count an assertion is three lines and nobody has; until somebody does, any count written down is a measurement and not a guarantee."
     :cites [:the-instrument-count-caught-a-stale-repl]}
    {:id :the-generative-property-extended-without-an-argument
     :kind :lesson
     :says "Nesting extended the generative property without an argument: nest one generated shape into a node of another and assert the parent lands in one of ITS nodes and the child in one of the CHILD'S. The two share an event vocabulary, so the child shadows the parent constantly — the interesting half rather than an accident."
     :cites [:a-machine-can-nest-in-a-node]}
    {:id :the-pass-through-property
     :kind :lesson
     :says "The pass-through property is the one worth having for completions, and it is genuinely independent rather than the implementation restated: split one generated edge a -e-> b into a -e-> mid {:done b}, and the reduction must end EXACTLY where it ended before. It compares two machines and recomputes nothing."
     :cites [:a-state-may-say-where-it-goes-when-it-completes]}
    {:id :a-shape-written-to-be-a-parent-is-usually-not-startable-as-a-child
     :kind :lesson
     :says "A shape written to be a parent is usually not startable as a child: `shipping`'s own first state insists on a :total, and entering a child with no :seed hands it NO DATA, so nesting it is :machine-cannot-start."
     :cites [:a-node-may-sow-its-child]}
    {:id :only-one-test-needs-a-clock
     :kind :lesson
     :says "Only one test needs a clock — serialisation asserted with a handler that really is slower, and it is ^:integration. Everything else uses immediate deferreds, or a deferred the test resolves by hand, and is deterministic."
     :cites [:completion-order-is-testable-without-a-clock]}
    {:id :the-licence-guard-is-implied-and-was-kept
     :kind :lesson
     :says "The licence's refusal around a completion is implied by the constructor and was asserted anyway, a deliberate exception to only-assert-what-can-fail: the argument spans two namespaces and the licence is load-bearing, so the condition is stated where it is relied on and the test asserts the fault that implies it."
     :cites [:a-completion-refuses-the-licence-around-it :only-assert-what-can-fail]}
    {:id :the-instrument-count-is-never-asserted
     :kind :decision
     :says "The instrument count is never asserted, and the fixture stays as it is: collects, instruments, returns nothing. A count over what the fixture happens to gather is a total that moves with every var added and says nothing about what broke — the anti-pattern this repository names for tests. What the count is FOR, catching a stale REPL, is a measurement a person makes at the REPL and reads against the last one, and it stays that."
     :from "the author, 2026-09-16, of a suite asserting edge and coverage totals over a fixture: `these tests are very frigile, a classic anti-pattern.`"
     :when "2026-09-16"
     :supersedes [:the-count-is-a-repl-habit-and-not-an-assertion]
     :cites [:the-instrument-count-caught-a-stale-repl]}
    {:id :gen-shape-never-made-a-completion
     :kind :lesson
     :says "`gen-shape` generates no :done, no outcome, no nesting and no final, so when the graph under a shape was rewritten on 2026-09-25 every line handling a COMPLETION edge — successors, continuations, the traversals, the drawing — was covered by examples alone, and the suite was green for it. `gen-completing-shape` lays those roles over it. Its first cut made a dead end in 15 shapes of 500, `gen-shape` making every state depart somewhere, and a property over dead ends would have been asking almost nothing; the :sink role took it to 236. Each property over it was then shown to FAIL: `walk` stopping a hop short, the adjacency forgetting completions, `predecessors` left unreversed and `continuations` losing its outcomes were each caught by at least two."
     :when "2026-09-25"
     :cites [:the-shape-owns-its-graph :a-completion-is-an-edge-and-not-a-node-attribute]}]}
  (:require [clojure.test.check.generators :as gen]
            [malli.instrument :as mi]
            [robertluo.state-graph.shape :as shape]))

(def namespaces
  "Every namespace whose :malli/schema metadata the fixture collects. THE FACADE IS IN HERE
   and contributes exactly one schema — `run`, the one function it really adds; its
   re-exports carry none on purpose and are guarded by the vars they delegate to."
  '[robertluo.state-graph.graph robertluo.state-graph.shape robertluo.state-graph.compile
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

(def gen-driven-shape
  "A well-formed shape EVERY EVENT OF WHICH A DRIVER CAN REPORT — `gen-shape` with a
   `:report` on each event, so the crank always has something to find.

   THE REPORT ANSWERS NOTHING, which is what keeps the properties structural: what is
   being asked is whether the crank applies events the machine admits and whether it
   has any memory, and a report that wrote data would make a failure ambiguous between
   the driving and the merging."
  (gen/fmap
   (fn [parts]
     (map (fn [p]
            (if (= :event (:robertluo.state-graph.shape/kind p))
              (shape/event (:id p) [:map] (constantly {}) nil
                           {:reads [:map] :report (constantly {})})
              p))
          parts))
   gen-shape))

(def gen-event
  "An event to feed a generated shape — mostly ones it knows, sometimes ones it does
   not, because what an unadmitted event does is half of what `compile` promises."
  (gen/fmap (fn [id] {:id id})
            (gen/elements [:e0 :e1 :e2 :e3 :e4 :e5 :no-such-event])))

(defn parts-of
  "The parts of one kind, out of a generated shape."
  [kind parts]
  (filter #(= kind (:robertluo.state-graph.shape/kind %)) parts))

(def ^:private completing-child
  "A child that can finish TWO ways, so a parent nesting it can say where each outcome goes.
   Its first state is not final, so the node nesting it does not complete on entry."
  (shape/shape (shape/state :c0 [:map] {:initial true})
               (shape/state :c1 [:map] {:final true})
               (shape/state :c2 [:map] {:final true})
               (shape/event :x [:map])
               (shape/event :y [:map])
               (shape/transition :c0 :x :c1)
               (shape/transition :c0 :y :c2)))

(defn- lay-roles
  "`gen-shape`'s parts with a ROLE laid on each state, the i-th role on the i-th state:

   :final   — {:final true}
   :done    — an unconditional completion, on entry, to a state of HIGHER index, so no two
              of them make a cycle; its own event edges go, being dead code beside it, and
              so does every event nothing fires any more. The last state cannot go further
              and stays plain.
   :nest    — nests `completing-child` and says where each of its two outcomes goes. It
              completes only when the child does, so it keeps its edges and may point
              anywhere.
   :sink    — loses its event edges and gains nothing: a DEAD END, which `gen-shape` almost
              never makes because every state it generates departs somewhere.
   :plain   — as generated.

   Built to be well formed rather than filtered into it; the property over it says whether
   it is."
  [parts roles]
  (let [states (vec (parts-of :state parts))
        n (count states)
        ids (mapv :id states)
        role (fn [i] (let [[r a b] (nth roles i)]
                       (if (and (= r :done) (= i (dec n))) [:plain a b] [r a b])))
        states' (map-indexed
                 (fn [i s]
                   (let [[r a b] (role i)]
                     (case r
                       :final (assoc s :final true)
                       :done  (assoc s :done (nth ids (+ i 1 (mod a (- n i 1)))))
                       :nest  (assoc s :machine completing-child
                                     :done {:c1 {:to (nth ids (mod a n))}
                                            :c2 {:to (nth ids (mod b n))}})
                       s)))
                 states)
        silenced (into (set (keep :id (filter #(and (:done %) (not (:machine %))) states')))
                       (keep-indexed (fn [i id] (when (= :sink (first (role i))) id)) ids))
        transitions (remove (comp silenced :from) (parts-of :transition parts))
        fired (set (map :event transitions))]
    (concat states'
            (filter (comp fired :id) (parts-of :event parts))
            transitions)))

(def gen-completing-shape
  "A WELL-FORMED shape with COMPLETION TRANSITIONS in it, as its parts: `gen-shape` with
   finals, unconditional :done continuations and nesting nodes whose :done names a target per
   outcome laid over it. All-plain roles give back a `gen-shape`, so nothing flat is lost.

   WHAT IT IS FOR: a completion is an edge, and everything that walks the graph — `successors`,
   `continuations`, `reachable`, `finishable`, `dead-ends`, the drawing — has to see it as one.
   `gen-shape` never makes one, so without this none of that is asked of a generated shape."
  (gen/bind gen-shape
            (fn [parts]
              (gen/fmap #(lay-roles parts %)
                        (gen/vector (gen/tuple (gen/elements [:plain :plain :final :done :nest :sink])
                                               gen/nat gen/nat)
                                    (count (parts-of :state parts)))))))

(defn completions-of
  "Every completion the PARTS declare, as [from outcome to] — outcome nil for an
   unconditional :done. Read off the state definitions and never off a built shape."
  [parts]
  (set (for [s (parts-of :state parts)
             :let [d (:done s)]
             :when d
             [outcome to] (if (map? d) (map (fn [[o {:keys [to]}]] [o to]) d) [[nil d]])]
         [(:id s) outcome to])))

(defn arrows
  "Every [from to] the PARTS declare — each transition, and each completion — the relation a
   reference model walks, independent of how a shape stores it."
  [parts]
  (into (set (map (juxt :from :to) (parts-of :transition parts)))
        (map (fn [[from _ to]] [from to]))
        (completions-of parts)))

(defn closure
  "The states reachable from `roots` along `arrows`, by FIXPOINT — add every arrow's head
   whose tail is in, until nothing changes. A reference model, deliberately not a traversal."
  [arrows roots]
  (loop [s (set roots)]
    (let [s' (into s (for [[a b] arrows :when (s a)] b))]
      (if (= s s') s (recur s')))))

(defn rename-states
  "The parts with every STATE id renamed by `f` wherever one is named — a state's :id, its
   :done target or targets, a transition's ends. Events, and anything inside a nested child,
   are left as they are."
  [parts f]
  (for [p parts]
    (case (:robertluo.state-graph.shape/kind p)
      :state (cond-> (update p :id f)
               (map? (:done p)) (update :done update-vals #(update % :to f))
               (keyword? (:done p)) (update :done f))
      :transition (-> p (update :from f) (update :to f))
      p)))

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

(defn shipping
  "A COMPLETION TRANSITION IN BOTH OF ITS FORMS, and it is the shape that says what the
   feature is for.

   :paying NESTS a machine and declares {:done :shipped :yield ...}, so the parent WAITS for
   its child and HARVESTS what it finished with. Before this the parent's only way out was
   an event, and taking one discarded the child's work entirely — measured, a child that
   finished with a result in :sub left {:id :p2} behind. :cancel is still that escape and
   still an abort, which is the commoner need and is why the two have to coexist.

   :shipped completes ON ENTRY, having no machine and so no activity to finish, and passes
   straight through to :closed. Same rule, and it is what makes a plain `then` expressible."
  []
  (let [payment (shape/shape
                 (shape/state :awaiting [:map] {:initial true})
                 (shape/state :paid [:map [:receipt :string]] {:final true})
                 (shape/event :authorize [:map [:receipt :string]])
                 (shape/transition :awaiting :authorize :paid))]
    (shape/shape
     (shape/state :paying [:map [:total :int]]
                  {:initial true :machine payment
                   :done :shipped :yield [:map [:receipt :string]]})
     (shape/state :shipped [:map [:total :int] [:receipt :string]] {:done :closed})
     (shape/state :closed [:map [:total :int] [:receipt :string]] {:final true})
     (shape/state :cancelled [:map [:total :int]] {:final true})
     (shape/event :cancel [:map])
     (shape/transition :paying :cancel :cancelled))))

(defn refining
  "A SEED AND A PER-OUTCOME COMPLETION, together, because they are what one loop over a
   nested machine needs and neither is much use alone.

   :working NESTS a worker and SOWS it with a :job — so the same node is entered twice with
   two different jobs, which is the thing a nesting could not do while a child was always
   started with nothing. The worker finishes either :done, and its result is harvested and
   reviewed, or :stuck, and the parent goes to :kept holding whatever it already had.

   IT IS THE SHAPE OF `make it work, then make it beautiful`: the second lap is the first
   lap's answer handed back with a different job, and the branch that gives up must not
   overwrite the answer that worked. That is why :stuck yields NOTHING — a state holds what
   it declares, so keeping the good answer costs no key and no copy."
  []
  (let [worker (shape/shape
                (shape/state :idle [:map [:job :string]] {:initial true})
                (shape/state :done [:map [:job :string] [:answer :string]] {:final true})
                (shape/state :stuck [:map [:job :string]] {:final true})
                (shape/event :answer [:map [:answer :string]])
                (shape/event :give-up [:map])
                (shape/transition :idle :answer  :done)
                (shape/transition :idle :give-up :stuck))]
    (shape/shape
     (shape/state :start [:map] {:initial true})
     (shape/state :working [:map [:job :string] [:answer {:optional true} :string]]
                  {:machine worker
                   :seed [:map [:job :string]]
                   :done {:done  {:to :judging :yield [:map [:answer :string]]}
                          :stuck {:to :kept}}})
     (shape/state :judging [:map [:job :string] [:answer :string]])
     (shape/state :kept [:map [:job :string] [:answer {:optional true} :string]] {:final true})
     (shape/state :happy [:map [:job :string] [:answer :string]] {:final true})
     (shape/event :begin [:map [:job :string]])
     (shape/event :verdict [:map [:verdict [:enum :good :again]] [:job {:optional true} :string]]
                  (fn [e] (select-keys e [:job])) nil)
     (shape/transition :start :begin :working)
     (shape/transition :judging :verdict :happy   {:when [:map [:verdict [:= :good]]]})
     (shape/transition :judging :verdict :working {:when [:map [:verdict [:= :again]]]}))))

(defn gathering
  "FAN-OUT, and the shape of it is one self-loop. n workers each report a result as ONE
   event of ONE id, and the state accumulates them under a key whose combine is SET UNION —
   which is commutative, so the licence lets two of those reports land in whichever order
   they finish.

   A SET AND NOT A VECTOR, and this is the trap: `into` on a vector is order-dependent, so
   `check/laws` refutes it in a handful of samples. Set union is the accumulator a join
   wants, and a map keyed by the item is the other one.

   THE WIDTH IS NOT IN THE SHAPE, deliberately. Who says `that is all of them` is the
   DRIVER, which dispatched the work and is the only party that knows n — the same answer
   this library gives to branching and to retry budgets. :stop is that report."
  []
  (shape/shape
   (shape/state :gathering
                [:map [:seen {:combine into :combine/commutes true} [:set :int]]]
                {:initial true})
   (shape/state :gathered [:map [:seen [:set :int]]] {:final true})
   (shape/event :found [:map [:seen [:set :int]]])
   (shape/event :stop  [:map] (constantly {}) [:map])
   (shape/transition :gathering :found :gathering)
   (shape/transition :gathering :stop  :gathered)))
