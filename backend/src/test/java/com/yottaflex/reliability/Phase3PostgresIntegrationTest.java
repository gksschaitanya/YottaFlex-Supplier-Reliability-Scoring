package com.yottaflex.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named="postgres.integration",matches="true")
@SpringBootTest
@AutoConfigureMockMvc
class Phase3PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mockMvc;

    @Test
    void allFiveFixturesQuarantineThenRequireCorrectionRevalidationAndAuditedRelease() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(System.getenv().getOrDefault("DB_NAME","yottaflex_synthetic"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_generation_run WHERE status='COMPLETED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staging_record",Integer.class)).isZero();
        String runResponse=mockMvc.perform(post("/api/v1/validation/runs")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andReturn().getResponse().getContentAsString();
        Map<String,Object> run=mapper.readValue(runResponse,new TypeReference<>(){});
        assertThat(run.get("status")).isEqualTo("COMPLETED");
        @SuppressWarnings("unchecked") List<Map<String,Object>> findings=(List<Map<String,Object>>)run.get("results");
        assertThat(findings).hasSize(5).allSatisfy(f -> assertThat(f.get("valid")).isEqualTo(false));
        assertThat(findings).extracting(f -> f.get("issueType")).containsExactlyInAnyOrder("PARENT_REFERENCE_MISSING","EVENT_CHRONOLOGY_INVALID","FUTURE_EVIDENCE_AFTER_CUTOFF","REQUIRED_FIELD_MISSING","CROSS_SUPPLIER_SITE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exception_record WHERE resolution_status='OPEN'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quarantined_record WHERE disposition='HELD'",Integer.class)).isEqualTo(5);
        mockMvc.perform(get("/api/v1/validation/runs")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("COMPLETED"));
        mockMvc.perform(get("/api/v1/exceptions")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
        mockMvc.perform(get("/api/v1/exceptions/{id}",findings.get(0).get("exceptionId"))).andExpect(status().isOk()).andExpect(jsonPath("$.issueType").value("PARENT_REFERENCE_MISSING"));
        mockMvc.perform(get("/api/v1/quarantined-records")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
        mockMvc.perform(get("/api/v1/quarantined-records/{id}",findings.get(0).get("quarantineId"))).andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("HELD"));

        for(Map<String,Object> finding:findings) {
            long id=((Number)finding.get("stagingRecordId")).longValue();
            JsonNode original=mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM staging_record WHERE id=?",String.class,id));
            ObjectNode fixed=original.deepCopy();
            switch((String)finding.get("fixtureCode")) {
                case "ANOMALY_SHIPMENT_NO_PO" -> fixed.put("purchaseOrderId",jdbc.queryForObject("SELECT po_id FROM purchase_order ORDER BY po_id LIMIT 1",String.class));
                case "ANOMALY_DELIVERY_BEFORE_DISPATCH" -> fixed.put("occurredAt",fixed.get("dispatchedAt").asText());
                case "ANOMALY_FUTURE_SCORE_INPUT" -> fixed.put("eventAt",fixed.get("scoreRunAsOf").asText());
                case "ANOMALY_RISK_MISSING_SOURCE_OR_TIME" -> {fixed.put("source","verified-fixture-source");fixed.put("startsAt","2026-10-22T12:00:00Z");}
                case "ANOMALY_CROSS_SUPPLIER_SITE" -> fixed.put("supplierId",jdbc.queryForObject("SELECT p.supplier_id FROM site s JOIN supplier p ON p.id=s.supplier_id WHERE s.site_id=?",String.class,fixed.get("siteId").asText()));
                default -> throw new AssertionError("Unexpected fixture code");
            }
            String quarantineId=(String)finding.get("quarantineId");
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/quarantined-records/{id}/correction",quarantineId).contentType("application/json").content(mapper.writeValueAsString(fixed))).andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/quarantined-records/{id}/release",quarantineId).contentType("application/json").content("{\"note\":\"Too early\",\"actorRef\":\"phase3-test-actor\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHASE3_STATE_CONFLICT"));
            String validationResponse=mockMvc.perform(post("/api/v1/quarantined-records/{id}/revalidate",quarantineId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Map<String,Object> validation=mapper.readValue(validationResponse,new TypeReference<>(){});
            @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)validation.get("result");
            assertThat(result.get("valid")).as((String)finding.get("fixtureCode")).isEqualTo(true);
            String exceptionId=(String)finding.get("exceptionId");
            mockMvc.perform(post("/api/v1/quarantined-records/{id}/release",quarantineId).contentType("application/json").content("{\"note\":\"Exception unresolved\",\"actorRef\":\"phase3-test-actor\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Release denied: resolve the linked exception first."));
            String resolutionBody=mapper.createObjectNode().put("status","RESOLVED").put("note","Corrected and passed complete validation.").put("actorRef","phase3-test-actor").toString();
            mockMvc.perform(post("/api/v1/exceptions/{id}/resolution",exceptionId).contentType("application/json").content(resolutionBody)).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/exceptions/{id}",exceptionId)).andExpect(status().isOk()).andExpect(jsonPath("$.resolutionActorRef").value("phase3-test-actor")).andExpect(jsonPath("$.resolutionAuditedAt").exists()).andExpect(jsonPath("$.originalPayload").isMap());
            String releaseBody=mapper.createObjectNode().put("note","Released after complete revalidation.").put("actorRef","phase3-test-actor").toString();
            mockMvc.perform(post("/api/v1/quarantined-records/{id}/release",quarantineId).contentType("application/json").content(releaseBody)).andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("RELEASED"));
            assertThat(mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM staging_record WHERE id=?",String.class,id))).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT disposition FROM quarantined_record WHERE quarantine_id=?",String.class,quarantineId)).isEqualTo("RELEASED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type='EXCEPTION_RESOLVED' AND actor_ref='phase3-test-actor'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type='QUARANTINE_RELEASED' AND actor_ref='phase3-test-actor'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staging_record",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exception_record",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quarantined_record",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier",Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order",Integer.class)).isEqualTo(120);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment",Integer.class)).isEqualTo(180);
        String repeatedResponse=mockMvc.perform(post("/api/v1/validation/runs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String,Object> repeated=mapper.readValue(repeatedResponse,new TypeReference<>(){});
        @SuppressWarnings("unchecked") List<Map<String,Object>> repeatedResults=(List<Map<String,Object>>)repeated.get("results");
        assertThat(repeatedResults).hasSize(5).allSatisfy(f -> assertThat(f.get("valid")).isEqualTo(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exception_record",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quarantined_record",Integer.class)).isEqualTo(5);
    }
}
