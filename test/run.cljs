#!/usr/bin/env nbb
;; nbb --classpath src:test test/run.cljs
(require '[clojure.test :as t] 'org-openstreetmap-overpass.core-test)

(let [{:keys [fail error]} (t/run-tests 'org-openstreetmap-overpass.core-test)]
  (js/process.exit (if (pos? (+ fail error)) 1 0)))
