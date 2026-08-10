# kotoba-gmir

Kotoba generic machine IR: the target-independent virtual-register contract
between checked KIR lowering and target instruction selection.

**Tier**: `T0`  **Role**: `contract`

## Owns

- the closed GMIR v1 abstract data model;
- virtual-register and label identity rules;
- structural validation for arguments, constants, integer arithmetic and
  comparisons, branches, and returns;
- deterministic EDN-shaped in-memory/reference data.

## Does not own

- source parsing or semantic analysis;
- KIR-to-GMIR lowering policy;
- target instruction selection or physical registers;
- instruction encoding, machine bytes, or object formats.

GMIR is an abstract data contract. EDN is its reference notation; printed EDN
and JSON are not identity encodings.

## Test

```bash
clojure -M:test
```
