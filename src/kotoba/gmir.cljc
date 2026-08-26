(ns kotoba.gmir
  "Closed target-independent Generic Machine IR contract.")

(def version 3)
(def supported-versions #{1 2 3})

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
   :gmir/data-address #{:gmir/op :gmir/dst :gmir/content}
   :gmir/add #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/subtract #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/multiply #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/quotient #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/bit-and #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/bit-or #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/bit-xor #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/shift-left #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/shift-right-signed #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/shift-right-unsigned #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-add #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-subtract #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-multiply #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-divide #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-min #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-max #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-sqrt #{:gmir/op :gmir/dst :gmir/input}
   :gmir/f64-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-less-than #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-less-or-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-greater-than #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-greater-or-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f64-unordered #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/kernel-load-u8 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                          :gmir/index :gmir/maximum}
   :gmir/kernel-store-u8 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/stored :gmir/maximum}
   :gmir/kernel-load-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/maximum}
   :gmir/kernel-store-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                            :gmir/index :gmir/stored :gmir/maximum}
   ;; The lock pair carries exactly the load's fields: it reads and writes one
   ;; u32 at `index`, and the value it produces is whether this caller won.
   ;; `:gmir/stored` is deliberately absent -- what gets stored is fixed by the
   ;; operation (1 to acquire, 0 to release), not supplied by the guest, which
   ;; is the whole difference between a lock and a compare-exchange.
   :gmir/kernel-try-lock-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                               :gmir/index :gmir/maximum}
   :gmir/kernel-unlock-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                             :gmir/index :gmir/maximum}
   :gmir/kernel-subregion #{:gmir/op :gmir/dst :gmir/base :gmir/length
                            :gmir/offset :gmir/size}
   :gmir/equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/less-than #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/greater-than #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/less-or-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/greater-or-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/label #{:gmir/op :gmir/id}
   :gmir/branch-zero #{:gmir/op :gmir/test :gmir/target}
   :gmir/jump #{:gmir/op :gmir/target}
   :gmir/phi #{:gmir/op :gmir/dst :gmir/incomings}
   :gmir/call #{:gmir/op :gmir/dst :gmir/callee :gmir/arguments}
   :gmir/tail-call #{:gmir/op :gmir/callee :gmir/arguments}
   :gmir/runtime-call #{:gmir/op :gmir/dst :gmir/runtime :gmir/arguments}
   :gmir/x86-privileged #{:gmir/op :gmir/dst :gmir/action :gmir/arguments}
   :gmir/capability-call #{:gmir/op :gmir/dst :gmir/capability
                           :gmir/kind :gmir/arguments}
   :gmir/return #{:gmir/op :gmir/value}})

(def ^:private v1-operations
  (disj (set (keys instruction-keysets)) :gmir/phi :gmir/tail-call))

(def ^:private v2-operations
  (set (keys instruction-keysets)))

(def ^:private v3-operations
  (set (keys instruction-keysets)))

(defn- operations-for [program-version]
  (case program-version
    1 v1-operations
    2 (disj v2-operations :gmir/call :gmir/tail-call)
    3 v3-operations
    #{}))

(defn function-id?
  "True for a canonical source-level function identifier. Symbols remain data
  here; target linkage names are deliberately owned by later layers."
  [value]
  (and (symbol? value) (not (empty? (name value)))))

(def runtime-operation-arities
  "Closed target-independent host-runtime operation contract. The runtime
  operation names and arities are semantic; target context-table offsets and
  calling conventions remain owned by MIR/codegen."
  {:pair 2
   :pair-first 1
   :pair-second 1
   :kgraph-assert! 3
   :kgraph-get 2
   :kgraph-count 1
   :kgraph-entity-at 2
   :string-byte-length 1
   :string=? 2
   :string-concat 2
   :string-substring 3
   :string-code-point-at 2
   :vector-new-empty 0
   :vector-conj 2
   :vector-count 1
   :vector-at 2
   :vector-assoc 3
   :vector-drop 2})

(def x86-privileged-action-arities
  "Closed x86-64 kernel instruction family. These actions are semantic at
  GMIR; MIR owns target admission and rejects them for every other target."
  {:boot-info 0
   :read-cr0 0 :write-cr0 1
   :read-cr2 0 :read-cr3 0 :write-cr3 1 :invlpg 1
   :read-cs 0 :page-fault-handler-address 0
   :rt-timer-handler-address 0
   :page-fault-recovery-handler-address 0
   :configure-page-fault-recovery 2 :load-idt 2
   :double-fault-handler-address 0
   :configure-double-fault-ist 2 :load-gdt-tss 2
   :probe-guard-write 0 :probe-text-write 0 :probe-nx-execute 0
   :probe-recoverable-guard-write 0 :probe-double-fault 0
   :cli 0 :sti 0 :hlt 0 :pause 0
   :out-u8 2 :out-u32 2 :in-u8 1 :in-u32 1
   :read-msr 1 :write-msr 2
   :cpuid-eax 2 :cpuid-ebx 2 :cpuid-ecx 2 :cpuid-edx 2})

(def capability-kinds
  "Closed native capability boundary. Zero is the scalar callback profile;
  positive values are the typed host ABI discriminator."
  {:i64 0 :string 1 :option-i64 2 :result-i64 3 :clock-v1 4 :dataspace-v1 5})

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

(defn- validate-instructions!
  [program-version instructions]
  (when-not (vector? instructions)
    (reject! :non-canonical-instructions instructions))
  (doseq [instruction instructions]
    (let [op (:gmir/op instruction)
          allowed (operations-for program-version)]
      (when-not (and (contains? allowed op)
                     (= (get instruction-keysets op) (set (keys instruction))))
        (reject! :non-canonical-instruction instruction))
      (doseq [register (keep instruction [:gmir/dst :gmir/input :gmir/left
                                           :gmir/right :gmir/test :gmir/base
                                           :gmir/length :gmir/stored
                                           :gmir/offset :gmir/size])]
        (when-not (vreg? register)
          (reject! :invalid-virtual-register instruction)))
      (when (and (contains? #{:gmir/kernel-load-u8 :gmir/kernel-store-u8
                              :gmir/kernel-load-u32 :gmir/kernel-store-u32
                              :gmir/kernel-try-lock-u32 :gmir/kernel-unlock-u32} op)
                 (not (vreg? (:gmir/index instruction))))
        (reject! :invalid-virtual-register instruction))
      (when (and (= op :gmir/return) (not (vreg? (:gmir/value instruction))))
        (reject! :invalid-virtual-register instruction))
      (when (contains? #{:gmir/call :gmir/tail-call} op)
        (when-not (and (or (= op :gmir/tail-call)
                           (vreg? (:gmir/dst instruction)))
                       (function-id? (:gmir/callee instruction))
                       (vector? (:gmir/arguments instruction))
                       (<= (count (:gmir/arguments instruction)) 5)
                       (every? vreg? (:gmir/arguments instruction)))
          (reject! :invalid-call instruction)))
      (when (= op :gmir/runtime-call)
        (let [runtime (:gmir/runtime instruction)
              arguments (:gmir/arguments instruction)
              expected (get runtime-operation-arities runtime ::missing)]
          (when-not (and (vreg? (:gmir/dst instruction))
                         (not= ::missing expected)
                         (vector? arguments)
                         (= expected (count arguments))
                         (every? vreg? arguments))
            (reject! :invalid-runtime-call instruction))))
      (when (= op :gmir/x86-privileged)
        (let [action (:gmir/action instruction)
              arguments (:gmir/arguments instruction)
              expected (get x86-privileged-action-arities action ::missing)]
          (when-not (and (vreg? (:gmir/dst instruction))
                         (not= ::missing expected)
                         (vector? arguments)
                         (= expected (count arguments))
                         (every? vreg? arguments))
            (reject! :invalid-x86-privileged instruction))))
      (when (= op :gmir/capability-call)
        (when-not (and (vreg? (:gmir/dst instruction))
                       (vector? (:gmir/arguments instruction))
                       (= 1 (count (:gmir/arguments instruction)))
                       (vreg? (first (:gmir/arguments instruction)))
                       (integer? (:gmir/capability instruction))
                       (<= 0 (:gmir/capability instruction) 255)
                       (contains? capability-kinds (:gmir/kind instruction)))
          (reject! :invalid-capability-call instruction)))
      (when (= op :gmir/phi)
        (when-not (and (vector? (:gmir/incomings instruction))
                       (every? phi-incoming? (:gmir/incomings instruction)))
          (reject! :invalid-phi-incomings instruction)))
      (when (and (= op :gmir/constant)
                 (not (i64-value? (:gmir/value instruction))))
        (reject! :constant-not-i64 instruction))
      (when (and (= op :gmir/data-address)
                 (not (string? (:gmir/content instruction))))
        (reject! :invalid-data-content instruction))
      (when (and (= op :gmir/argument)
                 (not (and (integer? (:gmir/index instruction))
                           (not (neg? (:gmir/index instruction))))))
        (reject! :argument-index-invalid instruction))
      (when (contains? #{:gmir/kernel-load-u8 :gmir/kernel-store-u8} op)
        (when-not (contains? #{512 4096 16384} (:gmir/maximum instruction))
          (reject! :invalid-kernel-memory-maximum instruction))
        (when (and (= op :gmir/kernel-store-u8)
                   (= 16384 (:gmir/maximum instruction)))
          (reject! :invalid-kernel-memory-maximum instruction)))
      (when (contains? #{:gmir/kernel-load-u32 :gmir/kernel-store-u32} op)
        (when-not (= 512 (:gmir/maximum instruction))
          (reject! :invalid-kernel-memory-maximum instruction)))
      ;; 4096 and only 4096, because there is one spelling of each lock
      ;; operation and it names a page. The value runtime's lock word lives at
      ;; offset 0 of an RW/NX page, and its callers declare lengths of 512 and
      ;; 4096; a 512 ceiling would trap the 4096 ones on the length check
      ;; before reaching the word they share. Pinned as a single value rather
      ;; than a set so a second spelling has to be added deliberately.
      (when (contains? #{:gmir/kernel-try-lock-u32 :gmir/kernel-unlock-u32} op)
        (when-not (= 4096 (:gmir/maximum instruction))
          (reject! :invalid-kernel-memory-maximum instruction)))
      (when (contains? #{:gmir/label :gmir/branch-zero :gmir/jump} op)
        (let [id (if (= op :gmir/label)
                   (:gmir/id instruction)
                   (:gmir/target instruction))]
          (when-not (label? id)
            (reject! :invalid-label instruction))))))
  (let [labels (map :gmir/id (filter #(= :gmir/label (:gmir/op %)) instructions))
        label-set (set labels)
        targets (keep :gmir/target instructions)]
    (when-not (= (count labels) (count label-set))
      (reject! :duplicate-label {:labels labels}))
    (doseq [target targets]
      (when-not (contains? label-set target)
        (reject! :unresolved-target {:target target})))
    (when (contains? #{2 3} program-version)
      (validate-phis! instructions)))
  instructions)

(defn- validate-v3-module!
  [{:gmir/keys [entry functions] :as program}]
  (when-not (and (= #{:gmir/version :gmir/entry :gmir/functions}
                    (set (keys program)))
                 (function-id? entry)
                 (vector? functions)
                 (seq functions))
    (reject! :non-canonical-module program))
  (let [names (mapv :gmir/name functions)
        signatures (into {} (map (juxt :gmir/name :gmir/arity) functions))]
    (when-not (and (every? function-id? names)
                   (= (count names) (count (distinct names))))
      (reject! :invalid-function-names names))
    (when-not (contains? signatures entry)
      (reject! :missing-entry-function {:entry entry}))
    (doseq [{:gmir/keys [name arity instructions] :as function} functions]
      (when-not (and (= #{:gmir/name :gmir/arity :gmir/instructions}
                        (set (keys function)))
                     (function-id? name)
                     (integer? arity)
                     (<= 0 arity 5))
        (reject! :non-canonical-function function))
      (validate-instructions! 3 instructions)
      (let [argument-indices (mapv :gmir/index
                                   (filter #(= :gmir/argument (:gmir/op %))
                                           instructions))]
        (when-not (and (every? #(< % arity) argument-indices)
                       (= (count argument-indices)
                          (count (distinct argument-indices))))
          (reject! :invalid-function-arguments
                   {:function name :arity arity :indices argument-indices})))
      (doseq [{:gmir/keys [callee arguments] :as call}
              (filter #(contains? #{:gmir/call :gmir/tail-call}
                                   (:gmir/op %)) instructions)]
        (let [callee-arity (get signatures callee ::missing)]
          (when (= ::missing callee-arity)
            (reject! :unresolved-callee call))
          (when-not (= callee-arity (count arguments))
            (reject! :call-arity-mismatch
                     {:function name :call call :expected callee-arity}))))))
  program)

(defn validate!
  "Validate and return a closed GMIR program. v1 is scalar/control, v2 adds
  phi values, and v3 owns a non-empty function module with bounded scalar
  direct calls. Labels and virtual registers remain function-local."
  [program]
  (when-not (and (map? program)
                 (contains? supported-versions (:gmir/version program)))
    (reject! :non-canonical-program program))
  (if (= 3 (:gmir/version program))
    (validate-v3-module! program)
    (do
      (when-not (= #{:gmir/version :gmir/instructions} (set (keys program)))
        (reject! :non-canonical-program program))
      (validate-instructions! (:gmir/version program) (:gmir/instructions program))
      program))
  program)
