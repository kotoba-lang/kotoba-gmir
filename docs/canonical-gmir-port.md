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

`src/kotoba/gmir/rodata.kotoba` contains the full literal boundary in canonical
source: the three encodings, alignment, bytes and content predicate. Public
data values become typed zero-argument accessors in this component. Generic
input/output values are documents, preserving unknown encoding/non-string
refusals and the difference between an empty vector and null.
Root/caller adaptation and the resulting Definition CIDs are still open;
unchanged literal bytes do not imply unchanged definition identity.

- Hex retains exact order, zero bytes, either letter case and empty success;
  odd lengths and non-hex characters return null.
- GUID accepts only the canonical 8-4-4-4-12 shape, decodes the first 4/2/2
  fields little-endian and preserves the last eight bytes in text order.
- UCS-2 preserves every admitted BMP scalar, embedded zero and the final
  two-byte terminator. Astral scalars are refused rather than encoded as
  surrogate pairs, matching the original UTF-16 code-unit walk. Byte reads
  are bounded; malformed byte sequences also refuse.

There is no host string/Java/JS conversion, regex, filesystem/process access,
new input cap, truncation, replacement character or successful empty fallback.
No parent whitelist or smaller semantic domain is substituted.

The canonical decoder self-check has 32 explicit cases, including every
decoder input in the legacy rodata suite plus Unicode/NUL boundaries. It
checks the complete returned byte vector and the content predicate, as well
as encoding/alignment values. It has not been typechecked, compiled or run.
The original suite is unchanged and still owns the parent validator/keyset,
firmware-call arity and whole-program checks; those are not covered by this
decoder self-check or declared complete.

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
