(ns kschltz.agent.memory.embedding
  "Embedding providers for session memory.

  External HTTP embedding is isolated behind EmbeddingProvider; the default
  provider uses LangChain4j in-process ONNX models (no network)."
  (:require [clojure.string :as str]
            [kschltz.agent.http :as http]
            [kschltz.agent.memory.schemas :as schemas]
            [malli.core :as m])
  (:import [dev.langchain4j.model.embedding.onnx.allminilml6v2q AllMiniLmL6V2QuantizedEmbeddingModel]))

(def default-method :langchain4j)
(def default-langchain4j-model "all-minilm-l6-v2-q")
(def default-http-model "nomic-embed-text")
(def default-dims 384)

(def ^:private langchain4j-model
  (delay (AllMiniLmL6V2QuantizedEmbeddingModel.)))

(defn- floats->doubles
  [^floats arr]
  (vec (map double arr)))

(defprotocol EmbeddingProvider
  (provider-method [this] "Keyword method id (:langchain4j or :http).")
  (provider-model [this] "Model name stored in session metadata.")
  (provider-dims [this] "Expected embedding dimensionality.")
  (embed-text* [this text] "Embed text; returns vector of doubles or nil."))

(deftype LangChain4jProvider [model-name dims]
  EmbeddingProvider
  (provider-method [_] :langchain4j)
  (provider-model [_] model-name)
  (provider-dims [_] dims)
  (embed-text* [_ text]
    (let [resp (.embed @langchain4j-model text)
          embedding (.content resp)]
      (floats->doubles (.vector embedding)))))

(deftype HttpEmbeddingProvider [base-url api-key model-name dims]
  EmbeddingProvider
  (provider-method [_] :http)
  (provider-model [_] model-name)
  (provider-dims [_] dims)
  (embed-text* [_ text]
    (http/embed base-url api-key model-name text)))

(defn- method->metadata
  [method]
  (case method
    :langchain4j "langchain4j-in-process"
    :http        "openai-compatible-http"
    (throw (ex-info "Unknown embedding method" {:method method}))))

(defn provider-metadata
  "Session metadata strings for a provider."
  [provider]
  {:session/emb-method (method->metadata (provider-method provider))
   :session/emb-model  (provider-model provider)})

(defn create-provider
  "Build an EmbeddingProvider from opts.
   opts:
     :method         - :langchain4j (default) or :http
     :model          - model name (defaults per method)
     :dims           - vector dimensions (default 384)
     :base-url       - required for :http
     :api-key        - optional for :http"
  [{:keys [method model dims base-url api-key]}]
  (let [method (or method default-method)
        dims   (or dims default-dims)
        model  (or model
                   (case method
                     :langchain4j default-langchain4j-model
                     :http        default-http-model
                     default-langchain4j-model))]
    (case method
      :langchain4j (->LangChain4jProvider model dims)
      :http        (->HttpEmbeddingProvider base-url api-key model dims)
      (throw (ex-info "Unknown embedding method" {:method method})))))

(defn embed-text
  "Embed text using provider. Validates input/output with Malli."
  [provider text]
  (when-not (str/blank? text)
    (when (m/validate schemas/EmbedText text)
      (try
        (when-let [result (embed-text* provider text)]
          (if (m/validate schemas/EmbeddingVector result)
            result
            (do
              (println "Warning: embedding provider returned invalid vector"
                       {:model (provider-model provider)
                        :size  (count result)})
              nil)))
        (catch Exception e
          (println "Warning: embedding failed:"
                   (.getMessage e)
                   {:method (provider-method provider)
                    :model  (provider-model provider)})
          nil)))))
