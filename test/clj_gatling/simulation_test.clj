(ns clj-gatling.simulation-test
  (:use clojure.test)
  (:require [clj-gatling.simulation :as simulation]
            [clj-gatling.httpkit :as httpkit]
            [clj-gatling.simulation-util :refer [choose-runner
                                                 weighted-scenarios]]
            [clj-containment-matchers.clojure-test :refer :all]
            [clj-async-test.core :refer :all]
            [clojure.core.async :refer [go <! <!! timeout]]
            [clj-time.core :as time]))

(defn- to-vector [channel]
  (loop [results []]
    (if-let [result (<!! channel)]
      (recur (conj results result))
      results)))

(defn- run-legacy-simulation [scenarios concurrency & [options]]
  (let [step-timeout (or (:timeout-in-ms options) 5000)]
    (-> (simulation/run-scenarios {:runner (choose-runner scenarios concurrency options)
                                   :timeout-in-ms step-timeout
                                   :context (:context options)}
                                  (weighted-scenarios (range concurrency) scenarios)
                                  true)
        to-vector)))

(defn- run-single-scenario [scenario & {:keys [concurrency context timeout-in-ms requests duration users]
                                        :or {timeout-in-ms 5000}}]
  (to-vector (simulation/run {:name "Simulation"
                              :scenarios [scenario]}
                             {:concurrency concurrency
                              :timeout-in-ms timeout-in-ms
                              :requests requests
                              :duration duration
                              :users users
                              :context context})))

(defn- run-two-scenarios [scenario1 scenario2 & {:keys [concurrency requests]}]
  (to-vector (simulation/run {:name "Simulation"
                              :scenarios [scenario1 scenario2]}
                             {:concurrency concurrency
                              :requests requests
                              :timeout-in-ms 5000})))

(defn successful-request [cb context]
  ;TODO Try to find a better way for this
  ;This is required so that multiple scenarios start roughly at the same time
  (Thread/sleep 50)
  (cb true (assoc context :to-next-request true)))

(defn failing-request [cb context] (cb false))

(defn- fake-async-http [url callback context]
  (future (Thread/sleep 50)
          (callback (= "success" url))))

(def scenario
  {:name "Test scenario"
   :requests [{:name "Request1" :fn successful-request}
              {:name "Request2" :fn failing-request}]})

(def http-scenario
  {:name "Test http scenario"
   :requests [{:name "Request1" :http "success"}
              {:name "Request2" :http "fail"}]})

(defn get-result [requests request-name]
  (:result (first (filter #(= request-name (:name %)) requests))))

(defn- step [step-name return]
  {:name step-name
   :request (fn [ctx]
              [return (assoc ctx :to-next-request return)])})

(deftest simulation-returns-result-when-run-with-one-user-with-legacy-format
  (let [result (run-legacy-simulation [scenario] 1)]
    (is (equal? result [{:name "Test scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "Request1"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}
                                    {:name "Request2"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result false}]}]))))

(deftest simulation-returns-result-when-run-with-one-user
  (let [result (run-single-scenario {:name "Test scenario"
                                     :steps [(step "Step1" true)
                                             (step "Step2" false)]}
                                    :concurrency 1)]
    (is (equal? result [{:name "Test scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "Step1"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}
                                    {:name "Step2"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result false}]}]))))

(deftest simulation-uses-given-user-ids
  (let [result (run-single-scenario {:name "Test scenario"
                                     :steps [(step "Step" true)]}
                                    :users [1 3])]
    (is (equal? (sort-by :id result) [{:name "Test scenario"
                                       :id 1
                                       :start number?
                                       :end number?
                                       :requests [{:name "Step"
                                                   :id 1
                                                   :start number?
                                                   :end number?
                                                   :result true}]}
                                      {:name "Test scenario"
                                       :id 3
                                       :start number?
                                       :end number?
                                       :requests [{:name "Step"
                                                   :id 3
                                                   :start number?
                                                   :end number?
                                                   :result true}]}]))))

(deftest simulation-with-request-returning-single-boolean-instead-of-tuple
  (let [result (run-single-scenario {:name "Test scenario"
                                     :steps [{:name "step"
                                              :request (fn [_] true)}]}
                                    :concurrency 1)]
    (is (equal? result [{:name "Test scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "step"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}]}]))))

(deftest simulation-with-request-returning-channel-with-boolean
  (let [result (run-single-scenario {:name "Test scenario"
                                     :steps [{:name "step"
                                              :request (fn [_] (go true))}]}
                                    :concurrency 1)]
    (is (equal? result [{:name "Test scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "step"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}]}]))))

(deftest when-function-returns-exception-it-is-handled-as-ko
  (let [s {:name "Exception scenario"
           :steps [{:name "Throwing" :request #(throw (Exception. "Simulated"))}]}
        result (run-single-scenario s :concurrency 1)]
    (is (equal? result [{:name "Exception scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "Throwing"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result false}]}]))))

(deftest simulation-passes-context-through-requests-in-scenario
  (let [result (run-single-scenario {:name "scenario"
                                     :steps [(step "step1" true)
                                             {:name "step2"
                                              :request (fn [{:keys [to-next-request] :as ctx}]
                                                         [to-next-request ctx])}]}
                                    :concurrency 1)]
    (is (equal? result [{:name "scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "step1"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}
                                    {:name "step2"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}]}]))))

(deftest simulation-passes-original-context-to-first-request
  (let [scenario {:name "scenario"
                  :steps [{:name "step1"
                           :request (fn [{:keys [test-val] :as ctx}]
                                      [(= 5 test-val) ctx])}]}
        result (run-single-scenario scenario :concurrency 1
                                    :context {:test-val 5})]
    (is (equal? result [{:name "scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "step1"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}]}]))))

(deftest does-not-stop-simulation-in-middle-of-scenario-by-default
  (let [scenario {:name "scenario"
                  :steps [{:name "step 1"
                           :request (fn [_]
                                      (Thread/sleep 500)
                                      true)}
                          {:name "step 2"
                           :request (fn [ctx]
                                      (Thread/sleep 2000)
                                      true)}]}
        result (run-single-scenario scenario
                                    :concurrency 1
                                    :duration (time/millis 100))]
    (is (equal? result [{:name "scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "step 1"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}
                                    {:name "step 2"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}]}]))))

(deftest stops-simulation-in-middle-of-scenario-when-enabled
  (let [scenario {:name "scenario"
                  :allow-early-termination? true
                  :steps [{:name "step 1"
                           :request (fn [_]
                                      (Thread/sleep 500)
                                      true)}
                          {:name "step 2"
                           :request (fn [ctx]
                                      (Thread/sleep 2000)
                                      true)}]}
        result (run-single-scenario scenario
                                    :concurrency 1
                                    :allow-early-termination? true
                                    :duration (time/millis 100))]
    (is (equal? result [{:name "scenario"
                         :id 0
                         :start number?
                         :end number?
                         :requests [{:name "step 1"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result true}]}]))))

(deftest sleeps-for-given-time-before-starting-request
  (let [request-started (promise)
        scenario {:name "scenario"
                  :steps [{:name "step"
                           :sleep-before (fn [ctx] 500)
                           :request (fn [ctx]
                                      (deliver request-started true)
                                      true)}]}
        result (future (run-single-scenario scenario :concurrency 1))]
    (Thread/sleep 200)
    (is (not (realized? request-started)))
    (Thread/sleep 400)
    (is (realized? request-started))
    (is @request-started)
    (is (equal? @result [{:name "scenario"
                          :id 0
                          :start number?
                          :end number?
                          :requests [{:name "step"
                                      :id 0
                                      :start number?
                                      :end number?
                                      :result true}]}]))))

(def first-fails-scenario
  {:name "Scenario"
   :steps [(step "first" false)
           (step "second" true)]})

(deftest simulation-skips-second-request-if-first-fails
  (let [result (run-single-scenario first-fails-scenario :concurrency 1)]
    ;Note scenario is ran twice in this case to match number of handled requests which should be 2
    (is (equal? result
                (repeat 2 {:name "Scenario"
                           :id 0
                           :start number?
                           :end number?
                           :requests [{:name "first"
                                       :id 0
                                       :start number?
                                       :end number?
                                       :result false}]})))))

(deftest second-request-is-not-skipped-in-failure-if-skip-next-after-failure-is-unset
  (let [result (run-single-scenario (assoc first-fails-scenario :skip-next-after-failure? false) :concurrency 1)]
    (is (equal? result
                [{:name "Scenario"
                           :id 0
                           :start number?
                           :end number?
                           :requests [{:name "first"
                                       :id 0
                                       :start number?
                                       :end number?
                                       :result false}
                                      {:name "second"
                                       :id 0
                                       :start number?
                                       :end number?
                                       :result true}]}]))))

(deftest simulation-returns-result-when-run-with-http-requests-using-legacy-format
  (with-redefs [httpkit/async-http-request fake-async-http]
    (let [result (first (run-legacy-simulation [http-scenario] 1))]
      (is (= "Test http scenario" (:name result)))
      (is (= true (get-result (:requests result) "Request1")))
      (is (= false (get-result (:requests result) "Request2"))))))

(deftest simulation-returns-result-when-run-with-multiple-scenarios-with-one-user
  (let [result (run-two-scenarios {:name "Test scenario"
                                   :steps [(step "Step" true)]}
                                  {:name "Test scenario2"
                                   :steps [(step "Step" true)]}
                                  :concurrency 2)]
    ;Stop condition is not synced between parallel scenarios
    ;so once in a while there might be one extra scenario
    ;This is ok tolerance for max requests
    (is (equal? (first (filter #(= 0 (:id %)) result)) {:name "Test scenario"
                                                       :id 0
                                                       :start number?
                                                       :end number?
                                                       :requests anything}))
    (is (equal? (first (filter #(= 1 (:id %)) result)) {:name "Test scenario2"
                                                       :id 1
                                                       :start number?
                                                       :end number?
                                                       :requests anything}))))

(deftest throws-exception-when-concurrency-is-smaller-than-number-of-parallel-scenarios
  (let [scenario1 {:name "scenario1" :steps [(step "step" true)]}
        scenario2 (assoc scenario1 :name "scenario2")]
    (is (thrown? AssertionError (run-two-scenarios scenario1 scenario2 :concurrency 1)))))

(deftest with-given-number-of-requests
  (let [result (run-single-scenario {:name "scenario"
                                     :steps [(step "step" true)]} :concurrency 1 :requests 2)]
    (is (equal? result (repeat 2 {:name "scenario"
                                  :start number?
                                  :end number?
                                  :id 0
                                  :requests [{:name "step"
                                              :id 0
                                              :start number?
                                              :end number?
                                              :result true}]})))))

(deftest with-multiple-number-of-requests
  (let [request-count (atom 0)
        result (run-single-scenario {:name "scenario"
                                     :steps [{:name "step"
                                              :request (fn [ctx]
                                                         (swap! request-count inc)
                                                         [true ctx])}]}
                                    :concurrency 100
                                    :requests 2000)
        handled-requests (->> result (map :requests) count)]
    (is (approximately== handled-requests 2000))
    (is (= handled-requests @request-count))))

(deftest duration-given
  (let [result (run-single-scenario {:name "scenario"
                                     :steps [(step "step" true)]}
                                    :concurrency 1
                                    :duration (time/millis 50))]
    (is (not (empty? result)))))

(deftest fails-requests-when-they-take-longer-than-timeout
  (let [result (run-single-scenario {:name "scenario"
                                     :steps [{:name "step"
                                              :request (fn [ctx]
                                                         (go
                                                           (<! (timeout 500))
                                                           [true ctx]))}]}
                                    :concurrency 1
                                    :timeout-in-ms 100)]
    (is (equal? result [{:name "scenario"
                         :start number?
                         :end number?
                         :id 0
                         :requests [{:name "step"
                                     :id 0
                                     :start number?
                                     :end number?
                                     :result false}]}]))))

(deftest scenario-weight
  (let [main-scenario {:name "Main"
                       :weight 2
                       :steps [(step "step1" true)
                               (step "step2" true)]}
        second-scenario {:name "Second"
                         :weight 1
                         :steps [(step "step1" true)]}
        result (group-by :name
                         (run-two-scenarios main-scenario second-scenario :concurrency 10 :requests 100))
        count-requests (fn [name] (reduce + (map #(count (:requests %)) (get result name))))]
    (is (approximately== (count-requests "Main") 66 :accuracy 20))
    (is (approximately== (count-requests "Second") 33 :accuracy 20))
    (is (approximately== 100 (+ (count-requests "Main") (count-requests "Second")) :accuracy 5))))
