(ns kschltz.agent.chain-test
  "Tests for the interceptor engine.

   Pinned semantics (copied from Pedestal's documented behavior):
   - :enter runs in queue order; each executed interceptor is pushed onto the stack
   - queue empty (or `terminate`) → :leave runs in reverse stack order
   - exception in any stage walks the stack calling :error; the handling
     interceptor's own :leave does NOT re-run
   - unhandled error rethrown as ex-info with :interceptor/name and
     :chain/stage; original throwable is on ex-cause
   - interceptors may `enqueue` more work mid-flight"
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]))

(defn recorder
  "Interceptor that records its :name into (:events ctx) for each stage
   it actually runs. Each stage fn is called as (f ctx) or (f ctx ex)."
  [name & {:keys [enter leave error]}]
  {:name name :enter enter :leave leave :error error})

;; ---- Acceptance criteria ----

(deftest enter-and-leave-ordering
  (testing ":enter in queue order, :leave in reverse stack order"
    (let [events (atom [])]
      (chain/execute
        {:events events}
        [(recorder :a
                   :enter (fn [ctx] (swap! (:events ctx) conj [:enter :a]) ctx)
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :a]) ctx))
         (recorder :b
                   :enter (fn [ctx] (swap! (:events ctx) conj [:enter :b]) ctx)
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :b]) ctx))
         (recorder :c
                   :enter (fn [ctx] (swap! (:events ctx) conj [:enter :c]) ctx)
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :c]) ctx))])
      (is (= [[:enter :a] [:enter :b] [:enter :c]
              [:leave :c] [:leave :b] [:leave :a]]
             @events)))))

(deftest re-enqueue-extends-the-queue
  (testing "an interceptor that enqueues runs after the current queue"
    (let [events (atom [])]
      (chain/execute
        {:events events}
        [(recorder :a
                   :enter (fn [ctx]
                            (swap! (:events ctx) conj [:enter :a])
                            (chain/enqueue
                              ctx
                              [(recorder :d
                                         :enter (fn [c] (swap! (:events c) conj [:enter :d]) c)
                                         :leave (fn [c] (swap! (:events c) conj [:leave :d]) c))
                               (recorder :e
                                         :enter (fn [c] (swap! (:events c) conj [:enter :e]) c)
                                         :leave (fn [c] (swap! (:events c) conj [:leave :e]) c))])))
         (recorder :b
                   :enter (fn [ctx] (swap! (:events ctx) conj [:enter :b]) ctx)
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :b]) ctx))])
      (is (= [[:enter :a] [:enter :b] [:enter :d] [:enter :e]
              [:leave :e] [:leave :d] [:leave :b]]
             @events)
          "enqueued d and e run after b, and leave in stack-reverse order (a has no :leave so doesn't appear)"))))

(deftest terminate-skips-remaining-enters
  (testing "remaining :enters skipped, already-entered :leaves still run"
    (let [events (atom [])]
      (chain/execute
        {:events events}
        [(recorder :a
                   :enter (fn [ctx]
                            (swap! (:events ctx) conj [:enter :a])
                            (chain/terminate ctx))
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :a]) ctx))
         (recorder :b
                   :enter (fn [ctx] (swap! (:events ctx) conj [:enter :b]) ctx)
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :b]) ctx))
         (recorder :c
                   :enter (fn [ctx] (swap! (:events ctx) conj [:enter :c]) ctx)
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :c]) ctx))])
      (is (= [[:enter :a] [:leave :a]] @events)
          "b and c never enter; a's :leave still runs"))))

(deftest error-walks-stack-in-reverse
  (testing "exception in :enter of :b: :c never enters, :error runs in stack order (b then a)"
    (let [events (atom [])
          bomb   (ex-info "boom" {:where :b})]
      (chain/execute
        {:events events}
        [(recorder :a
                   :error (fn [ctx _] (swap! (:events ctx) conj [:error :a]) ctx))
         (recorder :b
                   :enter (fn [_] (throw bomb))
                   :error (fn [ctx _] (swap! (:events ctx) conj [:error :b]) ctx))
         (recorder :c
                   :enter (fn [ctx] (swap! (:events ctx) conj [:enter :c]) ctx))])
      (is (= [[:error :b] [:error :a]] @events)
          "c never entered; errors walked b then a"))))

(deftest error-handlers-:leave-does-not-rerun
  (testing "an :error handler that clears the error suppresses the interceptor's :leave"
    (let [events (atom [])]
      (chain/execute
        {:events events}
        [(recorder :a
                   :enter (fn [ctx] (swap! (:events ctx) conj [:enter :a]) ctx)
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :a]) ctx)
                   :error (fn [ctx _] (swap! (:events ctx) conj [:error :a]) ctx))
         (recorder :b
                   :enter (fn [_] (throw (ex-info "boom" {})))
                   :leave (fn [ctx] (swap! (:events ctx) conj [:leave :b]) ctx))])
      ;; a handled the error → a's :leave is suppressed, b's :leave never runs
      (is (= [[:enter :a] [:error :a]] @events)))))

(deftest unhandled-error-rethrown-as-ex-info
  (testing "unhandled :error rethrown with ex-info carrying :interceptor/name, :chain/stage, :chain/unhandled?"
    (let [bomb (ex-info "boom" {})]
      (try
        (chain/execute
          {}
          [(recorder :a :enter (fn [_] (throw bomb)))])
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :a (:interceptor/name d)))
            (is (= :enter (:chain/stage d)))
            (is (true? (:chain/unhandled? d)))
            (is (identical? bomb (ex-cause e))
                "original throwable is preserved on the ex-cause chain"))))))

(deftest instrumentation-off-by-default
  (testing "no :chain/instrument? → no :chain/validate calls"
    (let [validate-calls (atom 0)
          validate (fn [ctx] (swap! validate-calls inc) nil)]
      (chain/execute
        {:chain/validate validate}
        [(recorder :a :enter identity)])
      (is (zero? @validate-calls)
          "validate must not run without :chain/instrument? true"))))

(deftest instrumentation-on-fails-closed
  (testing ":chain/instrument? true with a validate fn returning a non-nil explanation throws ex-info"
    (let [validate (fn [_] "bad state")]
      (try
        (chain/execute
          {:chain/instrument? true :chain/validate validate}
          [(recorder :a :enter identity)])
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :a (:interceptor/name d)))
            (is (= :enter (:chain/stage d)))
            (is (= "bad state" (:chain/explanation d))))))))))
