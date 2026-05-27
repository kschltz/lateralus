(ns kschltz.agent.tools.web
  "Web search tool — DuckDuckGo instant answers via a malli-instrumented protocol."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [hato.client :as hato]
   [kschltz.agent.tools :as tools]
   [malli.core :as m]))

;; ---- Schemas ----

(def SearchQuery
  "Non-empty search query string."
  [:string {:min 1 :max 500}])

(def SearchHit
  "One search result."
  [:map
   [:title string?]
   [:url string?]
   [:snippet string?]])

(def SearchResponse
  "Vector of search hits."
  [:vector SearchHit])

;; ---- Protocol ----

(defprotocol WebSearch
  (search [this query]))

(defn- normalize-query
  "Accept native tool args map {:query ...} or a bare query string."
  [args]
  (str/trim
    (str (cond
           (string? args) args
           (map? args) (:query args)
           :else args))))

(defn- encode-query
  [query]
  (java.net.URLEncoder/encode query "UTF-8"))

(defn- related-topic->hit
  [{:keys [Text FirstURL]}]
  (when (and (seq Text) (seq FirstURL))
    {:title (first (str/split Text #"\n" 2))
     :url   FirstURL
     :snippet Text}))

(defn- flatten-related-topics
  [topics]
  (mapcat (fn [topic]
            (if (:Topics topic)
              (flatten-related-topics (:Topics topic))
              (when-let [hit (related-topic->hit topic)]
                [hit])))
          topics))

(defn- parse-ddg-json
  [body]
  (let [data (json/parse-string body true)
        instant (when-let [text (:AbstractText data)]
                  (when (seq text)
                    [{:title (or (:Heading data) "Instant Answer")
                      :url   (or (:AbstractURL data) "")
                      :snippet text}]))
        related (flatten-related-topics (or (:RelatedTopics data) []))]
    (into (or instant [])
          (take 8 related))))

(defn- ddg-search-url
  [query]
  (str "https://api.duckduckgo.com/?q="
       (encode-query query)
       "&format=json&no_redirect=1&no_html=1&skip_disambig=1"))

(defn- fetch-ddg-hits
  [query]
  (let [url  (ddg-search-url query)
        body (:body (hato/get url {:as :string}))]
    (parse-ddg-json body)))

(defn duckduckgo-search
  "Search DuckDuckGo with malli-validated input and output."
  [query]
  (when-not (m/validate SearchQuery query)
    (throw (ex-info "Invalid search query" {:query query :schema SearchQuery})))
  (let [result (vec (fetch-ddg-hits query))]
    (when-not (m/validate SearchResponse result)
      (throw (ex-info "Invalid search response" {:result result :schema SearchResponse})))
    result))

(defrecord DuckDuckGoClient []
  WebSearch
  (search [_ query]
    (duckduckgo-search query)))

(defn web-search-tool
  "Create a :web tool that searches DuckDuckGo.
   Args: {:query string} (decoded from LLM JSON args via Malli).
   Returns: EDN vector of {:title :url :snippet} maps."
  ([]
   (web-search-tool {}))
  ([opts]
   {:type        :web
    :name        (or (:name opts) "web-search")
    :description (or (:description opts)
                      "Search the web via DuckDuckGo. Args: {:query string}. Returns: vector of result maps.")
    :parameters  [:map [:query :string]]}))

(defmethod tools/run :web
  [_tool args]
  (let [query (normalize-query args)]
    (if (str/blank? query)
      (pr-str [])
      (try
        (pr-str (duckduckgo-search query))
        (catch Exception e
          (pr-str {:error (.getMessage e)}))))))

(defmethod tools/parse :web
  [_ response]
  (try
    (clojure.edn/read-string response)
    (catch Exception _ response)))
