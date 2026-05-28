(ns kschltz.agent.memory.schemas
  "Malli schemas for memory and embedding HTTP boundaries.")

(def EmbedText
  "Non-empty text to embed."
  [:string {:min 1}])

(def EmbedModel
  "Embedding model identifier."
  [:string {:min 1}])

(def EmbeddingMethod
  "Embedding backend selection."
  [:enum :langchain4j :http])

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
   [:kind {:optional true} [:enum "fact"]]
   [:topic {:optional true} :string]
   [:tags {:optional true} [:vector :string]]
   [:tool-name {:optional true} :string]
   [:tool-result {:optional true} :string]
   [:tool-calls {:optional true} :string]
   [:tool-call-id {:optional true} :string]])

(def RememberInput
  "Args for the remember tool."
  [:map
   [:query [:string {:min 1}]]
   [:limit {:optional true} :int]])

(def RememberResult
  "Remember tool response map."
  [:map
   [:type [:= "memory"]]
   [:stored boolean?]
   [:content {:optional true} [:string {:min 1}]]
   [:msg-id {:optional true} :string]
   [:error {:optional true} :string]])

(def StoreResult
  "Result of storing and optionally indexing a message."
  [:map
   [:msg-id string?]
   [:stored boolean?]
   [:indexed boolean?]
   [:reason {:optional true} [:enum "embedding-failed" "index-failed"]]])
