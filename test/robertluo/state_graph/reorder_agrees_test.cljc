(ns robertluo.state-graph.reorder-agrees-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph.shapes :as shape]
            [robertluo.state-graph.crank :refer [reorder-agrees]]))

(deftest reorder-agrees-examples
  (testing "given calls return given results"
    (is (= {:b-then-a {:id :x, :b 2, :a 1}, :agree true, :a-then-b {:id :x, :b 2, :a 1}}
           (reorder-agrees (shape/shape (shape/state :s [:map] {:initial true})
                                        (shape/state :ta [:map [:a :int]])
                                        (shape/state :tb [:map [:b :int]])
                                        (shape/state :x [:map [:a :int] [:b :int]] {:final true})
                                        (shape/event :a [:map [:a :int]])
                                        (shape/event :b [:map [:b :int]])
                                        (shape/transition :s :a :ta)
                                        (shape/transition :s :b :tb)
                                        (shape/transition :ta :b :x)
                                        (shape/transition :tb :a :x))
                           {:id :s} {:id :a, :a 1} {:id :b, :b 2})))
    (is (= {:b-then-a {:id :cancelled}, :agree false, :a-then-b {:id :running}}
           (reorder-agrees (shape/shape (shape/state :idle [:map] {:initial true})
                                        (shape/state :running [:map] {:final true})
                                        (shape/state :cancelled [:map] {:final true})
                                        (shape/event :start [:map])
                                        (shape/event :cancel [:map])
                                        (shape/transition :idle :start :running)
                                        (shape/transition :idle :cancel :cancelled))
                           {:id :idle} {:id :start} {:id :cancel})))
    (is (true? (:agree (reorder-agrees (shape/shape (shape/state :sum [:map [:n {:combine +, :combine/commutes true} :int]] {:initial true})
                                                    (shape/event :add [:map [:n :int]])
                                                    (shape/transition :sum :add :sum))
                                       {:n 0, :id :sum} {:n 1, :id :add} {:n 2, :id :add}))))))

(deftest reorder-agrees-shape
  (testing "result map always has the three required keys"
    (let [shp (shape/shape (shape/state :sum [:map [:n {:combine +, :combine/commutes true} :int]] {:initial true})
                            (shape/event :add [:map [:n :int]])
                            (shape/transition :sum :add :sum))
          result (reorder-agrees shp {:n 0, :id :sum} {:n 1, :id :add} {:n 2, :id :add})]
      (is (contains? result :a-then-b))
      (is (contains? result :b-then-a))
      (is (contains? result :agree))
      (is (boolean? (:agree result))))))

(defspec reorder-agrees-commutative-sum-agrees 100
  (prop/for-all [n (gen/choose -1000 1000)
                 a (gen/choose -1000 1000)
                 b (gen/choose -1000 1000)]
    (let [shp (shape/shape (shape/state :sum [:map [:n {:combine +, :combine/commutes true} :int]] {:initial true})
                            (shape/event :add [:map [:n :int]])
                            (shape/transition :sum :add :sum))
          result (reorder-agrees shp {:n n, :id :sum} {:n a, :id :add} {:n b, :id :add})]
      (and (:agree result)
           (= (:a-then-b result) (:b-then-a result))
           (= (:a-then-b result) {:id :sum, :n (+ n a b)})))))

(defspec reorder-agrees-agree-flag-consistent-with-states 100
  (prop/for-all [n (gen/choose -500 500)
                 a (gen/choose -500 500)
                 b (gen/choose -500 500)]
    (let [shp (shape/shape (shape/state :sum [:map [:n {:combine +, :combine/commutes true} :int]] {:initial true})
                            (shape/event :add [:map [:n :int]])
                            (shape/transition :sum :add :sum))
          result (reorder-agrees shp {:n n, :id :sum} {:n a, :id :add} {:n b, :id :add})]
      (= (:agree result) (= (:a-then-b result) (:b-then-a result))))))
