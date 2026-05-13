# :metrics

Default implementations of the three soft-state metric interfaces. `DefaultDilationFactor` computes γ as a moving-window weighted ratio of flagged outcomes. `DefaultPredictiveEntropy` estimates Shannon entropy over the recent action-kind distribution. `DefaultTrajectoryDivergence` measures Euclidean distance between the observed outcome distribution and the reference envelope center. `DefaultNoiseSignalDecomposer` splits telemetry by an allowlist of known signal keys. All defaults are placeholders — replace with framework formalization before deployment.
