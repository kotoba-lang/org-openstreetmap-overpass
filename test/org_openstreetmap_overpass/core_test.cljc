(ns org-openstreetmap-overpass.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [org-openstreetmap-overpass.core :as ov]))

(def tokyo {:south 35.6800 :west 139.7650 :north 35.6830 :east 139.7690})

(deftest bbox-order-is-overpass-order
  (is (= "35.68,139.765,35.683,139.769" (ov/bbox-str tokyo))))

(deftest invalid-bbox-is-rejected
  (is (false? (ov/valid-bbox? {:south 36 :west 139 :north 35 :east 140})))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (ov/ql {:south 36 :west 139 :north 35 :east 140}))))

(deftest ql-includes-both-pole-tags
  (let [q (ov/ql tokyo)]
    (is (str/includes? q "[out:json][timeout:60]"))
    (is (str/includes? q "node[\"power\"=\"pole\"]"))
    (is (str/includes? q "node[\"man_made\"=\"utility_pole\"]"))
    (is (str/includes? q "out body;"))
    (testing "既定では街路灯も送電鉄塔も引かない"
      (is (not (str/includes? q "street_lamp")))
      (is (not (str/includes? q "\"power\"=\"tower\""))))))

(deftest street-lights-are-opt-in
  (let [q (ov/ql tokyo {:kinds [:utility-pole :street-light]})]
    (is (str/includes? q "street_lamp"))))

(deftest kind-is-decided-by-tags-not-by-selector
  (is (= :utility-pole (ov/tags->kind {"power" "pole"})))
  (is (= :utility-pole (ov/tags->kind {"man_made" "utility_pole"})))
  (is (= :street-light (ov/tags->kind {"highway" "street_lamp"})))
  (is (nil? (ov/tags->kind {"amenity" "bench"}))))

(def sample-response
  {"generator" "Overpass API"
   "elements"
   [{"type" "node" "id" 1234 "lat" 35.6812 "lon" 139.7671
     "tags" {"power" "pole" "operator" "東京電力パワーグリッド"}}
    {"type" "node" "id" 1235 "lat" 35.6813 "lon" 139.7672
     "tags" {"power" "pole"}}
    {"type" "node" "id" 1236 "lat" 35.6814 "lon" 139.7673
     "tags" {"amenity" "bench"}}
    {"type" "way" "id" 99 "tags" {"power" "line"}}]})

(deftest parse-counts-what-it-could-not-classify
  (let [{:keys [observations raw-count unclassified]} (ov/parse-response sample-response)]
    (is (= 2 (count observations)))
    (is (= 4 raw-count))
    (is (= 2 unclassified))
    (testing "落ちた数が見えないと『柱が無い』と『読めなかった』を区別できない"
      (is (pos? unclassified)))))

(deftest observation-shape-matches-denchu
  (let [o (first (:observations (ov/parse-response sample-response)))]
    (is (= :osm (:obs/source o)))
    (is (= "node/1234" (:obs/source-id o)))
    (is (= :utility-pole (:obs/kind o)))
    (is (= "https://www.openstreetmap.org/node/1234" (:obs/evidence-url o)))
    (is (= "東京電力パワーグリッド" (get-in o [:obs/tags "operator"])))))

(deftest operator-coverage-is-measured-not-assumed
  (let [obs (:observations (ov/parse-response sample-response))
        c (ov/operator-coverage obs)]
    (is (= 2 (:total c)))
    (is (= 1 (:with-operator c)))
    (is (= 1 (:without-operator c)))
    (is (= 0.5 (:ratio c)))))

;; ── 一般化（任意の選択子 + 呼び出し側の分類器） ─────────────────────

(deftest selectors-are-supplied-by-the-caller
  (let [q (ov/ql tokyo {:selectors [["advertising" "billboard"] ["advertising" "board"]]})]
    (is (str/includes? q "node[\"advertising\"=\"billboard\"]"))
    (is (str/includes? q "node[\"advertising\"=\"board\"]"))
    (testing "呼び出し側が選択子を渡したら電柱の既定は混ざらない"
      (is (not (str/includes? q "power"))))))

(deftest ways-are-opt-in-and-need-center
  (let [q (ov/ql tokyo {:selectors [["advertising" "billboard"]] :ways? true})]
    (is (str/includes? q "way[\"advertising\"=\"billboard\"]"))
    (is (str/includes? q "out center tags;")))
  (testing "既定は node だけ"
    (is (str/includes? (ov/ql tokyo {:selectors [["advertising" "billboard"]]}) "out body;"))))

(def advertising-response
  {"elements"
   [{"type" "node" "id" 1 "lat" 35.68 "lon" 139.77
     "tags" {"advertising" "billboard" "operator" "株式会社アトレ"}}
    {"type" "way" "id" 2 "center" {"lat" 35.681 "lon" 139.771}
     "tags" {"advertising" "board"}}
    {"type" "node" "id" 3 "lat" 35.682 "lon" 139.772 "tags" {"amenity" "bench"}}]})

(deftest classifier-and-attr-are-injectable
  (let [classify (fn [tags] (case (get tags "advertising")
                              "billboard" :billboard
                              "board" :board
                              nil))
        {:keys [observations unclassified]}
        (ov/parse-response advertising-response {:classify classify :attr :obs/medium})]
    (is (= 2 (count observations)))
    (is (= 1 unclassified))
    (is (= [:billboard :board] (mapv :obs/medium observations)))
    (testing "way は out center の座標を使い、id に type が入る"
      (is (= "way/2" (:obs/source-id (second observations))))
      (is (= 35.681 (:obs/lat (second observations))))
      (is (= "https://www.openstreetmap.org/way/2" (:obs/evidence-url (second observations)))))))

(deftest pole-path-still-works-unchanged
  (let [{:keys [observations]} (ov/parse-response sample-response)]
    (is (= 2 (count observations)))
    (is (= :utility-pole (:obs/kind (first observations))))
    (is (= "node/1234" (:obs/source-id (first observations))))))
