# :testing

Shared test fixtures, property generators, and sample data for all modules. `Fixtures` provides factory functions for `GovernanceState`, `ProposedAction`, `HistoryEntry`, and `ReferenceEnvelope` with sensible defaults. `PropertyGenerators` provides kotest `Arb` generators for property-based testing of all governance types (gamma values, reversibility tiers, outcomes, states, history sequences). Depends only on `:core`.
