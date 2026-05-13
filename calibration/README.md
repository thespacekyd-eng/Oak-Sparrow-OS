# :calibration

Recursive trust calibration with a defensive prior and warmup phase. `DefensivePriorCalibrator` implements `Calibrator` with two modes: warmup (large update magnitudes for rapid convergence, default first 100 decisions) and steady (small bounded updates for stability). A single benign success moves γ by at most a configured small constant; a single flagged outcome moves γ by at least the configured response magnitude. The reference envelope center slowly shifts toward the observed outcome distribution.
