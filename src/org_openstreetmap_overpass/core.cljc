(ns org-openstreetmap-overpass.core
  "OpenStreetMap の Overpass API に対する **純粋な** クエリ組み立てと応答正規化。

  この ns は I/O を持たない。ネットワークは `org-openstreetmap-overpass.fetch`
  （nbb）が担い、ここは『どの QL を投げるか』『返ってきた JSON をどう読むか』
  だけを決める。分けている理由は、クエリの正しさをネットワーク無しで検査
  できるようにするため。

  電柱に対応する OSM のタグは 1 つではない（実測されている実務差）:

    power=pole            配電柱。日本の電柱の大半はこれ
    man_made=utility_pole 電力/通信を問わない柱として使われることがある
    power=tower           送電鉄塔。電柱ではないので既定では引かない
    man_made=street_lamp  街路灯。電柱ではないが誤マッピングが実在するため
                          `:street-light` として**別 kind で**引ける

  `operator` タグがあれば所有者が分かるが、**多くの柱には付いていない**。
  付いていないことを『所有者不明』として持ち上げるのがこの ns の仕事で、
  地域から推測して埋めることはしない。"
  (:require [clojure.string :as str]))

(def default-endpoint
  "公開エンドポイント。Overpass API 自体は overpass-api.de で運用されるが、
  データとタグ体系の authority は openstreetmap.org（repo 名の由来）。"
  "https://overpass-api.de/api/interpreter")

(def mirrors
  ["https://overpass-api.de/api/interpreter"
   "https://overpass.kumi.systems/api/interpreter"])

(def pole-selectors
  "kind → OSM タグ選択子。`:street-light` は既定では引かない（`kinds` で明示）。"
  {:utility-pole [["power" "pole"] ["man_made" "utility_pole"]]
   :pole [["man_made" "mast"]]
   :street-light [["highway" "street_lamp"]]})

(def default-kinds [:utility-pole])

(defn bbox-str
  "Overpass の bbox は (south,west,north,east)。GeoJSON の順とは違うので
  ここで一度だけ変換し、呼び出し側に順序を覚えさせない。"
  [{:keys [south west north east]}]
  (str south "," west "," north "," east))

(defn valid-bbox? [{:keys [south west north east]}]
  (and (number? south) (number? west) (number? north) (number? east)
       (< south north) (< west east)
       (<= -90 south 90) (<= -90 north 90)
       (<= -180 west 180) (<= -180 east 180)))

(defn ql
  "bbox + kind 列 → Overpass QL。`out:json` 固定、`timeout` は明示。
  node だけを引く（柱は node としてマッピングされる）。"
  ([bbox] (ql bbox {}))
  ([bbox {:keys [kinds timeout] :or {kinds default-kinds timeout 60}}]
   (when-not (valid-bbox? bbox)
     (throw (ex-info "invalid bbox" {:bbox bbox})))
   (let [b (bbox-str bbox)
         clauses (for [k kinds
                       [tag v] (get pole-selectors k)]
                   (str "  node[\"" tag "\"=\"" v "\"](" b ");"))]
     (when (empty? clauses)
       (throw (ex-info "no selectors for kinds" {:kinds kinds})))
     (str "[out:json][timeout:" timeout "];\n"
          "(\n" (str/join "\n" clauses) "\n);\n"
          "out body;\n"))))

(defn tags->kind
  "タグ map → kind。どの選択子で引かれたかではなく**タグそのもの**から
  決めるので、複数選択子が同じ node を返しても分類が揺れない。"
  [tags]
  (cond
    (= "pole" (get tags "power")) :utility-pole
    (= "utility_pole" (get tags "man_made")) :utility-pole
    (= "mast" (get tags "man_made")) :pole
    (= "street_lamp" (get tags "highway")) :street-light
    :else nil))

(defn element->observation
  "Overpass の element → `denchu.pole` が受け取る観測。分類できない element は
  `nil`（呼び出し側が数える）。座標が無い element も `nil`。"
  [{:strs [type id lat lon tags] :as _el}]
  (let [tags (or tags {})
        kind (tags->kind tags)]
    (when (and kind (number? lat) (number? lon) (= "node" type))
      {:obs/source :osm
       :obs/source-id (str "node/" id)
       :obs/lat lat
       :obs/lon lon
       :obs/kind kind
       :obs/tags tags
       :obs/evidence-url (str "https://www.openstreetmap.org/node/" id)})))

(defn parse-response
  "Overpass の JSON（keyword 化していない string キーの map）→
  `{:observations [...] :unclassified n :raw-count n}`。

  分類できなかった element を**黙って落とさず数える** — 落ちた数が見えないと
  『この地域には柱が無い』と『この地域の柱を読めなかった』が区別できない。"
  [json]
  (let [els (get json "elements" [])
        obs (keep element->observation els)]
    {:observations (vec obs)
     :raw-count (count els)
     :unclassified (- (count els) (count obs))
     :generator (get json "generator")
     :osm3s (get json "osm3s")}))

(defn observations-with-operator
  "`operator` タグを持つ観測だけ。所有者を確定できる割合を測るために使う
  （収集率の分母を可視化する目的で、フィルタとしては使わない）。"
  [observations]
  (filterv #(seq (str (get-in % [:obs/tags "operator"] ""))) observations))

(defn operator-coverage
  "所有者タグの充足率。survey が『所有者不明が多い』ことを申告するための値。"
  [observations]
  (let [total (count observations)
        with (count (observations-with-operator observations))]
    {:total total
     :with-operator with
     :without-operator (- total with)
     :ratio (if (pos? total) (/ (double with) total) 0.0)}))
