(ns kotoba.gmir-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.gmir :as gmir]))

(def v0 (gmir/vreg 0))
(def v1 (gmir/vreg 1))
(def v2 (gmir/vreg 2))

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
