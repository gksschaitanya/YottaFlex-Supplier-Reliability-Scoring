package com.yottaflex.reliability.validation.application;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FixtureValidators {
    private static final String VALID = "Fixture passed its complete structured validation.";
    private static final Instant SCORE_CUTOFF = Instant.parse("2026-10-22T23:59:59Z");
    private final List<RecordValidator> validators;

    public FixtureValidators(JdbcTemplate jdbc) {
        validators = List.of(
            validator("ANOMALY_SHIPMENT_NO_PO", "SHIPMENT", "PARENT_REFERENCE_MISSING", "The referenced purchase order does not exist.", p -> {
                String po = text(p, "purchaseOrderId");
                return po != null && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM purchase_order WHERE po_id = ?)", Boolean.class, po);
            }),
            validator("ANOMALY_DELIVERY_BEFORE_DISPATCH", "DELIVERY_EVENT", "EVENT_CHRONOLOGY_INVALID", "Delivery event occurred before the shipment dispatch or does not match the persisted shipment.", p -> {
                String shipment=text(p,"shipmentId"); Instant persisted=shipment==null?null:jdbc.query("SELECT dispatched_at FROM shipment WHERE shipment_id=?",rs->rs.next()&&rs.getTimestamp(1)!=null?rs.getTimestamp(1).toInstant():null,shipment);
                Instant dispatch=instant(p,"dispatchedAt"),event=instant(p,"occurredAt");
                return persisted!=null&&dispatch!=null&&persisted.equals(dispatch)&&event!=null&&!event.isBefore(persisted);
            }),
            validator("ANOMALY_FUTURE_SCORE_INPUT", "SCORE_INPUT", "FUTURE_EVIDENCE_AFTER_CUTOFF", "Evidence timestamp is after the immutable score-run cutoff.", p -> {
                Instant declaredCutoff=instant(p,"scoreRunAsOf"),event=instant(p,"eventAt");
                return SCORE_CUTOFF.equals(declaredCutoff)&&event!=null&&!event.isAfter(SCORE_CUTOFF);
            }),
            validator("ANOMALY_RISK_MISSING_SOURCE_OR_TIME", "RISK_SIGNAL", "REQUIRED_FIELD_MISSING", "Risk evidence requires a nonblank source and start timestamp.", p -> {
                return text(p, "source") != null && !text(p, "source").isBlank() && instant(p, "startsAt") != null;
            }),
            validator("ANOMALY_CROSS_SUPPLIER_SITE", "SITE_REFERENCE", "CROSS_SUPPLIER_SITE", "The site must belong to the referenced supplier.", p -> {
                String supplier = text(p, "supplierId"), site = text(p, "siteId");
                return supplier != null && site != null && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM site s JOIN supplier p ON p.id=s.supplier_id WHERE p.supplier_id=? AND s.site_id=?)", Boolean.class, supplier, site);
            })
        );
    }

    public List<RecordValidator> all() { return validators; }
    public RecordValidator get(String fixtureCode) { return validators.stream().filter(v -> v.fixtureCode().equals(fixtureCode)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown fixture code: " + fixtureCode)); }
    private interface Check { boolean valid(JsonNode p); }
    private static RecordValidator validator(String code, String entity, String issue, String description, Check check) {
        return new RecordValidator() {
            public String fixtureCode(){return code;} public String entityType(){return entity;} public String issueType(){return issue;} public String description(){return description;}
            public ValidationFinding validate(JsonNode p) { return check.valid(p) ? ValidationFinding.valid(VALID) : ValidationFinding.invalid(issue, "HIGH", description); }
        };
    }
    static String text(JsonNode p, String name) { JsonNode n=p.get(name); return n==null||n.isNull()?null:n.asText(); }
    static Instant instant(JsonNode p, String name) { try { String v=text(p,name); return v==null?null:Instant.parse(v); } catch (RuntimeException e) { return null; } }
}
