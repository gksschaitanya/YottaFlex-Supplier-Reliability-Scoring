package com.yottaflex.reliability.scoring;

import com.yottaflex.reliability.scoring.application.DeliveryScoringCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryScoringCalculatorTest {
    private static final DeliveryScoringCalculator CALCULATOR = new DeliveryScoringCalculator();
    private static final LocalDate START = LocalDate.parse("2026-01-01");
    private static final LocalDate AS_OF_DATE = LocalDate.parse("2026-11-02");
    private static final LocalDateTime AS_OF = AS_OF_DATE.atTime(12, 0);
    private static final LocalDate DUE = LocalDate.parse("2026-10-20");

    @Test
    void scoresFullyOnTimeAndCompletedLatePoLinesSeparately() {
        var onTime = order(1, "OPEN", DUE, "100", List.of(shipment(11, "S-11", "100", DUE.atTime(10, 0))));
        var late = order(2, "FULFILLED", DUE, "100", List.of(shipment(12, "S-12", "100", DUE.plusDays(1).atTime(10, 0))));

        var result = calculate(onTime, late);

        assertThat(result.numerator()).isEqualByComparingTo("1");
        assertThat(result.denominator()).isEqualByComparingTo("2");
        assertThat(result.score()).isEqualByComparingTo("50.00");
        assertThat(result.metrics()).containsEntry("completedOnTimeCount", 1L)
                .containsEntry("completedLateCount", 1L)
                .containsEntry("completedPoLineCount", 2L)
                .containsEntry("completedOnTimeRate", new BigDecimal("50.00"))
                .containsEntry("completedLateRate", new BigDecimal("50.00"));
    }

    @Test
    void aggregatesMultiplePartialShipmentsAndCapturesCompletionTime() {
        var onTime = order(3, "PARTIALLY_FULFILLED", DUE, "100", List.of(
                shipment(13, "S-13A", "40", DUE.minusDays(1).atTime(9, 0)),
                shipment(14, "S-13B", "60", DUE.atTime(11, 0))));
        var late = order(4, "PARTIALLY_FULFILLED", DUE, "100", List.of(
                shipment(15, "S-14A", "40", DUE.atTime(9, 0)),
                shipment(16, "S-14B", "60", DUE.plusDays(1).atTime(9, 0))));

        var result = calculate(onTime, late);

        assertThat(result.numerator()).isEqualByComparingTo("1");
        assertThat(result.metrics()).containsEntry("completedOnTimeCount", 1L)
                .containsEntry("completedLateCount", 1L);
        assertThat((List<DeliveryScoringCalculator.Evidence>) result.evidence()).hasSize(2);
        assertThat(result.evidence().get(0).details()).containsEntry("deliveredQuantityAtCutoff", new BigDecimal("100"));
    }

    @Test
    void partialAndUnshippedOverdueLinesReportQuantitiesWithoutShipmentDenominators() {
        var partial = order(5, "PARTIALLY_FULFILLED", DUE, "100", List.of(shipment(17, "S-15", "35", DUE.atTime(8, 0))));
        var none = order(6, "OPEN", DUE, "80", List.of());

        var result = calculate(partial, none);

        assertThat(result.numerator()).isEqualByComparingTo("0");
        assertThat(result.denominator()).isEqualByComparingTo("2");
        assertThat(result.metrics()).containsEntry("partialDeliveryQuantity", new BigDecimal("35"))
                .containsEntry("overdueOutstandingQuantity", new BigDecimal("145"))
                .containsEntry("eligiblePoLineCount", 2L);
        assertThat(result.evidence().get(1).details()).containsEntry("deliveredQuantityAtCutoff", BigDecimal.ZERO)
                .containsKey("deliveryDataNote");
    }

    @Test
    void excludesNotDueDraftCancelledOutOfWindowAndUnknownHistoricalStatuses() {
        var notDue = order(7, "OPEN", AS_OF_DATE.plusDays(1), "10", List.of());
        var draft = order(8, "DRAFT", DUE, "10", List.of());
        var cancelled = order(9, "CANCELLED", DUE, "10", List.of());
        var old = order(10, "CLOSED", START.minusDays(1), "10", List.of(shipment(20, "S-20", "10", DUE.atTime(9, 0))));
        var changedAfterCutoff = new DeliveryScoringCalculator.OrderLine(11,"PO-TEST-000011",1,"CANCELLED",DUE,
                new BigDecimal("10"),AS_OF.minusDays(30),AS_OF.minusDays(2),AS_OF.plusMinutes(1),List.of());

        var result = calculate(notDue, draft, cancelled, old, changedAfterCutoff);

        assertThat(result.eligible()).isZero();
        assertThat(result.excluded()).isEqualTo(5);
        assertThat(result.metrics()).containsEntry("notYetDuePoLineCount", 1L)
                .containsEntry("draftPoLineCount", 1L)
                .containsEntry("cancelledPoLineCount", 1L)
                .containsEntry("outOfWindowPoLineCount", 1L)
                .containsEntry("statusUnknownAtCutoffPoLineCount", 1L);
        assertThat(result.evidence()).extracting(DeliveryScoringCalculator.Evidence::exclusionReason)
                .contains("NOT_DUE_AT_CUTOFF", "DRAFT_ORDER", "CANCELLED_ORDER", "OUTSIDE_SCORING_WINDOW", "ORDER_STATUS_NOT_KNOWN_AT_CUTOFF");
    }

    @Test
    void handlesEverySupportedOrderStatusExplicitly() {
        List<String> statuses = List.of("OPEN", "PARTIALLY_FULFILLED", "FULFILLED", "CLOSED", "CANCELLED", "DRAFT");
        List<DeliveryScoringCalculator.OrderLine> lines = new ArrayList<>();
        long id = 30;
        for (String status : statuses) {
            List<DeliveryScoringCalculator.ShipmentLine> shipments = status.equals("FULFILLED") || status.equals("CLOSED")
                    ? List.of(shipment(id + 100, "S-" + id, "10", DUE.atTime(9, 0))) : List.of();
            lines.add(order(id++, status, DUE, "10", shipments));
        }
        var result = calculate(lines.toArray(DeliveryScoringCalculator.OrderLine[]::new));
        assertThat(result.metrics()).containsEntry("eligiblePoLineCount", 4L)
                .containsEntry("cancelledPoLineCount", 1L)
                .containsEntry("draftPoLineCount", 1L);
    }

    @Test
    void rejectsFinalStatusWithoutDeliveryEvidenceInsteadOfScoringItAsZero() {
        var inconsistent = order(40, "FULFILLED", DUE, "10", List.of());
        var result = calculate(inconsistent);
        assertThat(result.status()).isEqualTo("INSUFFICIENT_OBSERVATIONS");
        assertThat(result.score()).isNull();
        assertThat(result.evidence().getFirst().exclusionReason()).isEqualTo("FINAL_ORDER_STATUS_WITHOUT_DELIVERY_EVIDENCE");
        assertThat(result.metrics()).containsEntry("incompleteFinalStatusPoLineCount", 1L);
    }

    @Test
    void duplicateDeliveredEventsNeverMultiplyQuantityOrImproveTheScore() {
        var baseLine = order(41, "OPEN", DUE, "100", List.of(shipment(141, "S-41", "100", DUE.atTime(10, 0))));
        var duplicatedShipmentLine=shipment(141, "S-41", "100", DUE.atTime(10, 0),
                event(500, DUE.atTime(10, 0)), event(501, DUE.plusDays(1).atTime(10, 0)));
        var duplicateLine = order(41, "OPEN", DUE, "100", List.of(duplicatedShipmentLine,duplicatedShipmentLine));

        var base = calculate(baseLine);
        var duplicate = calculate(duplicateLine);

        assertThat(duplicate.evidence().getFirst().details()).containsEntry("deliveredQuantityAtCutoff", new BigDecimal("100"));
        assertThat(duplicate.numerator()).isLessThanOrEqualTo(base.numerator());
        assertThat(duplicate.score()).isLessThanOrEqualTo(base.score());
    }

    @Test
    void missingAndChronologicallyInvalidEventsDoNotCountAsDelivery() {
        var missing = order(42,"OPEN",DUE,"10",List.of(new DeliveryScoringCalculator.ShipmentLine(
                242,"S-42",new BigDecimal("10"),AS_OF.minusDays(3),DUE.minusDays(1).atTime(8,0),AS_OF.minusDays(2),List.of())));
        var invalid = order(43,"OPEN",DUE,"10",List.of(new DeliveryScoringCalculator.ShipmentLine(
                243,"S-43",new BigDecimal("10"),AS_OF.minusDays(3),DUE.minusDays(1).atTime(8,0),AS_OF.minusDays(2),List.of(
                new DeliveryScoringCalculator.DeliveryEvent(643,"DELIVERED",DUE.atTime(10,0),DUE.atTime(9,0)),
                new DeliveryScoringCalculator.DeliveryEvent(644,"DELIVERED",DUE.minusDays(2).atTime(10,0),DUE.minusDays(2).atTime(11,0))))));
        var invalidQuantity = order(44,"OPEN",DUE,"10",List.of(new DeliveryScoringCalculator.ShipmentLine(
                244,"S-44",new BigDecimal("-2"),AS_OF.minusDays(3),DUE.minusDays(1).atTime(8,0),AS_OF.minusDays(2),
                List.of(event(645,DUE.atTime(10,0))))));

        var result=calculate(missing,invalid,invalidQuantity);

        assertThat(result.numerator()).isZero();
        assertThat(result.denominator()).isEqualByComparingTo("3");
        assertThat(result.evidence().get(0).details()).containsEntry("deliveredQuantityAtCutoff",BigDecimal.ZERO);
        assertThat(result.evidence().get(1).details()).containsEntry("invalidDeliveredEventCount",2L);
        assertThat(result.evidence().get(2).details()).containsEntry("invalidShipmentLineCount",1L);
    }

    @Test
    void cutoffAndWindowBoundariesExcludeFutureRowsAndIncludeEndpoints() {
        LocalDateTime afterCutoff = AS_OF.plusSeconds(1);
        var shipmentAfterCutoff = shipment(151, "S-51", "100", DUE.atTime(10, 0), event(601, DUE.atTime(10, 0)));
        shipmentAfterCutoff = new DeliveryScoringCalculator.ShipmentLine(shipmentAfterCutoff.id(),shipmentAfterCutoff.shipmentId(),
                shipmentAfterCutoff.quantity(),afterCutoff,shipmentAfterCutoff.shipmentDispatchedAt(),shipmentAfterCutoff.lineCreatedAt(),shipmentAfterCutoff.events());
        var lineAfterCutoff = shipment(152,"S-52","100",DUE.atTime(10,0),event(602,DUE.atTime(10,0)));
        lineAfterCutoff = new DeliveryScoringCalculator.ShipmentLine(lineAfterCutoff.id(),lineAfterCutoff.shipmentId(),
                lineAfterCutoff.quantity(),lineAfterCutoff.shipmentCreatedAt(),lineAfterCutoff.shipmentDispatchedAt(),afterCutoff,lineAfterCutoff.events());
        var eventAfterCutoff = new DeliveryScoringCalculator.ShipmentLine(153,"S-53",new BigDecimal("100"),
                AS_OF.minusDays(3),DUE.minusDays(1).atTime(8,0),AS_OF.minusDays(2),List.of(new DeliveryScoringCalculator.DeliveryEvent(
                603,"DELIVERED",DUE.atTime(10,0),afterCutoff)));
        var windowStart = order(51,"OPEN",START,"100",List.of(shipment(154,"S-54","100",START.atTime(0,0))));
        var cutoffDate = order(52,"OPEN",AS_OF_DATE,"100",List.of(shipment(155,"S-55","100",AS_OF.minusMinutes(1))));
        var cutoffDateFutureTime = order(53,"OPEN",AS_OF_DATE,"100",List.of(shipment(156,"S-56","100",AS_OF.plusMinutes(1))));
        var unseenOrderLine = new DeliveryScoringCalculator.OrderLine(54,"PO-TEST-000054",1,"OPEN",DUE,new BigDecimal("100"),
                AS_OF.plusSeconds(1),AS_OF.plusSeconds(1),AS_OF.plusSeconds(1),List.of());
        var unseenOrder = new DeliveryScoringCalculator.OrderLine(58,"PO-TEST-000058",1,"OPEN",DUE,new BigDecimal("100"),
                AS_OF.plusSeconds(1),AS_OF.minusDays(1),AS_OF.minusDays(1),List.of());

        var result = calculate(windowStart,cutoffDate,cutoffDateFutureTime,
                order(55,"OPEN",DUE,"100",List.of(shipmentAfterCutoff)),
                order(56,"OPEN",DUE,"100",List.of(lineAfterCutoff)),
                order(57,"OPEN",DUE,"100",List.of(eventAfterCutoff)),unseenOrderLine,unseenOrder);

        assertThat(result.metrics()).containsEntry("onTimeInFullCount", 2L);
        assertThat(result.observations()).isEqualTo(6);
        assertThat(result.evidence()).noneMatch(item -> item.sourceRecordId() == 54 || item.sourceRecordId() == 58);
        assertThat(result.metrics()).containsEntry("eligiblePoLineCount", 6L);
    }

    @Test
    void meetsFiveObservationMinimumAndKeepsEvidenceAndComponentMetricsAligned() {
        var lines = new DeliveryScoringCalculator.OrderLine[5];
        for (int i=0;i<5;i++) lines[i]=order(70+i,"OPEN",DUE,"10",
                i<3?List.of(shipment(170+i,"S-"+(170+i),"10",DUE.atTime(9,0))):List.of());
        var result = CALCULATOR.calculate(List.of(lines),START,AS_OF,5);

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.score()).isEqualByComparingTo("60.00");
        assertThat(result.evidence()).hasSize(5);
        assertThat(result.evidence().stream().filter(DeliveryScoringCalculator.Evidence::included).count()).isEqualTo(result.eligible());
        assertThat(result.numerator()).isEqualByComparingTo(BigDecimal.valueOf(((Number)result.metrics().get("onTimeInFullCount")).longValue()));
        assertThat(result.denominator()).isEqualByComparingTo(BigDecimal.valueOf(((Number)result.metrics().get("onTimeInFullDenominator")).longValue()));
    }

    private DeliveryScoringCalculator.Result calculate(DeliveryScoringCalculator.OrderLine... lines) {
        return CALCULATOR.calculate(List.of(lines), START, AS_OF, 1);
    }

    private DeliveryScoringCalculator.OrderLine order(long id, String status, LocalDate due, String quantity,
                                                       List<DeliveryScoringCalculator.ShipmentLine> shipments) {
        return new DeliveryScoringCalculator.OrderLine(id,"PO-TEST-"+String.format("%06d",id),1,status,due,
                new BigDecimal(quantity),AS_OF.minusDays(30),AS_OF.minusDays(30),AS_OF.minusDays(1),shipments);
    }

    private DeliveryScoringCalculator.ShipmentLine shipment(long id, String shipmentId, String quantity, LocalDateTime deliveredAt,
                                                            DeliveryScoringCalculator.DeliveryEvent... additionalEvents) {
        List<DeliveryScoringCalculator.DeliveryEvent> events=new ArrayList<>();
        events.add(event(id+1000,deliveredAt));
        events.addAll(List.of(additionalEvents));
        return new DeliveryScoringCalculator.ShipmentLine(id,shipmentId,new BigDecimal(quantity),deliveredAt.minusDays(3),
                deliveredAt.minusDays(1),AS_OF.minusDays(5),events);
    }

    private DeliveryScoringCalculator.DeliveryEvent event(long id, LocalDateTime occurredAt) {
        return new DeliveryScoringCalculator.DeliveryEvent(id,"DELIVERED",occurredAt,occurredAt.plusMinutes(1));
    }
}
