(ns stencil.postprocess.html
  "Replaces results of html() calls with external part relationships."
  (:require [clojure.zip :as zip]
            [clojure.data.xml :as xml]
            [stencil.functions :refer [call-fn]]
            [stencil.types :refer [control?]]
            [stencil.util :refer :all]
            [stencil.ooxml :as ooxml]))

(defrecord HtmlChunk [content])
(defmethod control? HtmlChunk [x] true)
(defmethod call-fn "html" [_ content] (->HtmlChunk content))

;; (def legal-tags #{"b" "i" "u" "s" "sup" "sub"})

;; throws an exception on invalid xml tags.
;; (defn- validate-tags [xml-tree])

(defn- parse-html [xml]
  (doto (xml/parse-str (.replaceAll (str xml) "<br>" "<br/>"))
    #_(validate-tags)))

;; (defn- color [c] {:tag :w/color :attrs {ooxml/val (str c)}})

(defn- walk-children [xml]
  (if (map? xml)
    ;; TODO: imepl
    (for [c (:content xml)
          x (walk-children c)]
      (update x :path conj (:tag xml)))
    [{:text xml}]))

(defn html->ooxml-runs
  "Parses html string and returns a seq of ooxml run elements.
   Parameter base-style is the default styling for each run."
  [html base-style]
  (when (seq html)
    (let [ch (walk-children (parse-html (str "<span>" html "</span>")))]
      (for [{:keys [text path]} ch]
        (let [prs (cond-> (set base-style)
                    (some #{:b :em} path)
                    (conj {:tag ooxml/b :attrs {ooxml/val "true"}})

                    (some #{:i} path)
                    (conj {:tag ooxml/i :attrs {ooxml/val "true"}})

                    (some #{:s} path)
                    (conj {:tag ooxml/strike :attrs {ooxml/val "true"}})

                    (some #{:u} path)
                    (conj {:tag ooxml/u :attrs {ooxml/val "single"}})

                    (some #{:sup} path)
                    (conj {:tag ooxml/vertAlign :attrs {ooxml/val "superscript"}})

                    (some #{:sub} path)
                    (conj {:tag ooxml/vertAlign :attrs {ooxml/val "subscript"}}))]
          {:tag ooxml/r
           :content [{:tag ooxml/rPr :content (vec prs)}
                     {:tag ooxml/t :content [(str text)]}]})))))

(defn fix-html-chunk [chunk-loc]
  (assert (instance? HtmlChunk (zip/node chunk-loc)))
  (let [lefts (zip/lefts chunk-loc)
        rights (zip/rights chunk-loc)

        t    (zip/node (zip/up chunk-loc))
        r    (zip/node (zip/up (zip/up chunk-loc)))

        ;;  t elems
        lefts1 (remove (comp #{ooxml/rPr} :tag) (zip/lefts (zip/up chunk-loc)))
        rights1 (zip/rights (zip/up chunk-loc))

        style (some #(when (= ooxml/rPr (:tag %)) %) (:content r))
        ooxml-runs (html->ooxml-runs (:content (zip/node chunk-loc)) (:content style))

        ->t (fn [xs]
              ;; TODO: kikapcsolni majd!
              (assert (every? string? xs))
              {:tag ooxml/t :content (vec xs)})
        ->run (fn [cts]
                ;; TODO: kikapcsolni majd
                (assert (every? map? cts) (str "Not maps" (mapv type cts)))
                (assoc r :content (vec (cons style cts))))]
    (assert (= ooxml/t (:tag t)))
    (assert (= ooxml/r (:tag r)))
    (assert (every? string? lefts) )
    (assert (every? string? rights))
    (-> chunk-loc
       (zip/up) ;; t
       (zip/up) ;; r

       (cond-> (seq lefts1) (zip/insert-left (->run lefts1)))
       (cond-> (seq lefts) (zip/insert-left (->run [(->t lefts)])))

       (cond-> (seq rights1) (zip/insert-right (->run rights1)))
       (cond-> (seq rights) (zip/insert-right (->run [(->t rights)])))


       (as-> * (reduce zip/insert-right * (reverse ooxml-runs)))
       (zip/remove)
       )))

(defn fix-html-chunks [xml-tree] (dfs-walk-xml-node xml-tree #(instance? HtmlChunk %) fix-html-chunk))
