(ns griffin.test.contract-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [griffin.test.contract :as c]))

(defprotocol RemoteAPI
  :extend-via-metadata true
  (create-file [this file])
  (file-exists? [this file]))

(def model
  (c/model
   {:protocols #{RemoteAPI}
    :methods [(c/method #'create-file
                        (fn [state [file]]
                          (if (not (get-in state [:files file]))
                            (c/return #{:ok}
                                      :next-state (update state :files conj file))
                            (c/return #{:error/file-exists}
                                      :next-state state)))
                        :args (fn [_state] (gen/tuple gen/string)))
              (c/method #'file-exists?
                        (fn [state [file]]
                          (let [exists? (boolean (get-in state [:files file]))]
                            (c/return (s/with-gen (fn [x] (= exists? x))
                                        (fn [] (gen/return exists?)))
                                      :next-state state)))
                        :args (fn [_state] (gen/tuple gen/string)))]

    :initial-state (fn []
                     {:files #{}})}))

(defn good-impl []
  (let [state (ref {:files #{}})]
    (reify
      RemoteAPI
      (create-file [_this f]
        (dosync
         (if (not (get (:files @state) f))
           (do
             (commute state update :files conj f)
             :ok)
           :error/file-exists)))
      (file-exists? [_this f]
        (boolean (get (:files @state) f))))))

(defn bad-impl []
  (reify
    RemoteAPI
    (create-file [_this _f]
      :ok)
    (file-exists? [_this _f]
      false)))

(deftest model-works
  (is (:pass? (tc/quick-check 100 (c/test-model model)))))

(deftest mocks-work
  (let [mock (c/mock model)]
    (is (= :ok (create-file mock "hello")))
    (is (= :error/file-exists (create-file mock "hello")))))

(defn state-impl-is-thread-safe [state]
  (let [mock (c/mock model :mock-state state)]
    (is (= {true 100}
           (frequencies
            (map deref
                 (doall (map (fn [fname]
                               (future
                                 (create-file mock fname)
                                 (file-exists? mock fname)))
                             (range 100)))))))))

(deftest mock-state-is-thread-safe
  (doseq [state [(c/ephemeral-state) (c/ref-state (ref {}))]]
    (testing state
      (state-impl-is-thread-safe state))))

(deftest verify-works
  (let [ret (tc/quick-check 100 (c/verify model good-impl))]
    (is (:pass? ret) ret)))

(deftest verify-catches-errors
  (let [ret (tc/quick-check 100 (c/verify model bad-impl))]
    ;; (println "ret:" ret)
    (is (find ret :pass?) ret)
    (is (false? (:pass? ret)) ret)))

(deftest test-proxy
  (let [good-mock (c/test-proxy model (good-impl))]
    (is (= :ok (create-file good-mock "/foo")))
    (is (= :error/file-exists (create-file good-mock "/foo"))))

  (let [bad-mock (c/test-proxy model (bad-impl))]
    (is (= :ok (create-file bad-mock "/foo")))
    (is (thrown? Exception (create-file bad-mock "/foo")))))

;; Simulates an external API that rejects duplicate IDs (e.g. Form3 409 Conflict).
;; Without method-level cleanup, shrink attempts would replay the same IDs and get
;; conflicts instead of surfacing the real failure.
(defprotocol ExternalAPI
  :extend-via-metadata true
  (submit [this id value]))

(def id-counter (atom 0))

(def external-model
  (c/model
   {:protocols #{ExternalAPI}
    :methods [(c/method #'submit
                        (fn [state [id value]]
                          (c/return #{:ok}
                                    :next-state (update state :submitted conj id)))
                        :args (fn [_state]
                                (gen/tuple (gen/fmap (fn [_] (swap! id-counter inc))
                                                    (gen/return nil))
                                           gen/nat))
                        :cleanup (fn [[_id value]]
                                   [(swap! id-counter inc) value]))]
    :initial-state (fn [] {:submitted #{}})}))

(defn external-impl-rejects-dupes []
  (let [seen (atom #{})]
    (reify ExternalAPI
      (submit [_this id _value]
        (if (@seen id)
          (throw (ex-info "409 Conflict: duplicate ID" {:id id}))
          (do (swap! seen conj id)
              :ok))))))

(deftest cleanup-prevents-conflicts
  (let [ret (tc/quick-check 100 (c/verify external-model external-impl-rejects-dupes))]
    (is (:pass? ret) ret)))

(deftest refresh-calls-generates-fresh-args
  (let [calls [{:method (first (:methods external-model))
                :args [999 42]
                :return (c/return #{:ok})}
               {:method (first (:methods external-model))
                :args [999 7]
                :return (c/return #{:ok})}]
        refreshed (c/refresh-calls external-model calls)
        ids (map (comp first :args) refreshed)]
    (is (= 2 (count (set ids))) "refreshed calls should have distinct IDs")
    (is (every? #(not= 999 %) ids) "refreshed IDs should differ from originals")))
