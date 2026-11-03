# Scoring reproducibility

Runs store `as_of`, the 365-day window, algorithm version, weight/threshold snapshot, minimum observations, exposure scale, severity weights, and rounding description. Completed runs are immutable through database triggers. Historical source selection must require both event time and recorded/created time to be no later than the run cutoff.

Disruption uses a pure calculator over signals known by the cutoff and emits metrics and evidence from one result. It applies half-open window bounds, fractional active-day duration, snapshotted severity weights, and the 90-day half-life measured from `starts_at`. Exact non-null `(source, source_reference)` duplicates are resolved deterministically; distinct references are not merged based only on overlapping dates. Same-cutoff repeated-run equivalence still needs isolated PostgreSQL verification.

The Phase 4 clone has two completed runs (`SR-cd421e4e4ea548718978`, `SR-6e3afa1ab30c41dfa18a`), but they used different cutoffs. Same-cutoff repeat comparison, canonical score/evidence checksums, and late-record cutoff tests have not been run. Reproducibility is therefore pending. Use a new run with exactly the same cutoff/config on the isolated Phase 4 database; never regenerate or modify the Phase 2/3 datasets.
# Compliance history boundary

Compliance policy and scope selection uses the latest recorded history at or before the score run's persisted `as_of`. Assessment status and evidence events after the cutoff are ignored. V8 captures the actual transaction timestamp in its first migration transaction and starts this history prospectively at that cutover; assessment/check history and evidence events are recorded prospectively, with no fabricated legacy status or AVAILABLE events. Consequently, legacy evidence is unavailable to the new historical evaluator until it is recorded prospectively. Completed score runs remain immutable.
