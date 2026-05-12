# :gate

Composite decision logic combining soft-state metrics, reversibility weighting, and hard barriers. `CompositeGate` evaluates the gate rules (hard barrier VETO, Irreversible HOLD, effective-γ thresholds, entropy/divergence checks, HOLD stickiness) and produces a `GateResult`. `DefaultGovernanceKernel` implements the `GovernanceKernel` interface by composing metrics, the gate, attestation signing, calibration, and audit writing into the three top-level operations: `decide`, `resolve`, and `snapshot`.
