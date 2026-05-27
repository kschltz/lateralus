(ns kschltz.agent.tools.web
  "Web search tool — DuckDuckGo Lite HTML results with Wikipedia fallback."
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

(def ^:private max-hits 8)

(def ^:private http-user-agent
  "Mozilla/5.0 (compatible; Lateralus-Agent/1.0)")

(def ^:private ddg-lite-url "https://lite.duckduckgo.com/lite/")

(def ^:private wiki-api-url "https://en.wikipedia.org/w/api.php")

(def ^:private result-link-re
  #"<a rel=\"nofollow\" href=\"([^\"]+)\" class='result-link'>([^<]+)</a>")

(def ^:private result-snippet-re
  #"<td class='result-snippet'>\s*([\s\S]*?)\s*</td>")

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

(defn- http-timeout-ms
  []
  (or (some-> (System/getenv "LATERALUS_HTTP_TIMEOUT_MS") parse-long)
      15000))

(defn- http-get
  [url]
  (:body (hato/get url {:as      :string
                        :timeout (http-timeout-ms)
                        :headers {"User-Agent" http-user-agent}})))

(defn- http-post-form
  [url form-params]
  (:body (hato/post url {:as           :string
                         :timeout      (http-timeout-ms)
                         :headers      {"User-Agent"   http-user-agent
                                        "Content-Type" "application/x-www-form-urlencoded"}
                         :form-params  form-params})))

(defn- html-unescape
  [s]
  (-> s
      (str/replace "&amp;" "&")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&nbsp;" " ")))

(defn- strip-html-tags
  [s]
  (-> s
      (str/replace #"<[^>]+>" "")
      html-unescape
      str/trim))

(defn parse-ddg-lite-html
  "Extract {:title :url :snippet} hits from DuckDuckGo Lite HTML."
  [body]
  (let [links (for [[_ href title] (re-seq result-link-re body)]
                {:url   (html-unescape href)
                 :title (strip-html-tags title)})
        snippets (map strip-html-tags (map second (re-seq result-snippet-re body)))]
    (vec (take max-hits
               (map (fn [link snippet]
                      (assoc link :snippet (or snippet "")))
                    links
                    (concat snippets (repeat "")))))))

(defn- wiki-title->url
  [title]
  (str "https://en.wikipedia.org/wiki/"
       (java.net.URLEncoder/encode (str/replace (str/trim title) " " "_")
                                   "UTF-8")))

(defn- wikipedia-search
  [query]
  (let [url  (str wiki-api-url
                  "?action=query&list=search&format=json&utf8=1&srlimit="
                  max-hits "&srsearch=" (encode-query query))
        body (http-get url)
        items (get-in (json/parse-string body true) [:query :search])]
    (vec (for [{:keys [title snippet]} items]
           {:title   title
            :url     (wiki-title->url title)
            :snippet (strip-html-tags snippet)}))))

(defn- fetch-ddg-lite-hits
  [query]
  (parse-ddg-lite-html (http-post-form ddg-lite-url {:q query})))

(defn- fetch-wikipedia-hits
  [query]
  (wikipedia-search query))

(defn web-search
  "Search the web. Uses DuckDuckGo Lite; falls back to Wikipedia when DDG has no hits."
  [query]
  (when-not (m/validate SearchQuery query)
    (throw (ex-info "Invalid search query" {:query query :schema SearchQuery})))
  (let [ddg    (try (fetch-ddg-lite-hits query)
                    (catch Exception _ []))
        hits   (if (seq ddg) ddg (try (fetch-wikipedia-hits query) (catch Exception _ [])))
        result (vec hits)]
    (when-not (m/validate SearchResponse result)
      (throw (ex-info "Invalid search response" {:result result :schema SearchResponse})))
    result))

(defn duckduckgo-search
  "Backward-compatible alias for web-search."
  [query]
  (web-search query))

(defrecord DuckDuckGoClient []
  WebSearch
  (search [_ query]
    (web-search query)))

(defn web-search-tool
  "Create a :web tool that searches the web.
   Args: {:query string} (decoded from LLM JSON args via Malli).
   Returns: EDN vector of {:title :url :snippet} maps."
  ([]
   (web-search-tool {}))
  ([opts]
   {:type        :web
    :name        (or (:name opts) "web-search")
    :description (or (:description opts)
                      "Search the web. Args: {:query string}. Returns up to 8 maps with :title, :url, :snippet.")
    :parameters  [:map [:query :string]]}))

(defmethod tools/run :web
  [_tool args]
  (let [query (normalize-query args)]
    (if (str/blank? query)
      (pr-str [])
      (try
        (pr-str (web-search query))
        (catch Exception e
          (pr-str {:error (.getMessage e)}))))))

(defmethod tools/parse :web
  [_ response]
  (try
    (clojure.edn/read-string response)
    (catch Exception _ response)))
