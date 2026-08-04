# org-openstreetmap-overpass

**OpenStreetMap の Overpass API クライアント**（純 `.cljc` のクエリ組み立て +
応答正規化、nbb の I/O アダプタ）。

origin 面の repo。名前は authority の登録可能ドメイン `openstreetmap.org` を
逆順にした `org-openstreetmap` + 主題 `overpass`。API 自体は
`overpass-api.de` で運用されるが、タグ体系（`power=pole` /
`man_made=utility_pole`）とデータの authority は OSM 側にある。

## なぜ在るか

`denchu`（電柱広告）の在庫候補を、**鍵なしで今日集められる唯一の広域ソース**が
OSM だから。Mapillary の画像検出（`com-mapillary-graph-api`）は access token を
要するが、Overpass は認証なしで叩ける。両者は `loop-denchu-survey` が束ねる。

## 設計

- **I/O は `fetch.cljs` にしか無い。** `core.cljc` は「どの QL を投げるか」
  「返った JSON をどう読むか」だけを決める純関数で、ネットワーク無しで検査できる。
- **kind は選択子ではなくタグから決める。** 複数の選択子が同じ node を返しても
  分類が揺れない。
- **分類できなかった element を黙って落とさず数える**（`:unclassified`）。
  落ちた数が見えないと「この地域に柱が無い」と「この地域の柱を読めなかった」が
  区別できない。
- **`operator` タグの充足率を測る**（`operator-coverage`）。日本の電柱の多くは
  `operator` を持たないので、所有者不明の割合は survey が正直に申告すべき数値。
- **共有インフラへの節度**: 識別可能な User-Agent、呼び出し間隔（既定 1200ms）、
  429/504 は 1 度だけ retry してそれ以上は諦める。

## 使う

```clojure
(require '[org-openstreetmap-overpass.core :as ov])

(ov/ql {:south 35.680 :west 139.765 :north 35.683 :east 139.769})
;; => "[out:json][timeout:60];\n(\n  node[\"power\"=\"pole\"](35.68,139.765,35.683,139.769);\n ...

;; nbb から実際に叩く
(require '[org-openstreetmap-overpass.fetch :as f])
(-> (f/fetch-poles bbox) (.then (fn [r] (count (:observations r)))))
```

送電鉄塔（`power=tower`）は電柱ではないので既定では引かない。街路灯
（`highway=street_lamp`）は誤マッピング調査のため `:kinds` で明示した時だけ引く。

## テスト

```bash
nbb --classpath src:test test/run.cljs     # 8 tests / 27 assertions
```

データは OpenStreetMap contributors 提供、ODbL。取得したデータを再配布する場合は
出典表示とライセンス条件に従うこと。

MIT。
