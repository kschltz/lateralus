(ns kschltz.agent.chain
  "Minimal Pedestal-style interceptor engine.

   An interceptor is a plain map:
     {:name  :keyword            ; required
      :enter (fn [ctx] ctx')     ; optional
      :leave (fn [ctx] ctx')     ; optional
      :error (fn [ctx ex] ctx')} ; optional

   Semantics (synchronous, copied from Pedestal's documented behavior):
   - :enter fns run in queue order; each executed interceptor is pushed
     onto the stack
   - when the queue is empty (or `terminate` is called), :leave fns run
     in reverse stack order
   - an exception in any stage switches to error mode: the stack is
     walked in stack-reverse calling :error. When an :error handler
     returns a ctx without ::error, the error is cleared and the leave
     phase begins from the remaining stack (the handling interceptor's
     own :leave is NOT re-run).
   - an unhandled error at the bottom of the stack is rethrown, wrapped
     in ex-info carrying :chain/stage, :interceptor/name, and any
     ex-data the original throwable carried
   - interceptors may `enqueue` more interceptors mid-flight

   Engine state lives under namespaced keys (::queue ::stack ::error) so
   it never collides with domain keys.

   Instrumentation: when the ctx carries {:chain/instrument? true
   :chain/validate (fn [ctx] nil-or-explanation)}, the validate fn runs
   after every stage; a non-nil result throws with the offending
   interceptor's name and the explanation. With the flag off, validate
   is never called."
  (:import [clojure.lang PersistentQueue]))

(def ^:private empty-queue PersistentQueue/EMPTY)

(defn enqueue
  "Append `interceptors` to the remaining execution queue of `ctx`."
  [ctx interceptors]
  (update ctx ::queue (fnil into empty-queue) interceptors))

(defn terminate
  "Drop the remaining queue; already-entered interceptors still :leave."
  [ctx]
  (assoc ctx ::queue empty-queue))

(defn- error-map
  "Build the ::error map from a throwable, preserving ex-data and tagging
   with the stage and interceptor name."
  [t interceptor stage]
  (let [ex-data (when (instance? clojure.lang.IExceptionInfo t) (ex-data t))]
    (cond-> {:exception        t
             :interceptor/name (:name interceptor)
             :chain/stage      stage}
      ex-data (merge ex-data))))

(defn- check-instrumented
  [ctx interceptor stage]
  (when (and (:chain/instrument? ctx) (:chain/validate ctx))
    (when-some [explanation ((:chain/validate ctx) ctx)]
      (throw (ex-info "Interceptor returned invalid context"
                      {:interceptor/name (:name interceptor)
                       :chain/stage      stage
                       :chain/explanation explanation}))))
  ctx)

(defn- try-stage
  "Run a :enter or :leave fn. On throw (or instrumentation throw) sets
   ::error and returns ctx without re-throwing."
  [ctx interceptor stage]
  (if-some [f (get interceptor stage)]
    (try
      (-> (f ctx)
          (check-instrumented interceptor stage))
      (catch Throwable t
        (assoc ctx ::error (error-map t interceptor stage))))
    ctx))

(defn- try-error
  "Run interceptor's :error handler. A handler that returns a ctx
   without ::error has handled it."
  [ctx interceptor]
  (if-some [f (:error interceptor)]
    (let [{:keys [exception]} (::error ctx)]
      (try
        (-> (f (dissoc ctx ::error) exception)
            (check-instrumented interceptor :error))
        (catch Throwable t
          (assoc ctx ::error (error-map t interceptor :error)))))
    ctx))

(defn- rethrow-unhandled
  [{:keys [exception] :as error}]
  (let [{:keys [interceptor/name stage] :as data} (dissoc error :exception)]
    (throw (ex-info (str "Unhandled interceptor error: " (ex-message exception))
                    (assoc data :chain/unhandled? true)
                    exception))))

(defn execute
  "Run `interceptors` (when given) over `ctx` to completion and return
   the final ctx with engine keys removed. Rethrows unhandled errors."
  ([ctx]
   ;; Phase 1: enter. Pop queue, push stack, run :enter until queue
   ;; empty or an error is set. Stack is a vector; conj appends,
   ;; peek/pop give LIFO order for the leave walk.
   (let [entered (loop [ctx ctx]
                   (let [queue (::queue ctx)]
                     (if (or (::error ctx) (empty? queue))
                       ctx
                       (let [interceptor (peek queue)
                             ctx         (-> ctx
                                             (assoc ::queue (pop queue))
                                             (update ::stack (fnil conj []) interceptor))]
                         (recur (try-stage ctx interceptor :enter))))))
         ;; Phase 2: error walk. Only runs when ::error is set. Walks
         ;; the entire stack from top to bottom calling :error on every
         ;; interceptor. This gives every interceptor a chance to
         ;; handle or observe the error. If ::error is still set after
         ;; the walk, it is rethrown at the end.
         handled (if (::error entered)
                   (loop [ctx entered]
                     (let [stack (::stack ctx)]
                       (if (seq stack)
                         (let [top (peek stack)
                               ctx (-> ctx
                                       (assoc ::stack (pop stack))
                                       (try-error top))]
                           (recur ctx))
                         ctx)))
                   entered)
         ;; Phase 3: leave. Pop stack, run :leave. Errors during :leave
         ;; re-enter phase 2 (continuing the walk).
         unwound (loop [ctx handled]
                   (let [stack (::stack ctx)]
                     (if (seq stack)
                       (let [top (peek stack)
                             ctx (try-stage (assoc ctx ::stack (pop stack)) top :leave)]
                         (recur ctx))
                       ctx)))]
     (if-some [error (::error unwound)]
       (rethrow-unhandled error)
       (dissoc unwound ::queue ::stack ::error))))
  ([ctx interceptors]
   (execute (enqueue (assoc ctx ::stack []) interceptors))))
