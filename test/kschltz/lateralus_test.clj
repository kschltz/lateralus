(ns kschltz.lateralus-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli :as agent-cli]
            [kschltz.lateralus :as sut]))

(deftest greet-prints-name
  (testing "greet remains available for -X:run-x"
    (is (= "Hello, Clojure!\n"
           (with-out-str (sut/greet {:name "Clojure"}))))))

(deftest run-agent-delegates-to-cli
  (testing "run-agent forwards args to agent CLI"
    (let [captured (atom nil)]
      (with-redefs [agent-cli/run-agent (fn [& args] (reset! captured args))]
        (sut/run-agent "-h")
        (is (= ["-h"] @captured))))))

(deftest main-and-run-agent-are-fns
  (testing "entry points are functions"
    (is (fn? sut/-main))
    (is (fn? sut/run-agent))))
