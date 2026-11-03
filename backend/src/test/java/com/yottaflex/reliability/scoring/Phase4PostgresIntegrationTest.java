package com.yottaflex.reliability.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "postgres.phase4.integration", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("phase4-test")
@ContextConfiguration(initializers = Phase4TestDatabaseGuard.class)
class Phase4PostgresIntegrationTest {
    private static final String TEST_DATABASE = "yottaflex_phase4_test";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void complianceCutoverAndScopeBaselineAreConsistent() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo(TEST_DATABASE);
        assertThat(jdbc.queryForObject("select count(*) from compliance_requirement_policy_history",Long.class)).isEqualTo(12L);
        long cutoverSiteScopes=jdbc.queryForObject("select count(*) from compliance_scope_history where scope_type='SITE' and recorded_at=(select cutover_at from compliance_history_cutover where id=1)",Long.class);
        long activeSites=jdbc.queryForObject("select count(*) from site where status='ACTIVE' and effective_from<=(select cutover_at::date from compliance_history_cutover where id=1) and (effective_to is null or effective_to>=(select cutover_at::date from compliance_history_cutover where id=1))",Long.class);
        assertThat(cutoverSiteScopes).isEqualTo(activeSites);
        assertThat(jdbc.queryForObject("select count(*) from compliance_scope_requirement sr join compliance_scope_history h on h.id=sr.scope_history_id where h.scope_type='SITE' and h.recorded_at=(select cutover_at from compliance_history_cutover where id=1)",Long.class)).isEqualTo(activeSites*3);
        assertThat(jdbc.queryForObject("select count(*) from compliance_scope_requirement sr join compliance_scope_history h on h.id=sr.scope_history_id where h.scope_type='SUPPLIER' and h.recorded_at=(select cutover_at from compliance_history_cutover where id=1)",Long.class)).isEqualTo(jdbc.queryForObject("select count(*) from supplier",Long.class)*9);
        assertThat(jdbc.queryForObject("select count(*) from compliance_evidence_event where event_type='AVAILABLE' and recorded_at<=(select cutover_at from compliance_history_cutover where id=1)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from compliance_assessment_scoring_history",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from compliance_check_scoring_history",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from compliance_requirement_policy_history p join compliance_requirement r on r.id=p.requirement_id where p.mandatory<>(r.requirement_code in ('BUSINESS_LICENSE','TAX_REGISTRATION','SANCTIONS_SCREEN','INSURANCE_COVER')) or p.critical<>(r.requirement_code in ('SANCTIONS_SCREEN','INSURANCE_COVER')) or p.scope_type<>(case when r.requirement_code in ('WORKPLACE_SAFETY','ORIGIN_DECLARATION','TRACEABILITY') then 'SITE' else 'SUPPLIER' end)",Long.class)).isZero();
    }

    @Test
    void multipleSiteTransitionsHaveDistinctEventsAndRollbackAtomically() {
        long siteId=jdbc.queryForObject("select id from site order by id limit 1",Long.class);
        String original=jdbc.queryForObject("select status from site where id=?",String.class,siteId);
        String other=original.equals("ACTIVE")?"INACTIVE":"ACTIVE";
        long before=jdbc.queryForObject("select count(*) from compliance_scope_history where site_id=?",Long.class,siteId);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            jdbc.update("update site set status=? where id=?",other,siteId);
            jdbc.update("update site set status=? where id=?",original,siteId);
            assertThat(jdbc.queryForObject("select count(*) from compliance_scope_history where site_id=?",Long.class,siteId)).isEqualTo(before+2);
            assertThat(jdbc.queryForObject("select count(distinct event_id) from compliance_scope_history where site_id=?",Long.class,siteId)).isEqualTo(before+2);
            throw new IllegalStateException("rollback probe");
        })).hasMessage("rollback probe");
        assertThat(jdbc.queryForObject("select count(*) from compliance_scope_history where site_id=?",Long.class,siteId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from site where id=?",String.class,siteId)).isEqualTo(original);
    }

    @Test
    void evidenceTransitionsArePerItemIdempotentAndTransactional() {
        long assessment=jdbc.queryForObject("select id from compliance_assessment order by id limit 1",Long.class);
        String original=jdbc.queryForObject("select evidence_reference from compliance_assessment where id=?",String.class,assessment);
        long before=jdbc.queryForObject("select count(*) from compliance_evidence_event e join compliance_evidence_item i on i.id=e.evidence_item_id where i.assessment_id=?",Long.class,assessment);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            jdbc.update("update compliance_assessment set evidence_reference='v8-test-one' where id=?",assessment);
            jdbc.update("update compliance_assessment set evidence_reference='v8-test-two' where id=?",assessment);
            jdbc.update("update compliance_assessment set evidence_reference='v8-test-two' where id=?",assessment);
            jdbc.update("update compliance_assessment set evidence_reference=? where id=?",original,assessment);
            assertThat(jdbc.queryForObject("select count(*) from compliance_evidence_event e join compliance_evidence_item i on i.id=e.evidence_item_id where i.assessment_id=?",Long.class,assessment)).isGreaterThan(before);
            assertThat(jdbc.queryForObject("select count(distinct event_key) from compliance_evidence_event e join compliance_evidence_item i on i.id=e.evidence_item_id where i.assessment_id=?",Long.class,assessment)).isEqualTo(jdbc.queryForObject("select count(*) from compliance_evidence_event e join compliance_evidence_item i on i.id=e.evidence_item_id where i.assessment_id=?",Long.class,assessment));
            throw new IllegalStateException("rollback probe");
        })).hasMessage("rollback probe");
        assertThat(jdbc.queryForObject("select count(*) from compliance_evidence_event e join compliance_evidence_item i on i.id=e.evidence_item_id where i.assessment_id=?",Long.class,assessment)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select evidence_reference from compliance_assessment where id=?",String.class,assessment)).isEqualTo(original);
    }

    @Test
    void concurrentMultiSupplierWritersUseOneGlobalSupplierLockOrder() throws Exception {
        List<Long> sites=jdbc.query("select distinct on (supplier_id) id from site order by supplier_id,id limit 2",(rs,row)->rs.getLong(1));
        assertThat(sites).hasSize(2);
        ExecutorService writers=Executors.newFixedThreadPool(2);
        try {
            Callable<Void> first=()->runOrderedSiteProbe(sites.get(0));
            Callable<Void> second=()->runOrderedSiteProbe(sites.get(1));
            var a=writers.submit(first);var b=writers.submit(second);
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        } finally {
            writers.shutdownNow();writers.awaitTermination(5,TimeUnit.SECONDS);
        }
    }

    private Void runOrderedSiteProbe(long siteId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            jdbc.query("select id from supplier order by id for update",(rs,row)->rs.getLong(1));
            jdbc.update("update site set status=case when status='ACTIVE' then 'INACTIVE' else 'ACTIVE' end where id=?",siteId);
            tx.setRollbackOnly();
        });
        return null;
    }

    @Test
    void scoringRunPersistsSupplierComponentsAndServesPaginatedApis() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(TEST_DATABASE);
        long suppliersBefore = jdbc.queryForObject("select count(*) from supplier", Long.class);
        long stagingBefore = jdbc.queryForObject("select count(*) from staging_record", Long.class);
        long exceptionsBefore = jdbc.queryForObject("select count(*) from exception_record", Long.class);
        long quarantineBefore = jdbc.queryForObject("select count(*) from quarantined_record", Long.class);

        String response = mvc.perform(post("/api/v1/score-runs").header("Idempotency-Key", "phase4-it-" + UUID.randomUUID()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = mapper.readTree(response).path("runId").asText();
        assertThat(runId).isNotBlank();

        long deadline = System.currentTimeMillis() + 30_000;
        String runStatus;
        do {
            runStatus = jdbc.queryForObject("select run_status from score_run where run_id=?", String.class, runId);
            if ("COMPLETED".equals(runStatus) || "FAILED".equals(runStatus)) break;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        assertThat(runStatus).as("asynchronous score run terminal status").isEqualTo("COMPLETED");

        long runPk = jdbc.queryForObject("select id from score_run where run_id=?", Long.class, runId);
        assertThat(jdbc.queryForObject("select count(*) from supplier_score where score_run_id=?", Long.class, runPk)).isEqualTo(suppliersBefore);
        assertThat(jdbc.queryForObject("select count(*) from supplier_score_component c join supplier_score s on s.id=c.supplier_score_id where s.score_run_id=?", Long.class, runPk)).isEqualTo(suppliersBefore * 6);
        assertThat(jdbc.queryForObject("select count(*) from score_run_source_observation_count where score_run_id=?", Long.class, runPk)).isEqualTo(suppliersBefore * 6);
        assertThat(jdbc.queryForObject("select count(*) from score_evidence where score_run_id=?", Long.class, runPk)).isPositive();
        assertThat(jdbc.queryForObject("select count(distinct component) from score_evidence where score_run_id=?", Long.class, runPk)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                WITH delivery_evidence AS (
                  SELECT supplier_score_component_id,COUNT(*) evidence_count,
                    COUNT(*) FILTER (WHERE included) eligible_count,
                    COUNT(*) FILTER (WHERE included AND contribution=1) otif_count
                  FROM score_evidence WHERE score_run_id=? AND component='DELIVERY'
                  GROUP BY supplier_score_component_id
                )
                SELECT COUNT(*) FROM supplier_score_component c
                JOIN supplier_score ss ON ss.id=c.supplier_score_id
                LEFT JOIN delivery_evidence e ON e.supplier_score_component_id=c.id
                WHERE ss.score_run_id=? AND c.component='DELIVERY' AND (
                  c.observation_count<>COALESCE(e.evidence_count,0) OR
                  c.eligible_observation_count<>COALESCE(e.eligible_count,0) OR
                  c.numerator<>COALESCE(e.otif_count,0) OR
                  c.denominator<>COALESCE(e.eligible_count,0) OR
                  (c.metrics->>'onTimeInFullCount')::bigint<>COALESCE(e.otif_count,0) OR
                  (c.metrics->>'eligiblePoLineCount')::bigint<>COALESCE(e.eligible_count,0) OR
                  (c.metrics->>'cancelledPoLineCount')::bigint+(c.metrics->>'otherExcludedPoLineCount')::bigint
                    <>COALESCE(e.evidence_count,0)-COALESCE(e.eligible_count,0))
                """,Long.class,runPk,runPk)).isZero();
        assertThat(jdbc.queryForObject("""
                WITH evidence AS (
                  SELECT supplier_score_component_id,COUNT(*) evidence_count,
                    COUNT(*) FILTER(WHERE included) eligible_count,
                    SUM(contribution) FILTER(WHERE included) numerator
                  FROM score_evidence WHERE score_run_id=? AND component='COMPLIANCE' GROUP BY supplier_score_component_id
                )
                SELECT COUNT(*) FROM supplier_score_component c JOIN supplier_score s ON s.id=c.supplier_score_id
                LEFT JOIN evidence e ON e.supplier_score_component_id=c.id
                WHERE s.score_run_id=? AND c.component='COMPLIANCE' AND (
                  c.observation_count<>COALESCE(e.evidence_count,0) OR
                  c.eligible_observation_count<>COALESCE(e.eligible_count,0) OR
                  c.numerator<>COALESCE(e.numerator,0) OR c.denominator<>COALESCE(e.eligible_count,0))
                """,Long.class,runPk,runPk)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM score_evidence WHERE score_run_id=? AND component='COMPLIANCE'
                  AND (details->>'requirementCode' IS NULL OR details->>'scopeType' IS NULL
                    OR details->>'mandatory' IS NULL OR details->>'critical' IS NULL
                    OR details->>'applicabilityRecordId' IS NULL)
                """,Long.class,runPk)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from score_evidence e join supplier_score s on s.id=e.supplier_score_id
                join supplier_score_component c on c.id=e.supplier_score_component_id
                where e.score_run_id=? and (s.score_run_id<>e.score_run_id or s.supplier_id<>e.supplier_id
                  or c.supplier_score_id<>s.id or c.component<>e.component)
                """, Long.class, runPk)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from score_evidence e where e.score_run_id=? and not (
                  (e.source_entity_type='PO_LINE' and exists(select 1 from po_line x where x.id=e.source_record_id)) or
                  (e.source_entity_type='INSPECTION_LINE' and exists(select 1 from inspection_line x where x.id=e.source_record_id)) or
                  (e.source_entity_type='INVOICE_LINE' and exists(select 1 from invoice_line x where x.id=e.source_record_id)) or
                  (e.source_entity_type='CAPACITY_COMMITMENT' and exists(select 1 from capacity_commitment x where x.id=e.source_record_id)) or
                  (e.source_entity_type='RECEIPT_LINE' and exists(select 1 from receipt_line x where x.id=e.source_record_id)) or
                  (e.source_entity_type='COMPLIANCE_CHECK' and exists(select 1 from compliance_check x where x.id=e.source_record_id)) or
                  (e.source_entity_type='COMPLIANCE_ASSESSMENT' and exists(select 1 from compliance_assessment x where x.id=e.source_record_id)) or
                  (e.source_entity_type='COMPLIANCE_REQUIREMENT' and exists(select 1 from compliance_requirement x where x.id=e.source_record_id)) or
                  (e.source_entity_type='COMPLIANCE_REQUIREMENT_SCOPE' and exists(select 1 from compliance_scope_requirement x where x.id=e.source_record_id and x.requirement_id=(e.details->>'requirementId')::bigint)) or
                  (e.source_entity_type='RISK_SIGNAL' and exists(select 1 from risk_signal x where x.id=e.source_record_id)))
                """, Long.class, runPk)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from score_evidence e where e.score_run_id=? and not (
                  (e.source_entity_type='PO_LINE' and exists(select 1 from po_line x join purchase_order p on p.id=x.purchase_order_id where x.id=e.source_record_id and p.supplier_id=e.supplier_id)) or
                  (e.source_entity_type='INSPECTION_LINE' and exists(select 1 from inspection_line x join receipt r on r.id=x.receipt_id where x.id=e.source_record_id and r.supplier_id=e.supplier_id)) or
                  (e.source_entity_type='INVOICE_LINE' and exists(select 1 from invoice_line x join invoice i on i.id=x.invoice_id where x.id=e.source_record_id and i.supplier_id=e.supplier_id)) or
                  (e.source_entity_type='CAPACITY_COMMITMENT' and exists(select 1 from capacity_commitment x where x.id=e.source_record_id and x.supplier_id=e.supplier_id)) or
                  (e.source_entity_type='RECEIPT_LINE' and exists(select 1 from receipt_line x join receipt r on r.id=x.receipt_id where x.id=e.source_record_id and r.supplier_id=e.supplier_id)) or
                  (e.source_entity_type='COMPLIANCE_CHECK' and exists(select 1 from compliance_check x join compliance_assessment a on a.id=x.assessment_id where x.id=e.source_record_id and a.supplier_id=e.supplier_id)) or
                  (e.source_entity_type='COMPLIANCE_REQUIREMENT_SCOPE' and exists(
                    select 1 from compliance_scope_requirement sr join compliance_scope_history h on h.id=sr.scope_history_id
                    where sr.id=e.source_record_id and sr.requirement_id=(e.details->>'requirementId')::bigint
                      and h.supplier_id=e.supplier_id and (
                        (h.scope_type='SUPPLIER' and h.site_id is null) or
                        (h.scope_type='SITE' and exists(select 1 from compliance_scope_site cs where cs.scope_history_id=h.id and cs.supplier_id=h.supplier_id and cs.site_id=h.site_id))))) or
                  (e.source_entity_type='RISK_SIGNAL' and exists(select 1 from risk_signal x where x.id=e.source_record_id and x.supplier_id=e.supplier_id)))
                """,Long.class,runPk)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from score_evidence e join score_run r on r.id=e.score_run_id
                where e.score_run_id=? and (e.evaluated_at>r.as_of OR
                  (e.source_entity_type='PO_LINE' and (
                    exists(select 1 from po_line x join purchase_order p on p.id=x.purchase_order_id
                      where x.id=e.source_record_id and (p.created_at>r.as_of or x.created_at>r.as_of)) OR
                    (e.included and exists(select 1 from po_line x join purchase_order p on p.id=x.purchase_order_id
                      where x.id=e.source_record_id and p.updated_at>r.as_of)) OR
                    exists(select 1 from jsonb_array_elements(e.details->'shipmentDeliveries') sd(value)
                      cross join lateral jsonb_array_elements_text(sd.value->'shipmentLineIds') line_ids(value)
                      join shipment_line sl on sl.id=line_ids.value::bigint join shipment sh on sh.id=sl.shipment_id
                      where sh.created_at>r.as_of or sl.created_at>r.as_of) OR
                    exists(select 1 from jsonb_array_elements(e.details->'shipmentDeliveries') sd(value)
                      cross join lateral jsonb_array_elements_text(sd.value->'eventIds') event_ids(value)
                      join delivery_event de on de.id=event_ids.value::bigint
                      where de.occurred_at>r.as_of or de.recorded_at>r.as_of))) OR
                  (e.source_entity_type='INSPECTION_LINE' and exists(select 1 from inspection_line x join inspection i on i.id=x.inspection_id where x.id=e.source_record_id and (i.created_at>r.as_of or i.inspected_at>r.as_of))) OR
                  (e.source_entity_type='INVOICE_LINE' and exists(select 1 from invoice_line x join invoice i on i.id=x.invoice_id where x.id=e.source_record_id and (i.created_at>r.as_of or i.invoice_date>r.as_of::date))) OR
                  (e.source_entity_type='CAPACITY_COMMITMENT' and exists(select 1 from capacity_commitment x where x.id=e.source_record_id and x.recorded_at>r.as_of)) OR
                  (e.source_entity_type='RECEIPT_LINE' and exists(select 1 from receipt_line x join receipt rc on rc.id=x.receipt_id
                    join shipment sh on sh.id=rc.shipment_id join shipment_line sl on sl.id=x.shipment_line_id
                    join po_line pl on pl.id=sl.po_line_id join purchase_order po on po.id=pl.purchase_order_id
                    where x.id=e.source_record_id and (rc.created_at>r.as_of or rc.received_at>r.as_of or x.created_at>r.as_of
                      or sh.created_at>r.as_of or sl.created_at>r.as_of or po.created_at>r.as_of or pl.created_at>r.as_of))) OR
                  (e.source_entity_type='COMPLIANCE_CHECK' and exists(select 1 from compliance_check x join compliance_assessment a on a.id=x.assessment_id where x.id=e.source_record_id and (x.checked_at>r.as_of or x.created_at>r.as_of or a.assessed_at>r.as_of or a.created_at>r.as_of))) OR
                  (e.source_entity_type='RISK_SIGNAL' and exists(select 1 from risk_signal x where x.id=e.source_record_id and (x.recorded_at>r.as_of or x.starts_at>r.as_of))))
                """,Long.class,runPk)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from score_evidence where score_run_id=? and not included and (exclusion_reason is null or btrim(exclusion_reason)='')",Long.class,runPk)).isZero();

        JsonNode scores = mapper.readTree(mvc.perform(get("/api/v1/score-runs/{runId}/scores", runId).param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(scores.path("totalElements").asLong()).isEqualTo(suppliersBefore);
        assertThat(scores.path("content").size()).isEqualTo(1);
        String supplierId = scores.path("content").get(0).path("supplierId").asText();
        mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}", runId, supplierId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/components", runId, supplierId)).andExpect(status().isOk());

        JsonNode evidence = mapper.readTree(mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/evidence", runId, supplierId)
                        .param("page", "0").param("size", "1").param("sort", "sourceBusinessId,asc"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(evidence.has("content")).isTrue();
        assertThat(evidence.has("totalElements")).isTrue();
        assertThat(evidence.path("totalElements").asLong()).isPositive();
        assertThat(evidence.path("content").size()).isEqualTo(1);
        assertThat(evidence.path("content").get(0).path("scoreRunId").asText()).isEqualTo(runId);
        assertThat(evidence.path("content").get(0).path("disposition").asText()).isIn("CONTRIBUTION","EXCLUSION");
        JsonNode componentEvidence=mapper.readTree(mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/components/QUALITY/evidence",runId,supplierId).param("size","100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(componentEvidence.path("content").size()).isEqualTo(componentEvidence.path("totalElements").asInt());
        JsonNode contributions=mapper.readTree(mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/evidence",runId,supplierId).param("included","true").param("size","100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(contributions.path("content").size()).isEqualTo(contributions.path("totalElements").asInt());
        JsonNode exclusions=mapper.readTree(mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/evidence",runId,supplierId).param("included","false").param("size","100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(exclusions.path("content").size()).isEqualTo(exclusions.path("totalElements").asInt());
        long supplierPk=jdbc.queryForObject("select id from supplier where supplier_id=?",Long.class,supplierId);
        assertThat(contributions.path("totalElements").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from score_evidence where score_run_id=? and supplier_id=? and included",Long.class,runPk,supplierPk));
        assertThat(exclusions.path("totalElements").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from score_evidence where score_run_id=? and supplier_id=? and not included",Long.class,runPk,supplierPk));
        JsonNode sourceFiltered=mapper.readTree(mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/evidence",runId,supplierId).param("sourceEntityType","RISK_SIGNAL").param("size","100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(sourceFiltered.path("totalElements").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from score_evidence where score_run_id=? and supplier_id=? and source_entity_type='RISK_SIGNAL'",Long.class,runPk,supplierPk));
        long evidenceCountBefore=jdbc.queryForObject("select count(*) from score_evidence where score_run_id=?",Long.class,runPk);
        mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/evidence",runId,supplierId)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from score_evidence where score_run_id=?",Long.class,runPk)).isEqualTo(evidenceCountBefore);
        mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/evidence","not-a-run",supplierId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/evidence",runId,"SUP-999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/score-runs/{runId}/scores/{supplierId}/components/UNKNOWN/evidence",runId,supplierId)).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from supplier", Long.class)).isEqualTo(suppliersBefore);
        assertThat(jdbc.queryForObject("select count(*) from staging_record", Long.class)).isEqualTo(stagingBefore);
        assertThat(jdbc.queryForObject("select count(*) from exception_record", Long.class)).isEqualTo(exceptionsBefore);
        assertThat(jdbc.queryForObject("select count(*) from quarantined_record", Long.class)).isEqualTo(quarantineBefore);
    }

    @Test
    void failedEvidenceWriteRollsBackAllScoreResults() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo(TEST_DATABASE);
        jdbc.execute("DROP TRIGGER IF EXISTS tr_phase4_test_fail_evidence ON public.score_evidence");
        jdbc.execute("DROP FUNCTION IF EXISTS public.phase4_test_fail_evidence()");
        jdbc.execute("""
                CREATE FUNCTION public.phase4_test_fail_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'phase4 integration test evidence failure'; END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER tr_phase4_test_fail_evidence BEFORE INSERT ON public.score_evidence FOR EACH ROW EXECUTE FUNCTION public.phase4_test_fail_evidence()");
        try {
            String response=mvc.perform(post("/api/v1/score-runs").header("Idempotency-Key","phase4-it-failure-"+UUID.randomUUID()))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            String runId=mapper.readTree(response).path("runId").asText();
            long deadline=System.currentTimeMillis()+30_000;
            String status;
            do {
                status=jdbc.queryForObject("select run_status from score_run where run_id=?",String.class,runId);
                if("COMPLETED".equals(status)||"FAILED".equals(status))break;
                Thread.sleep(100);
            } while(System.currentTimeMillis()<deadline);
            assertThat(status).isEqualTo("FAILED");
            long runPk=jdbc.queryForObject("select id from score_run where run_id=?",Long.class,runId);
            assertThat(jdbc.queryForObject("select count(*) from supplier_score where score_run_id=?",Long.class,runPk)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from supplier_score_component c join supplier_score s on s.id=c.supplier_score_id where s.score_run_id=?",Long.class,runPk)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from score_run_source_observation_count where score_run_id=?",Long.class,runPk)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from score_evidence where score_run_id=?",Long.class,runPk)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS tr_phase4_test_fail_evidence ON public.score_evidence");
            jdbc.execute("DROP FUNCTION IF EXISTS public.phase4_test_fail_evidence()");
        }
    }
}
