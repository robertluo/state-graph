(ns robertluo.state-graph.draw-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph.shape :as shape]
            [clojure.string :as str]
            [robertluo.state-graph.check :refer [draw! dot]]))

(defspec draw-dot-save-matches-dot-source 15
  (prop/for-all [n (gen/choose 2 5)]
    (let [build-shape (fn [n]
                         (apply shape/shape
                                (concat
                                 (map (fn [i]
                                        (shape/state (keyword (str "s" i)) [:map]
                                                     (cond-> {}
                                                       (zero? i) (assoc :initial true)
                                                       (= i (dec n)) (assoc :final true))))
                                      (range n))
                                 [(shape/event :go [:map])]
                                 (map (fn [i]
                                        (shape/transition (keyword (str "s" i)) :go (keyword (str "s" (inc i)))))
                                      (range (dec n))))))
          sh (build-shape n)
          f (str "/tmp/draw-spec-test-" n "-" (System/nanoTime) ".dot")]
      (draw! sh {:save {:filename f :format :dot}})
      (let [content (slurp f)
            expected (dot sh)]
        (.delete (java.io.File. f))
        (= content expected)))))

(deftest draw-dot-save-returns-nil-and-starts-with-digraph
  (let [sh (shape/shape (shape/state :draft [:map] {:initial true})
                         (shape/state :done [:map] {:final true})
                         (shape/event :submit [:map])
                         (shape/transition :draft :submit :done))
        f (str "/tmp/draw-nil-test-" (System/nanoTime) ".dot")
        result (draw! sh {:save {:filename f :format :dot}})]
    (is (nil? result))
    (is (clojure.string/starts-with? (slurp f) "digraph"))
    (.delete (java.io.File. f))))

(deftest draw-png-save-writes-nonempty-file
  (let [sh (shape/shape (shape/state :draft [:map] {:initial true})
                         (shape/state :done [:map] {:final true})
                         (shape/event :submit [:map])
                         (shape/transition :draft :submit :done))
        f (str "/tmp/draw-png-test-" (System/nanoTime) ".png")]
    (draw! sh {:save {:filename f :format :png}})
    (is (pos? (.length (java.io.File. f))))
    (.delete (java.io.File. f))))

(deftest draw-dot-save-content-independent-of-repeated-calls
  (let [sh (shape/shape (shape/state :a [:map] {:initial true})
                         (shape/state :b [:map] {:final true})
                         (shape/event :go [:map])
                         (shape/transition :a :go :b))
        f1 (str "/tmp/draw-idem-test-1-" (System/nanoTime) ".dot")
        f2 (str "/tmp/draw-idem-test-2-" (System/nanoTime) ".dot")]
    (draw! sh {:save {:filename f1 :format :dot}})
    (draw! sh {:save {:filename f2 :format :dot}})
    (let [c1 (slurp f1)
          c2 (slurp f2)]
      (.delete (java.io.File. f1))
      (.delete (java.io.File. f2))
      (is (= c1 c2)))))