# Coverage and missing data

`coverage = sum(weights for AVAILABLE components) / total configured weights × 100`. Overall score is the weighted sum divided by eligible weight sum. Below 70% coverage, the overall score is null and classification is `INSUFFICIENT_DATA`.

Every component has a status: `AVAILABLE`, `INSUFFICIENT_OBSERVATIONS`, `MISSING_DATA`, `NOT_APPLICABLE`, or `EXCLUDED_INVALID_DATA`. Only `AVAILABLE` contributes weight. A genuinely observed zero-risk-signal population can score 100; inability to read a risk source must not.

Coverage measures available configured component weight. It is not statistical confidence. Observation counts are evidence volume only; no confidence interval is claimed.

Default minimums currently configured: 5 due delivery lines; 3 inspections and 100 inspected units; 5 comparable cost lines; 3 capacity commitments; 5 applicable completed compliance checks. These are architectural defaults and can be overridden with `platform.scoring.*` properties. The minimum rules remain subject to the implementation gaps documented in `scoring-methodology.md`.
# Compliance coverage

Compliance coverage is eligible completed checks divided by applicable requirement scopes. It is distinct from the 10% overall component weight. Missing assessment history, status history, check data, evidence-at-cutoff, and expired assessments do not count as passes. A component below its configured minimum is INSUFFICIENT_OBSERVATIONS; zero eligible observations with missing applicable records is MISSING_DATA. The V8 prospective baseline intentionally does not claim historical site/status/evidence availability before cutover.
