(ns org-openstreetmap-overpass.fetch
  "Overpass API への I/O（nbb / Node）。純粋部分は `core` にある。

  Overpass は無償の共有インフラで、利用規約が節度ある利用を求めている。
  したがってこのアダプタは:
  - 識別可能な User-Agent を必ず送る（匿名の連打をしない）
  - 呼び出し間に最小間隔を空ける（既定 1200ms）
  - 429 / 504 を受けたら**待ってから**1度だけ retry し、それ以上は諦めて
    呼び出し側にエラーを返す（無限 retry でサーバを叩き続けない）"
  (:require [org-openstreetmap-overpass.core :as core]))

(def default-user-agent
  "kotoba-lang/org-openstreetmap-overpass (utility-pole survey; contact: root@junkawasaki.com)")

(def ^:private last-call (atom 0))

(defn- now-ms [] (.getTime (js/Date.)))

(defn- sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- throttle!
  [min-interval-ms]
  (let [wait (max 0 (- (+ @last-call min-interval-ms) (now-ms)))]
    (.then (if (pos? wait) (sleep wait) (js/Promise.resolve nil))
           (fn [_] (reset! last-call (now-ms)) nil))))

(defn post-ql
  "QL 文字列 → parse 済み応答の Promise。`{:endpoint :user-agent
  :min-interval-ms :retry?}`。"
  ([ql-string] (post-ql ql-string {}))
  ([ql-string {:keys [endpoint user-agent min-interval-ms retry? parse-opts]
               :or {endpoint core/default-endpoint
                    user-agent default-user-agent
                    min-interval-ms 1200
                    retry? true
                    parse-opts {}}}]
   (-> (throttle! min-interval-ms)
       (.then (fn [_]
                (js/fetch endpoint
                          #js {:method "POST"
                               :headers #js {"Content-Type" "text/plain; charset=utf-8"
                                             "User-Agent" user-agent}
                               :body ql-string})))
       (.then (fn [res]
                (cond
                  (.-ok res)
                  (.then (.json res) (fn [j] (core/parse-response (js->clj j) parse-opts)))

                  (and retry? (#{429 502 503 504} (.-status res)))
                  (.then (sleep 5000)
                         (fn [_] (post-ql ql-string {:endpoint endpoint
                                                     :user-agent user-agent
                                                     :min-interval-ms min-interval-ms
                                                     :parse-opts parse-opts
                                                     :retry? false})))

                  :else
                  (throw (ex-info "overpass request failed"
                                  {:status (.-status res) :endpoint endpoint})))))) ))

(defn fetch-poles
  "bbox → 電柱の観測。`core/ql` を通すので選択子の正しさはテスト済みの経路を通る。"
  ([bbox] (fetch-poles bbox {}))
  ([bbox opts]
   (post-ql (core/ql bbox (select-keys opts [:kinds :timeout])) opts)))

(defn fetch-features
  "bbox + 任意の選択子 → 観測。屋外広告物の媒体を引くときはこちら
  （`okugai.medium/osm-selectors` と `osm-tags->medium` を渡す）。

     (fetch-features bbox {:selectors (medium/osm-selectors ids)
                           :ways? true
                           :parse-opts {:classify medium/osm-tags->medium
                                        :attr :obs/medium}})"
  ([bbox] (fetch-features bbox {}))
  ([bbox opts]
   (post-ql (core/ql bbox (select-keys opts [:selectors :kinds :timeout :ways?])) opts)))
