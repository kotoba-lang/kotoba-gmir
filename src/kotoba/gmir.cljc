(ns kotoba.gmir
  "Closed target-independent Generic Machine IR v1/v2 contract.")

(def version 2)
(def supported-versions #{1 2})

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
   :gmir/phi #{:gmir/op :gmir/dst :gmir/incomings}
   :gmir/return #{:gmir/op :gmir/value}})

(def ^:private v1-operations
  (disj (set (keys instruction-keysets)) :gmir/phi))

(def ^:private v2-operations
  (set (keys instruction-keysets)))

(defn- operations-for [program-version]
  (case program-version
    1 v1-operations
    2 v2-operations
    #{}))

(defn- phi-incoming? [incoming]
  (and (map? incoming)
       (= #{:gmir/predecessor :gmir/value} (set (keys incoming)))
       (label? (:gmir/predecessor incoming))
       (vreg? (:gmir/value incoming))))

(defn- preceding-label
  [instructions index]
  (loop [cursor (dec index)]
    (when (not (neg? cursor))
      (let [instruction (nth instructions cursor)]
        (cond
          (= :gmir/label (:gmir/op instruction)) (:gmir/id instruction)
          (= :gmir/phi (:gmir/op instruction)) (recur (dec cursor))
          :else nil)))))

(defn- block-label-before
  [instructions index]
  (some (fn [cursor]
          (let [instruction (nth instructions cursor)]
            (when (= :gmir/label (:gmir/op instruction))
              (:gmir/id instruction))))
        (range (dec index) -1 -1)))

(defn- validate-phis!
  [instructions]
  (doseq [[index {:gmir/keys [op incomings] :as instruction}]
          (map-indexed vector instructions)
          :when (= :gmir/phi op)]
    (let [join (preceding-label instructions index)
          incoming-predecessors (mapv :gmir/predecessor incomings)
          incoming-set (set incoming-predecessors)
          jump-predecessors
          (->> instructions
               (map-indexed vector)
               (keep (fn [[jump-index candidate]]
                       (when (and (= :gmir/jump (:gmir/op candidate))
                                  (= join (:gmir/target candidate)))
                         (block-label-before instructions jump-index))))
               set)]
      (when-not join
        (reject! :phi-not-at-block-entry instruction))
      (when-not (and (vector? incomings)
                     (<= 2 (count incomings))
                     (every? phi-incoming? incomings)
                     (= (count incoming-predecessors) (count incoming-set)))
        (reject! :invalid-phi-incomings instruction))
      (when-not (= incoming-set jump-predecessors)
        (reject! :phi-predecessor-mismatch
                 {:join join :incoming incoming-set :jumps jump-predecessors}))
      (when (some #(and (= :gmir/branch-zero (:gmir/op %))
                        (= join (:gmir/target %)))
                  instructions)
        (reject! :phi-critical-edge instruction))
      (let [join-index (.indexOf instructions
                                 (first (filter #(and (= :gmir/label (:gmir/op %))
                                                      (= join (:gmir/id %)))
                                                instructions)))]
        (when (or (zero? join-index)
                  (not= :gmir/jump (:gmir/op (nth instructions (dec join-index)))))
          (reject! :phi-fallthrough-predecessor instruction))))))

(defn validate!
  "Validate and return a closed GMIR v1 or v2 program. v2 adds canonical phi
  nodes whose incoming edges are explicit predecessor jumps."
  [program]
  (when-not (and (map? program)
                 (= #{:gmir/version :gmir/instructions} (set (keys program)))
                 (contains? supported-versions (:gmir/version program))
                 (vector? (:gmir/instructions program)))
    (reject! :non-canonical-program program))
  (doseq [instruction (:gmir/instructions program)]
    (let [op (:gmir/op instruction)
          allowed (operations-for (:gmir/version program))]
      (when-not (and (contains? allowed op)
                     (= (get instruction-keysets op) (set (keys instruction))))
        (reject! :non-canonical-instruction instruction))
      (doseq [register (keep instruction [:gmir/dst :gmir/left :gmir/right
                                           :gmir/test])]
        (when-not (vreg? register)
          (reject! :invalid-virtual-register instruction)))
      (when (and (= op :gmir/return) (not (vreg? (:gmir/value instruction))))
        (reject! :invalid-virtual-register instruction))
      (when (= op :gmir/phi)
        (when-not (and (vector? (:gmir/incomings instruction))
                       (every? phi-incoming? (:gmir/incomings instruction)))
          (reject! :invalid-phi-incomings instruction)))
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
        (reject! :unresolved-target {:target target})))
    (when (= 2 (:gmir/version program))
      (validate-phis! instructions)))
  program)
