package com.yottaflex.reliability.scoring.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Pure PO-line delivery calculation shared by score metrics and their evidence. */
@Component
public final class DeliveryScoringCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<String> SUPPORTED_STATUSES = List.of(
            "OPEN", "PARTIALLY_FULFILLED", "FULFILLED", "CLOSED", "CANCELLED", "DRAFT");

    public Result calculate(List<OrderLine> lines, LocalDate windowStart, LocalDateTime asOf, int minimumObservations) {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(asOf, "asOf");
        if (minimumObservations < 0) throw new IllegalArgumentException("minimumObservations cannot be negative");

        LocalDate asOfDate = asOf.toLocalDate();
        long eligible = 0, cancelled = 0, notYetDue = 0, outsideWindow = 0, drafts = 0, unknownStatus = 0;
        long incompleteFinalStatus = 0, invalid = 0, noDeliveredEvent = 0, invalidShipmentLines = 0;
        long otif = 0, completedOnTime = 0, completedLate = 0;
        BigDecimal partialQuantity = BigDecimal.ZERO, overdueQuantity = BigDecimal.ZERO;
        List<Evidence> evidence = new ArrayList<>();

        List<OrderLine> knownLines = lines.stream().filter(line -> line != null && line.createdAt() != null
                && line.orderCreatedAt() != null && !line.createdAt().isAfter(asOf)
                && !line.orderCreatedAt().isAfter(asOf)).toList();
        for (OrderLine line : knownLines) {
            String exclusion = validate(line);
            if (exclusion == null && line.createdAt().isAfter(asOf)) {
                exclusion = "PO_LINE_CREATED_AFTER_CUTOFF";
            } else if (exclusion == null && line.statusUpdatedAt().isAfter(asOf)) {
                exclusion = "ORDER_STATUS_NOT_KNOWN_AT_CUTOFF";
                unknownStatus++;
            } else if (exclusion == null && "CANCELLED".equals(line.status())) {
                exclusion = "CANCELLED_ORDER";
                cancelled++;
            } else if (exclusion == null && "DRAFT".equals(line.status())) {
                exclusion = "DRAFT_ORDER";
                drafts++;
            } else if (exclusion == null && line.requiredDeliveryOn().isAfter(asOfDate)) {
                exclusion = "NOT_DUE_AT_CUTOFF";
                notYetDue++;
            } else if (exclusion == null && line.requiredDeliveryOn().isBefore(windowStart)) {
                exclusion = "OUTSIDE_SCORING_WINDOW";
                outsideWindow++;
            }

            if (exclusion != null) {
                if (!"CANCELLED_ORDER".equals(exclusion) && !"DRAFT_ORDER".equals(exclusion)
                        && !"NOT_DUE_AT_CUTOFF".equals(exclusion) && !"OUTSIDE_SCORING_WINDOW".equals(exclusion)
                        && !"ORDER_STATUS_NOT_KNOWN_AT_CUTOFF".equals(exclusion)) invalid++;
                evidence.add(toEvidence(line, false, null, exclusion, BigDecimal.ZERO, null, List.of()));
                continue;
            }

            eligible++;
            List<ShipmentLine> knownShipmentLines=shipmentLinesKnownByCutoff(line.shipmentLines(),asOf);
            List<ShipmentDelivery> shipments = shipmentsAsOf(knownShipmentLines, asOf);
            long lineInvalidEvents = invalidDeliveredEventCount(knownShipmentLines, asOf);
            BigDecimal delivered = shipments.stream().map(ShipmentDelivery::quantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long lineInvalidShipmentLines=invalidShipmentLineCount(knownShipmentLines);
            invalidShipmentLines+=lineInvalidShipmentLines;
            if (delivered.signum() == 0 && !knownShipmentLines.isEmpty()) noDeliveredEvent++;
            BigDecimal cappedDelivered = delivered.min(line.orderedQuantity());
            LocalDateTime completedAt = completionAt(shipments, line.orderedQuantity());

            // A final order status without sufficient delivered quantity is inconsistent source data.
            if (("FULFILLED".equals(line.status()) || "CLOSED".equals(line.status()))
                    && completedAt == null) {
                eligible--;
                incompleteFinalStatus++;
                evidence.add(toEvidence(line, false, null, "FINAL_ORDER_STATUS_WITHOUT_DELIVERY_EVIDENCE",
                        delivered, null, shipments));
                continue;
            }

            boolean isOnTime = completedAt != null && !completedAt.toLocalDate().isAfter(line.requiredDeliveryOn());
            if (isOnTime) {
                otif++;
                completedOnTime++;
            } else if (completedAt != null) {
                completedLate++;
            }
            if (completedAt == null && delivered.signum() > 0) partialQuantity = partialQuantity.add(cappedDelivered);
            if (completedAt == null) {
                overdueQuantity = overdueQuantity.add(line.orderedQuantity().subtract(cappedDelivered).max(BigDecimal.ZERO));
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("orderStatusAtCutoff", line.status());
            details.put("requiredDeliveryOn", line.requiredDeliveryOn());
            details.put("orderedQuantity", line.orderedQuantity());
            details.put("deliveredQuantityAtCutoff", delivered);
            details.put("completedAt", completedAt);
            details.put("onTimeInFull", isOnTime);
            details.put("partialDeliveryQuantity", completedAt == null ? cappedDelivered : BigDecimal.ZERO);
            details.put("overdueOutstandingQuantity", completedAt == null
                    ? line.orderedQuantity().subtract(cappedDelivered).max(BigDecimal.ZERO) : BigDecimal.ZERO);
            details.put("shipmentDeliveries", shipments.stream().map(ShipmentDelivery::toMap).toList());
            details.put("invalidDeliveredEventCount", lineInvalidEvents);
            details.put("invalidShipmentLineCount", lineInvalidShipmentLines);
            details.put("deliveryDataNote", delivered.signum() == 0
                    ? "No qualifying DELIVERED event was known by the cutoff; due commitment remains unfulfilled."
                    : null);
            evidence.add(new Evidence(line.id(), line.businessId(), true,
                    isOnTime ? BigDecimal.ONE : BigDecimal.ZERO, null, details));
        }

        long observations = knownLines.size();
        long excluded = observations - eligible;
        long completed = completedOnTime + completedLate;
        BigDecimal otifRate = rate(otif, eligible);
        BigDecimal completedOnTimeRate = rate(completedOnTime, completed);
        BigDecimal completedLateRate = rate(completedLate, completed);
        String status = observations == 0 ? "MISSING_DATA"
                : eligible == 0 || eligible < minimumObservations ? "INSUFFICIENT_OBSERVATIONS" : "AVAILABLE";
        String reason = status.equals("AVAILABLE") ? null : eligible == 0
                ? "No eligible PO-line observations" : "Eligible PO-line observations below configured minimum (" + minimumObservations + ")";
        BigDecimal score = status.equals("AVAILABLE") ? otifRate : null;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("onTimeInFullCount", otif);
        metrics.put("onTimeInFullDenominator", eligible);
        metrics.put("onTimeInFullRate", otifRate);
        metrics.put("completedOnTimeCount", completedOnTime);
        metrics.put("completedOnTimeDenominator", completed);
        metrics.put("completedOnTimeRate", completedOnTimeRate);
        metrics.put("completedLateCount", completedLate);
        metrics.put("completedLateDenominator", completed);
        metrics.put("completedLateRate", completedLateRate);
        metrics.put("partialDeliveryQuantity", partialQuantity);
        metrics.put("overdueOutstandingQuantity", overdueQuantity);
        metrics.put("eligiblePoLineCount", eligible);
        metrics.put("cancelledPoLineCount", cancelled);
        metrics.put("otherExcludedPoLineCount", excluded - cancelled);
        metrics.put("observedPoLineCount", observations);
        metrics.put("notYetDuePoLineCount", notYetDue);
        metrics.put("outOfWindowPoLineCount", outsideWindow);
        metrics.put("draftPoLineCount", drafts);
        metrics.put("statusUnknownAtCutoffPoLineCount", unknownStatus);
        metrics.put("incompleteFinalStatusPoLineCount", incompleteFinalStatus);
        metrics.put("invalidPoLineCount", invalid);
        metrics.put("completedPoLineCount", completed);
        metrics.put("eligibleLinesWithoutQualifyingDeliveredEvent", noDeliveredEvent);
        metrics.put("invalidShipmentLineCount", invalidShipmentLines);
        metrics.put("windowStart", windowStart);
        metrics.put("asOfDate", asOfDate);
        return new Result(score, BigDecimal.valueOf(otif), BigDecimal.valueOf(eligible), observations,
                eligible, excluded, status, reason, metrics, List.copyOf(evidence));
    }

    private String validate(OrderLine line) {
        if (line == null || line.id() <= 0 || line.businessId() == null || line.businessId().isBlank()
                || line.status() == null || !SUPPORTED_STATUSES.contains(line.status())
                || line.requiredDeliveryOn() == null || line.orderedQuantity() == null
                || line.orderedQuantity().signum() <= 0 || line.createdAt() == null || line.orderCreatedAt() == null
                || line.statusUpdatedAt() == null) {
            return "INVALID_PO_LINE_OR_ORDER_DATA";
        }
        return null;
    }

    private Evidence toEvidence(OrderLine line, boolean included, BigDecimal contribution, String reason,
                               BigDecimal delivered, LocalDateTime completedAt, List<ShipmentDelivery> shipments) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (line != null) {
            details.put("requiredDeliveryOn", line.requiredDeliveryOn());
            details.put("orderedQuantity", line.orderedQuantity());
            details.put("lineCreatedAt", line.createdAt());
            details.put("orderCreatedAt", line.orderCreatedAt());
            if ("ORDER_STATUS_NOT_KNOWN_AT_CUTOFF".equals(reason)) {
                details.put("orderStatusUnavailableAtCutoff", true);
            } else {
                details.put("orderStatusAtCutoff", line.status());
            }
        }
        details.put("deliveredQuantityAtCutoff", delivered);
        details.put("completedAt", completedAt);
        details.put("exclusionReason", reason);
        details.put("shipmentDeliveries", shipments.stream().map(ShipmentDelivery::toMap).toList());
        return new Evidence(line == null ? 0 : line.id(), line == null ? "INVALID" : line.businessId(),
                included, contribution, reason, details);
    }

    private List<ShipmentDelivery> shipmentsAsOf(List<ShipmentLine> shipmentLines, LocalDateTime asOf) {
        Map<Long, ShipmentLine> uniqueLines = new LinkedHashMap<>();
        for (ShipmentLine line : shipmentLines == null ? List.<ShipmentLine>of() : shipmentLines) {
            if (line == null || line.id() <= 0 || line.shipmentId() == null || line.shipmentId().isBlank()
                    || line.quantity() == null || line.quantity().signum() <= 0
                    || line.shipmentCreatedAt() == null || line.lineCreatedAt() == null
                    || line.shipmentCreatedAt().isAfter(asOf)
                    || line.lineCreatedAt().isAfter(asOf)) continue;
            uniqueLines.putIfAbsent(line.id(), line);
        }

        Map<String, List<ShipmentLine>> byShipment = new LinkedHashMap<>();
        uniqueLines.values().forEach(line -> byShipment.computeIfAbsent(line.shipmentId(), ignored -> new ArrayList<>()).add(line));
        List<ShipmentDelivery> deliveries = new ArrayList<>();
        for (var entry : byShipment.entrySet()) {
            List<DeliveryEvent> events = entry.getValue().stream().flatMap(line -> safeEvents(line).stream())
                    .filter(event -> "DELIVERED".equals(event.type()) && event.id() > 0
                            && event.occurredAt() != null && event.recordedAt() != null
                            && !event.occurredAt().isAfter(asOf)
                            && !event.recordedAt().isAfter(asOf) && eventIsChronological(entry.getValue(),event))
                    .collect(java.util.stream.Collectors.toMap(DeliveryEvent::id, event -> event, (left, right) -> left))
                    .values().stream().toList();
            if (events.isEmpty()) continue;
            LocalDateTime conservativeDeliveredAt = events.stream().map(DeliveryEvent::occurredAt)
                    .max(Comparator.naturalOrder()).orElseThrow();
            BigDecimal quantity = entry.getValue().stream().map(ShipmentLine::quantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Long> eventIds = events.stream().map(DeliveryEvent::id).sorted().toList();
            List<Long> shipmentLineIds = entry.getValue().stream().map(ShipmentLine::id).sorted().toList();
            deliveries.add(new ShipmentDelivery(entry.getKey(), quantity, conservativeDeliveredAt, eventIds, shipmentLineIds));
        }
        return deliveries.stream().sorted(Comparator.comparing(ShipmentDelivery::deliveredAt)
                .thenComparing(ShipmentDelivery::shipmentId)).toList();
    }

    private List<ShipmentLine> shipmentLinesKnownByCutoff(List<ShipmentLine> lines, LocalDateTime asOf) {
        if (lines == null) return List.of();
        return lines.stream().filter(Objects::nonNull)
                .filter(line -> (line.shipmentCreatedAt() == null || !line.shipmentCreatedAt().isAfter(asOf))
                        && (line.lineCreatedAt() == null || !line.lineCreatedAt().isAfter(asOf))).toList();
    }

    private long invalidDeliveredEventCount(List<ShipmentLine> lines, LocalDateTime asOf) {
        if (lines == null) return 0;
        return lines.stream().filter(Objects::nonNull)
                .flatMap(line -> safeEvents(line).stream().filter(event -> "DELIVERED".equals(event.type()) && event.id() > 0
                        && event.occurredAt() != null && event.recordedAt() != null
                        && !event.occurredAt().isAfter(asOf) && !event.recordedAt().isAfter(asOf)
                        && !eventIsChronological(List.of(line),event)))
                .map(DeliveryEvent::id).distinct().count();
    }

    private long invalidShipmentLineCount(List<ShipmentLine> lines) {
        if (lines == null) return 0;
        return lines.stream().filter(Objects::nonNull).filter(line -> line.id() <= 0
                || line.shipmentId() == null || line.shipmentId().isBlank() || line.quantity() == null
                || line.quantity().signum() <= 0 || line.shipmentCreatedAt() == null || line.lineCreatedAt() == null).count();
    }

    private boolean eventIsChronological(List<ShipmentLine> lines, DeliveryEvent event) {
        return lines.stream().map(ShipmentLine::shipmentDispatchedAt).filter(Objects::nonNull)
                .findFirst().filter(dispatchedAt -> !event.occurredAt().isBefore(dispatchedAt)).isPresent()
                && !event.recordedAt().isBefore(event.occurredAt());
    }

    private List<DeliveryEvent> safeEvents(ShipmentLine line) {
        return line.events() == null ? List.of() : line.events();
    }

    private LocalDateTime completionAt(List<ShipmentDelivery> deliveries, BigDecimal ordered) {
        BigDecimal cumulative = BigDecimal.ZERO;
        for (ShipmentDelivery delivery : deliveries) {
            cumulative = cumulative.add(delivery.quantity());
            if (cumulative.compareTo(ordered) >= 0) return delivery.deliveredAt();
        }
        return null;
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator).multiply(HUNDRED).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    public record OrderLine(long id, String businessId, int lineNumber, String status, LocalDate requiredDeliveryOn,
                            BigDecimal orderedQuantity, LocalDateTime orderCreatedAt, LocalDateTime createdAt, LocalDateTime statusUpdatedAt,
                            List<ShipmentLine> shipmentLines) { }
    public record ShipmentLine(long id, String shipmentId, BigDecimal quantity, LocalDateTime shipmentCreatedAt,
                               LocalDateTime shipmentDispatchedAt, LocalDateTime lineCreatedAt,
                               List<DeliveryEvent> events) { }
    public record DeliveryEvent(long id, String type, LocalDateTime occurredAt, LocalDateTime recordedAt) { }
    public record Evidence(long sourceRecordId, String sourceBusinessId, boolean included, BigDecimal contribution,
                           String exclusionReason, Map<String, Object> details) { }
    public record Result(BigDecimal score, BigDecimal numerator, BigDecimal denominator, long observations,
                         long eligible, long excluded, String status, String reason, Map<String, Object> metrics,
                         List<Evidence> evidence) { }

    private record ShipmentDelivery(String shipmentId, BigDecimal quantity, LocalDateTime deliveredAt,
                                    List<Long> eventIds, List<Long> shipmentLineIds) {
        Map<String, Object> toMap() {
            return Map.of("shipmentId", shipmentId, "quantity", quantity, "deliveredAt", deliveredAt,
                    "eventIds", eventIds, "shipmentLineIds", shipmentLineIds);
        }
    }
}
