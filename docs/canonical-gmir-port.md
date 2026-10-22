# Canonical GMIR source port in progress

Owning reference: `1ae0811ec3591bf5a3e3d7bee069df928106feca`.
The legacy root has 28 public exports. Its capability-kind table is still
0..7; byte/chunk proposal metadata elsewhere does not admit new kinds here.

The whole GMIR contract remains the acceptance scope: all instruction and
runtime/action tables, scalar/window/slice/atomic/dequant ceilings, vreg and
function identities, literal decoding, canonical keysets, v1/v2/v3 admission,
labels, phi predecessor/critical-edge/fallthrough rules, full module linkage
and named refusal data. No subset validator or compatibility projection is
an accepted replacement.

## Owning literal boundary

`src/kotoba/gmir/rodata.kotoba` is a canonical source candidate for the
three encodings, alignment, byte decoding and content predicate. Public data
values become typed zero-argument accessors. The owning root still uses its
reference implementation; this component has not replaced it or its callers.

A source review found that the first draft's generic document-vector result
could not represent literals beyond the unchanged **32-item document
container limit**. Wrapping a maximum-size native string in a serialized
document also adds overhead. That draft did not preserve the full literal
boundary. The revised candidate uses nominal inputs and typed byte chunks;
it does not widen document/value/ABI limits or narrow the parent grammar.

- `string-input` uses the native string domain (at most 65,536 UTF-8 bytes
  per value); `chunks-input` carries longer logical UTF-8 text with
  `[:list :bytes]`, including units split across chunks and empty chunks.
- `keyword-input` and `invalid-input` retain the distinction between the
  admitted encoding kind and every other kind. The parent adapter must
  retain the original AST/value for its full diagnostics.
- `rodata-bytes` returns `[:option [:ref :gmir.rodata/decoded]]`. `None`
  means invalid content/encoding. Empty hex is `Some` with zero bytes and
  no payload chunks. No allocation, uniqueness or value failure is caught
  and converted into success or empty data.
- `decoded-chunks`, `decoded-byte-count` and checked `decoded-byte-at`
  expose all bytes. Private output pieces are at most 16 KiB; this is an
  allocation strategy, not a logical literal limit. Mutable local vectors
  are copied with `bytes-from-vector-i64` before crossing helper boundaries;
  normal compiler uniqueness admission is still required.

Hex retains exact order, zero bytes, either letter case and empty success;
odd lengths and non-hex characters refuse. GUID accepts exactly the
8-4-4-4-12 shape, reverses the first 4/2/2-byte fields and preserves the final
eight bytes. UCS-2 preserves admitted BMP scalars, embedded zero and one
final two-byte terminator. Astral scalars, surrogates and malformed UTF-8
refuse. Byte reads are bounded against their own chunks. There is no host
string/Java/JS conversion, regex, filesystem/process access, replacement
character, truncation or successful empty fallback.

The revised canonical self-check source retains all 32 original small
cases, with fixed oracle documents of at most 32 elements, and adds 18
chunk/UTF-8 cases. `main` also checks seven complete output patterns and
three refusals after a long prefix: outputs above 32 bytes, 65,536-byte
native strings, 65,537-byte and 1 MiB logical input, UTF-16 expansion and
piece boundaries. Pattern comparison walks forward through every byte,
including the final terminator. A separate `large-internal-case` constructs
and consumes an 8 MiB decoded hex result and returns only i64.

Long values are constructed and consumed internally. These cases do not
admit a value above the canonical boundary traversal budget across a public
export/capability ABI. Per-leaf/list bounds and native arena/fuel/stack
budgets still apply. Cursor records allocate while decoding, so successful
large-input execution and its resource cost remain unqualified. No decoder
or self-check has been typechecked, compiled or run. Source-data inspection
is not evidence of semantic parity or native resource admission.

The original source and four suites are unchanged. Their parent validator,
keyset, firmware arity and whole-program checks remain required. Root/caller
adaptation and resulting Definition CIDs are open; matching literal bytes
do not imply unchanged definition identity.

## Remaining whole-component work

The root canonical GMIR module must wire these four literal exports and
implement its remaining 24 exports and validators. In addition,
all native consumers of data accessors/results, proposed capability kinds
8..11 and matching signature/type/value/lowering/ABI are still required.
An existing language-side mechanical `.kotoba` projection contains an empty
`i64-value?` body and host forms. It is migration inventory, not an owning
canonical implementation or native proof. Do not fill it with stubs or use
it to bypass owning checks.

The final build/packaging/execution closure must use Amu selfhost-built and
rebuilt tools, without CLJK/CLJC/CLJS, NBB/KBB, Node, Clojure/Java/JVM/Graal,
or C compiler/loader/runtime fallbacks. No qualified independent native
compiler entry is currently located. Native EDN-reader/target predicates,
full positive/refusal/resource/authority corpus, actual execution, owning
pins/CIDs and full self-rebuild remain open. No workflow, protection, live
service or route changes are part of this source work.

This supporting-library port does not replace the adopted Amu compiler
core/lineage or alter its Embench baseline. The measured compiler's complete
frontend, lowering, optimizer and backend behavior must be preserved when
the whole closure is integrated. No benchmark or compiler-core change is
claimed here.
