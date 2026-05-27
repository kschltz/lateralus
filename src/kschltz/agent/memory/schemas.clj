(ns kschltz.agent.memory.schemas
  "Malli schemas for memory and embedding HTTP boundaries.")

(def EmbedText
  "Non-empty text to embed."
  [:string {:min 1}])

(def EmbedModel
  "Embedding model identifier."
  [:string {:min 1}])

(def BaseUrl
  "OpenAI-compatible API base URL."
  [:string {:min 1}])

(def ApiKey
  "Optional API key."
  [:maybe :string])

(def EmbeddingVector
  "Embedding vector returned by /v1/embeddings."
  [:vector double?])

(def StoreMessage
  "Message map accepted by the Datalevin store."
  [:map
   [:role {:optional true} [:enum "user" "assistant" "tool"]]
   [:text {:optional true} :string]
   [:id {:optional true} :string]
   [:timestamp {:optional true} int?]
   [:session-id {:optional true} :string]
   [:tool-name {:optional true} :string]
   [:tool-result {:optional true} :string]
   [:tool-calls {:optional true} :string]
   [:tool-call-id {:optional true} :string]])

(def StoreResult
  "Result of storing and optionally indexing a message."
  [:map
   [:msg-id string?]
   [:stored boolean?]
   [:indexed boolean?]
   [:reason {:optional true} [:enum "embedding-failed" "index-failed"]]])
