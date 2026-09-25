(ns robertluo.state-graph.graph-test
  (:require [clojure.set :as set]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer [use-fixtures]]
            [robertluo.state-graph.graph :as graph]
            [robertluo.state-graph.test-support :as ts]))

(use-fixtures :once ts/instrumented)

;;; ------------------------------------------------------------------ generators

(def gen-graph
  "A small labelled multigraph: up to seven nodes over THREE node labels and up to a dozen
   edges over THREE edge labels, so ties, parallel edges, self-loops and look-alike nodes are
   common rather than rare."
  (gen/bind (gen/choose 1 7)
            (fn [n]
              (let [ids (mapv #(keyword (str "n" %)) (range n))]
                (gen/fmap (fn [[labels edges]]
                            {:nodes (zipmap ids labels)
                             :edges (mapv (fn [[f t l]] {:from f :to t :l l}) edges)})
                          (gen/tuple (gen/vector (gen/choose 0 2) n)
                                     (gen/vector (gen/tuple (gen/elements ids) (gen/elements ids)
                                                            (gen/choose 0 2))
                                                 0 12)))))))

(defn- arrows [g] (set (map (juxt :from :to) (:edges g))))

(defn- renamed
  "`g` with every node renamed by `f` and its edges in another order."
  [g f order]
  {:nodes (update-keys (:nodes g) f)
   :edges (mapv #(-> % (update :from f) (update :to f)) (map (vec (:edges g)) order))})

(def gen-renamed-pair
  "A graph, a copy of it with every node renamed onto fresh ids and its edges shuffled, and
   the renaming."
  (gen/bind gen-graph
            (fn [g]
              (gen/fmap (fn [[ids order]]
                          (let [f (zipmap (keys (:nodes g)) (map #(keyword (str "r" (name %))) ids))]
                            [g (renamed g f order) f]))
                        (gen/tuple (gen/shuffle (keys (:nodes g)))
                                   (gen/shuffle (range (count (:edges g)))))))))

(defn- carries?
  "Does renaming `a` by `m` give exactly `b` — every node's label, and the edges counted?"
  [a b m]
  (and (= (count m) (count (:nodes a)) (count (:nodes b)))
       (= (count (set (vals m))) (count m))
       (every? (fn [[n l]] (= l (get (:nodes b) (m n)))) (:nodes a))
       (= (frequencies (map #(-> % (update :from m) (update :to m)) (:edges a)))
          (frequencies (:edges b)))))

;;; ----------------------------------------------------------------------- paths

(defspec a-path-is-a-shortest-chain-of-edges-to-every-reachable-node 200
  ;; The model: the nodes within k edges of `from`, grown one level at a time as SETS until
  ;; nothing changes. A node's distance is the level it first appears at.
  (prop/for-all [g gen-graph]
                (let [from (first (sort (keys (:nodes g))))
                      levels (->> #{from}
                                  (iterate #(into % (for [[a b] (arrows g) :when (% a)] b)))
                                  (partition 2 1)
                                  (take-while (fn [[x y]] (not= x y)))
                                  (map second)
                                  (cons #{from}))
                      distance (fn [n] (count (take-while #(not (% n)) levels)))
                      ps (graph/paths g from)]
                  (and (= (ts/closure (arrows g) [from]) (set (keys ps)))
                       (every? (fn [[to p]]
                                 (and (= (count p) (distance to))
                                      (= from (:from (first p) from))
                                      (= to (:to (peek p) from))
                                      (every? (fn [[x y]] (= (:to x) (:from y))) (partition 2 1 p))
                                      (every? (set (:edges g)) p)))
                               ps)))))

;;; ------------------------------------------------------------------ structure

(defspec components-partition-the-nodes-by-mutual-reachability 200
  (prop/for-all [g gen-graph]
                (let [cs (graph/components g)
                      reach (fn [n] (ts/closure (arrows g) [n]))
                      comp-of (into {} (for [c cs, n c] [n c]))]
                  (and (= (set (keys (:nodes g))) (apply set/union cs))
                       (= (count (keys (:nodes g))) (reduce + (map count cs)))
                       (every? (fn [[a b]] (= (= (comp-of a) (comp-of b))
                                              (boolean (and ((reach a) b) ((reach b) a)))))
                               (for [a (keys (:nodes g)), b (keys (:nodes g))] [a b]))))))

(defspec topsort-orders-every-edge-forward-or-finds-a-cycle 200
  ;; A cycle is a node that reaches itself in ONE OR MORE steps — a self-loop included.
  (prop/for-all [g gen-graph]
                (let [cyclic? (some (fn [n] ((ts/closure (arrows g) (for [[a b] (arrows g) :when (= a n)] b)) n))
                                    (keys (:nodes g)))
                      order (graph/topsort g)
                      at (zipmap order (range))]
                  (if cyclic?
                    (nil? order)
                    (and (= (sort (keys (:nodes g))) (sort order))
                         (every? (fn [[a b]] (< (at a) (at b))) (arrows g)))))))

;;; ----------------------------------------------------------------- comparison

(defspec a-renamed-copy-is-found-isomorphic 200
  (prop/for-all [[a b _] gen-renamed-pair]
                (let [m (graph/isomorphism a b)]
                  (and (some? m) (carries? a b m)))))


(def gen-rewired-pair
  "A graph and a renamed copy with the TARGETS OF TWO EDGES OF ONE LABEL SWAPPED — u->v and
   x->y become u->y and x->v. Every node keeps its label and the labels of the edges in and
   out of it, so no signature tells the two apart, and only the pairs can: the case the
   search's own check exists for, which unrelated graphs almost never reach."
  (gen/fmap (fn [[a b _]]
              (let [es (vec (:edges b))
                    [i j] (first (for [i (range (count es)) j (range (inc i) (count es))
                                       :when (= (:l (es i)) (:l (es j)))]
                                   [i j]))]
                [a (if i
                     (assoc b :edges (-> es
                                         (assoc-in [i :to] (:to (es j)))
                                         (assoc-in [j :to] (:to (es i)))))
                     b)]))
            gen-renamed-pair))

(defspec an-answered-isomorphism-holds-where-no-signature-tells-them-apart 200
  (prop/for-all [[a b] gen-rewired-pair]
                (let [m (graph/isomorphism a b)]
                  (or (nil? m) (carries? a b m)))))

(defspec a-copy-with-one-edge-relabelled-is-not-isomorphic 200
  (prop/for-all [[a b _] (gen/such-that #(seq (:edges (first %))) gen-renamed-pair)]
                (nil? (graph/isomorphism a (update b :edges #(assoc-in (vec %) [0 :l] :fresh))))))

(defspec a-part-of-a-graph-is-a-subgraph-and-a-foreign-edge-is-not 200
  (prop/for-all [[g keep-nodes keep-edges] (gen/bind gen-graph
                                                     (fn [g]
                                                       (gen/tuple (gen/return g)
                                                                  (gen/vector gen/boolean (count (:nodes g)))
                                                                  (gen/vector gen/boolean (count (:edges g))))))]
                (let [nodes (into {} (keep-indexed (fn [i [n l]] (when (nth keep-nodes i) [n l]))
                                                   (sort-by key (:nodes g))))
                      edges (vec (keep-indexed (fn [i e] (when (and (nth keep-edges i)
                                                                    (contains? nodes (:from e))
                                                                    (contains? nodes (:to e)))
                                                           e))
                                               (:edges g)))
                      part {:nodes nodes :edges edges}]
                  (and (graph/subgraph? part g)
                       (or (empty? nodes)
                           (let [n (key (first nodes))]
                             (not (graph/subgraph? (update part :edges conj {:from n :to n :l :fresh}) g))))))))
