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

;; ---- Chat completion HTTP boundaries ----

(def ChatModel
  "Chat completion model identifier."
  [:string {:min 1}])

(def ChatRole
  "OpenAI-compatible chat message role."
  [:enum "user" "assistant" "system" "tool"])

(def ToolCall
  "Native function-call descriptor in an assistant message."
  [:map
   [:id string?]
   [:type {:optional true} string?]
   [:function {:optional true} :map]])

(def ChatMessage
  "One message in a /v1/chat/completions request."
  [:map
   [:role ChatRole]
   [:content {:optional true} [:maybe :string]]
   [:tool_call_id {:optional true} :string]
   [:tool_calls {:optional true} [:vector ToolCall]]
   [:reasoning_content {:optional true} :string]])

(def CompletionMessages
  "Non-empty chat message list for completion requests."
  [:vector {:min 1} ChatMessage])

(def CompletionTools
  "Optional OpenAI-style tool definitions."
  [:vector :map])

(def AssistantMessage
  "Assistant message in a completion response."
  [:map
   [:role {:optional true} string?]
   [:content {:optional true} [:maybe :string]]
   [:tool_calls {:optional true} [:vector ToolCall]]
   [:reasoning_content {:optional true} :string]])

(def CompletionChoice
  "One choice in a completion response."
  [:map
   [:message AssistantMessage]
   [:index {:optional true} int?]
   [:finish_reason {:optional true} string?]])

(def CompletionResponse
  "Validated body from /v1/chat/completions."
  [:map
   [:choices [:vector {:min 1} CompletionChoice]]])

(def CompletionRequestOpts
  "Optional keyword args for completion-request."
  [:map
   [:chat-history {:optional true} [:vector ChatMessage]]
   [:messages {:optional true} CompletionMessages]
   [:tools {:optional true} CompletionTools]])

(def CompletionRequestFn
  "Instrumented schema for http/completion-request (network I/O)."
  [:=> [:cat BaseUrl ApiKey ChatModel [:maybe :string] [:? CompletionRequestOpts]]
        CompletionResponse])

(def EmbedRequestFn
  "Instrumented schema for http/embed-request (network I/O)."
  [:=> [:cat BaseUrl ApiKey EmbedModel EmbedText]
        EmbeddingVector])

(def EmbedFn
  "Instrumented schema for http/embed (may return nil on failure)."
  [:=> [:cat BaseUrl ApiKey EmbedModel EmbedText]
        [:maybe EmbeddingVector]])
