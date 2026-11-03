package com.yottaflex.reliability.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yottaflex.reliability.scoring.application.DeliveryScoringCalculator;
import com.yottaflex.reliability.scoring.application.ScoreEvidenceWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class ScoreEvidenceWriterTest {
    @Test
    void componentDispatcherRoutesAllSixComponentsToTheirOwnEvidenceWriters() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        ScoreEvidenceWriter writer = spy(new ScoreEvidenceWriter(jdbc,new ObjectMapper().findAndRegisterModules()));
        doReturn(1).when(writer).persistDelivery(anyLong(),anyLong(),anyLong(),anyLong(),any(),anyList());
        doReturn(1).when(writer).persistCost(anyLong(),anyLong(),anyLong(),anyLong(),any(),anyList());
        doReturn(1).when(writer).persistCapacity(anyLong(),anyLong(),anyLong(),anyLong(),any(),anyList());
        doReturn(1).when(writer).persistCompliance(anyLong(),anyLong(),anyLong(),anyLong(),any(),any());
        doReturn(1).when(writer).persistDisruption(anyLong(),anyLong(),anyLong(),anyLong(),any(),anyList());
        var cutoff = LocalDateTime.parse("2026-10-22T12:00:00");
        var start = cutoff.minusDays(365);

        for (String component : List.of("DELIVERY", "QUALITY", "COST", "CAPACITY", "COMPLIANCE", "DISRUPTION")) {
            assertThat(writer.persistComponent(1,2,3,4,component,cutoff,start,
                    List.of(),List.of(),List.of(),null,List.of())).isEqualTo(1);
        }

        verify(writer).persistDelivery(eq(1L),eq(2L),eq(3L),eq(4L),eq(cutoff),anyList());
        verify(writer).persistQuality(eq(1L),eq(2L),eq(3L),eq(4L),eq(cutoff),eq(start));
        verify(writer).persistCost(eq(1L),eq(2L),eq(3L),eq(4L),eq(cutoff),anyList());
        verify(writer).persistCapacity(eq(1L),eq(2L),eq(3L),eq(4L),eq(cutoff),anyList());
        verify(writer).persistCompliance(eq(1L),eq(2L),eq(3L),eq(4L),eq(cutoff),isNull());
        verify(writer).persistDisruption(eq(1L),eq(2L),eq(3L),eq(4L),eq(cutoff),anyList());
    }

    @Test
    void qualityDispatchNeverInvokesDisruptionEvidencePersistence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        ScoreEvidenceWriter writer = spy(new ScoreEvidenceWriter(jdbc,new ObjectMapper().findAndRegisterModules()));
        var cutoff = LocalDateTime.parse("2026-10-22T12:00:00");

        writer.persistComponent(1,2,3,4,"QUALITY",cutoff,cutoff.minusDays(365),
                List.of(),List.of(),List.of(),null,List.of());

        verify(jdbc).update(contains("'QUALITY','INSPECTION_LINE'"),any(Object[].class));
        verify(writer,never()).persistDisruption(anyLong(),anyLong(),anyLong(),anyLong(),any(),anyList());
        verify(jdbc,never()).batchUpdate(any(String.class),anyList());
    }

    @Test
    void complianceCannotUseAnIndependentLegacyEvidenceQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        var writer = new ScoreEvidenceWriter(jdbc,new ObjectMapper().findAndRegisterModules());
        var cutoff = LocalDateTime.parse("2026-11-02T12:00:00");
        var start = cutoff.minusDays(365);

        writer.persist(1, 2, 3, 4, "QUALITY", cutoff, start);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> writer.persist(1, 2, 3, 4, "COMPLIANCE", cutoff, start));

        org.mockito.ArgumentCaptor<String> sql=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> arguments=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains("'QUALITY','INSPECTION_LINE'", "i.inspection_id||':RECEIPT_LINE:'||il.receipt_line_id",
                "il.id", "i.created_at<=?", "i.inspected_at<=?", "r.created_at<=?", "rl.created_at<=?", "il.created_at<=?");
        assertThat(arguments.getValue()).hasSize(18);
        assertThat(arguments.getValue()[15]).isEqualTo(java.sql.Timestamp.valueOf(cutoff));
        assertThat(arguments.getValue()[16]).isEqualTo(java.sql.Timestamp.valueOf(cutoff));
        assertThat(arguments.getValue()[17]).isEqualTo(java.sql.Timestamp.valueOf(cutoff));
        verify(jdbc, never()).update(contains("FROM compliance_check"), any(Object[].class));
        verify(jdbc, times(1)).update(any(String.class), any(Object[].class));
    }

    @Test
    void compliancePersistenceUsesSharedResultAndRetainsEvidenceEventIdentity() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(any(String.class),anyList())).thenReturn(new int[]{1});
        var writer=new ScoreEvidenceWriter(jdbc,new ObjectMapper().findAndRegisterModules());
        var event=new com.yottaflex.reliability.scoring.application.ComplianceScoringCalculator.EvidenceItemState(
                7L,"item-key",9L,"event-key","AVAILABLE",LocalDateTime.parse("2026-10-20T12:00:00"),"AVAILABLE",false,"proof-7");
        var item=new com.yottaflex.reliability.scoring.application.ComplianceScoringCalculator.Evidence(
                1L,"BUSINESS_LICENSE",null,"SUPPLIER",true,false,2L,"COMPLETED",3L,"PASS",4L,true,
                BigDecimal.ONE,null,true,null,java.util.Map.of("evidenceItems",List.of(event)));
        var result=new com.yottaflex.reliability.scoring.application.ComplianceScoringCalculator.Result(
                new BigDecimal("100.00"),BigDecimal.ONE,BigDecimal.ONE,1,1,0,"AVAILABLE",null,false,
                List.of(),java.util.Map.of("passedRequirements",1L),List.of(item));
        assertThat(writer.persistCompliance(1,2,3,4,LocalDateTime.parse("2026-10-22T12:00:00"),result)).isEqualTo(1);
        org.mockito.ArgumentCaptor<List<Object[]>> rows=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(contains("INSERT INTO score_evidence"),rows.capture());
        assertThat(rows.getValue().getFirst()[12].toString()).contains("item-key","event-key","recordedAt","AVAILABLE");
        verify(jdbc,never()).update(contains("FROM compliance_check"),any(Object[].class));
    }

    @Test
    void persistsDisruptionEvidenceFromTheSharedCalculationResult() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(any(String.class),anyList())).thenReturn(new int[]{1,1});
        var writer=new ScoreEvidenceWriter(jdbc,new ObjectMapper().findAndRegisterModules());
        var evidence=List.of(
                new com.yottaflex.reliability.scoring.application.DisruptionScoringCalculator.Evidence(12,"RSK-12",true,
                        new BigDecimal("0.50"),null,java.util.Map.of("severity","HIGH")),
                new com.yottaflex.reliability.scoring.application.DisruptionScoringCalculator.Evidence(13,"RSK-13",false,
                        BigDecimal.ZERO,"INVALID_SEVERITY",java.util.Map.of("severity","UNKNOWN")));
        assertThat(writer.persistDisruption(1,2,3,4,LocalDateTime.parse("2026-11-02T12:00:00"),evidence)).isEqualTo(2);
        verify(jdbc).batchUpdate(contains("INSERT INTO score_evidence"),anyList());
    }

    @Test
    void persistsCapacityEvidenceRowsFromTheSharedAllocationResult() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(any(String.class),anyList())).thenReturn(new int[]{1,1});
        var writer=new ScoreEvidenceWriter(jdbc,new ObjectMapper().findAndRegisterModules());
        var evidence=List.of(
                new com.yottaflex.reliability.scoring.application.CapacityScoringCalculator.Evidence("CAPACITY_COMMITMENT",11,"CAP-1",true,new BigDecimal("12"),null,java.util.Map.of("committedQuantity",new BigDecimal("20"))),
                new com.yottaflex.reliability.scoring.application.CapacityScoringCalculator.Evidence("RECEIPT_LINE",22,"REC-1:LINE:22",true,null,null,java.util.Map.of("allocatedQuantity",new BigDecimal("12"))));

        assertThat(writer.persistCapacity(1,2,3,4,LocalDateTime.parse("2026-11-02T12:00:00"),evidence)).isEqualTo(2);
        verify(jdbc).batchUpdate(contains("INSERT INTO score_evidence"),anyList());
    }

    @Test
    void persistsCostEvidenceProducedByTheCostCalculation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(any(String.class), anyList())).thenReturn(new int[]{1});
        var writer = new ScoreEvidenceWriter(jdbc,new ObjectMapper().findAndRegisterModules());
        var evidence = List.of(new com.yottaflex.reliability.scoring.application.CostScoringCalculator.Evidence(
                44,"INV-1:LINE:44",true,new BigDecimal("100.000000000000"),null,
                java.util.Map.of("poLineId",77L,"unitOfMeasure","EA")));

        assertThat(writer.persistCost(1,2,3,4,LocalDateTime.parse("2026-11-02T12:00:00"),evidence)).isEqualTo(1);
        verify(jdbc).batchUpdate(contains("INSERT INTO score_evidence"),anyList());
    }

    @Test
    void persistsDeliveryEvidenceFromTheCalculatedLineResults() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(any(String.class), anyList())).thenReturn(new int[]{1,1});
        var writer = new ScoreEvidenceWriter(jdbc,new ObjectMapper().findAndRegisterModules());
        var evidence = List.of(
                new DeliveryScoringCalculator.Evidence(21,"PO-TEST-000021:LINE:1",true,BigDecimal.ONE,null,java.util.Map.of("orderedQuantity",new BigDecimal("10"))),
                new DeliveryScoringCalculator.Evidence(22,"PO-TEST-000022:LINE:1",false,null,"DRAFT_ORDER",java.util.Map.of("exclusionReason","DRAFT_ORDER")));

        int inserted = writer.persistDelivery(1,2,3,4,LocalDateTime.parse("2026-11-02T12:00:00"),evidence);

        assertThat(inserted).isEqualTo(2);
        verify(jdbc).batchUpdate(contains("INSERT INTO score_evidence"),anyList());
    }
}
