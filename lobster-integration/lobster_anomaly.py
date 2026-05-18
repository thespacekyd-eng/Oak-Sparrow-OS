"""
Campaign-level threat detection: Xenarch Mk14 anomaly scoring on Lobster Trap DPI streams.

Treats each Lobster Trap DPI event as a feature vector — analogous to a pixel row in an
image chip — and applies the Xenarch-derived sliding-window scorer to detect multi-step
attack campaigns that individual per-prompt inspection misses.

Feature mapping (Xenarch Mk14 → this module):
  image chip (256×256 px)  →  EventWindow (N consecutive DPI events)
  pixel row features       →  LobsterFeature (risk_score, intent_drift, pii, injection,
                               exfil_signal, time_delta)
  mse_score                →  MSE vs reference mean embedding
  density_score            →  Mahalanobis-approx L2 distance from reference distribution
  contextual_score         →  burst detector (risk spikes, PII/injection/exfil rates)
  gradient_score           →  rate-of-change of risk and intent drift over window
  edge_regularity_score    →  pattern_regularity (low variance → scripted replay attack)
  normalize_scores         →  min-max normalization, same 50/30/20 confidence weighting
  run_analysis             →  CampaignDetector.analyze()

No external dependencies — pure Python stdlib.
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass, field
from typing import List, Optional, Dict


# ── DPI event schema ──────────────────────────────────────────────────────────

@dataclass
class LobsterEvent:
    """
    Structured metadata from a single Lobster Trap DPI inspection.

    Populated either from the real _lobstertrap JSON field in a proxied
    response, or from the simulate_dpi() fallback for development.
    """
    timestamp: float
    risk_score: float                 # [0, 1]
    intent_category: str              # e.g. "file_read", "data_access", "admin"
    declared_intent: str              # from _lobstertrap.intent in the request
    detected_intent: str              # Lobster Trap's inferred intent
    pii_detected: bool
    injection_detected: bool
    exfiltration_patterns: List[str]  # e.g. ["base64_encoding", "outbound_request"]
    target_paths: List[str]
    domains: List[str]
    risky_commands: List[str]
    credentials_detected: bool
    policy_action: str                # ALLOW | LOG | HUMAN_REVIEW | QUARANTINE | DENY
    session_id: str = ""
    prompt_excerpt: str = ""          # first 120 chars, for audit explainability


# ── Feature encoding ──────────────────────────────────────────────────────────

class LobsterFeature:
    """
    Encodes a LobsterEvent as a 6-dimensional normalized feature vector.

    Direct analog of BehaviorFeature.kt, but sourced from Lobster Trap DPI
    output instead of governance kernel HistoryEntry records.

    Dimensions (all in [0, 1]):
      0  risk_score_norm    — Lobster Trap composite risk score
      1  intent_drift       — semantic distance between declared and detected intent
      2  pii_detected       — binary
      3  injection_detected — binary
      4  exfil_signal       — 1.0 if any exfiltration pattern or credential detected
      5  time_delta_norm    — inter-prompt interval, normalized to MAX_INTERVAL_S
    """
    DIM = 6
    MAX_INTERVAL_S = 300.0  # 5-minute cap — beyond this, sessions are unrelated

    # Intent groups for drift scoring
    _GROUPS: Dict[str, set] = {
        "read":      {"file_read", "data_query", "search", "list", "info", "summarize"},
        "write":     {"file_write", "data_modify", "create", "update", "delete"},
        "execute":   {"command_exec", "code_run", "shell", "admin", "system"},
        "network":   {"api_call", "network", "download", "upload", "http"},
        "sensitive": {"credential_access", "auth", "exfiltration", "injection_attempt",
                      "data_exfil", "privilege_escalation"},
    }

    @classmethod
    def _group(cls, intent: str) -> str:
        low = intent.lower()
        for group, keywords in cls._GROUPS.items():
            if any(kw in low for kw in keywords):
                return group
        return "unknown"

    @classmethod
    def intent_drift(cls, declared: str, detected: str) -> float:
        """
        Semantic distance between declared and detected intent.
        0.0 = same category, 0.5 = unrelated categories, 1.0 = sensitive escalation.
        """
        if not declared or not detected or declared.lower() == detected.lower():
            return 0.0
        g1, g2 = cls._group(declared), cls._group(detected)
        if g1 == g2:
            return 0.1
        # Any movement into sensitive/execute from a benign declared intent is high risk
        if g2 in {"sensitive", "execute"} and g1 in {"read", "unknown"}:
            return 1.0
        if g2 == "sensitive":
            return 0.9
        return 0.5

    @classmethod
    def encode(cls, event: LobsterEvent, prev_timestamp: Optional[float]) -> List[float]:
        time_delta = 0.0
        if prev_timestamp is not None:
            delta_s = max(0.0, event.timestamp - prev_timestamp)
            time_delta = min(delta_s / cls.MAX_INTERVAL_S, 1.0)

        exfil = 1.0 if (event.exfiltration_patterns or event.credentials_detected) else 0.0

        return [
            min(max(event.risk_score, 0.0), 1.0),
            cls.intent_drift(event.declared_intent, event.detected_intent),
            1.0 if event.pii_detected else 0.0,
            1.0 if event.injection_detected else 0.0,
            exfil,
            time_delta,
        ]


# ── Sliding window extraction ─────────────────────────────────────────────────

@dataclass
class EventWindow:
    """
    A sliding window of DPI events with flattened feature embedding.
    Analog of BehaviorWindow.kt / Xenarch image chip.
    """
    window_index: int
    start_index: int
    events: List[LobsterEvent]
    embedding: List[float]  # len = window_size * LobsterFeature.DIM

    @property
    def size(self) -> int:
        return len(self.events)


def extract_windows(
    events: List[LobsterEvent],
    window_size: int,
    stride: int,
) -> List[EventWindow]:
    """Xenarch extract_chips() analog — sliding windows over the DPI event stream."""
    if len(events) < window_size:
        return []
    windows: List[EventWindow] = []
    idx, win_idx = 0, 0
    while idx + window_size <= len(events):
        slice_ = events[idx: idx + window_size]
        embedding: List[float] = []
        for i, ev in enumerate(slice_):
            prev_ts = slice_[i - 1].timestamp if i > 0 else None
            embedding.extend(LobsterFeature.encode(ev, prev_ts))
        windows.append(EventWindow(
            window_index=win_idx,
            start_index=idx,
            events=slice_,
            embedding=embedding,
        ))
        idx += stride
        win_idx += 1
    return windows


# ── Per-window scores ─────────────────────────────────────────────────────────

@dataclass
class WindowScore:
    window_index: int
    mse: float
    density: float
    contextual: float
    gradient: float
    pattern_regularity: float


@dataclass
class ScoredWindow:
    window_index: int
    combined: float
    confidence: float
    mse_norm: float
    density_norm: float
    contextual_norm: float
    gradient_norm: float
    pattern_norm: float
    # Explainability fields populated by CampaignDetector
    primary_signals: List[str] = field(default_factory=list)


# ── Campaign report ───────────────────────────────────────────────────────────

@dataclass
class CampaignReport:
    total_windows: int
    anomaly_count: int
    high_confidence_count: int
    top_confidence: float
    anomaly_threshold: float
    scored_windows: List[ScoredWindow]
    is_anomalous: bool
    primary_signals: List[str] = field(default_factory=list)

    @classmethod
    def empty(cls) -> "CampaignReport":
        return cls(0, 0, 0, 0.0, 0.0, [], False)


# ── Xenarch-derived scorer ────────────────────────────────────────────────────

class XenarchDPIScorer:
    """
    Xenarch Mk14-derived anomaly scorer adapted for Lobster Trap DPI streams.

    Trained on reference_windows (observed normal behavior), then scores
    arbitrary windows by deviation from the reference distribution.

    Five metrics — direct Python port of BehaviorAnomalyScorer.kt with
    feature dimensions remapped to DPI metadata:

      mse              MSE between window embedding and reference mean
      density          Mahalanobis-approximation L2 distance from reference
      contextual       Burst detector: risk spikes, PII/injection/exfil rates
                       (analog of Xenarch's bright-region detection)
      gradient         Rate-of-change of risk and intent drift over the window
                       (analog of Xenarch's gradient_score)
      pattern_regularity  Low variance → scripted/replay attack
                       (analog of Xenarch's edge_regularity_score)
    """

    def __init__(self, reference_windows: List[EventWindow]) -> None:
        if not reference_windows:
            self._ref_mean: List[float] = []
            self._ref_std: List[float] = []
            return

        dim = len(reference_windows[0].embedding)
        mean = [0.0] * dim
        for w in reference_windows:
            for i, v in enumerate(w.embedding):
                mean[i] += v
        mean = [m / len(reference_windows) for m in mean]

        var = [0.0] * dim
        for w in reference_windows:
            for i, v in enumerate(w.embedding):
                var[i] += (v - mean[i]) ** 2
        std = [math.sqrt(v / len(reference_windows)) + 1e-8 for v in var]

        self._ref_mean = mean
        self._ref_std = std

    # ── Five metric functions ─────────────────────────────────────────────────

    def mse_score(self, w: EventWindow) -> float:
        """Xenarch mse_score: reconstruction error vs reference mean embedding."""
        n = max(len(w.embedding), 1)
        if not self._ref_mean:
            return sum(v * v for v in w.embedding) / n
        return sum((a - b) ** 2 for a, b in zip(w.embedding, self._ref_mean)) / n

    def density_score(self, w: EventWindow) -> float:
        """Xenarch density_score: Mahalanobis-approx distance from reference."""
        if not self._ref_mean:
            return 0.0
        dist = math.sqrt(sum(
            ((v - m) / s) ** 2
            for v, m, s in zip(w.embedding, self._ref_mean, self._ref_std)
        ))
        return dist / max(len(w.embedding), 1)

    def contextual_score(self, w: EventWindow) -> float:
        """
        Xenarch contextual_score: burst detection.

        Measures elevated rates of high-risk prompts, PII, injection, and
        exfiltration signals — analogous to Xenarch's bright-pixel cluster analysis.
        """
        if not w.events:
            return 0.0
        n = len(w.events)

        high_risk_rate = sum(1 for e in w.events if e.risk_score > 0.6) / n
        pii_rate       = sum(1 for e in w.events if e.pii_detected) / n
        injection_rate = sum(1 for e in w.events if e.injection_detected) / n
        exfil_rate     = sum(1 for e in w.events
                             if e.exfiltration_patterns or e.credentials_detected) / n

        # Risk score variance spike — analogous to texture anomaly
        risks = [e.risk_score for e in w.events]
        mean_r = sum(risks) / n
        std_r  = math.sqrt(sum((r - mean_r) ** 2 for r in risks) / n)
        risk_spike = min(std_r / (mean_r + 1e-8), 1.0)

        return min(
            0.30 * high_risk_rate +
            0.20 * pii_rate       +
            0.20 * injection_rate +
            0.20 * exfil_rate     +
            0.10 * risk_spike,
            1.0,
        )

    def gradient_score(self, w: EventWindow) -> float:
        """
        Xenarch gradient_score: rapid risk escalation or intent drift.

        Measures the mean absolute deviation of first-differences in risk_score
        and intent_drift — sudden shifts signal attack mode switches.
        """
        if len(w.events) < 2:
            return 0.0

        def _series_grad(values: List[float]) -> float:
            diffs = [abs(b - a) for a, b in zip(values, values[1:])]
            if not diffs:
                return 0.0
            mean_d = sum(diffs) / len(diffs)
            return sum(abs(d - mean_d) for d in diffs) / len(diffs)

        risk_grad  = _series_grad([e.risk_score for e in w.events])
        drift_grad = _series_grad([
            LobsterFeature.intent_drift(e.declared_intent, e.detected_intent)
            for e in w.events
        ])
        return min((risk_grad + drift_grad) / 2.0, 1.0)

    def pattern_regularity_score(self, w: EventWindow) -> float:
        """
        Xenarch edge_regularity_score: suspiciously low variance.

        A scripted or replay-style campaign shows unnaturally consistent risk
        scores and intent drift — low variance is itself the anomaly signal.
        """
        if len(w.events) < 3:
            return 0.0

        def _std(values: List[float]) -> float:
            mean = sum(values) / len(values)
            return math.sqrt(sum((v - mean) ** 2 for v in values) / len(values))

        risk_std  = _std([e.risk_score for e in w.events])
        drift_std = _std([
            LobsterFeature.intent_drift(e.declared_intent, e.detected_intent)
            for e in w.events
        ])
        return min(1.0 - (risk_std + drift_std) / 2.0, 1.0)

    def score(self, w: EventWindow) -> WindowScore:
        return WindowScore(
            window_index=w.window_index,
            mse=self.mse_score(w),
            density=self.density_score(w),
            contextual=self.contextual_score(w),
            gradient=self.gradient_score(w),
            pattern_regularity=self.pattern_regularity_score(w),
        )

    def normalize_and_rank(self, raw: List[WindowScore]) -> List[ScoredWindow]:
        """
        Xenarch normalize_scores + compute_confidence — direct Python port.

        Combined weights (matching BehaviorAnomalyScorer.kt):
          MSE 30% · density 20% · contextual 30% · gradient 15% · pattern 5%

        Confidence (matching Xenarch):
          50% normalized combined + 30% contextual norm + 20% MSE norm
        """
        if not raw:
            return []

        def _norm(values: List[float]) -> List[float]:
            lo, hi = min(values), max(values)
            r = hi - lo + 1e-8
            return [(v - lo) / r for v in values]

        mse_n  = _norm([s.mse for s in raw])
        den_n  = _norm([s.density for s in raw])
        ctx_n  = _norm([s.contextual for s in raw])
        grad_n = _norm([s.gradient for s in raw])
        pat_n  = _norm([s.pattern_regularity for s in raw])

        combined = [
            0.30 * mse_n[i] + 0.20 * den_n[i] + 0.30 * ctx_n[i] +
            0.15 * grad_n[i] + 0.05 * pat_n[i]
            for i in range(len(raw))
        ]
        comb_n = _norm(combined)

        confidence = [
            min(0.50 * comb_n[i] + 0.30 * ctx_n[i] + 0.20 * mse_n[i], 1.0)
            for i in range(len(raw))
        ]

        return [
            ScoredWindow(
                window_index=raw[i].window_index,
                combined=combined[i],
                confidence=confidence[i],
                mse_norm=mse_n[i],
                density_norm=den_n[i],
                contextual_norm=ctx_n[i],
                gradient_norm=grad_n[i],
                pattern_norm=pat_n[i],
            )
            for i in range(len(raw))
        ]


# ── Campaign detector (top-level entry point) ─────────────────────────────────

class CampaignDetector:
    """
    Applies Xenarch-derived sliding window anomaly scoring to a Lobster Trap
    DPI event stream to detect multi-step attack campaigns.

    Lobster Trap catches what's wrong in a single prompt.
    CampaignDetector catches what's wrong across a sequence of prompts.

    Usage:
        detector = CampaignDetector()
        for event in lobster_event_stream:
            detector.add(event)
            report = detector.analyze()
            if report.is_anomalous:
                print(report.primary_signals)
    """

    def __init__(
        self,
        window_size: int = 8,
        stride: Optional[int] = None,
        anomaly_percentile: float = 90.0,
        reference_ratio: float = 0.4,
        high_confidence_threshold: float = 0.75,
        max_events: int = 10_000,
    ) -> None:
        if window_size < 1:
            raise ValueError(f"window_size must be >= 1, got {window_size}")
        resolved_stride = stride if stride is not None else max(1, window_size // 2)
        if resolved_stride < 1:
            raise ValueError(f"stride must be >= 1, got {resolved_stride}")
        if not (0.0 < anomaly_percentile <= 100.0):
            raise ValueError(f"anomaly_percentile must be in (0, 100], got {anomaly_percentile}")
        if not (0.0 < reference_ratio < 1.0):
            raise ValueError(f"reference_ratio must be in (0, 1), got {reference_ratio}")
        if max_events < 1:
            raise ValueError(f"max_events must be >= 1, got {max_events}")
        self.window_size = window_size
        self.stride = resolved_stride
        self.anomaly_percentile = anomaly_percentile
        self.reference_ratio = reference_ratio
        self.high_confidence_threshold = high_confidence_threshold
        self.max_events = max_events
        self._events: List[LobsterEvent] = []

    def add(self, event: LobsterEvent) -> None:
        """Append a new DPI event to the rolling stream, capping at max_events."""
        self._events.append(event)
        if len(self._events) > self.max_events:
            self._events = self._events[-self.max_events :]

    def analyze(self, events: Optional[List[LobsterEvent]] = None) -> CampaignReport:
        """
        Score the current event stream and return a CampaignReport.

        Pass events explicitly, or omit to use the stream built via add().
        """
        stream = events if events is not None else self._events
        if len(stream) < self.window_size:
            return CampaignReport.empty()

        windows = extract_windows(stream, self.window_size, self.stride)
        if not windows:
            return CampaignReport.empty()

        ref_count   = max(1, int(len(windows) * self.reference_ratio))
        ref_windows = windows[:ref_count]

        scorer    = XenarchDPIScorer(ref_windows)
        raw       = [scorer.score(w) for w in windows]
        scored    = scorer.normalize_and_rank(raw)

        sorted_combined = sorted(s.combined for s in scored)
        thresh_idx  = min(
            int(self.anomaly_percentile / 100.0 * len(sorted_combined)),
            len(sorted_combined) - 1,
        )
        threshold   = sorted_combined[thresh_idx]

        ranked      = sorted(scored, key=lambda s: s.confidence, reverse=True)
        anomalies   = sum(1 for s in scored if s.combined >= threshold)
        high_conf   = sum(1 for s in scored if s.confidence >= self.high_confidence_threshold)
        top_conf    = ranked[0].confidence if ranked else 0.0

        # Build explainability signals from the top-ranked window
        signals: List[str] = []
        if ranked:
            top   = ranked[0]
            top_w = windows[top.window_index]

            if top.contextual_norm > 0.65:
                hr = [e for e in top_w.events if e.risk_score > 0.6]
                if hr:
                    signals.append(f"risk burst: {len(hr)}/{len(top_w.events)} high-risk prompts")
                exfil = [p for e in top_w.events for p in e.exfiltration_patterns]
                if exfil:
                    signals.append(f"exfiltration patterns: {list(set(exfil))}")
                inj = [e for e in top_w.events if e.injection_detected]
                if inj:
                    signals.append(f"injection detected in {len(inj)} prompt(s)")

            if top.gradient_norm > 0.65:
                signals.append("rapid risk escalation across window")

            if top.mse_norm > 0.75:
                signals.append("embedding diverged from reference distribution")

            drift_evs = [
                e for e in top_w.events
                if LobsterFeature.intent_drift(e.declared_intent, e.detected_intent) > 0.5
            ]
            if drift_evs:
                pairs = {f"{e.declared_intent}→{e.detected_intent}" for e in drift_evs}
                signals.append(f"declared≠detected intent: {pairs}")

            top.primary_signals = signals

        return CampaignReport(
            total_windows=len(windows),
            anomaly_count=anomalies,
            high_confidence_count=high_conf,
            top_confidence=top_conf,
            anomaly_threshold=threshold,
            scored_windows=ranked,
            is_anomalous=anomalies > 0 and top_conf >= self.high_confidence_threshold,
            primary_signals=signals,
        )


# ── DPI simulation (development mode without Lobster Trap) ───────────────────

def simulate_dpi(
    prompt: str,
    response: str,
    declared_intent: str = "",
    session_id: str = "",
) -> LobsterEvent:
    """
    Simulate Lobster Trap DPI metadata from prompt/response content.

    Used when LOBSTER_TRAP_URL is not set. Provides realistic metadata so
    CampaignDetector behaves identically with or without the proxy running.
    """
    combined = (prompt + " " + response).lower()

    pii = any(kw in combined for kw in [
        "email", "@", "phone", "ssn", "social security", "date of birth",
        "address", "credit card", "passport",
    ])
    injection = any(kw in combined for kw in [
        "ignore previous", "disregard", "forget your instructions", "jailbreak",
        "pretend you are", "you are now", "bypass", "{{", "}}",
        "system prompt", "override", "new persona",
    ])
    creds = any(kw in combined for kw in [
        "password", "api_key", "apikey", "secret", "credential", "token",
        "private_key", "private key", ".env", "bearer", "authorization",
        "access_key", "secret_key", "connection string",
    ])

    exfil_patterns: List[str] = []
    if any(kw in combined for kw in ["base64", "b64encode", "encode"]):
        exfil_patterns.append("base64_encoding")
    if any(kw in combined for kw in ["curl", "wget", "http://", "https://", "external"]):
        exfil_patterns.append("outbound_request")
    if creds and any(kw in combined for kw in ["send", "post", "upload", "forward", "exfil"]):
        exfil_patterns.append("credential_exfiltration")

    risky_cmds = [c for c in ["curl", "wget", "bash", "sh", "python", "nc", "netcat",
                               "ssh", "scp", "ftp"] if c in combined]

    target_paths = [p for p in ["/etc/passwd", "/etc/shadow", ".env", "id_rsa",
                                 "credentials", "secrets"] if p in combined]

    domains = [d for d in ["external.com", "attacker.com", "evil.com", "pastebin.com"]
               if d in combined]

    # Infer detected intent from content
    if injection:
        detected = "injection_attempt"
    elif creds and exfil_patterns:
        detected = "data_exfil"
    elif creds:
        detected = "credential_access"
    elif any(kw in combined for kw in ["database", "schema", "query", "select", "table"]):
        detected = "data_access"
    elif risky_cmds:
        detected = "command_exec"
    elif any(kw in combined for kw in ["curl", "wget", "http", "api"]):
        detected = "network"
    else:
        detected = declared_intent or "file_read"

    # Composite risk score
    risk = 0.05
    if pii:       risk += 0.10
    if injection: risk += 0.40
    if creds:     risk += 0.25
    risk += len(exfil_patterns) * 0.15
    risk += len(risky_cmds) * 0.04
    risk += len(target_paths) * 0.05
    risk = min(risk, 1.0)

    if injection or risk > 0.85:
        action = "DENY"
    elif creds or risk > 0.65:
        action = "HUMAN_REVIEW"
    elif exfil_patterns or risk > 0.40:
        action = "LOG"
    else:
        action = "ALLOW"

    return LobsterEvent(
        timestamp=time.time(),
        risk_score=round(risk, 3),
        intent_category=detected,
        declared_intent=declared_intent,
        detected_intent=detected,
        pii_detected=pii,
        injection_detected=injection,
        exfiltration_patterns=exfil_patterns,
        target_paths=target_paths,
        domains=domains,
        risky_commands=risky_cmds,
        credentials_detected=creds,
        policy_action=action,
        session_id=session_id,
        prompt_excerpt=prompt[:120],
    )
