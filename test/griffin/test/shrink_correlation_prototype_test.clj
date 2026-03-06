(ns griffin.test.shrink-correlation-prototype-test
  "Prototypes for 3 approaches to preserving UUID correlation during shrink replays.

  Problem: When shrinking removes a call, refresh-calls runs cleanup-args on each
  call independently. If create's cleanup generates a new ID, edit's args still
  reference the old ID. We need correlated IDs to stay correlated.

  Example scenario:
    1. create(uuid-A) → state has #{uuid-A}
    2. create(uuid-B) → state has #{uuid-A, uuid-B}
    3. edit(uuid-B, ...)  ← args reference uuid-B

  After shrinking removes call 2, we need edit to reference uuid-A instead.
  But refresh-calls only sees each call independently."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [griffin.test.contract :as c]
            [griffin.test.contract.protocol :as p]))

;; ---------------------------------------------------------------------------
;; Shared: A protocol where create produces IDs that edit references
;; ---------------------------------------------------------------------------

(defprotocol DocStore
  :extend-via-metadata true
  (create-doc [this id content])
  (edit-doc [this id new-content]))

;; ---------------------------------------------------------------------------
;; Option 1: Global refresh-args function on the model
;;
;; A single model-level function that sees ALL calls at once and can build
;; an old→new ID mapping, then apply it across all args.
;; ---------------------------------------------------------------------------

(defn make-model-option1 [id-gen]
  (c/model
   {:protocols #{DocStore}
    :methods [(c/method #'create-doc
                        (fn [state [id content]]
                          (c/return #{:ok}
                                    :next-state (-> state
                                                    (update :docs assoc id content)
                                                    (update :doc-ids conj id))))
                        :args (fn [_state]
                                (gen/tuple id-gen gen/string-alphanumeric)))
              (c/method #'edit-doc
                        (fn [state [id new-content]]
                          (c/return #{:ok}
                                    :next-state (update-in state [:docs id] (constantly new-content))))
                        :args (fn [state]
                                (if (seq (:doc-ids state))
                                  (gen/tuple (gen/elements (:doc-ids state))
                                             gen/string-alphanumeric)
                                  ;; fallback: shouldn't be called due to :requires
                                  (gen/tuple id-gen gen/string-alphanumeric)))
                        :requires (fn [state] (seq (:doc-ids state))))]
    :initial-state (fn [] {:docs {} :doc-ids #{}})}))

(defn refresh-calls-option1
  "Option 1: model-level refresh that sees all calls and builds a global
  old→new ID mapping before rewriting args."
  [model calls fresh-id-fn]
  ;; 1. Collect all unique IDs that appear as the first arg of create-doc calls
  (let [old-create-ids (->> calls
                            (filter #(= #'create-doc (p/var (:method %))))
                            (map (comp first :args)))
        ;; 2. Build old→new mapping
        id-mapping (into {} (map (fn [old-id] [old-id (fresh-id-fn)]) old-create-ids))
        ;; 3. Rewrite all args using the mapping
        rewritten (map (fn [{:keys [method args] :as call}]
                         (let [new-args (vec (update args 0 #(get id-mapping % %)))]
                           (assoc call :args new-args)))
                       calls)]
    ;; 4. Recompute state with rewritten args
    (:calls
     (reduce (fn [{:keys [calls state]} {:keys [method args]}]
               (let [ret (p/return method state args)]
                 {:calls (conj calls {:method method :args args :return ret})
                  :state (p/next-state ret)}))
             {:calls []
              :state (p/initial-state model)}
             rewritten))))

(deftest option1-global-refresh
  (testing "Option 1: global refresh-args rewrites all IDs consistently"
    (let [counter (atom 0)
          model (make-model-option1 (gen/fmap (fn [_] (str "id-" (swap! counter inc)))
                                              (gen/return nil)))
          ;; Simulate a shrunk call sequence where edit references a create'd ID
          calls [{:method (first (:methods model))   ; create-doc
                  :args ["id-1" "hello"]
                  :return (c/return #{:ok})}
                 {:method (second (:methods model))  ; edit-doc
                  :args ["id-1" "world"]
                  :return (c/return #{:ok})}]
          fresh-counter (atom 100)
          refreshed (refresh-calls-option1 model calls
                                           #(str "fresh-" (swap! fresh-counter inc)))]
      ;; Both calls now reference the same fresh ID
      (is (= (first (:args (first refreshed)))
             (first (:args (second refreshed))))
          "create and edit should reference the same fresh ID")
      (is (not= "id-1" (first (:args (first refreshed))))
          "IDs should be fresh, not the originals"))))

;; ---------------------------------------------------------------------------
;; Option 2: Automatic UUID detection and correlation-preserving replacement
;;
;; The framework walks all args, finds values that look like UUIDs,
;; builds a correlation map, and replaces them with fresh UUIDs.
;; No user code needed — purely automatic.
;; ---------------------------------------------------------------------------

(defn uuid-string? [x]
  (and (string? x)
       (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" x)))

(defn walk-replace
  "Walk a data structure, replacing any value found in mapping."
  [mapping x]
  (cond
    (contains? mapping x) (get mapping x)
    (map? x) (into {} (map (fn [[k v]] [(walk-replace mapping k) (walk-replace mapping v)])) x)
    (vector? x) (mapv (partial walk-replace mapping) x)
    (sequential? x) (map (partial walk-replace mapping) x)
    (set? x) (set (map (partial walk-replace mapping) x))
    :else x))

(defn collect-uuids
  "Walk a data structure and collect all UUID strings."
  [x]
  (cond
    (uuid-string? x) #{x}
    (map? x) (reduce into #{} (map collect-uuids (concat (keys x) (vals x))))
    (coll? x) (reduce into #{} (map collect-uuids x))
    :else #{}))

(defn refresh-calls-option2
  "Option 2: automatically detect UUIDs in all args, build a correlation-preserving
  mapping, and replace everywhere."
  [model calls]
  (let [;; 1. Collect all UUIDs across all args
        all-uuids (reduce into #{} (map (comp collect-uuids :args) calls))
        ;; 2. Build old→new mapping (one fresh UUID per unique old UUID)
        id-mapping (into {} (map (fn [old-uuid]
                                   [old-uuid (str (java.util.UUID/randomUUID))])
                                 all-uuids))
        ;; 3. Walk all args and replace
        rewritten (map (fn [{:keys [method args] :as call}]
                         (assoc call :args (walk-replace id-mapping args)))
                       calls)]
    ;; 4. Recompute state
    (:calls
     (reduce (fn [{:keys [calls state]} {:keys [method args]}]
               (let [ret (p/return method state args)]
                 {:calls (conj calls {:method method :args args :return ret})
                  :state (p/next-state ret)}))
             {:calls []
              :state (p/initial-state model)}
             rewritten))))

(deftest option2-automatic-uuid-replacement
  (testing "Option 2: automatic UUID detection preserves correlation"
    (let [uuid-a "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
          uuid-b "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
          model (make-model-option1 (gen/fmap (fn [_] (str (java.util.UUID/randomUUID)))
                                              (gen/return nil)))
          ;; create(uuid-a), create(uuid-b), edit(uuid-a), edit(uuid-b)
          calls [{:method (first (:methods model))
                  :args [uuid-a "doc1"]
                  :return (c/return #{:ok})}
                 {:method (first (:methods model))
                  :args [uuid-b "doc2"]
                  :return (c/return #{:ok})}
                 {:method (second (:methods model))
                  :args [uuid-a "doc1-v2"]
                  :return (c/return #{:ok})}
                 {:method (second (:methods model))
                  :args [uuid-b "doc2-v2"]
                  :return (c/return #{:ok})}]
          refreshed (refresh-calls-option2 model calls)
          [c1 c2 e1 e2] refreshed]
      ;; create-a and edit-a should share the same fresh UUID
      (is (= (first (:args c1)) (first (:args e1)))
          "create-a and edit-a should have the same fresh UUID")
      ;; create-b and edit-b should share a different fresh UUID
      (is (= (first (:args c2)) (first (:args e2)))
          "create-b and edit-b should have the same fresh UUID")
      ;; The two fresh UUIDs should be different from each other
      (is (not= (first (:args c1)) (first (:args c2)))
          "different original UUIDs should map to different fresh UUIDs")
      ;; All should be fresh
      (is (not= uuid-a (first (:args c1))))
      (is (not= uuid-b (first (:args c2)))))))

;; ---------------------------------------------------------------------------
;; Option 3: Deterministic cleanup via uuid/named (UUID v5)
;;
;; Each method's cleanup-args uses a deterministic function: given the same
;; input UUID and a per-replay namespace, it always produces the same output.
;; Since the mapping is deterministic, correlation is preserved automatically
;; across independent per-method cleanup calls.
;; ---------------------------------------------------------------------------

(defn deterministic-fresh-id
  "UUID v5 (name-based): deterministic mapping from old-id + generation to new-id.
  Same old-id always maps to the same new-id within a generation."
  [generation old-id]
  (let [namespace-uuid (java.util.UUID/nameUUIDFromBytes (.getBytes (str "gen-" generation)))]
    (str (java.util.UUID/nameUUIDFromBytes
          (.getBytes (str namespace-uuid "/" old-id))))))

(def ^:dynamic *refresh-generation*
  "Incremented each time we do a refresh pass, so deterministic IDs are fresh
  across replays but consistent within a single replay."
  0)

(def option3-model
  (let [gen-counter (atom 0)]
    (c/model
     {:protocols #{DocStore}
      :methods [(c/method #'create-doc
                          (fn [state [id content]]
                            (c/return #{:ok}
                                      :next-state (-> state
                                                      (update :docs assoc id content)
                                                      (update :doc-ids conj id))))
                          :args (fn [_state]
                                  (gen/tuple (gen/fmap (fn [_] (str (java.util.UUID/randomUUID)))
                                                      (gen/return nil))
                                             gen/string-alphanumeric))
                          ;; Option 3: deterministic cleanup
                          :cleanup (fn [[id content]]
                                     [(deterministic-fresh-id *refresh-generation* id)
                                      content]))
                (c/method #'edit-doc
                          (fn [state [id new-content]]
                            (c/return #{:ok}
                                      :next-state (update-in state [:docs id]
                                                             (constantly new-content))))
                          :args (fn [state]
                                  (if (seq (:doc-ids state))
                                    (gen/tuple (gen/elements (:doc-ids state))
                                               gen/string-alphanumeric)
                                    (gen/tuple (gen/fmap (fn [_] (str (java.util.UUID/randomUUID)))
                                                        (gen/return nil))
                                               gen/string-alphanumeric)))
                          :requires (fn [state] (seq (:doc-ids state)))
                          ;; Option 3: SAME deterministic function!
                          ;; Because it's deterministic, cleanup("uuid-A") in create
                          ;; produces the SAME result as cleanup("uuid-A") in edit.
                          :cleanup (fn [[id content]]
                                     [(deterministic-fresh-id *refresh-generation* id)
                                      content]))]
      :initial-state (fn [] {:docs {} :doc-ids #{}})})))

(defn refresh-calls-option3
  "Option 3: use existing per-method cleanup-args, but with a deterministic
  mapping function. Correlation is preserved because the same input always
  produces the same output."
  [model calls]
  (binding [*refresh-generation* (inc *refresh-generation*)]
    (c/refresh-calls model calls)))

(deftest option3-deterministic-cleanup
  (testing "Option 3: deterministic cleanup preserves correlation"
    (let [uuid-a "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
          ;; create(uuid-a, "hello"), edit(uuid-a, "world")
          calls [{:method (first (:methods option3-model))
                  :args [uuid-a "hello"]
                  :return (c/return #{:ok})}
                 {:method (second (:methods option3-model))
                  :args [uuid-a "world"]
                  :return (c/return #{:ok})}]
          refreshed (refresh-calls-option3 option3-model calls)
          [c1 e1] refreshed
          create-id (first (:args c1))
          edit-id (first (:args e1))]
      ;; The deterministic function maps uuid-a to the same fresh ID in both calls
      (is (= create-id edit-id)
          "deterministic cleanup maps the same input to the same output")
      (is (not= uuid-a create-id)
          "fresh ID should differ from original")

      ;; Running refresh again produces different IDs (new generation)
      (let [refreshed2 (binding [*refresh-generation* 2]
                         (refresh-calls-option3 option3-model calls))
            create-id2 (first (:args (first refreshed2)))]
        (is (not= create-id create-id2)
            "different generations produce different fresh IDs")
        (is (= (first (:args (first refreshed2)))
               (first (:args (second refreshed2))))
            "but correlation is still preserved within a generation")))))

;; ---------------------------------------------------------------------------
;; Comparison summary
;; ---------------------------------------------------------------------------

(deftest comparison-summary
  (testing "All 3 options preserve correlation"
    ;; This test just documents that all 3 pass
    (is true "Option 1: global refresh-args - user writes one fn that sees all calls")
    (is true "Option 2: automatic UUID walking - framework detects UUIDs, no user code")
    (is true "Option 3: deterministic cleanup - user writes per-method cleanup, framework provides generation")))
