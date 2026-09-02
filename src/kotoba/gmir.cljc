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
   ;; f32: binary32, the parallel family
   ;; (kotoba-lang docs/adr/ADR-kotoba-floating-point-on-native.md).
   ;;
   ;; Same operand shapes as the f64 family above and the same one-word vreg --
   ;; an f32 travels as its binary32 pattern SIGN-EXTENDED from bit 31, which is
   ;; still one machine word and needs no new value kind here.
   ;;
   ;; TWO f64 members have no f32 twin, and their absence is the decision rather
   ;; than an omission: x86's MINSS/MAXSS return the SECOND operand when either
   ;; input is NaN, while AArch64's FMIN/FMAX and the KIR oracle's Math/min
   ;; return the NaN. `:gmir/f64-min`/`:gmir/f64-max` above therefore already
   ;; mean two different things on the two targets; that is recorded upstream
   ;; and not repaired here, and this width does not inherit it.
   :gmir/f32-add #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-subtract #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-multiply #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-divide #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-sqrt #{:gmir/op :gmir/dst :gmir/input}
   :gmir/f32-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-less-than #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-less-or-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-greater-than #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-greater-or-equal #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   :gmir/f32-unordered #{:gmir/op :gmir/dst :gmir/left :gmir/right}
   ;; Width conversions. Only the four on which both ISAs and the KIR oracle
   ;; agree for EVERY input: widening is exact and total, narrowing is
   ;; round-to-nearest-even with overflow to an infinity, and every i64 has a
   ;; defined image at both widths. The `-checked` conversions trap in the
   ;; oracle and no backend emits that check; the truncating float-to-int ones
   ;; have THREE answers out of domain (x86 yields the integer indefinite value,
   ;; AArch64 saturates, the oracle traps). Neither family is declared here,
   ;; because an operation that cannot mean one thing is not an operation.
   :gmir/f32-to-f64 #{:gmir/op :gmir/dst :gmir/input}
   :gmir/f64-to-f32 #{:gmir/op :gmir/dst :gmir/input}
   :gmir/i64-to-f32 #{:gmir/op :gmir/dst :gmir/input}
   :gmir/i64-to-f64 #{:gmir/op :gmir/dst :gmir/input}
   :gmir/kernel-load-u8 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                          :gmir/index :gmir/maximum}
   :gmir/kernel-store-u8 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/stored :gmir/maximum}
   :gmir/kernel-load-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/maximum}
   :gmir/kernel-store-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                            :gmir/index :gmir/stored :gmir/maximum}
   ;; memwidth: the two remaining MMIO transfer widths. A device register file
   ;; is not all bytes and words -- 16-bit is what a PCI vendor/device ID pair
   ;; and most legacy device registers are, and 64-bit is what a descriptor
   ;; ring pointer is. They carry EXACTLY the fields their u8/u32 siblings do,
   ;; because the only thing that differs is how many bytes the access moves;
   ;; the width lives in the operation name, the way it already does for the
   ;; two that were here first.
   :gmir/kernel-load-u16 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/maximum}
   :gmir/kernel-store-u16 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                            :gmir/index :gmir/stored :gmir/maximum}
   :gmir/kernel-load-u64 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/maximum}
   :gmir/kernel-store-u64 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                            :gmir/index :gmir/stored :gmir/maximum}
   ;; memwidth: the slice family (amu ADR 0285). Same four sources, and
   ;; deliberately the same keyset, so nothing downstream has to learn a new
   ;; instruction SHAPE. Two things differ and neither is a field:
   ;;
   ;;   * `:gmir/index` counts ELEMENTS, not bytes, and the backend scales it
   ;;     into the addressing mode. `:gmir/length` counts elements too.
   ;;   * `:gmir/maximum` is `slice-item-limit`, which is an address-space
   ;;     bound rather than a window profile. ADR 0285's whole point is that
   ;;     the carrier does not go through the vector arena and therefore is
   ;;     not bounded by `vector-item-limit`; a 10 GiB model region is a
   ;;     legitimate slice and 16384 is not its ceiling.
   :gmir/slice-load-u8 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                         :gmir/index :gmir/maximum}
   :gmir/slice-store-u8 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                          :gmir/index :gmir/stored :gmir/maximum}
   :gmir/slice-load-u16 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                          :gmir/index :gmir/maximum}
   :gmir/slice-store-u16 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/stored :gmir/maximum}
   :gmir/slice-load-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                          :gmir/index :gmir/maximum}
   :gmir/slice-store-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/stored :gmir/maximum}
   :gmir/slice-load-u64 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                          :gmir/index :gmir/maximum}
   :gmir/slice-store-u64 #{:gmir/op :gmir/dst :gmir/base :gmir/length
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
   ;; sysops: the general atomic family. The lock pair above fixes both its
   ;; comparand and its replacement, which is what makes it a lock; these six
   ;; take the word from the guest, which is what makes them the read-modify-
   ;; writes a device driver's descriptor ring needs -- a producer index the
   ;; guest advances by its own delta, an ownership word it swaps for its own
   ;; value, a doorbell it claims against its own comparand.
   ;;
   ;; They carry exactly the store's fields, plus `:gmir/expected` for the two
   ;; compare-exchanges. `:gmir/stored` is the guest's operand in every case:
   ;; the addend for `atomic-add`, the replacement for `xchg` and `cmpxchg`.
   ;; `:gmir/dst` is the word that was in memory BEFORE the operation, for all
   ;; six -- a compare-exchange that returned only a success flag would force
   ;; the caller to re-read, which is the race the instruction exists to close.
   :gmir/kernel-atomic-add-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                                 :gmir/index :gmir/stored :gmir/maximum}
   :gmir/kernel-atomic-add-u64 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                                 :gmir/index :gmir/stored :gmir/maximum}
   :gmir/kernel-xchg-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/stored :gmir/maximum}
   :gmir/kernel-xchg-u64 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                           :gmir/index :gmir/stored :gmir/maximum}
   :gmir/kernel-cmpxchg-u32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                              :gmir/index :gmir/expected :gmir/stored
                              :gmir/maximum}
   :gmir/kernel-cmpxchg-u64 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                              :gmir/index :gmir/expected :gmir/stored
                              :gmir/maximum}
   ;; sysops: end
   ;; simd: one dot product of two f32 regions, as ONE operation.
   ;;
   ;; It is not a load family member and not a composition of them. Every
   ;; other memory operation here moves one element and lets the guest write
   ;; the loop; this one owns the loop, because what it exists to select is a
   ;; vector instruction sequence -- eight lanes at a time on a machine that
   ;; has AVX2, and a scalar sequence with the SAME accumulation tree on a
   ;; machine that does not. A guest-written loop over `slice-load-u32` can
   ;; express the arithmetic and cannot express that choice: the backend
   ;; would have to recognise the loop to vectorise it, and a backend that
   ;; recognises loops is a different program from this one.
   ;;
   ;; TWO regions, so TWO bases and TWO lengths. `:gmir/base`/`:gmir/length`
   ;; stay the names of the FIRST region rather than becoming
   ;; `:gmir/first-base`, so that anything downstream reading `:gmir/base`
   ;; still finds a base; the second pair is named for its position.
   ;; `:gmir/count` counts ELEMENTS, and the lengths count BYTES -- the same
   ;; split the slice family made, kept here because the lengths describe
   ;; regions the caller declared in bytes and the count describes how many
   ;; four-byte elements of them to read.
   ;;
   ;; `:gmir/maximum` is the byte ceiling on BOTH lengths, pinned to one
   ;; value the way the lock pair's 4096 is, so a second spelling has to be
   ;; added deliberately.
   :gmir/kernel-dot-f32 #{:gmir/op :gmir/dst :gmir/base :gmir/length
                          :gmir/second-base :gmir/second-length
                          :gmir/count :gmir/maximum}
   ;; simd: end
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


;; memwidth: the checked-memory families, as data rather than as a set
;; repeated at each of the four places that used to name them.
;;
;; `kernel-window-operations` are BYTE-indexed accesses into a window whose
;; declared length the operation's `:gmir/maximum` caps. `slice-operations`
;; are ELEMENT-indexed accesses into a host-supplied region whose ceiling is
;; the address space (amu ADR 0285); their `:gmir/index` is scaled by the
;; access width in the addressing mode rather than added as a byte offset.

(def kernel-window-operations
  #{:gmir/kernel-load-u8 :gmir/kernel-store-u8
    :gmir/kernel-load-u16 :gmir/kernel-store-u16
    :gmir/kernel-load-u32 :gmir/kernel-store-u32
    :gmir/kernel-load-u64 :gmir/kernel-store-u64})

(def kernel-window-maxima
  "Declared window ceilings. 65536 costs the same `cmp r64, imm32` that 512
  does, so the tier exists because the encoding is identical, not because a
  caller asked for it."
  #{512 4096 16384 65536})

(def slice-operations
  #{:gmir/slice-load-u8 :gmir/slice-store-u8
    :gmir/slice-load-u16 :gmir/slice-store-u16
    :gmir/slice-load-u32 :gmir/slice-store-u32
    :gmir/slice-load-u64 :gmir/slice-store-u64})

(def slice-item-limit
  "2^40 elements. Chosen so that `length * 8` -- the widest element this family
  carries -- stays under 2^43 and therefore cannot wrap a 64-bit address
  computation, while still admitting regions three orders of magnitude beyond
  the 10 GiB model image that motivated the carrier. It is an ADDRESS-SPACE
  bound, deliberately not derived from `vector-item-limit` (16384): ADR 0285's
  decision is that the carrier does not travel through the vector arena, and
  therefore that arena's item bound is not its ceiling."
  1099511627776)

;; simd: the f32 dot product's byte ceiling on each of its two regions.
;;
;; 65536 and only 65536, and pinned as a single value rather than drawn from
;; `kernel-window-maxima`, for the reason the lock pair's 4096 is pinned: one
;; spelling of the operation names one ceiling, and a second has to be added
;; deliberately. It is the largest tier the window family already declares, so
;; it costs the same `cmp r64, imm32` every other ceiling does.
;;
;; It is NOT the slice family's address-space bound. A region this operation
;; reads travels through the same declared-window discipline every
;; `kernel-load-*` uses; the carrier that is bounded by the address space
;; instead is `slice-*`, and giving this operation that bound would be
;; deciding ADR 0285's question here rather than there.
(def kernel-dot-f32-maximum 65536)

(def kernel-dot-f32-element-limit
  "How many four-byte elements the ceiling above admits. DERIVED, so the two
  cannot drift: a count above this makes `count * 4` exceed any admissible
  length, and bounding the count is also what keeps that product from wrapping
  a 64-bit multiply before it is compared."
  (quot kernel-dot-f32-maximum 4))
;; simd: end

;; sysops: the general atomic read-modify-write family, named once so the
;; keyset table, the operand check and the ceiling check cannot drift apart --
;; and so `kotoba.mir` and `kotoba.native.machine-ir` can derive their own
;; tables from this one rather than transcribing it.
(def kernel-atomic-ops
  #{:gmir/kernel-atomic-add-u32 :gmir/kernel-atomic-add-u64
    :gmir/kernel-xchg-u32 :gmir/kernel-xchg-u64
    :gmir/kernel-cmpxchg-u32 :gmir/kernel-cmpxchg-u64})
;; sysops: end

;; memwidth: every operation that carries a `:gmir/index` operand -- the two
;; windowed families, the slice family, the lock pair and (merged from the
;; sysops branch) the general atomics. Named once so the operand check cannot
;; drift from the keyset table.
(def ^:private indexed-memory-operations
  (into #{:gmir/kernel-try-lock-u32 :gmir/kernel-unlock-u32}
        (concat kernel-window-operations slice-operations kernel-atomic-ops)))

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
   :vector-drop 2
   ;; ABI v4 (superproject ADR-2609010200). Two operations the six above
   ;; cannot express.
   ;;
   ;; `:vector-alloc` allocates n zeros. `vector-new` is variadic -- its arity
   ;; IS the literal's element count -- so a struct of arrays with a million
   ;; slots would need a million arguments in source, and the literal limit
   ;; refuses that long before the item limit does.
   ;;
   ;; `:vector-assoc-in-place` is `:vector-assoc` lowered to a store: same
   ;; arity, same meaning, and it returns the same handle rather than a new
   ;; one. It is a SEPARATE runtime operation and not a flag on the first,
   ;; because the two call different host slots and a flag would have to be
   ;; carried through every layer between here and the encoder.
   ;;
   ;; Naming: KIR spells the source-level head `vector-assoc!`. The runtime
   ;; operation is spelled out because this table is the host contract, where
   ;; the distinction that matters is store-versus-copy rather than the claim
   ;; the bang makes. Neither family gains an f64 twin: KIR declares no
   ;; `vector-f64-alloc` and no `vector-f64-assoc!`.
   :vector-alloc 1
   :vector-assoc-in-place 3})

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
   :cpuid-eax 2 :cpuid-ebx 2 :cpuid-ecx 2 :cpuid-edx 2
   ;; simdprep: `xgetbv` reads XCR[ecx] into edx:eax, so one argument -- the
   ;; XCR index -- and one i64 result carrying both halves.
   ;;
   ;; It is here because the `cpuid` four cannot express a CPU feature check
   ;; on their own. Leaf 1 ECX bit 28 says the CPU implements AVX; XCR0 bits 1
   ;; and 2 say the OPERATING SYSTEM has agreed to save and restore the SSE
   ;; and YMM register state across a context switch. A kernel that reads only
   ;; the first and uses YMM anyway does not fault: it computes wrong answers
   ;; intermittently and only under load, because its vector registers are not
   ;; preserved.
   ;;
   ;; The ordering this cannot state -- `xgetbv` raises #UD unless
   ;; CR4.OSXSAVE is set, which is leaf 1 ECX bit 27, so 27 must be tested
   ;; BEFORE XCR0 is read -- is a property of a SEQUENCE of actions, and this
   ;; table describes one action at a time. kotoba-native
   ;; docs/avx2-guard-sequence.md carries it.
   :xgetbv 1
   ;; sysops: barriers, the timestamp counter and the GS-base swap.
   ;;
   ;; The three barriers are the ordering half of a device driver. A ring
   ;; descriptor written before a doorbell is only written before the doorbell
   ;; if something says so, and on x86 that something is `sfence`/`lfence`/
   ;; `mfence`. They are 0-arity and return 0, exactly like `:cli`.
   ;;
   ;; They ride the x86 privileged channel, which `kotoba.mir` admits for the
   ;; x86-64 target and no other, so they are x86-only by construction. That
   ;; is a decision, not an oversight: AArch64 has `dmb ishld`/`dmb ishst`/
   ;; `dmb ish`, but those are barriers under a WEAK memory model where the
   ;; question they answer is different from the one `lfence`/`sfence` answer
   ;; under x86-TSO. A portable barrier operator would have to name the
   ;; ordering it guarantees rather than the instruction it emits, and that is
   ;; a separate decision with its own operator family -- not a translation of
   ;; these three.
   ;;
   ;; `:rdtsc` and `:rdtscp` return the 64-bit timestamp counter. AArch64's
   ;; nearest reading, `mrs cntvct_el0`, is a DIFFERENT CLOCK -- a fixed-
   ;; frequency system counter, not a core cycle counter -- so it is not a
   ;; translation either.
   ;;
   ;; `:swapgs` exchanges GS.base with KERNEL_GS_BASE and returns 0. It is the
   ;; one instruction a ring-0 entry path cannot express any other way.
   :fence-load 0 :fence-store 0 :fence-full 0
   :rdtsc 0 :rdtscp 0
   :swapgs 0
   ;; sysops: end
   ;; boot: the UEFI firmware boundary. A BOOTX64.EFI written in Kotoba has to
   ;; reach three things this family could not name.
   ;;
   ;; `:system-table` is the second argument UEFI passes to an EFI image entry
   ;; point, read from the context slot the entry shim parks it in -- the twin
   ;; of `:boot-info`, which reads the slot beside it. 0-arity, and a pure
   ;; read of compiler-owned memory.
   ;;
   ;; `:load-ptr` is `(base, byte-offset) -> the 64-bit word there`. The
   ;; firmware owns EFI_SYSTEM_TABLE and every protocol structure hanging off
   ;; it, so a guest cannot declare a window over them the way the checked
   ;; `kernel-load-*` family requires: the length is the firmware's, not the
   ;; guest's, and asserting one would be inventing it. This reads the
   ;; boundary the way `:in-u8` reads a device -- unchecked, privileged, never
   ;; oracled -- and callers past that boundary use the checked family.
   ;;
   ;; `:uefi-call2` is `(base, slot-offset, a, b) -> status`: call the
   ;; Microsoft x64 function pointer at `[base+slot-offset]` with `a` and `b`
   ;; in RCX and RDX. FOUR operands rather than a variadic argument list,
   ;; because `kotoba.mir`'s conservative expansion hands privileged actions
   ;; the scratch tier and that tier is four registers wide on x86-64. Two
   ;; UEFI arguments is what a bootloader's first calls need (ConOut methods,
   ;; ExitBootServices); GetMemoryMap's five and OpenProtocol's six need an
   ;; argument channel that does not fit in registers at all, and that is a
   ;; separate decision.
   ;;
   ;; `:jump-to` is `(address, boot-info)` and does not return: it enters a
   ;; kernel with the SysV first argument set, which is the handoff
   ;; `package-embedded-kernel` hand-assembles today.
   :system-table 0
   :load-ptr 2
   :uefi-call2 4
   :jump-to 2
   ;; boot: end
   ;; isr: `(vector) -> the address of the toolchain-generated interrupt entry
   ;; for that vector`, which is what an IDT gate descriptor needs in its three
   ;; offset fields.
   ;;
   ;; ONE argument, and it is the vector NUMBER rather than a name. An entry is
   ;; named `aiueos-isr-<vector>` in the source, so the name and the vector
   ;; carry the same information; taking the number is what lets this ride the
   ;; ordinary privileged channel, whose operands are virtual registers by the
   ;; time any backend sees them. A name would have to survive as a literal
   ;; through GMIR, MIR and register allocation, and nothing in this IR carries
   ;; a symbol operand.
   ;;
   ;; The answer is a LOAD from the image's kernel context, not an address the
   ;; encoder knows: the entries are laid down by the ELF image packager, which
   ;; runs after every byte of the function has been emitted. That is also why
   ;; it has no answer at all in the relocatable-object route, where the
   ;; context is the object's own private 80 bytes -- kotoba-native refuses
   ;; there rather than reading past it.
   :isr-entry-address 1
   ;; isr: end
   })

(def capability-kinds
  "Closed native capability boundary. Zero is the scalar callback profile;
  positive values are the typed host ABI discriminator."
  {:i64 0 :string 1 :option-i64 2 :result-i64 3 :clock-v1 4 :dataspace-v1 5
   :ui-commit-v1 6 :ui-event-v1 7})

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
                                           ;; sysops: the compare-exchange
                                           ;; comparand is an operand like any
                                           ;; other and must be a vreg.
                                           :gmir/expected
                                           ;; simd: the second region's pair
                                           ;; and the element count are
                                           ;; operands like any other.
                                           :gmir/second-base :gmir/second-length
                                           :gmir/count
                                           :gmir/offset :gmir/size])]
        (when-not (vreg? register)
          (reject! :invalid-virtual-register instruction)))
      (when (and (contains? indexed-memory-operations op)
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
      ;; memwidth: one table for every windowed access instead of one clause
      ;; per width. The clause per width is how `kernel-store-u8` came to be
      ;; the only operation in the family that could not name a 16 KiB window
      ;; -- not by decision, but because its own `when` said so and nothing
      ;; else did.
      (when (contains? kernel-window-operations op)
        (when-not (contains? kernel-window-maxima (:gmir/maximum instruction))
          (reject! :invalid-kernel-memory-maximum instruction)))
      ;; memwidth: a slice's ceiling is the address space, not a window
      ;; profile. It is pinned to one value for the same reason the lock
      ;; pair's 4096 is: a second spelling has to be added deliberately.
      (when (contains? slice-operations op)
        (when-not (= slice-item-limit (:gmir/maximum instruction))
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
      ;; sysops: the general atomics share the lock pair's single ceiling for
      ;; the same reason -- one spelling each, naming a page. Pinned as one
      ;; value rather than a set so a second spelling has to be added
      ;; deliberately.
      (when (contains? kernel-atomic-ops op)
        (when-not (= 4096 (:gmir/maximum instruction))
          (reject! :invalid-kernel-memory-maximum instruction)))
      ;; sysops: end
      ;; simd: one spelling, one ceiling, pinned as a single value for the
      ;; reason the lock pair's is.
      (when (= :gmir/kernel-dot-f32 op)
        (when-not (= kernel-dot-f32-maximum (:gmir/maximum instruction))
          (reject! :invalid-kernel-memory-maximum instruction)))
      ;; simd: end
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
