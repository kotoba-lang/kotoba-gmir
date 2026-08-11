(ns kotoba.gmir-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.gmir :as gmir]))

(def v0 (gmir/vreg 0))
(def v1 (gmir/vreg 1))
(def v2 (gmir/vreg 2))
(def v3 (gmir/vreg 3))
(def v4 (gmir/vreg 4))

(def program
  {:gmir/version 1
   :gmir/instructions
   [{:gmir/op :gmir/argument :gmir/dst v0 :gmir/index 0}
    {:gmir/op :gmir/constant :gmir/dst v1 :gmir/value 1}
    {:gmir/op :gmir/add :gmir/dst v2 :gmir/left v0 :gmir/right v1}
    {:gmir/op :gmir/branch-zero :gmir/test v2 :gmir/target :test.label/zero}
    {:gmir/op :gmir/return :gmir/value v2}
    {:gmir/op :gmir/label :gmir/id :test.label/zero}
    {:gmir/op :gmir/return :gmir/value v1}]})

(deftest canonical-program-is-admitted
  (is (= program (gmir/validate! program)))
  (is (= (keyword "kotoba.gmir.vreg" "0") v0)))

(deftest closed-i64-scalar-family-is-admitted
  (doseq [op [:gmir/add :gmir/subtract :gmir/multiply :gmir/quotient
              :gmir/bit-and :gmir/bit-or :gmir/bit-xor
              :gmir/shift-left :gmir/shift-right-signed
              :gmir/shift-right-unsigned
              :gmir/equal :gmir/less-than :gmir/greater-than
              :gmir/less-or-equal :gmir/greater-or-equal]]
    (is (= op
           (-> {:gmir/version 1
                :gmir/instructions
                [{:gmir/op :gmir/constant :gmir/dst v0 :gmir/value 6}
                 {:gmir/op :gmir/constant :gmir/dst v1 :gmir/value 3}
                 {:gmir/op op :gmir/dst v2 :gmir/left v0 :gmir/right v1}
                 {:gmir/op :gmir/return :gmir/value v2}]}
               gmir/validate!
               (get-in [:gmir/instructions 2 :gmir/op]))))))

(deftest closed-f64-bit-pattern-family-is-admitted
  (doseq [op [:gmir/f64-add :gmir/f64-subtract :gmir/f64-multiply
              :gmir/f64-divide :gmir/f64-min :gmir/f64-max
              :gmir/f64-equal :gmir/f64-less-than :gmir/f64-less-or-equal
              :gmir/f64-greater-than :gmir/f64-greater-or-equal
              :gmir/f64-unordered]]
    (is (= op
           (-> {:gmir/version 1
                :gmir/instructions
                [{:gmir/op :gmir/argument :gmir/dst v0 :gmir/index 0}
                 {:gmir/op :gmir/argument :gmir/dst v1 :gmir/index 1}
                 {:gmir/op op :gmir/dst v2 :gmir/left v0 :gmir/right v1}
                 {:gmir/op :gmir/return :gmir/value v2}]}
               gmir/validate!
               (get-in [:gmir/instructions 2 :gmir/op])))))
  (is (= :gmir/f64-sqrt
         (-> {:gmir/version 1
              :gmir/instructions
              [{:gmir/op :gmir/argument :gmir/dst v0 :gmir/index 0}
               {:gmir/op :gmir/f64-sqrt :gmir/dst v1 :gmir/input v0}
               {:gmir/op :gmir/return :gmir/value v1}]}
             gmir/validate!
             (get-in [:gmir/instructions 1 :gmir/op])))))

(deftest bounded-kernel-memory-family-is-admitted
  (let [constants (mapv (fn [register value]
                          {:gmir/op :gmir/constant :gmir/dst register
                           :gmir/value value})
                        [v0 v1 v2 v3] [4096 512 3 42])]
    (doseq [instruction
            [{:gmir/op :gmir/kernel-load-u8 :gmir/dst v4
              :gmir/base v0 :gmir/length v1 :gmir/index v2 :gmir/maximum 512}
             {:gmir/op :gmir/kernel-store-u8 :gmir/dst v4
              :gmir/base v0 :gmir/length v1 :gmir/index v2 :gmir/stored v3
              :gmir/maximum 4096}
             {:gmir/op :gmir/kernel-load-u32 :gmir/dst v4
              :gmir/base v0 :gmir/length v1 :gmir/index v2 :gmir/maximum 512}
             {:gmir/op :gmir/kernel-store-u32 :gmir/dst v4
              :gmir/base v0 :gmir/length v1 :gmir/index v2 :gmir/stored v3
              :gmir/maximum 512}
             {:gmir/op :gmir/kernel-subregion :gmir/dst v4
              :gmir/base v0 :gmir/length v1 :gmir/offset v2 :gmir/size v3}]]
      (let [candidate {:gmir/version 1
                       :gmir/instructions
                       (conj constants instruction
                             {:gmir/op :gmir/return :gmir/value v4})}]
        (is (= candidate (gmir/validate! candidate))))))
  (testing "profile maxima are part of the closed operation contract"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  {:gmir/version 1
                   :gmir/instructions
                   [{:gmir/op :gmir/kernel-load-u8 :gmir/dst v3
                     :gmir/base v0 :gmir/length v1 :gmir/index v2
                     :gmir/maximum 513}
                    {:gmir/op :gmir/return :gmir/value v3}]})))))

(deftest contract-fails-closed
  (testing "unknown operations and extra fields"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate! {:gmir/version 1
                                  :gmir/instructions [{:gmir/op :gmir/magic}]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate! (update-in program [:gmir/instructions 0]
                                            assoc :ambient/policy true)))))
  (testing "invalid registers and constants"
    (is (thrown? clojure.lang.ExceptionInfo (gmir/vreg -1)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate! (assoc-in program [:gmir/instructions 1 :gmir/value]
                                           (inc (bigint Long/MAX_VALUE)))))))
  (testing "control flow is a closed local graph"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate! (assoc-in program [:gmir/instructions 3 :gmir/target]
                                           :test.label/missing))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate! (update program :gmir/instructions conj
                                         {:gmir/op :gmir/label
                                          :gmir/id :test.label/zero}))))))

(def phi-program
  {:gmir/version 2
   :gmir/instructions
   [{:gmir/op :gmir/argument :gmir/dst v0 :gmir/index 0}
    {:gmir/op :gmir/branch-zero :gmir/test v0 :gmir/target :test.label/else}
    {:gmir/op :gmir/label :gmir/id :test.label/then}
    {:gmir/op :gmir/constant :gmir/dst v1 :gmir/value 11}
    {:gmir/op :gmir/label :gmir/id :test.label/then-exit}
    {:gmir/op :gmir/jump :gmir/target :test.label/join}
    {:gmir/op :gmir/label :gmir/id :test.label/else}
    {:gmir/op :gmir/constant :gmir/dst v2 :gmir/value 22}
    {:gmir/op :gmir/label :gmir/id :test.label/else-exit}
    {:gmir/op :gmir/jump :gmir/target :test.label/join}
    {:gmir/op :gmir/label :gmir/id :test.label/join}
    {:gmir/op :gmir/phi :gmir/dst v3
     :gmir/incomings [{:gmir/predecessor :test.label/then-exit :gmir/value v1}
                      {:gmir/predecessor :test.label/else-exit :gmir/value v2}]}
    {:gmir/op :gmir/constant :gmir/dst v4 :gmir/value 1}
    {:gmir/op :gmir/add :gmir/dst (gmir/vreg 5) :gmir/left v3 :gmir/right v4}
    {:gmir/op :gmir/return :gmir/value (gmir/vreg 5)}]})

(deftest v2-phi-has-explicit-complete-predecessors
  (is (= phi-program (gmir/validate! phi-program)))
  (testing "v1 remains closed and cannot acquire v2 operations"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate! (assoc phi-program :gmir/version 1)))))
  (testing "incoming predecessor sets are exact, unique, and local"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in phi-program [:gmir/instructions 11 :gmir/incomings 1
                                         :gmir/predecessor]
                            :test.label/then-exit))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (update-in phi-program [:gmir/instructions 11 :gmir/incomings] pop)))))
  (testing "phi is a block-entry operation and rejects fallthrough/critical edges"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (update phi-program :gmir/instructions
                          #(vec (concat (subvec % 0 11)
                                        [{:gmir/op :gmir/constant
                                          :gmir/dst (gmir/vreg 9) :gmir/value 0}]
                                        (subvec % 11)))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in phi-program [:gmir/instructions 1 :gmir/target]
                            :test.label/join))))))

(def scalar-call-module
  {:gmir/version 3
   :gmir/entry 'main
   :gmir/functions
   [{:gmir/name 'add-one
     :gmir/arity 1
     :gmir/instructions
     [{:gmir/op :gmir/argument :gmir/dst v0 :gmir/index 0}
      {:gmir/op :gmir/constant :gmir/dst v1 :gmir/value 1}
      {:gmir/op :gmir/add :gmir/dst v2 :gmir/left v0 :gmir/right v1}
      {:gmir/op :gmir/return :gmir/value v2}]}
    {:gmir/name 'main
     :gmir/arity 1
     :gmir/instructions
     [{:gmir/op :gmir/argument :gmir/dst v0 :gmir/index 0}
      {:gmir/op :gmir/call :gmir/dst v1 :gmir/callee 'add-one
       :gmir/arguments [v0]}
      {:gmir/op :gmir/add :gmir/dst v2 :gmir/left v0 :gmir/right v1}
      {:gmir/op :gmir/return :gmir/value v2}]}]})

(deftest v3-owns-function-scopes-and-scalar-direct-calls
  (is (= scalar-call-module (gmir/validate! scalar-call-module)))
  (is (gmir/function-id? 'main))
  (testing "vregs and labels may be reused by distinct function scopes"
    (is (= v0 (get-in (gmir/validate! scalar-call-module)
                       [:gmir/functions 1 :gmir/instructions 0 :gmir/dst]))))
  (testing "v1 and v2 cannot silently acquire calls"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  {:gmir/version 2
                   :gmir/instructions
                   [{:gmir/op :gmir/call :gmir/dst v0 :gmir/callee 'f
                     :gmir/arguments []}]})))))

(deftest v3-owns-terminal-tail-calls
  (let [module (-> scalar-call-module
                   (assoc-in [:gmir/functions 1 :gmir/instructions]
                             [{:gmir/op :gmir/argument :gmir/dst v0 :gmir/index 0}
                              {:gmir/op :gmir/tail-call :gmir/callee 'add-one
                               :gmir/arguments [v0]}]))]
    (is (= module (gmir/validate! module)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in module
                            [:gmir/functions 1 :gmir/instructions 1 :gmir/dst]
                            v1))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in module
                            [:gmir/functions 1 :gmir/instructions 1 :gmir/callee]
                            'missing))))))

(deftest v3-call-graph-and-function-boundaries-fail-closed
  (testing "entry and callee names resolve inside the same module"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate! (assoc scalar-call-module :gmir/entry 'missing))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in scalar-call-module
                            [:gmir/functions 1 :gmir/instructions 1 :gmir/callee]
                            'missing)))))
  (testing "calls and argument loads match declared arities"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in scalar-call-module
                            [:gmir/functions 1 :gmir/instructions 1 :gmir/arguments]
                            []))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in scalar-call-module
                            [:gmir/functions 1 :gmir/instructions 0 :gmir/index]
                            1)))))
  (testing "function names are unique and each function owns a closed shape"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in scalar-call-module [:gmir/functions 1 :gmir/name]
                            'add-one))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in scalar-call-module [:gmir/functions 0 :ambient/policy]
                            true))))))

(deftest runtime-calls-have-a-closed-operation-and-arity-contract
  (let [program {:gmir/version 1
                 :gmir/instructions
                 [{:gmir/op :gmir/argument :gmir/dst v0 :gmir/index 0}
                  {:gmir/op :gmir/runtime-call :gmir/dst v1
                   :gmir/runtime :vector-count :gmir/arguments [v0]}
                  {:gmir/op :gmir/return :gmir/value v1}]}]
    (is (= program (gmir/validate! program)))
    (is (= 3 (:kgraph-assert! gmir/runtime-operation-arities)))
    (testing "unknown runtime operations fail closed"
      (is (thrown? clojure.lang.ExceptionInfo
                   (gmir/validate!
                    (assoc-in program [:gmir/instructions 1 :gmir/runtime]
                              :ambient-host-call)))))
    (testing "the semantic operation owns its exact arity"
      (is (thrown? clojure.lang.ExceptionInfo
                   (gmir/validate!
                    (assoc-in program [:gmir/instructions 1 :gmir/arguments]
                              [])))))))

(deftest x86-privileged-actions-have-a-closed-contract
  (let [instructions
        [{:gmir/op :gmir/constant :gmir/dst v0 :gmir/value 1}
         {:gmir/op :gmir/constant :gmir/dst v1 :gmir/value 2}
         {:gmir/op :gmir/x86-privileged :gmir/dst v2
          :gmir/action :write-msr :gmir/arguments [v0 v1]}
         {:gmir/op :gmir/return :gmir/value v2}]
        program {:gmir/version 3 :gmir/entry 'main
                 :gmir/functions [{:gmir/name 'main :gmir/arity 0
                                   :gmir/instructions instructions}]}]
    (is (= program (gmir/validate! program)))
    (is (= 2 (:write-msr gmir/x86-privileged-action-arities)))
    (doseq [malformed [(assoc-in program [:gmir/functions 0 :gmir/instructions 2
                                          :gmir/action] :unknown)
                       (update-in program [:gmir/functions 0 :gmir/instructions 2
                                           :gmir/arguments] pop)
                       (assoc-in program [:gmir/functions 0 :gmir/instructions 2
                                          :gmir/ambient-policy] true)]]
      (is (thrown? clojure.lang.ExceptionInfo (gmir/validate! malformed))))
    (is (= {:gmir/version 1 :gmir/instructions instructions}
           (gmir/validate! {:gmir/version 1 :gmir/instructions instructions})))))

(deftest immutable-data-addresses-carry-closed-utf8-content
  (let [program {:gmir/version 1
                 :gmir/instructions
                 [{:gmir/op :gmir/data-address :gmir/dst v0
                   :gmir/content "hello😀"}
                  {:gmir/op :gmir/return :gmir/value v0}]}]
    (is (= program (gmir/validate! program)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gmir/validate!
                  (assoc-in program [:gmir/instructions 0 :gmir/content]
                            [:ambient "bytes"]))))))

(deftest capability-calls-close-the-id-and-typed-boundary
  (let [program {:gmir/version 1
                 :gmir/instructions
                 [{:gmir/op :gmir/constant :gmir/dst v0 :gmir/value 9}
                  {:gmir/op :gmir/capability-call :gmir/dst v1
                   :gmir/capability 7 :gmir/kind :result-i64
                   :gmir/arguments [v0]}
                  {:gmir/op :gmir/return :gmir/value v1}]}]
    (is (= program (gmir/validate! program)))
    (is (= 3 (:result-i64 gmir/capability-kinds)))
    (is (= 4 (:clock-v1 gmir/capability-kinds)))
    (doseq [[path value] [[[:gmir/instructions 1 :gmir/capability] 256]
                          [[:gmir/instructions 1 :gmir/capability] -1]
                          [[:gmir/instructions 1 :gmir/kind] :ambient-object]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (gmir/validate! (assoc-in program path value)))
          [path value]))))
