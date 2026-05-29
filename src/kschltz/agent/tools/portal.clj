(ns kschltz.agent.tools.portal
  "Portal visualization tool — opens djblue/portal inspector windows for
   rich data visualization from the LLM agent.

   Provides:
     1. A :visualize tool registered with the agent so the LLM can display
        data, tables, charts, diffs, etc. during conversations.
     2. Hiccup helper functions so the LLM can compose HTML-like UI in
        Clojure data that Portal renders natively.

   Usage from the REPL:
     (require '[kschltz.agent.core :as agent])
     (def ag (agent/make-agent {...}))
     ;; visualize tool is included by default — no setup needed

   Direct Portal access:
     (require '[kschltz.agent.tools.portal :as portal])
     (def p (portal/open!))              ; Open a Portal window
     (portal/submit! p {:data [1 2 3]})  ; Send data
     (portal/close! p)                    ; Close when done"
  (:require [portal.api :as p]
            [portal.viewer :as pv]
            [kschltz.agent.tools :as tools]
            [clojure.string :as str]
            [cheshire.core :as json]
            [edamame.core :as edamame]
            [malli.core :as m]))

;; ---- Portal Lifecycle ----

(defn open!
  "Open a Portal inspector window. Returns a portal session reference.
   Options map supports:
     :theme     — e.g. :portal.colors/nord (default)
     :port      — HTTP server port (default: random)
     :launcher  — :vs-code, :intellij, :emacs (default: browser)"
  ([] (open! {}))
  ([opts]
   (p/open (into {} (filter val) opts))))

(defn close!
  "Close a Portal inspector window. Pass :all to close all sessions."
  ([portal] (p/close portal) nil)
  ([] (p/close :all) nil))

(defn submit!
  "Submit a value to a Portal inspector. Returns nil (side-effect only)."
  [portal value]
  (p/submit portal value)
  nil)

(defn clear!
  "Clear all values from a Portal inspector."
  [portal]
  (p/clear portal)
  nil)

(defn inspect!
  "Open a new Portal window to inspect a single value."
  [value]
  (p/inspect value)
  nil)

(defn tap-portal!
  "Open a Portal and add it as a tap> target. Returns the portal session.
   Usage:
     (def p (tap-portal!))
     (tap> {:hello :world})"
  ([] (tap-portal! {}))
  ([opts]
   (let [portal (open! opts)]
     (add-tap #'p/submit)
     portal)))

;; ---- Hiccup Helpers ----
;; Portal renders hiccup (Clojure data representing HTML) natively.
;; These helpers let the LLM compose rich visualizations without writing
;; raw HTML strings.

(defn h
  "Create a hiccup element: [tag attrs? content...].
   Tag can be a keyword with CSS classes: :div.card.p-4
   Attrs is an optional map: {:class \"foo\" :style {:color \"red\"}}
   Content can be strings, numbers, nested hiccup, or nil (filtered).

   Examples:
     (h :div \"Hello\")                          ;=> [:div \"Hello\"]
     (h :h1 {:class \"text-xl\"} \"Title\")      ;=> [:h1 {:class \"text-xl\"} \"Title\"]
     (h :ul (h :li \"One\") (h :li \"Two\"))     ;=> [:ul [:li \"One\"] [:li \"Two\"]]"
  [tag & args]
  (let [[attrs children] (if (map? (first args))
                           [(first args) (rest args)]
                           [nil args])
        children' (remove nil? children)]
    (if attrs
      (into [tag attrs] children')
      (into [tag] children'))))

(defn h-fragment
  "Create a list of sibling hiccup elements without a wrapping parent.
   Example:
     (h-fragment (h :p \"One\") (h :p \"Two\"))"
  [& elements]
  (vec (remove nil? elements)))

(defn h-style
  "Create a CSS style map from keyword-value pairs.
   Example:
     (h-style :color \"red\" :font-size \"16px\" :margin-top 8)
     ;=> {:color \"red\" :font-size \"16px\" :margin-top 8}"
  [& kvs]
  (apply hash-map kvs))

;; ---- Pre-built hiccup components ----

(defn h-table
  "Render a data table from column specs and rows.

   cols  — [{:key :name :label \"Name\"} ...]
   rows  — vector of maps

   Example:
     (h-table [{:key :name :label \"Name\"}
               {:key :age  :label \"Age\"}]
              [{:name \"Alice\" :age 30}
               {:name \"Bob\"   :age 25}])"
  [cols rows]
  (let [header (into [:tr]
                     (map (fn [c]
                            [:th {:style {:padding "8px 12px"
                                          :border-bottom "1px solid #ddd"
                                          :text-align "left"}}
                             (or (:label c) (name (:key c)))])
                          cols))
        body   (map (fn [row]
                      (into [:tr]
                            (map (fn [c]
                                   [:td {:style {:padding "8px 12px"
                                                 :border-bottom "1px solid #eee"}}
                                    (str (get row (:key c) ""))])
                                 cols)))
                    rows)]
    [:table {:style {:border-collapse "collapse"
                     :width "100%"
                     :font-family "monospace"}}
     [:thead header]
     (into [:tbody] body)]))

(defn h-card
  "A card component with a title and optional subtitle.

   Example:
     (h-card \"Memory Stats\"
             {:subtitle \"Session: my-session\"
              :content (h-table cols rows)})"
  [title opts]
  (let [subtitle (:subtitle opts)]
    [:div {:style {:border "1px solid #e0e0e0"
                   :border-radius "8px"
                   :padding "16px"
                   :margin-bottom "12px"
                   :font-family "sans-serif"}}
     [:h3 {:style {:margin "0 0 4px 0"}} title]
     (when subtitle
       [:p {:style {:color "#666" :margin "0 0 12px 0" :font-size "14px"}} subtitle])
     (:content opts)]))

(defn h-badge
  "A colored badge/tag label.

   Example:
     (h-badge \"OK\" {:color \"green\"})
     (h-badge \"ERROR\" {:color \"red\" :background \"#ffe0e0\"})"
  [text opts]
  [:span {:style {:display "inline-block"
                  :padding "2px 8px"
                  :border-radius "4px"
                  :font-size "12px"
                  :font-weight "bold"
                  :color (or (:color opts) "#fff")
                  :background-color (or (:background opts)
                                        (case (:color opts)
                                          "green" "#4caf50"
                                          "red" "#f44336"
                                          "blue" "#2196f3"
                                          "orange" "#ff9800"
                                          "#666"))}}
   text])

(defn h-code
  "A code block with optional language label.

   Example:
     (h-code \"(+ 1 2 3)\" {:lang \"clojure\"})"
  [text opts]
  [:pre {:style {:background "#1e1e1e"
                 :color "#d4d4d4"
                 :padding "12px"
                 :border-radius "6px"
                 :overflow-x "auto"
                 :font-size "13px"
                 :font-family "monospace"}}
   (when (:lang opts)
     [:div {:style {:font-size "11px"
                    :color "#888"
                    :margin-bottom "4px"}}
      (:lang opts)])
   [:code text]])

(defn h-progress
  "A progress bar component.

   Example:
     (h-progress 75 {:label \"Complete\" :color \"#4caf50\"})"
  [pct opts]
  (let [clamped (max 0 (min 100 (int pct)))
        color (or (:color opts) "#2196f3")]
    [:div {:style {:width "100%"
                   :margin "8px 0"}}
     (when (:label opts)
       [:div {:style {:font-size "13px"
                      :margin-bottom "4px"
                      :font-family "sans-serif"}}
        (str (:label opts) " — " clamped "%")])
     [:div {:style {:background "#e0e0e0"
                    :border-radius "4px"
                    :height "8px"}}
      [:div {:style {:background color
                     :border-radius "4px"
                     :height "8px"
                     :width (str clamped "%")}}]]]))

(defn h-metric
  "A key metric display — big number + label.

   Example:
     (h-metric \"1,234\" \"Requests today\")"
  [value label]
  [:div {:style {:text-align "center"
                 :padding "12px"}}
   [:div {:style {:font-size "28px"
                  :font-weight "bold"
                  :font-family "sans-serif"}} value]
   [:div {:style {:font-size "13px"
                  :color "#666"
                  :margin-top "4px"}} label]])

(defn h-list
  "A styled list component.

   Example:
     (h-list [\"Item 1\" \"Item 2\" \"Item 3\"] {:ordered true})"
  [items opts]
  (let [tag (if (:ordered opts) :ol :ul)
        style {:padding-left "20px"
               :font-family "sans-serif"}]
    [tag {:style style}
     (for [item items]
       [:li item])]))

(defn h-columns
  "Lay out children in a horizontal row using flexbox.

   Example:
     (h-columns [(h-metric \"42\" \"Errors\")
                 (h-metric \"99%\" \"Uptime\")])"
  [children]
  [:div {:style {:display "flex"}
         :flex-direction "row"
         :gap "16px"
         :flex-wrap "wrap"}
   (for [c (remove nil? children)]
     [:div {:style {:flex "1 1 0"}} c])])

(defn h-detail
  "An expandable detail/summary section.

   Example:
     (h-detail \"API Response\" (h-code json-str {:lang \"json\"}))"
  [summary content]
  [:details {:style {:margin "8px 0"
                     :font-family "sans-serif"}}
   [:summary {:style {:cursor "pointer"
                      :font-weight "bold"}} summary]
   [:div {:style {:margin-top "8px"}} content]])

;; ---- Visualize Tool ----
;; A :visualize tool that the LLM can invoke to display data in Portal.

(defn- coerce-viewer
  "Turn a viewer value into a proper :portal.viewer/xxx keyword.
   Handles strings from LLM JSON args like \":table\" or \"table\"."
  [v]
  (cond
    (nil? v)     nil
    (keyword? v) (if (namespace v)
                   v
                   (keyword "portal.viewer" (name v)))
    (string? v) (let [s (str/trim (str/replace v #"^:" ""))]
                  (when (seq s)
                    (keyword "portal.viewer" s)))
    :else        nil))

(def ^:private edamame-opts
  {:read-cond :allow :auto-resolve 'data})

(def ^:private structured-viewers
  "Viewers that need parsed Clojure data — not a raw string."
  #{:portal.viewer/table :portal.viewer/tree :portal.viewer/hiccup
    :portal.viewer/html :portal.viewer/json :portal.viewer/edn
    :portal.viewer/vega :portal.viewer/vega-lite})

(defn- keywordize-keys
  "Recursively keywordize map keys (JSON tool args use string keys)."
  [x]
  (cond
    (map? x)     (into {} (map (fn [[k v]] [(keyword k) (keywordize-keys v)]) x))
    (vector? x)  (mapv keywordize-keys x)
    (sequential? x) (mapv keywordize-keys x)
    :else x))

(defn- try-parse-data
  "Parse string :data from LLM JSON args into Clojure data.
   Tries edamame (EDN/hiccup), then JSON. Returns {:data :parsed? :error}."
  [data]
  (cond
    (not (string? data))
    {:data (keywordize-keys data) :parsed? true}

    (str/blank? data)
    {:data data :parsed? false :error "data is empty"}

    :else
    (let [trimmed (str/trim data)]
      (try
        (let [parsed (edamame/parse-string trimmed edamame-opts)]
          (if (symbol? parsed)
            {:data parsed :parsed? false
             :error (str "data looks like a variable name, not data: " trimmed
                          " — pass the actual EDN/JSON value")}
            {:data parsed :parsed? true}))
        (catch Exception e1
          (try
            {:data (keywordize-keys (json/parse-string trimmed true)) :parsed? true}
            (catch Exception _e2
              (if (or (str/starts-with? trimmed "[")
                      (str/starts-with? trimmed "{"))
                {:data data :parsed? false
                 :error (str "Failed to parse data as EDN/JSON: " (.getMessage e1))}
                {:data data :parsed? false
                 :error (str "data looks like plain text, not structured data: "
                             (subs trimmed 0 (min 80 (count trimmed))))}))))))))

(defn- data-ready?
  "True when data is suitable for the requested viewer."
  [data viewer]
  (if (or (nil? viewer) (not (structured-viewers viewer)))
    true
    (case viewer
      :portal.viewer/hiccup
      (and (vector? data) (keyword? (first data)))

      :portal.viewer/html
      (and (vector? data) (keyword? (first data)))

      :portal.viewer/table
      (or (vector? data) (map? data))

      :portal.viewer/tree
      (some? data)

      (not (or (string? data) (symbol? data) (keyword? data))))))

(defn- normalize-visualize-args
  "Accept {:data ..., :viewer ..., :title ...} or bare data value.
   Parses data strings and coerces viewer strings from LLM JSON args."
  [args]
  (let [parse (fn [raw]
                (let [{:keys [data parsed? error]} (try-parse-data raw)]
                  {:data data :parsed? parsed? :parse-error error}))]
    (cond
      (map? args)
      (let [{:keys [data parsed? parse-error]} (parse (:data args))
            viewer (some-> (:viewer args) coerce-viewer)]
        (cond-> {:data data :parsed? parsed? :parse-error parse-error}
          viewer (assoc :viewer viewer)
          (:title args) (assoc :title (:title args))))

      :else
      (let [{:keys [data parsed? parse-error]} (parse args)]
        {:data data :parsed? parsed? :parse-error parse-error}))))

(defn visualize-tool
  "Create a :visualize tool that sends data to Portal via tap>.

   Args (from LLM JSON):
     {:data    <any>      — the data to display (required)
      :viewer  <string>   — preferred viewer, e.g. \"table\", \"tree\", \"json\" (optional)
      :title   <string>   — window title (optional)"
  ([] (visualize-tool {}))
  ([opts]
   {:type        :visualize
    :name        (or (:name opts) "visualize")
    :description (or (:description opts)
                     (str "Display data in Portal (opens automatically via tap>). "
                          "Args: {:data <edn-or-json-string-or-value>, :viewer string, :title string?}. "
                          "IMPORTANT: :data must be the actual data — NOT a variable name. "
                          "For tables pass EDN/JSON like [{:model \"RTX 4090\" :price 1599}]. "
                          "For hiccup/html pass an EDN vector like [:html [:body ...]]. "
                          "Build complex hiccup with repl-eval first, then pass the vector in :data. "
                          "Viewers: table, tree, json, edn, hiccup, html, text, code, diff, vega, vega-lite."))
    :parameters  [:map
                  [:data :any]
                  [:viewer {:optional true} :string]
                  [:title {:optional true} :string]]}))

(defmethod tools/run :visualize
  [_tool args]
  (let [{:keys [data viewer parsed? parse-error]} (normalize-visualize-args args)]
    (cond
      (false? parsed?)
      (pr-str {:status :error
               :message (or parse-error
                            "Could not parse :data — pass actual EDN/JSON, not a variable name")})

      (not (data-ready? data viewer))
      (pr-str {:status :error
               :message (str "Viewer " viewer " needs parsed data (vector/map), got string. "
                             "Pass EDN like [{:k v}] or [:html [:body ...]]")})

      :else
      (let [valued (if viewer (pv/default data viewer) data)]
        (clojure.core/tap> valued)
        (pr-str {:status   :ok
                 :viewer   (or viewer :default)
                 :data-type (.getSimpleName (class data))
                 :preview  (subs (pr-str data) 0 (min 120 (count (pr-str data))))})))))

(defmethod tools/parse :visualize
  [_ response]
  (try
    (clojure.edn/read-string response)
    (catch Exception _ response)))