(ns build
  "The release, as the author's other libraries do it: `clojure -T:build ci` cleans, runs
   every suite and builds the jar; `clojure -T:build deploy` sends it to Clojars under
   io.github.robertluo. The version is 0.1 followed by the commit count, so every commit
   is a version and none is typed."
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as cb]))

(defn project
  "The project's coordinates and licence, merged under `opts`."
  [opts]
  (merge {:lib      'io.github.robertluo/state-graph
          :version  (format "0.1.%s" (b/git-count-revs nil))
          :scm      {:url "https://github.com/robertluo/state-graph"}
          :pom-data [[:licenses
                      [:license
                       [:name "MIT License"]
                       [:url "https://opensource.org/license/mit/"]]]]}
         opts))

(defn tests
  "Every suite: the JVM's two — the fast one, then the gate, which shells out to graphviz —
   and then the ClojureScript one, every .cljc test namespace on node."
  [opts]
  (-> opts
      (cb/run-task [:dev :test])
      (cb/run-task [:cljs-test])))

(defn ci
  [opts]
  (-> opts project cb/clean tests cb/jar))

(defn deploy
  [opts]
  (-> opts project cb/deploy))
