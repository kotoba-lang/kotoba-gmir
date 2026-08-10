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
