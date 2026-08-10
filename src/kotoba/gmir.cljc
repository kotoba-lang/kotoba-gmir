(ns kotoba.gmir
  "Closed target-independent Generic Machine IR v1 contract.")

(def version 1)

(defn- reject! [problem instruction]
  (throw (ex-info (str "GMIR rejected: " (name problem))
                  {:phase :gmir :problem problem :instruction instruction})))

(defn vreg
  "Construct a canonical virtual-register identifier."
  [index]
  (when-not (and (integer? index) (not (neg? index)))
    (reject! :invalid-virtual-register-index {:index index}))
  (keyword "kotoba.gmir.vreg" (str index)))

(defn vreg? [value]
  (and (keyword? value)
       (= "kotoba.gmir.vreg" (namespace value))
       (boolean (re-matches #"(?:0|[1-9][0-9]*)" (name value)))))

(defn label? [value]
  (and (keyword? value) (some? (namespace value))))

(defn i64-value?
  "True for an exact signed i64 on the current host."
  [value]
  #?(:clj (and (integer? value)
               (<= (bigint Long/MIN_VALUE) (bigint value) (bigint Long/MAX_VALUE)))
     :cljs (or (and (number? value) (js/Number.isSafeInteger value))
               (try
                 (= value (js/BigInt.asIntN 64 value))
                 (catch :default _ false)))))

(def instruction-keysets
  {:gmir/argument #{:gmir/op :gmir/dst :gmir/index}
   :gmir/constant #{:gmir/op :gmir/dst :gmir/value}
   :gmir/add #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/subtract #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/multiply #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/quotient #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/bit-and #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/bit-or #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/bit-xor #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/less-than #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/greater-than #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/less-or-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/greater-or-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/label #{:gmir/op :gmir/id}
   :gmir/branch-zero #{:gmir/op :gmir/test :gmir/target}
   :gmir/jump #{:gmir/op :gmir/target}
   :gmir/return #{:gmir/op :gmir/value}})

(defn validate!
  "Validate and return a closed `{:gmir/version 1 :gmir/instructions [...]}`."
  [program]
  (when-not (and (map? program)
                 (= #{:gmir/version :gmir/instructions} (set (keys program)))
                 (= version (:gmir/version program))
                 (vector? (:gmir/instructions program)))
    (reject! :non-canonical-program program))
  (doseq [instruction (:gmir/instructions program)]
    (let [op (:gmir/op instruction)]
      (when-not (= (get instruction-keysets op) (set (keys instruction)))
        (reject! :non-canonical-instruction instruction))
      (doseq [register (keep instruction [:gmir/dst :gmir/left :gmir/right
                                           :gmir/test])]
        (when-not (vreg? register)
          (reject! :invalid-virtual-register instruction)))
      (when (and (= op :gmir/return) (not (vreg? (:gmir/value instruction))))
        (reject! :invalid-virtual-register instruction))
      (when (and (= op :gmir/constant)
                 (not (i64-value? (:gmir/value instruction))))
        (reject! :constant-not-i64 instruction))
      (when (and (= op :gmir/argument)
                 (not (and (integer? (:gmir/index instruction))
                           (not (neg? (:gmir/index instruction))))))
        (reject! :argument-index-invalid instruction))
      (when (contains? #{:gmir/label :gmir/branch-zero :gmir/jump} op)
        (let [id (if (= op :gmir/label)
                   (:gmir/id instruction)
                   (:gmir/target instruction))]
          (when-not (label? id)
            (reject! :invalid-label instruction))))))
  (let [instructions (:gmir/instructions program)
        labels (map :gmir/id (filter #(= :gmir/label (:gmir/op %)) instructions))
        label-set (set labels)
        targets (keep :gmir/target instructions)]
    (when-not (= (count labels) (count label-set))
      (reject! :duplicate-label {:labels labels}))
    (doseq [target targets]
      (when-not (contains? label-set target)
        (reject! :unresolved-target {:target target}))))
  program)
