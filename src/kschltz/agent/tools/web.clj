(ns kschltz.agent.tools.web
  "Web search tool — Mojeek primary, Startpage fallback, Wikipedia last resort.

   Search backend priority:
     1. Mojeek          — independent search index, clean HTML, no API key
     2. Startpage       — Google results via privacy proxy, no API key
     3. Wikipedia API   — always available, Wikipedia-only results

   DDG was removed: both Lite and HTML endpoints now serve CAPTCHAs (2026)."
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
  "Browser-like UA. Many sites reject bot-like agents with 403."
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

(def ^:private mojeek-url "https://www.mojeek.com/search")
(def ^:private startpage-url "https://www.startpage.com/sp/search")
(def ^:private wiki-api-url "https://en.wikipedia.org/w/api.php")

;; ---- Mojeek selectors ----
;; <a class="abs" href="URL">Title</a>   — older pages
;; <a class="title" href="URL">Title</a> — newer pages
;; Both patterns handled.

(def ^:private mojeek-title-re
  #"<a[^>]+class=\"(?:title|abs)\"[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>")

(def ^:private mojeek-snippet-re
  #"<p[^>]+class=\"s\"[^>]*>([\s\S]*?)</p>")

;; ---- Startpage selectors ----

(def ^:private startpage-link-re
  #"<a[^>]+class=\"result-title result-link[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>([\s\S]*?)</a>")

;; ---- Protocol ----

(defprotocol WebSearch
  (search [this query]))

;; ---- Helpers ----

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
      60000))

(defn- http-get
  ([url]
   (http-get url nil))
  ([url extra-headers]
   (:body (hato/get url {:as      :string
                         :timeout (http-timeout-ms)
                         :headers (merge {"User-Agent" http-user-agent}
                                        extra-headers)}))))

(defn- html-unescape
  "Decode common HTML entities including numeric like &#039; and &#x27;."
  [s]
  (-> s
      (str/replace "&amp;" "&")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace #"&#(\d+);"
        (fn [[_ code]] (str (char (Integer/parseInt code)))))
      (str/replace #"&#x([0-9a-fA-F]+);"
        (fn [[_ hex]] (str (char (Integer/parseInt hex 16)))))
      (str/replace "&nbsp;" " ")))

(defn- strip-style-blocks
  "Remove <style>...</style> blocks before tag stripping."
  [s]
  (str/replace s #"<style[^>]*>[\s\S]*?</style>" ""))

(defn- strip-css-text
  "Remove inline CSS that leaks into text (e.g. .css-xxxx{...} patterns from Startpage)."
  [s]
  (str/replace s #"\.[a-zA-Z][\w-]*\{[^}]*\}" ""))

(defn- strip-html-tags
  [s]
  (-> s
      strip-style-blocks
      (str/replace #"<[^>]+>" "")
      strip-css-text
      html-unescape
      str/trim))

;; ---- Mojeek Search ----

(defn- fetch-mojeek-hits
  "Search via Mojeek. Clean HTML, independent index, no API key."
  [query]
  (let [url  (str mojeek-url "?q=" (encode-query query))
        body (http-get url)
        links   (for [[_ href title] (re-seq mojeek-title-re body)]
                  {:url   (html-unescape href)
                   :title (strip-html-tags title)})
        snippets (map strip-html-tags
                      (map second (re-seq mojeek-snippet-re body)))]
    (vec (take max-hits
               (map (fn [link snippet]
                      (assoc link :snippet (or snippet "")))
                    links
                    (concat snippets (repeat "")))))))

;; ---- Startpage Search ----

(defn- fetch-startpage-hits
  "Search via Startpage (Google results via privacy proxy). No API key."
  [query]
  (let [url  (str startpage-url "?q=" (encode-query query))
        body (http-get url {"Accept" "text/html,application/xhtml+xml"})
        ;; Strip <style> blocks first to prevent CSS leaking into titles
        clean   (strip-style-blocks body)
        links   (for [[_ href title-html] (re-seq startpage-link-re clean)]
                  {:url   (html-unescape href)
                   :title (strip-html-tags title-html)})
        snippet-texts (->> (re-seq #"<p[^>]*>([^<]{30,})</p>" clean)
                           (map second)
                           (map strip-html-tags)
                           (remove str/blank?))]
    (vec (take max-hits
               (map (fn [link snippet]
                      (assoc link :snippet (or snippet "")))
                    links
                    (concat snippet-texts (repeat "")))))))

;; ---- Wikipedia Search ----

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
        body (http-get url {"Accept" "application/json"})
        items (get-in (json/parse-string body true) [:query :search])]
    (vec (for [{:keys [title snippet]} items]
           {:title   title
            :url     (wiki-title->url title)
            :snippet (strip-html-tags snippet)}))))

(defn- fetch-wikipedia-hits
  [query]
  (wikipedia-search query))

;; ---- Public API ----

(defn web-search
  "Search the web. Priority: Mojeek → Startpage → Wikipedia."
  [query]
  (when-not (m/validate SearchQuery query)
    (throw (ex-info "Invalid search query" {:query query :schema SearchQuery})))
  (let [mojeek    (try (fetch-mojeek-hits query) (catch Exception _ nil))
        startpage (try (fetch-startpage-hits query) (catch Exception _ nil))
        hits      (cond
                   (seq mojeek)    mojeek
                   (seq startpage) startpage
                   :else          (try (fetch-wikipedia-hits query) (catch Exception _ [])))
        result    (vec hits)]
    (when-not (m/validate SearchResponse result)
      (throw (ex-info "Invalid search response" {:result result :schema SearchResponse})))
    result))

(defn duckduckgo-search
  "Deprecated. Use web-search instead."
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