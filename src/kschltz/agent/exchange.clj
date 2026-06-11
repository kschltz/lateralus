(ns kschltz.agent.exchange
  "The default per-exchange chain: error-boundary + tool loop stages +
   memory persistence + delivery. Lives in its own namespace to break
   the loop <-> interceptors cycle (both `loop` and `interceptors`
   are needed to assemble a chain; this ns is the assembly point)."
  (:require [kschltz.agent.interceptors :as ix]))

(def default-exchange-chain
  "The default chain of interceptor stages for one exchange (a user
   turn + any tool calls + a final response). Order matters:
     - error-boundary FIRST so it catches anything later
     - compose-context → llm-call → parse-response → dispatch
       (dispatch re-enqueues llm-call / parse / itself / execute-tools
       while there are pending tool calls)
     - :leave stages run in stack-reverse: store-exchange,
       update-history, deliver-responses, notify

   Phase 4 cutover: process-messages runs this chain. The legacy
   llm-turn function is kept as a thin adapter for the parity harness
   and any external callers."
  [ix/error-boundary
   ix/compose-context
   ix/llm-call
   ix/parse-response
   ix/dispatch
   ix/deliver-responses
   ix/update-history
   ix/store-exchange
   ix/notify])
