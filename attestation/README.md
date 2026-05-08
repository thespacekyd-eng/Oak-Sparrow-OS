# :attestation

Ed25519 decision signing and verification for tamper-evident governance decisions. Provides `EphemeralKeyProvider` (in-memory Ed25519 keypair), `DecisionSigner` (produces signed `GateDecision` instances with content-addressed audit IDs), `AttestationVerifier` (verifies that a decision's attestation is authentic and untampered), and `CanonicalJson` (deterministic JSON serialization with sorted keys for replay-deterministic hashing).
