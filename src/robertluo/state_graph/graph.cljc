(ns robertluo.state-graph.graph
  "THE BOTTOM: a labelled multi-digraph as plain data, and the algorithms over it.

   A GRAPH IS {:nodes {id label} :edges [edge ...]}, an edge being a map carrying :from and
   :to and, beside them, whatever else it carries — which is its LABEL. The edges may be a
   set or a sequence, and a sequence may hold one edge twice: a multigraph counts it twice.

   IT KNOWS NOTHING OF STATES, EVENTS OR SCHEMAS. `shape` stores a machine in this form and
   puts these to a machine's use; nothing here asks what a node or an edge means. That is
   what lets it be written once, for both hosts, and tested against a reference model rather
   than against a machine.

   Requires malli, for its signatures, and nothing else."
  {:knowledge
   [{:id :the-graph-algorithms-are-generic-and-below-the-shape
     :kind :decision
     :says "Graph algorithms live in their own namespace BELOW `shape`, over plain {:nodes {id label} :edges [edge]} data, and know nothing of states, events or schemas. `shape` converts a machine into that form and answers in its own vocabulary — a path of events, an isomorphism of state ids."
     :why "Chosen over putting them in `shape`, which would have grown generic code unrelated to states and events, and over `check`, which sits above `shape` and `compile` so that nothing below could ever have asked for a path. Generic code tests against a reference model with no machine around it, and is .cljc from its first line, which is the portability the graph was taken in-house for."
     :from "the author, 2026-09-25, choosing the new namespace for shortest paths, strongly connected components, topological order, isomorphism, containment and degree"
     :when "2026-09-25"}
    {:id :a-graph-algorithm-is-written-rather-than-borrowed
     :kind :decision
     :says "Each algorithm here is the plainest one that is correct at the size of a state machine — a component is the meet of a forward and a backward closure, a topological order is Kahn's, an isomorphism is a backtracking search pruned by label signatures — rather than the fastest one known."
     :why "A state machine has tens of states and not millions, and the whole reason the graph library went was that borrowing one cost more than writing what was used. Where the size assumption breaks, the component and the isomorphism search are what to replace first."
     :cites [:the-graph-algorithms-are-generic-and-below-the-shape]}]}
  (:require [clojure.set :as set]))

;;; ---------------------------------------------------------------- vocabulary

(def Edge
  "An arrow: where it leaves, where it lands, and whatever else it carries, which is its
   label."
  [:map [:from :any] [:to :any]])

(def Graph
  "{:nodes {id label} :edges [edge ...]} — a labelled multi-digraph. The edges may be a set
   or a sequence."
  [:map
   [:nodes [:map-of :any :any]]
   [:edges [:or [:set Edge] [:sequential Edge]]]])

(def Adjacency
  "{node -> the nodes one edge away}, an entry for every node whether or not an edge
   touches it."
  [:map-of :any [:set :any]])

(defn- label
  "What an edge carries besides its ends."
  [e]
  (dissoc e :from :to))

(defn- ordered
  "Sorted by printed form, so that what a set iterates in no particular order is walked in
   one. Deterministic only where the values are plain data — a closure prints its address."
  [xs]
  (sort-by pr-str xs))

;;; ---------------------------------------------------------------- adjacency

(defn- adjacent
  [{:keys [nodes edges]} near far]
  (reduce (fn [m e] (update m (near e) (fnil conj #{}) (far e)))
          (zipmap (keys nodes) (repeat #{}))
          edges))

(defn successors
  "{node -> the nodes ONE EDGE AWAY}, for every node; empty where a node has nowhere to go."
  {:malli/schema [:=> [:cat Graph] Adjacency]}
  [g]
  (adjacent g :from :to))

(defn predecessors
  "{node -> the nodes ONE EDGE BEFORE it}, for every node — `successors` with every edge
   reversed."
  {:malli/schema [:=> [:cat Graph] Adjacency]}
  [g]
  (adjacent g :to :from))

(defn reachable
  "Every node reachable from `roots` along `next-of` — an Adjacency, forwards or backwards —
   the roots included."
  {:malli/schema [:=> [:cat Adjacency [:sequential :any]] [:set :any]]}
  [next-of roots]
  (loop [seen #{} stack (vec roots)]
    (if (seq stack)
      (let [n (peek stack)]
        (if (seen n)
          (recur seen (pop stack))
          (recur (conj seen n) (into (pop stack) (get next-of n)))))
      seen)))

;;; -------------------------------------------------------------------- paths

(defn paths
  "{node -> the SHORTEST PATH to it from `from`}, a path being a vector of EDGES, for every
   node reachable from `from` — [] for `from` itself, and no entry for a node nothing
   reaches.

   Shortest in EDGES, found breadth first. Where two paths tie, the one found first wins, and
   out-edges are tried in printed order so that it is the same one every time — which holds
   where the edges are plain data."
  {:malli/schema [:=> [:cat Graph :any] [:map-of :any [:vector Edge]]]}
  [{:keys [edges]} from]
  (let [out (group-by :from (ordered (distinct edges)))]
    (loop [frontier [from] found {from []}]
      (if (empty? frontier)
        found
        (let [[found' next]
              (reduce (fn [[f nx] e]
                        (if (contains? f (:to e))
                          [f nx]
                          [(assoc f (:to e) (conj (f (:from e)) e)) (conj nx (:to e))]))
                      [found []]
                      (mapcat out frontier))]
          (recur next found'))))))

;;; ------------------------------------------------------------------ structure

(defn components
  "The STRONGLY CONNECTED COMPONENTS: every node in exactly one set, and two nodes in the
   same set exactly when each reaches the other. A node on no cycle is a component of one."
  {:malli/schema [:=> [:cat Graph] [:set [:set :any]]]}
  [g]
  (let [fwd (successors g) back (predecessors g)]
    (loop [left (set (keys (:nodes g))) found #{}]
      (if-let [n (first left)]
        (let [c (set/intersection (reachable fwd [n]) (reachable back [n]))]
          (recur (set/difference left c) (conj found c)))
        found))))

(defn topsort
  "The nodes in an order every edge goes FORWARD in, or nil where there is a cycle — a
   self-loop included. Kahn's: a node is placed once nothing is left that leads to it, and
   of the nodes ready together the first in printed order goes first."
  {:malli/schema [:=> [:cat Graph] [:maybe [:vector :any]]]}
  [g]
  (let [fwd (successors g)
        waiting (update-vals (predecessors g) count)]
    (loop [ready (ordered (filter (comp zero? waiting) (keys waiting)))
           waiting waiting
           placed []]
      (if-let [n (first ready)]
        (let [freed (for [m (fwd n) :when (= 1 (waiting m))] m)
              waiting (reduce #(update %1 %2 dec) waiting (fwd n))]
          (recur (ordered (concat (rest ready) freed)) waiting (conj placed n)))
        (when (= (count placed) (count (:nodes g))) placed)))))

;;; --------------------------------------------------------------- comparison

(defn- between
  "{[from to] {label count}} — how many edges of each label join each ordered pair."
  [{:keys [edges]}]
  (reduce (fn [m e] (update-in m [[(:from e) (:to e)] (label e)] (fnil inc 0)))
          {}
          edges))

(defn- signature
  "What any image of a node must share with it: its own label, and the labels of the edges
   leaving it and arriving at it, counted."
  [{:keys [nodes edges]} n]
  [(get nodes n)
   (frequencies (map label (filter #(= n (:from %)) edges)))
   (frequencies (map label (filter #(= n (:to %)) edges)))])

(defn isomorphism
  "A one-to-one renaming of `a`'s nodes onto `b`'s under which every node keeps its label and
   the edges, labels and all, are exactly `b`'s — as {node-of-a node-of-b} — or nil where
   there is none.

   A backtracking search. Each node of `a` may only go to a node of `b` with the same
   signature — its own label and the labels of the edges in and out of it — and each choice
   is checked against the pairs already made, so a wrong one is abandoned at once. Where
   several renamings exist, the one found first is answered, the candidates being tried in
   printed order."
  {:malli/schema [:=> [:cat Graph Graph] [:maybe [:map-of :any :any]]]}
  [a b]
  (when (and (= (count (:nodes a)) (count (:nodes b)))
             (= (count (:edges a)) (count (:edges b)))
             (= (frequencies (vals (:nodes a))) (frequencies (vals (:nodes b)))))
    (let [pa (between a) pb (between b)
          by-signature (group-by #(signature b %) (ordered (keys (:nodes b))))
          candidates (into {} (for [n (keys (:nodes a))]
                                [n (get by-signature (signature a n) [])]))
          todo (sort-by (juxt (comp count candidates) pr-str) (keys (:nodes a)))
          fits? (fn [m n c]
                  (and (= (pa [n n]) (pb [c c]))
                       (every? (fn [[x fx]] (and (= (pa [n x]) (pb [c fx]))
                                                 (= (pa [x n]) (pb [fx c]))))
                               m)))
          extend (fn extend [m used [n & more]]
                   (if (nil? n)
                     m
                     (some (fn [c] (when (and (not (used c)) (fits? m n c))
                                     (extend (assoc m n c) (conj used c) more)))
                           (candidates n))))]
      (extend {} #{} todo))))

(defn subgraph?
  "Is `a` contained in `b` as it stands — every node of `a` in `b` with the same label, and
   every edge of `a` in `b` as many times as `a` has it? No renaming: containment under the
   identity."
  {:malli/schema [:=> [:cat Graph Graph] :boolean]}
  [a b]
  (let [ea (frequencies (:edges a)) eb (frequencies (:edges b))]
    (and (every? (fn [[n l]] (and (contains? (:nodes b) n) (= l (get (:nodes b) n))))
                 (:nodes a))
         (every? (fn [[e k]] (<= k (get eb e 0))) ea))))
