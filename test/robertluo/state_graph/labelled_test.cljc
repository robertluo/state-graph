(ns robertluo.state-graph.labelled-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph.shapes :as shape]
            [clojure.string :as str]
            [robertluo.state-graph.check :refer [labelled]]
            [robertluo.state-graph.test-support :as ts]))

(defspec labelled-edges-are-the-declared-transitions-and-completions 100
  ;; Read off the PARTS: an event edge is labelled with its event (the generator writes no
  ;; guards), a completion is marked :done and labelled with its outcome in brackets, or with
  ;; nothing where it is unconditional. A multiset, so an edge drawn twice or not at all fails.
  (prop/for-all [parts ts/gen-completing-shape]
                (= (frequencies
                    (concat (for [t (ts/parts-of :transition parts)]
                              [(:from t) (:to t) false (name (:event t))])
                            (for [[from outcome to] (ts/completions-of parts)]
                              [from to true (if outcome (str "[" (name outcome) "]") "")])))
                   (frequencies
                    (map (juxt :from :to :done :label)
                         (:edges (labelled (apply shape/shape parts))))))))

(deftest labelled-basic-example
  (testing "simple linear shape as given"
    (let [sh (shape/shape (shape/state :draft [:map] {:initial true})
                           (shape/state :done [:map] {:final true})
                           (shape/event :submit [:map])
                           (shape/transition :draft :submit :done))
          d (labelled sh)]
      (is (= {:nodes [{:label "\u25b8 draft" :id :draft} {:label "\u25fc done" :id :done}]
              :edges [{:done false :from :draft :label "submit" :to :done}]}
             d)))))

(deftest labelled-nested-example
  (testing "state nesting a machine with a single-target completion"
    (let [sh (shape/shape (shape/state :work [:map] {:done :end
                                                       :machine (shape/shape (shape/state :go [:map] {:final true :initial true}))
                                                       :initial true})
                           (shape/state :end [:map] {:final true}))
          d (labelled sh)]
      (is (= {:nodes [{:nested 1 :label "\u25b8 \u229e 1 work" :id :work} {:label "\u25fc end" :id :end}]
              :edges [{:done true :from :work :label "" :to :end}]}
             d)))))

(deftest labelled-edge-label-plain
  (testing "an unconditional, unnested transition is labelled with just the event name"
    (let [sh (shape/shape (shape/state :a [:map] {:initial true})
                           (shape/state :b [:map] {:final true})
                           (shape/event :go [:map])
                           (shape/transition :a :go :b))
          d (labelled sh)
          e (first (:edges d))]
      (is (= "go" (:label e)))
      (is (= false (:done e)))
      (is (= :a (:from e)))
      (is (= :b (:to e))))))

(deftest labelled-nested-count
  (testing ":nested is the count of the child machine's states, and the label shows it"
    (let [child (shape/shape (shape/state :x [:map] {:initial true :final true}))
          sh (shape/shape (shape/state :work [:map] {:initial true :machine child :done :end})
                           (shape/state :end [:map] {:final true}))
          d (labelled sh)
          work-node (first (filter #(= :work (:id %)) (:nodes d)))]
      (is (= 1 (:nested work-node)))
      (is (clojure.string/includes? (:label work-node) "\u229e 1")))))

(defspec labelled-counts-match-shape 40
  (prop/for-all [n (gen/choose 2 10)]
    (let [ids (mapv #(keyword (str "s" %)) (range n))
          states (map-indexed (fn [i id]
                                 (shape/state id [:map]
                                              (cond-> {}
                                                (zero? i) (assoc :initial true)
                                                (= i (dec n)) (assoc :final true))))
                               ids)
          events (map #(shape/event (keyword (str "e" %)) [:map]) (range (dec n)))
          transitions (map (fn [i] (shape/transition (nth ids i) (keyword (str "e" i)) (nth ids (inc i))))
                            (range (dec n)))
          sh (apply shape/shape (concat states events transitions))
          d (labelled sh)]
      (and (= n (count (:nodes d)))
           (= (set ids) (set (map :id (:nodes d))))
           (= (dec n) (count (:edges d)))
           (every? #(clojure.string/ends-with? (:label %) (name (:id %))) (:nodes d))
           (every? #(boolean? (:done %)) (:edges d))
           (every? string? (map :label (:edges d)))))))

(defspec labelled-markers-present 40
  (prop/for-all [n (gen/choose 2 6)]
    (let [ids (mapv #(keyword (str "t" %)) (range n))
          states (map-indexed (fn [i id]
                                 (shape/state id [:map]
                                              (cond-> {}
                                                (zero? i) (assoc :initial true)
                                                (= i (dec n)) (assoc :final true))))
                               ids)
          events (map #(shape/event (keyword (str "u" %)) [:map]) (range (dec n)))
          transitions (map (fn [i] (shape/transition (nth ids i) (keyword (str "u" i)) (nth ids (inc i))))
                            (range (dec n)))
          sh (apply shape/shape (concat states events transitions))
          d (labelled sh)
          by-id (into {} (map (juxt :id identity) (:nodes d)))
          middle-ids (if (> n 2) (butlast (rest ids)) [])]
      (and (clojure.string/includes? (:label (by-id (first ids))) "\u25b8")
           (clojure.string/includes? (:label (by-id (last ids))) "\u25fc")
           (every? #(not (clojure.string/includes? (:label (by-id %)) "\u25b8")) middle-ids)
           (every? #(not (clojure.string/includes? (:label (by-id %)) "\u25fc")) middle-ids)))))
