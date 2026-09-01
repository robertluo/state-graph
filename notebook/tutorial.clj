;; # robertluo.state-graph — a tutorial
;;
;; A finite state machine whose **shape is a graph**. This notebook works through the whole
;; facade — ten functions — and ends with a workflow big enough to be worth drawing.
;;
;; Every diagram below is the library's own drawing, rendered in your browser. Reading this
;; page needs nothing installed; `sg/draw!` with a real image format needs graphviz.

(ns tutorial
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [robertluo.state-graph :as sg]
            [robertluo.state-graph.shape :as shape]
            [scicloj.kindly.v4.kind :as kind]))

;; ## A machine is three kinds of part
;;
;; A **state** is a node, shaped by a malli schema and validated on enter. The schema
;; describes the state's *own* data — `:id` (which node it is in) and `:instance` (which run
;; it belongs to) are the machine's to write, and it writes them for you.

(sg/state :todo [:map [:what :string]] {:initial true})

;; A part is a plain map, so a shape is something you can build with `map`, `for`, or
;; anything else that makes data. Exactly one state carries `{:initial true}`.
;;
;; An **event** is shaped by a schema too, and it *carries its handler*. The handler takes
;; the event alone — never the state it is about to change — and answers a map that is
;; **merged into** the state. The last argument declares that map's schema, which is what
;; makes the static check further down possible.

(sg/event :start [:map [:who :string]] (fn [e] {:who (:who e)}) [:map [:who :string]])

;; A **transition** is an edge: from a state, on an event, to a state. Three keywords and no
;; function, because what handles an event belongs to the event.

(sg/transition :todo :start :doing)

;; ## A shape is a graph

(def task
  (sg/shape
   (sg/state :todo  [:map [:what :string]]                  {:initial true})
   (sg/state :doing [:map [:what :string] [:who :string]])
   (sg/state :done  [:map [:what :string] [:who :string]]    {:final true})

   (sg/event :start  [:map [:who :string]] (fn [e] {:who (:who e)}) [:map [:who :string]])
   (sg/event :finish [:map]                (constantly {})          [:map])

   (sg/transition :todo  :start  :doing)
   (sg/transition :doing :finish :done)))

;; `sg/shape` will not build a broken machine. Everything answerable from the parts alone —
;; a transition naming a state nobody defined, two transitions leaving one state on one
;; event, an event no transition fires, a state redeclaring `:id` — throws, with the faults
;; in `ex-data`. The layer below answers the same question without throwing, which is handy
;; when you are generating shapes:

(shape/problems (sg/state :a [:map] {:initial true})
                (sg/event :go [:map] (constantly {}))
                (sg/transition :a :go :nowhere))

;; Here is the machine itself. The drawing marks the initial state `▸`, gives a final state
;; a double circle, and labels every node with its schema. One helper, used throughout:

(defn picture
  "The shape as a diagram on this page.

   `sg/dot` is the drawing as DATA — the graphviz source, as a string, needing nothing
   installed — where `sg/draw!` is the drawing as an effect, opening a viewer or writing a
   file. Clay hands the source to viz.js, which draws it in the browser."
  [sh]
  (kind/graphviz [(sg/dot sh)]))

(picture task)

;; ## Running it: the reduction
;;
;; A shape compiles to an ordinary function of a state and an event. That is the whole
;; runtime — no object, no atom, no protocol.

(def step (sg/compile task))

(sg/initial task {:what "write the tutorial"})

;; The lifecycle of an instance is a reduction over a seq of events:

(reduce step
        (sg/initial task {:what "write the tutorial"})
        [{:id :start :who "ada"} {:id :finish}])

;; `reductions` shows the machine walking, which is the same thing said with more detail:

(reductions step
            (sg/initial task {:what "write the tutorial"})
            [{:id :start :who "ada"} {:id :finish}])

;; A step goes anywhere an accumulator does — a fold, a transducer, `core.async`, a test.
;; Nothing in the core knows what a stream is.

;; ## An event the state cannot handle
;;
;; It is **not an error**. Nothing controls the order events arrive in behind a stream, so a
;; `:finish` landing before a `:start` is ordinary traffic and not a defect. The handler
;; never runs, and the state comes back unchanged:

(step (sg/initial task {:what "write the tutorial"}) {:id :finish})

;; Which raises the question the stream door answers: how would a caller *know*? The state
;; is untouched, so nothing in it says anything happened. Hold that thought.

;; ## What is a defect
;;
;; A crossing that does not hold. An event that is not what its schema says, a handler
;; answering something its own declaration denies, or a state its target's schema will not
;; admit — those are bugs, not facts about the run, and they throw with the problem as data.
;;
;; The library itself never catches; this page does, in order to print one. That is what
;; *errors are data* buys — a map you can read, match on and log, rather than prose:

(-> (try (step (sg/initial task {:what "write the tutorial"})
               {:id :start :who 42})
         (catch clojure.lang.ExceptionInfo e (ex-data e)))
    (select-keys [:crossing :from :event :to :errors]))

;; ## What the graph buys
;;
;; This is the part no other FSM library has. Everything that needs the *built graph* is
;; opt-in, because a half-finished machine is worth looking at — a shape you cannot build is
;; a shape you cannot draw.

(sg/problems task)

;; Now a machine with three *kinds* of fault, every one of them structural — it passes
;; construction and is quietly wrong.

(def leaky
  (sg/shape
   (sg/state :draft     [:map]               {:initial true})
   (sg/state :review    [:map [:by :string]])
   (sg/state :limbo     [:map [:by :string]])
   (sg/state :retrying  [:map [:by :string]])
   (sg/state :published [:map [:by :string]] {:final true})
   (sg/state :archived  [:map [:by :string]] {:final true})

   (sg/event :submit  [:map] (constantly {}) [:map])
   (sg/event :publish [:map] (constantly {}))
   (sg/event :oops    [:map] (constantly {}))
   (sg/event :retry   [:map] (constantly {}))
   (sg/event :back    [:map] (constantly {}))

   (sg/transition :draft    :submit  :review)
   (sg/transition :review   :publish :published)
   (sg/transition :review   :oops    :limbo)
   (sg/transition :limbo    :retry   :retrying)
   (sg/transition :retrying :back    :limbo)))

(sg/problems leaky)

;; - `:unreachable` — a traversal from the initial state never arrives. Note that this is
;;   *not* `has no in-edge`: two states that reach only each other both have in-edges and
;;   are both unreachable.
;; - `:trap` — reachable, has somewhere to go, and can never reach an ending. `:limbo` and
;;   `:retrying` bounce off each other for ever. A dead end is a trap of size one; this is
;;   the smallest interesting one, and the reason a `no out-edge` check is not enough.
;; - `:target-refuses` — `:submit` declares that it answers `[:map]`, so nothing ever sets
;;   `:by`, and `:review` requires it. **Found without running anything.** The checker is
;;   partial on purpose: it answers yes, no, or *don't know*, reports only what it can
;;   prove, and never lies.
;;
;; And the same faults by the means a person is better at. The pocket that `:oops` leads
;; into has no arrow reaching a double circle, and `:archived` floats free:

(picture leaky)

;; ## Running it: the stream
;;
;; The other door. `sg/run` takes a source of events and answers a source of results and a
;; deferred. **The caller owns the lifecycle either way** — this is the same reduction with
;; the loop shipped, not a different kind of machine.

(defn fed
  "A source carrying exactly these events, then closed."
  [events]
  (let [in (s/stream (max 1 (count events)))]
    (s/put-all! in events)
    (s/close! in)
    in))

(def ran
  (sg/run task {:what "write the tutorial"}
          (fed [{:id :start :who "ada"} {:id :finish} {:id :finish}])))

(deref (s/reduce conj [] (:states ran)) 1000 ::timeout)

;; `:states` carries one **transition result** per event: what the machine was told, the
;; state it produced, and whether it `:fired` at all. That last field is the answer to the
;; question held above — the second `:finish` had nowhere to go, and the result says so
;; where the state could not. It is the one fact a consumer cannot recover for itself: an
;; ignored event answers the state unchanged, and a self-loop whose handler answers `{}`
;; answers a state identical to the old one.
;;
;; This library **stores nothing**. The results *are* the history, and writing them down is
;; yours — which is why they carry the event and not just the state.

(deref (:done ran) 1000 ::timeout)

;; `:done` is a deferred `{instance -> final state}`. Nobody named a machine here, so the
;; one machine is under `nil`.
;;
;; A defect belongs on `:done` and never on a stream of things that happened. `d/catch` is
;; manifold's combinator, not a bare try/catch:

(-> (sg/run task {:what "x"} (fed [{:id :start :who 42}]))
    :done
    (d/catch ex-data)
    (deref 1000 ::timeout)
    :crossing)

;; ## A workflow worth drawing
;;
;; Nine states, nine events, twelve transitions: a manuscript through review and publishing.
;; Look at what the shape says before reading the code — the review cycle, the retry loop on
;; `:publishing`, and one event (`:withdraw`) leaving three different states.

(def pipeline
  (sg/shape
   (sg/state :draft             [:map]                                     {:initial true})
   (sg/state :submitted         [:map [:title :string] [:author :string]])
   (sg/state :in-review         [:map [:title :string] [:author :string] [:reviewer :string]])
   (sg/state :changes-requested [:map [:title :string] [:author :string]
                                 [:reviewer :string] [:notes :string]])
   (sg/state :approved          [:map [:title :string] [:author :string] [:reviewer :string]])
   (sg/state :publishing        [:map [:title :string] [:author :string] [:attempt :int]])
   (sg/state :published         [:map [:title :string] [:author :string] [:url :string]]
             {:final true})
   (sg/state :rejected          [:map [:title :string] [:reason :string]]   {:final true})
   (sg/state :withdrawn         [:map [:title :string]]                     {:final true})

   (sg/event :submit          [:map [:title :string] [:author :string]]
             (fn [e] (select-keys e [:title :author]))
             [:map [:title :string] [:author :string]])
   (sg/event :assign          [:map [:reviewer :string]] (fn [e] {:reviewer (:reviewer e)})
             [:map [:reviewer :string]])
   (sg/event :request-changes [:map [:notes :string]]    (fn [e] {:notes (:notes e)})
             [:map [:notes :string]])
   (sg/event :revise          [:map [:title :string]]    (fn [e] {:title (:title e)})
             [:map [:title :string]])
   (sg/event :approve         [:map]                     (constantly {})
             [:map])
   (sg/event :reject          [:map [:reason :string]]   (fn [e] {:reason (:reason e)})
             [:map [:reason :string]])
   (sg/event :publish         [:map [:attempt :int]]     (fn [e] {:attempt (:attempt e)})
             [:map [:attempt :int]])
   (sg/event :confirm         [:map [:url :string]]      (fn [e] {:url (:url e)})
             [:map [:url :string]])
   (sg/event :withdraw        [:map]                     (constantly {})
             [:map])

   (sg/transition :draft             :submit          :submitted)
   (sg/transition :submitted         :assign          :in-review)
   (sg/transition :in-review         :request-changes :changes-requested)
   (sg/transition :changes-requested :revise          :in-review)
   (sg/transition :in-review         :approve         :approved)
   (sg/transition :in-review         :reject          :rejected)
   (sg/transition :approved          :publish         :publishing)
   (sg/transition :publishing        :publish         :publishing)
   (sg/transition :publishing        :confirm         :published)
   (sg/transition :submitted         :withdraw        :withdrawn)
   (sg/transition :in-review         :withdraw        :withdrawn)
   (sg/transition :changes-requested :withdraw        :withdrawn)))

(picture pipeline)

;; Nothing structurally wrong with it — every state reachable, every state able to finish,
;; and every handler's answer admitted by the state it lands in:

(sg/problems pipeline)

;; Three things in that shape are worth naming.
;;
;; **`:publish` leaves two different states**, `:approved` and `:publishing`, with one
;; handler and one declaration. The handler is the *event's*; where the machine lands is the
;; *graph's*. The self-loop is a retry.
;;
;; **The retry cannot count itself.** A handler never sees the state it is changing, so
;; `:attempt` cannot be incremented — the event carries the number, and whoever produces the
;; event knows which attempt it is. This is the sharpest constraint in the library and the
;; one to design around.
;;
;; **`:withdraw` leaves three states.** One event, one handler, three edges — and the
;; subsumption check asks separately, for each of them, whether `:withdrawn` admits what
;; comes out.
;;
;; Now a real event log for two manuscripts at once. `:instance` is what routes an event to
;; its machine, and it is read off the **event**, because routing happens before any state
;; is in hand.

(def log
  [{:instance "m-1" :id :submit :title "On computable numbers" :author "ada"}
   {:instance "m-2" :id :submit :title "A note on parsers" :author "linus"}
   {:instance "m-1" :id :assign :reviewer "grace"}
   {:instance "m-1" :id :request-changes :notes "tighten section 3"}
   {:instance "m-1" :id :confirm :url "https://journal.example/early"}
   {:instance "m-2" :id :withdraw}
   {:instance "m-1" :id :revise :title "On computable numbers (v2)"}
   {:instance "m-1" :id :approve}
   {:instance "m-1" :id :publish :attempt 1}
   {:instance "m-1" :id :publish :attempt 2}
   {:instance "m-1" :id :confirm :url "https://journal.example/17"}])

(def published
  (sg/run pipeline {} (fed log)))

(def history
  (deref (s/reduce conj [] (:states published)) 5000 ::timeout))

;; The history a caller would store — one row per event, whatever became of it:

(kind/table
 {:column-names [:instance :event :landed-in :fired]
  :row-vectors (for [r history]
                 [(:instance r) (-> r :event :id) (-> r :state :id) (:fired r)])})

;; Read that table for the three things it shows.
;;
;; **The `:confirm` that arrived early did not fire.** It is row five, the machine sat in
;; `:changes-requested`, and nothing happened — no error, no handler, no state change, and a
;; row in the history saying so. Without `:fired` that row is indistinguishable from a
;; transition that worked.
;;
;; **The two machines interleave.** `m-2` finished while `m-1` was still in review, and the
;; order of these rows is *not* promised: parallelism is across instances. What is promised
;; is that each machine's own events are applied strictly in order — one partition is one
;; machine, serialised.

(kind/table
 {:column-names [:instance :path]
  :row-vectors (for [[inst rs] (group-by :instance history)]
                 [inst (->> rs (map (comp :id :state)) (cons :draft) (interpose "→")
                            (apply str))])})

;; **And each machine ends somewhere the shape allows**, carrying everything it gathered:

(deref (:done published) 5000 ::timeout)

;; Note `:notes` still riding along in the published manuscript. A handler's answer is
;; *merged*, and a merge cannot remove a key — a state that must drop a field is not
;; expressible in v1. It is in the README's list of limits, and this is what it looks like.

;; ## Nesting: a machine in a node
;;
;; The pipeline above is nine states in one graph, and that is roughly where one graph stops
;; being readable. The answer is not a bigger graph: a state can carry **a whole machine of
;; its own**, and then each machine stays the size a person can hold.
;;
;; Here is a payment, on its own, as an ordinary machine:

(def payment
  (sg/shape
   (sg/state :unpaid     [:map]                 {:initial true})
   (sg/state :authorized [:map [:auth :string]])
   (sg/state :captured   [:map [:auth :string]] {:final true})

   (sg/event :authorize [:map [:auth :string]] (fn [e] {:auth (:auth e)}) [:map [:auth :string]])
   (sg/event :capture   [:map]                 (constantly {})           [:map])

   (sg/transition :unpaid     :authorize :authorized)
   (sg/transition :authorized :capture    :captured)))

(picture payment)

;; And an order that *contains* it. One option on one state is the whole of the syntax:

(def order
  (sg/shape
   (sg/state :cart      [:map] {:initial true})
   (sg/state :paying    [:map] {:machine payment})
   (sg/state :shipped   [:map] {:final true})
   (sg/state :cancelled [:map] {:final true})

   (sg/event :checkout [:map] (constantly {}) [:map])
   (sg/event :ship     [:map] (constantly {}) [:map])
   (sg/event :cancel   [:map] (constantly {}) [:map])

   (sg/transition :cart   :checkout :paying)
   (sg/transition :paying :ship     :shipped)
   (sg/transition :paying :cancel   :cancelled)))

;; `:paying ⊞ 3 states` is the marker for a node that nests one. The child is not drawn
;; inside its parent — graphviz clusters are not reachable through ubergraph — so a nested
;; machine is two pictures, and the parent's says where to look:

(picture order)

;; Now watch one order run. The parent's `:id` and the child's sit side by side:

(def order-step (sg/compile order))

(kind/table
 {:column-names [:event :order :payment]
  :row-vectors (let [events [{:id :checkout}
                             {:id :authorize :auth "tok_9"}
                             {:id :capture}
                             {:id :ship}]]
                 (map (fn [e s] [(:id e) (:id s) (-> s :sub :id)])
                      (cons nil events)
                      (reductions order-step (sg/initial order {}) events)))})

;; **Inner first.** A nested machine gets every event before the node's own edges do — so
;; `:authorize` and `:capture` moved the payment while the order stood still in `:paying`,
;; and only `:ship` moved the order.
;;
;; Which means **the child's own vocabulary decides who handles an event**. `:authorize` is
;; the payment's word, so the order never sees it. `:cancel` is not, so it escapes at once:

(mapv (juxt :id (comp :id :sub))
      (reductions order-step (sg/initial order {}) [{:id :checkout} {:id :cancel}]))

;; **A finished child stops competing**, and this is the part that makes nesting cost the
;; design nothing at all. A final state admits nothing — you saw that at the top of this page
;; — so once the payment is `:captured` every later event falls straight through to the
;; order. No guards, no done-event, no queue, no run-to-completion:

(let [captured (reduce order-step (sg/initial order {})
                       [{:id :checkout} {:id :authorize :auth "tok_9"} {:id :capture}])]
  {:child-is-done (:sub captured)
   :and-ignores-its-own-events (= captured (order-step captured {:id :authorize :auth "again"}))
   :so-the-parent-gets-the-next-one (:id (order-step captured {:id :ship}))})

;; `:sub` belongs to the machinery, exactly as `:id` and `:instance` do: it is seeded when the
;; node is entered, dropped on the way out, restarted if the node is re-entered, and a handler
;; that answers `{:sub ...}` is simply overwritten. And a child is an ordinary shape, so the
;; static checks recurse into it and report its faults under the node that hosts it:

(sg/problems
 (sg/shape (sg/state :p [:map] {:initial true :machine (sg/shape
                                                       (sg/state :i [:map] {:initial true})
                                                       (sg/state :a [:map])
                                                       (sg/state :b [:map])
                                                       (sg/event :x [:map] (constantly {}) [:map])
                                                       (sg/event :y [:map] (constantly {}) [:map])
                                                       (sg/transition :i :x :a)
                                                       (sg/transition :a :y :b)
                                                       (sg/transition :b :x :a))})
           (sg/state :q [:map] {:final true})
           (sg/event :e [:map] (constantly {}) [:map])
           (sg/transition :p :e :q)))

;; A child that can never finish is a fault of the child, reported `:within [:p]` — a path,
;; because nesting nests.
;;
;; ### The limit, said plainly
;;
;; **The escape is unconditional.** Nothing stops `:ship` firing while the payment is half
;; done, because that would be a guard and v1 has none:

(mapv (juxt :id (comp :id :sub))
      (reductions order-step (sg/initial order {}) [{:id :checkout} {:id :ship}]))

;; So deciding *when* is the producer's job — which is the same answer v1 gives to branching,
;; and the child's state is on every result, so a producer can see exactly what it needs to
;; decide. A door left open, not designed: a node could declare where to go when its child
;; finishes, which is the statechart done-transition and needs no event queue here.

;; ## Beneath the facade
;;
;; `robertluo.state-graph` is the only namespace an application needs, but it is a
;; convenience over four that are usable directly — `.shape`, `.compile`, `.check` and
;; `.async`. Drop through when you want something the facade does not offer.
;;
;; `check/commuting`, for instance, asks which pairs of events pending in one state could
;; safely be applied in whichever order finished first. For this pipeline, as for every
;; shape measured so far, the answer is none — divergence is what a state machine is *for*:

(require '[robertluo.state-graph.check :as check])

(check/commuting pipeline)

;; So `sg/run` serialises within a machine, always, and says so rather than pretending.
;;
;; The other reason to drop through is starting data: `sg/run` gives every machine the same
;; initial data, and `async/fan` takes a function of the instance instead. This pipeline
;; sidesteps it by having `:draft` carry nothing and letting `:submit` bring the title.

;; ## Rendering this notebook
;;
;; ```
;; clojure -X:notebook          # writes docs/tutorial.html
;; ```
;;
;; Or, from a REPL started with the `:notebook` alias, for a live page that reloads as you
;; edit:

(comment
  (require '[scicloj.clay.v2.api :as clay])
  (clay/make! {:source-path "notebook/tutorial.clj"})
  (clay/browse!))
