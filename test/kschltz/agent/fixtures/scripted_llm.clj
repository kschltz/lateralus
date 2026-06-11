(ns kschltz.agent.fixtures.scripted-llm
  "Scripted LlmClient for parity testing.

   Records every request received and returns the next response from a
   pre-scripted sequence. The parity harness runs each scenario
   through BOTH `loop/llm-turn` and `chain/execute` and asserts that:
     - the LLM received the same requests
     - the final response is byte-identical
     - the on-thought event sequence is byte-identical"
  (:require [cheshire.core :as json]
            [kschltz.agent.llm.client :as llm-client]))

(defrecord ScriptedLlmClient [script calls]
  llm-client/LlmClient
  (call [_ opts]
    (let [idx (count @calls)
          resp (nth script idx {:choices [{:message {:content (str "(script exhausted at call " idx ")")}}]})]
      (swap! calls conj (assoc opts :_script-index idx))
      resp))
  (start-heartbeat! [_] (atom (System/currentTimeMillis)))
  (cancel [_ _] nil))

(defn scripted
  "Build a scripted LLM client.
   - `script` is a vector of response maps. Each call returns the next.
   - Returns a map {:client <ScriptedLlmClient> :calls <atom>}."
  [script]
  (let [calls (atom [])
        client (->ScriptedLlmClient (vec script) calls)]
    {:client client :calls calls}))

(defn text-response
  "Convenience: build a plain text response."
  [text]
  {:choices [{:message {:content text}}]})

(defn tool-call-response
  "Convenience: build a response with a single native tool call.
   `name` and `args-map` are the function name and JSON-decoded args.
   The args are encoded as JSON (not EDN) because the real OpenAI
   path decodes JSON via cheshire."
  [call-id name args-map]
  {:choices [{:message {:content ""
                        :tool_calls [{:id call-id
                                      :type "function"
                                      :function {:name name
                                                 :arguments (json/generate-string args-map)}}]}}]})
