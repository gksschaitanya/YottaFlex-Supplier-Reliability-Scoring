package com.yottaflex.reliability.scoring.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yottaflex.reliability.scoring.api.ScoreEvidenceResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class ScoringService {
    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);
    private static final String ALGORITHM_VERSION="phase4-2.0";
    private static final List<String> COMPONENTS=List.of("DELIVERY","QUALITY","COST","CAPACITY","COMPLIANCE","DISRUPTION");
    private static final Map<String,BigDecimal> WEIGHTS=Map.of("DELIVERY",bd("25"),"QUALITY",bd("20"),"COST",bd("15"),"CAPACITY",bd("15"),"COMPLIANCE",bd("10"),"DISRUPTION",bd("15"));
    private final JdbcTemplate db; private final ThreadPoolTaskExecutor executor; private final ObjectMapper json;
    private final ScoreEvidenceWriter evidenceWriter; private final DeliveryScoringCalculator deliveryCalculator; private final CostScoringCalculator costCalculator = new CostScoringCalculator(); private final CapacityScoringCalculator capacityCalculator=new CapacityScoringCalculator(); private final TransactionTemplate resultTransaction;
    private final OverallScoringCalculator overallCalculator=new OverallScoringCalculator();
    static final String QUALITY_AGGREGATE_SQL = """
            select count(distinct i.id),count(il.id),coalesce(sum(il.accepted_quantity),0),
                   coalesce(sum(il.inspected_quantity),0),coalesce(sum(il.rejected_quantity),0)
            from inspection i
            join receipt r on r.id=i.receipt_id
            join inspection_line il on il.inspection_id=i.id and il.receipt_id=i.receipt_id
            join receipt_line rl on rl.id=il.receipt_line_id and rl.receipt_id=il.receipt_id
            where r.supplier_id=? and i.status='COMPLETED' and i.inspected_at between ? and ?
              and i.created_at<=? and r.created_at<=? and rl.created_at<=? and il.created_at<=?
              and il.inspected_quantity>0
            """;
    private volatile LocalDateTime runAsOf = LocalDateTime.now(ZoneOffset.UTC), runWindowStart = runAsOf.minusDays(365);
    @Value("${platform.scoring.coverage-threshold:70}") private BigDecimal coverageThreshold;
    @Value("${platform.scoring.minimum-delivery-observations:5}") private long deliveryMin;
    @Value("${platform.scoring.minimum-quality-inspections:3}") private long inspectionMin;
    @Value("${platform.scoring.minimum-quality-units:100}") private BigDecimal qualityMin;
    @Value("${platform.scoring.minimum-cost-lines:5}") private long costMin;
    @Value("${platform.scoring.minimum-capacity-commitments:3}") private long capacityMin;
    @Value("${platform.scoring.minimum-compliance-checks:5}") private long complianceMin;
    @Value("${platform.scoring.disruption-exposure-scale:500}") private BigDecimal exposureScale;
    public ScoringService(JdbcTemplate db, @Qualifier("scoringExecutor") ThreadPoolTaskExecutor executor, ObjectMapper json,
                          ScoreEvidenceWriter evidenceWriter, DeliveryScoringCalculator deliveryCalculator,
                          ObjectProvider<PlatformTransactionManager> transactionManagers){this.db=db;this.executor=executor;this.json=json;this.evidenceWriter=evidenceWriter;this.deliveryCalculator=deliveryCalculator;PlatformTransactionManager manager=transactionManagers.getIfAvailable();this.resultTransaction=manager==null?null:new TransactionTemplate(manager);}
    private static BigDecimal bd(String s){return new BigDecimal(s);}
    private String json(Object x){try{return json.writeValueAsString(x);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}

    public Map<String,Object> start(String key){
        if(key!=null&&!key.isBlank()){var old=db.query("select run_id,run_status from score_run where idempotency_key=?",(r,n)->Map.of("runId",r.getString(1),"status",r.getString(2)),key);if(!old.isEmpty())return new LinkedHashMap<>(old.getFirst());}
        String runId="SR-"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        Instant asOf=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); LocalDateTime cutoff=LocalDateTime.ofInstant(asOf,ZoneOffset.UTC); LocalDateTime start=cutoff.minusDays(365);
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("weights",WEIGHTS);snapshot.put("coverageThreshold",coverageThreshold);snapshot.put("minimumDeliveryObservations",deliveryMin);snapshot.put("minimumQualityInspections",inspectionMin);snapshot.put("minimumQualityUnits",qualityMin);snapshot.put("minimumCostLines",costMin);snapshot.put("minimumCapacityCommitments",capacityMin);snapshot.put("minimumComplianceChecks",complianceMin);snapshot.put("disruptionHalfLifeDays",90);snapshot.put("disruptionExposureScale",exposureScale);snapshot.put("disruptionSeverityWeights",Map.of("INFO",bd("0.25"),"LOW",bd("0.5"),"MEDIUM",bd("1"),"HIGH",bd("2"),"CRITICAL",bd("4")));snapshot.put("disruptionDeduplication","Exact same non-null source and source_reference");snapshot.put("disruptionIntervalPolicy","Half-open; fractional duration; zero duration excluded");snapshot.put("disruptionSignalAgeOrigin","starts_at");snapshot.put("rounding","HALF_UP, 2 decimal score places; money 4 decimal places");
        db.update("insert into score_run(run_id,as_of,window_start,window_end,algorithm_version,coverage_threshold,run_status,started_at,created_at,idempotency_key,configuration_snapshot) values (?,?,?,?,? ,?,'QUEUED',?,?,?,cast(? as jsonb))",runId,TimestampCompat.of(asOf),TimestampCompat.of(start),TimestampCompat.of(cutoff),ALGORITHM_VERSION,coverageThreshold,TimestampCompat.of(asOf),TimestampCompat.of(asOf),key,json(snapshot));
        long runPk=pk(runId);
        WEIGHTS.forEach((component,weight)->db.update("insert into score_run_component_config(score_run_id,component,weight,minimum_observations,minimum_quantity) values (?,?,?,?,?)",runPk,component,weight,switch(component){case "DELIVERY"->deliveryMin;case "QUALITY"->inspectionMin;case "COST"->costMin;case "CAPACITY"->capacityMin;case "COMPLIANCE"->complianceMin;default->0L;},component.equals("QUALITY")?qualityMin:null));
        for(String cls:List.of("EXCELLENT","GOOD","MODERATE","POOR","CRITICAL")){BigDecimal lower=switch(cls){case "EXCELLENT"->bd("90");case "GOOD"->bd("80");case "MODERATE"->bd("65");case "POOR"->bd("50");default->BigDecimal.ZERO;};BigDecimal upper=switch(cls){case "EXCELLENT"->BigDecimal.valueOf(100);case "GOOD"->bd("90");case "MODERATE"->bd("80");case "POOR"->bd("65");default->bd("50");};db.update("insert into score_run_classification_threshold(score_run_id,classification,lower_bound,upper_bound,lower_inclusive,upper_inclusive) values (?,?,?,?,?,?)",runPk,cls,lower,upper,true,cls.equals("EXCELLENT")||cls.equals("CRITICAL"));}
        Map<String,BigDecimal> severity=Map.of("INFO",bd("0.25"),"LOW",bd("0.5"),"MEDIUM",bd("1"),"HIGH",bd("2"),"CRITICAL",bd("4"));severity.forEach((name,weight)->db.update("insert into score_run_severity_config(score_run_id,severity,severity_weight,recency_half_life_days,exposure_scale) values (?,?,?,?,?)",runPk,name,weight,90,exposureScale));
        executor.execute(()->calculate(runId)); return Map.of("runId",runId,"status","QUEUED");
    }
    private void calculate(String rid){try{int claimed=db.update("update score_run set run_status='RUNNING',started_at=clock_timestamp() where run_id=? and run_status='QUEUED'",rid);if(claimed!=1)return;Long runPk=pk(rid);Runnable persistResults=()->{Map<String,Object> cutoff=db.queryForMap("select as_of,window_start from score_run where id=?",runPk);runAsOf=((java.sql.Timestamp)cutoff.get("as_of")).toLocalDateTime();runWindowStart=((java.sql.Timestamp)cutoff.get("window_start")).toLocalDateTime();var suppliers=db.query("select id,supplier_id,name from supplier order by supplier_id",(r,n)->new Supplier(r.getLong(1),r.getString(2),r.getString(3)));for(Supplier s:suppliers)score(runPk,rid,s);db.update("update score_run set run_status='COMPLETED',completed_at=clock_timestamp(),duration_millis=(extract(epoch from (clock_timestamp()-started_at))*1000)::bigint where id=?",runPk);};if(resultTransaction==null)persistResults.run();else resultTransaction.executeWithoutResult(tx->persistResults.run());
    }catch(Exception e){log.error("Scoring run {} failed",rid,e);db.update("update score_run set run_status='FAILED',completed_at=clock_timestamp(),failure_reason=?,duration_millis=(extract(epoch from (clock_timestamp()-started_at))*1000)::bigint where run_id=? and run_status<>'COMPLETED'",safe(e),rid);}}
    private String safe(Exception e){String m=e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),"scoring failure");return m.length()>990?m.substring(0,990):m;}
    private long pk(String rid){return db.queryForObject("select id from score_run where run_id=?",Long.class,rid);}
    private record Supplier(long id,String businessId,String name){}
    private record Metric(BigDecimal score,BigDecimal num,BigDecimal den,long obs,long eligible,long excluded,String status,String reason,Map<String,Object> extra){}
    private record DisruptionResult(Metric metric, DisruptionScoringCalculator.Result calculation){}
    private void score(long runPk,String rid,Supplier s){
        long year=LocalDate.now(ZoneOffset.UTC).getYear(); long seq=db.queryForObject("select coalesce(max(id),0)+1 from supplier_score",Long.class);
        String scoreId=String.format(Locale.ROOT,"SCORE-%04d-%06d",year,seq);
        DeliveryScoringCalculator.Result delivery = delivery(s);
        CostScoringCalculator.Result cost = cost(s);
        CapacityScoringCalculator.Result capacity=capacity(s);
        DisruptionResult disruption=disruption(s);
        ComplianceScoringCalculator.Result compliance=complianceResult(s);
        Map<String,Object> complianceMetrics=new LinkedHashMap<>(compliance.metrics());complianceMetrics.put("policy","approved-compliance-policy-v1");
        List<Metric> metrics=List.of(new Metric(delivery.score(),delivery.numerator(),delivery.denominator(),delivery.observations(),delivery.eligible(),delivery.excluded(),delivery.status(),delivery.reason(),delivery.metrics()),quality(s),new Metric(cost.score(),cost.numerator(),cost.denominator(),cost.observations(),cost.eligibleObservations(),cost.excludedObservations(),cost.status(),cost.reason(),cost.metrics()),new Metric(capacity.score(),capacity.numerator(),capacity.denominator(),capacity.observations(),capacity.eligibleObservations(),capacity.excludedObservations(),capacity.status(),capacity.reason(),capacity.metrics()),new Metric(compliance.score(),BigDecimal.valueOf(compliance.metrics().get("passedRequirements") instanceof Number n?n.longValue():0),BigDecimal.valueOf(compliance.eligible()),compliance.observations(),compliance.eligible(),compliance.excluded(),compliance.status(),compliance.reason(),complianceMetrics),disruption.metric());
        boolean critical=false;List<String> explanations=new ArrayList<>();List<OverallScoringCalculator.Component> overallComponents=new ArrayList<>();
        for(int i=0;i<metrics.size();i++){Metric m=metrics.get(i);String c=COMPONENTS.get(i);overallComponents.add(new OverallScoringCalculator.Component(c,m.score,WEIGHTS.get(c),m.status));explanations.add(c+"="+(m.score==null?m.status:m.score.toPlainString())+(m.reason==null?"":" ("+m.reason+")"));if(c.equals("COMPLIANCE")&&Boolean.TRUE.equals(m.extra.get("criticalViolation")))critical=true;}
        OverallScoringCalculator.Result overallResult=overallCalculator.calculate(overallComponents,coverageThreshold);
        BigDecimal coverage=overallResult.coverage();BigDecimal overall=overallResult.score();String cl=overallResult.classification();
        Map<String,Object> scoreMetrics=new LinkedHashMap<>();scoreMetrics.put("components",explanations);scoreMetrics.put("availableWeight",overallResult.eligibleWeight());scoreMetrics.put("coverageThreshold",coverageThreshold);scoreMetrics.put("coveragePercentage",coverage);scoreMetrics.put("overallScore",overall);scoreMetrics.put("classification",cl);
        Long scorePk=db.queryForObject("insert into supplier_score(score_id,score_run_id,supplier_id,overall_score,coverage,classification,explanation,critical_compliance_violation,metrics) values (?,?,?,?,?,?,?, ?,cast(? as jsonb)) returning id",Long.class,scoreId,runPk,s.id,overall,coverage,cl,String.join("; ",explanations),critical,json(scoreMetrics));
        for(int i=0;i<metrics.size();i++) {
            Metric m=metrics.get(i);String c=COMPONENTS.get(i);
            Map<String,Object> componentMetrics=new LinkedHashMap<>(m.extra);componentMetrics.put("configuredWeight",WEIGHTS.get(c));componentMetrics.put("coverageWeightContribution","AVAILABLE".equals(m.status)?WEIGHTS.get(c):BigDecimal.ZERO);componentMetrics.put("weightedScoreContribution","AVAILABLE".equals(m.status)?m.score.multiply(WEIGHTS.get(c)):null);
            Long compPk=db.queryForObject("insert into supplier_score_component(supplier_score_id,component,component_score,weight,observation_count,available,missing_reason,explanation,numerator,denominator,eligible_observation_count,excluded_observation_count,component_status,metrics) values (?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb)) returning id",Long.class,scorePk,c,m.score,WEIGHTS.get(c),m.obs,"AVAILABLE".equals(m.status),m.reason,explanation(c,m),m.num,m.den,m.eligible,m.excluded,m.status,json(componentMetrics));
            long excludedAfterCutoff=c.equals("COST")?((Number)m.extra.get("excludedAfterCutoffObservationCount")).longValue():c.equals("CAPACITY")?((Number)m.extra.get("excludedAfterCutoffCommitmentCount")).longValue():c.equals("DISRUPTION")?((Number)m.extra.get("excludedAfterCutoffObservationCount")).longValue():0;
            long excludedInvalid=c.equals("COST")?((Number)m.extra.get("excludedInvalidObservationCount")).longValue():c.equals("CAPACITY")?((Number)m.extra.get("excludedInvalidCommitmentCount")).longValue():c.equals("DISRUPTION")?((Number)m.extra.get("excludedInvalidObservationCount")).longValue():m.excluded;
            db.update("insert into score_run_source_observation_count(score_run_id,supplier_id,source_type,eligible_count,excluded_after_cutoff_count,excluded_invalid_count) values (?,?,?,?,?,?)",runPk,s.id,c,m.eligible,excludedAfterCutoff,excludedInvalid);
            evidenceWriter.persistComponent(runPk,scorePk,compPk,s.id,c,runAsOf,runWindowStart,
                    delivery.evidence(),cost.evidence(),capacity.evidence(),compliance,disruption.calculation().evidence());
        }
    }
    private String explanation(String c,Metric m){if(c.equals("COMPLIANCE"))return "Compliance: "+m.extra.getOrDefault("passedRequirements",0)+" passed of "+m.eligible+" eligible completed checks; missing="+m.extra.getOrDefault("missingEvidence",0)+", legacy evidence availability unknown="+m.extra.getOrDefault("legacyEvidenceUnknownCount",0)+", pending="+m.extra.getOrDefault("pendingRequirements",0)+", expired="+m.extra.getOrDefault("expiredRequirements",0)+", critical violation="+m.extra.getOrDefault("criticalViolation",false)+"; score="+(m.score==null?m.status:m.score)+"."+(m.reason==null?"":" "+m.reason);return c+" score "+(m.score==null?"unavailable":m.score)+"; numerator="+m.num+", denominator="+m.den+", observations="+m.obs+"."+(m.reason==null?"":" "+m.reason);}
    private DeliveryScoringCalculator.Result delivery(Supplier s){
        List<DeliveryScoringCalculator.OrderLine> orders=db.query("""
                select pl.id,po.po_id,pl.line_number,po.status,po.required_delivery_on,pl.ordered_quantity,po.created_at order_created_at,pl.created_at,po.updated_at
                from po_line pl join purchase_order po on po.id=pl.purchase_order_id
                where po.supplier_id=? and po.created_at<=? and pl.created_at<=? order by po.po_id,pl.line_number
                """,(rs,row)->new DeliveryScoringCalculator.OrderLine(rs.getLong("id"),rs.getString("po_id"),
                rs.getInt("line_number"),rs.getString("status"),rs.getDate("required_delivery_on").toLocalDate(),
                rs.getBigDecimal("ordered_quantity"),rs.getTimestamp("order_created_at").toLocalDateTime(),rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),new ArrayList<>()),s.id,TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf));
        Map<Long,List<DeliveryScoringCalculator.ShipmentLine>> shipmentLines=new HashMap<>();
        db.query("""
                select sl.id shipment_line_id,sl.po_line_id,sh.shipment_id,sl.shipped_quantity,sh.created_at shipment_created_at,sh.dispatched_at shipment_dispatched_at,sl.created_at line_created_at
                from shipment_line sl join shipment sh on sh.id=sl.shipment_id
                join po_line pl on pl.id=sl.po_line_id join purchase_order po on po.id=pl.purchase_order_id
                where po.supplier_id=? and po.created_at<=? and sh.created_at<=? and sl.created_at<=?
                order by sl.po_line_id,sh.shipment_id,sl.id
                """,rs->{while(rs.next())shipmentLines.computeIfAbsent(rs.getLong("po_line_id"),k->new ArrayList<>()).add(
                    new DeliveryScoringCalculator.ShipmentLine(rs.getLong("shipment_line_id"),rs.getString("shipment_id"),
                            rs.getBigDecimal("shipped_quantity"),rs.getTimestamp("shipment_created_at").toLocalDateTime(),
                            rs.getTimestamp("shipment_dispatched_at")==null?null:rs.getTimestamp("shipment_dispatched_at").toLocalDateTime(),
                            rs.getTimestamp("line_created_at").toLocalDateTime(),new ArrayList<>()));return null;},
                s.id,TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf));
        Map<String,List<DeliveryScoringCalculator.DeliveryEvent>> events=new HashMap<>();
        db.query("""
                select de.id,de.event_id,de.shipment_id,de.event_type,de.occurred_at,de.recorded_at
                from delivery_event de join shipment sh on sh.id=de.shipment_id
                join purchase_order po on po.id=sh.purchase_order_id
                where po.supplier_id=? and po.created_at<=? and sh.created_at<=?
                  and de.occurred_at<=? and de.recorded_at<=?
                order by de.shipment_id,de.occurred_at,de.id
                """,rs->{while(rs.next())events.computeIfAbsent(rs.getString("shipment_id"),k->new ArrayList<>()).add(
                    new DeliveryScoringCalculator.DeliveryEvent(rs.getLong("id"),rs.getString("event_type"),
                            rs.getTimestamp("occurred_at").toLocalDateTime(),rs.getTimestamp("recorded_at").toLocalDateTime()));return null;},
                s.id,TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf));
        Map<String,List<DeliveryScoringCalculator.DeliveryEvent>> eventSnapshot=events;
        List<DeliveryScoringCalculator.OrderLine> populated=orders.stream().map(order->{
            List<DeliveryScoringCalculator.ShipmentLine> lines=shipmentLines.getOrDefault(order.id(),List.of()).stream().map(line->
                    new DeliveryScoringCalculator.ShipmentLine(line.id(),line.shipmentId(),line.quantity(),line.shipmentCreatedAt(),line.shipmentDispatchedAt(),line.lineCreatedAt(),
                            eventSnapshot.getOrDefault(line.shipmentId(),List.of()))).toList();
            return new DeliveryScoringCalculator.OrderLine(order.id(),order.businessId(),order.lineNumber(),order.status(),
                    order.requiredDeliveryOn(),order.orderedQuantity(),order.orderCreatedAt(),order.createdAt(),order.statusUpdatedAt(),lines);
        }).toList();
        return deliveryCalculator.calculate(populated,runWindowStart.toLocalDate(),runAsOf,Math.toIntExact(deliveryMin));
    }
    private Metric quality(Supplier s){return aggregate(s,"QUALITY",QUALITY_AGGREGATE_SQL);}
    private CostScoringCalculator.Result cost(Supplier s){
      List<CostScoringCalculator.Line> lines=db.query("""
          select il.id invoice_line_id,inv.invoice_id,inv.source_reference,inv.purchase_order_id invoice_po_id,inv.supplier_id invoice_supplier_id,
                 inv.invoice_date,inv.currency invoice_currency,inv.subtotal,inv.tax_amount,inv.freight_amount,inv.total_amount,inv.status,
                 inv.created_at invoice_created_at,inv.updated_at invoice_updated_at,
                 il.purchase_order_id line_po_id,pl.id po_line_id,pl.purchase_order_id po_line_po_id,po.supplier_id po_supplier_id,
                 po.currency po_currency,pl.product_code,pl.unit_of_measure,pl.ordered_quantity,pl.unit_price po_unit_price,
                 il.quantity invoice_quantity,il.unit_price invoice_unit_price,il.line_amount,po.created_at po_created_at,
                 pl.created_at po_line_created_at,il.created_at invoice_line_created_at
          from invoice_line il join invoice inv on inv.id=il.invoice_id
          join purchase_order po on po.id=inv.purchase_order_id
          join po_line pl on pl.id=il.po_line_id
          where inv.supplier_id=?
          order by pl.id,inv.invoice_date,inv.invoice_id,il.id
          """,(rs,row)->new CostScoringCalculator.Line(rs.getLong("invoice_line_id"),rs.getString("invoice_id"),rs.getString("source_reference"),
              rs.getLong("invoice_po_id"),rs.getLong("invoice_supplier_id"),rs.getDate("invoice_date").toLocalDate(),rs.getString("invoice_currency"),
              rs.getBigDecimal("subtotal"),rs.getBigDecimal("tax_amount"),rs.getBigDecimal("freight_amount"),rs.getBigDecimal("total_amount"),rs.getString("status"),
              rs.getTimestamp("invoice_created_at").toLocalDateTime(),rs.getTimestamp("invoice_updated_at").toLocalDateTime(),rs.getLong("line_po_id"),
              rs.getLong("po_line_id"),rs.getLong("po_line_po_id"),rs.getLong("po_supplier_id"),rs.getString("po_currency"),rs.getString("product_code"),
              rs.getString("unit_of_measure"),rs.getBigDecimal("ordered_quantity"),rs.getBigDecimal("po_unit_price"),rs.getBigDecimal("invoice_quantity"),
              rs.getBigDecimal("invoice_unit_price"),rs.getBigDecimal("line_amount"),rs.getTimestamp("po_created_at").toLocalDateTime(),
              rs.getTimestamp("po_line_created_at").toLocalDateTime(),rs.getTimestamp("invoice_line_created_at").toLocalDateTime()),s.id);
      return costCalculator.calculate(lines,runWindowStart.toLocalDate(),runAsOf.toLocalDate(),runAsOf,costMin);
    }
    private CapacityScoringCalculator.Result capacity(Supplier s){
      List<CapacityScoringCalculator.Commitment> commitments=db.query("""
          select c.id,c.commitment_id,c.supplier_id,c.site_id,site.supplier_id site_owner_supplier_id,c.product_code,c.unit_of_measure,
                 c.period_start,c.period_end,c.committed_quantity,c.created_at,c.recorded_at
          from capacity_commitment c left join site on site.id=c.site_id
          where c.supplier_id=? and c.created_at<=? and c.recorded_at<=?
          order by c.commitment_id
          """,(rs,row)->new CapacityScoringCalculator.Commitment(rs.getLong("id"),rs.getString("commitment_id"),rs.getLong("supplier_id"),
              rs.getObject("site_id",Long.class),rs.getObject("site_owner_supplier_id",Long.class),rs.getString("product_code"),rs.getString("unit_of_measure"),
              rs.getDate("period_start").toLocalDate(),rs.getDate("period_end").toLocalDate(),rs.getBigDecimal("committed_quantity"),
              rs.getObject("created_at",java.time.OffsetDateTime.class).toInstant(),rs.getObject("recorded_at",java.time.OffsetDateTime.class).toInstant()),
          s.id,TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf));
      List<CapacityScoringCalculator.ReceiptLine> receipts=db.query("""
          select rl.id receipt_line_id,r.receipt_id,r.supplier_id receipt_supplier_id,r.site_id receipt_site_id,r.shipment_id receipt_shipment_id,rl.shipment_id receipt_line_shipment_id,
                 r.received_at,r.created_at receipt_created_at,sh.id shipment_id,sh.supplier_id shipment_supplier_id,sh.site_id shipment_site_id,
                 sh.purchase_order_id shipment_po_id,sh.created_at shipment_created_at,sl.id resolved_shipment_line_id,
                 sl.shipment_id shipment_line_shipment_id,sl.purchase_order_id shipment_line_po_id,sl.po_line_id,
                 sl.shipped_quantity,sl.created_at shipment_line_created_at,po.po_id,po.supplier_id po_supplier_id,po.site_id po_site_id,
                 pl.purchase_order_id po_line_po_id,pl.product_code,pl.unit_of_measure,po.created_at po_created_at,
                 pl.created_at po_line_created_at,rl.received_quantity,rl.created_at receipt_line_created_at
          from receipt_line rl join receipt r on r.id=rl.receipt_id
          left join shipment sh on sh.id=r.shipment_id
          left join shipment_line sl on sl.id=rl.shipment_line_id
          left join po_line pl on pl.id=sl.po_line_id
          left join purchase_order po on po.id=pl.purchase_order_id
          where r.supplier_id=?
            and r.created_at<=? and r.received_at<=? and rl.created_at<=?
            and (sh.created_at is null or sh.created_at<=?)
            and (sl.created_at is null or sl.created_at<=?)
            and (po.created_at is null or po.created_at<=?)
            and (pl.created_at is null or pl.created_at<=?)
          order by r.received_at,r.receipt_id,rl.id
          """,(rs,row)->new CapacityScoringCalculator.ReceiptLine(rs.getLong("receipt_line_id"),rs.getString("receipt_id"),
              rs.getLong("receipt_supplier_id"),rs.getObject("receipt_site_id",Long.class),rs.getLong("receipt_shipment_id"),rs.getLong("receipt_line_shipment_id"),
              rs.getObject("received_at",java.time.OffsetDateTime.class).toInstant(),rs.getObject("receipt_created_at",java.time.OffsetDateTime.class).toInstant(),
              rs.getObject("shipment_id",Long.class)==null?-1:rs.getLong("shipment_id"),rs.getObject("shipment_supplier_id",Long.class)==null?-1:rs.getLong("shipment_supplier_id"),
              rs.getObject("shipment_site_id",Long.class),rs.getObject("shipment_po_id",Long.class),rs.getObject("shipment_created_at",java.time.OffsetDateTime.class)==null?null:rs.getObject("shipment_created_at",java.time.OffsetDateTime.class).toInstant(),
              rs.getObject("resolved_shipment_line_id",Long.class),rs.getObject("shipment_line_shipment_id",Long.class),rs.getObject("shipment_line_po_id",Long.class),
              rs.getObject("po_line_id",Long.class),rs.getBigDecimal("shipped_quantity"),rs.getObject("shipment_line_created_at",java.time.OffsetDateTime.class)==null?null:rs.getObject("shipment_line_created_at",java.time.OffsetDateTime.class).toInstant(),
              rs.getString("po_id"),rs.getObject("po_supplier_id",Long.class)==null?-1:rs.getLong("po_supplier_id"),rs.getObject("po_site_id",Long.class),
              rs.getObject("po_line_po_id",Long.class),rs.getString("product_code"),rs.getString("unit_of_measure"),
              rs.getObject("po_created_at",java.time.OffsetDateTime.class)==null?null:rs.getObject("po_created_at",java.time.OffsetDateTime.class).toInstant(),
              rs.getObject("po_line_created_at",java.time.OffsetDateTime.class)==null?null:rs.getObject("po_line_created_at",java.time.OffsetDateTime.class).toInstant(),
              rs.getBigDecimal("received_quantity"),rs.getObject("receipt_line_created_at",java.time.OffsetDateTime.class).toInstant()),
          s.id,TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),
          TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf));
      return capacityCalculator.calculate(s.id,commitments,receipts,runWindowStart.toLocalDate(),runAsOf.toInstant(ZoneOffset.UTC),capacityMin);
    }
    private ComplianceScoringCalculator.Result complianceResult(Supplier supplier) {
        List<ComplianceScoringCalculator.Observation> observations=db.query("""
          WITH policy_versions AS (
            SELECT p.*,row_number() over(partition by p.requirement_id,p.scope_type order by p.recorded_at desc,p.id desc) version_rank
            FROM compliance_requirement_policy_history p WHERE p.recorded_at<=?
          ), policy AS (SELECT * FROM policy_versions WHERE version_rank=1 AND active AND effective_from<=?::date AND (effective_to IS NULL OR effective_to>=?::date)),
          supplier_slots AS (
            SELECT p.requirement_id,p.applicability_code,p.scope_type,p.mandatory,p.critical,p.active,p.effective_from,p.effective_to,p.recorded_at,NULL::bigint site_id,sr.id applicability_id
            FROM policy p JOIN compliance_scope_history h ON h.supplier_id=? AND h.scope_type='SUPPLIER' AND h.active AND h.recorded_at<=?
            JOIN compliance_scope_requirement sr ON sr.scope_history_id=h.id AND sr.requirement_id=p.requirement_id
            WHERE p.scope_type='SUPPLIER' AND p.active
              AND h.id=(SELECT h2.id FROM compliance_scope_history h2 WHERE h2.supplier_id=h.supplier_id AND h2.scope_type='SUPPLIER' AND h2.recorded_at<=? ORDER BY h2.recorded_at DESC,h2.id DESC LIMIT 1)
          ),
          site_latest AS (
            SELECT DISTINCT ON (h.supplier_id,h.site_id) h.supplier_id,h.site_id,h.active,h.id scope_id,h.effective_from,h.effective_to
            FROM compliance_scope_history h WHERE h.supplier_id=? AND h.scope_type='SITE' AND h.recorded_at<=?
            ORDER BY h.supplier_id,h.site_id,h.recorded_at DESC,h.id DESC
          ),
          site_slots AS (
            SELECT p.requirement_id,p.applicability_code,p.scope_type,p.mandatory,p.critical,p.active,p.effective_from,p.effective_to,p.recorded_at,sl.site_id,sr.id applicability_id
            FROM policy p CROSS JOIN site_latest sl JOIN compliance_scope_requirement sr ON sr.scope_history_id=sl.scope_id AND sr.requirement_id=p.requirement_id WHERE p.scope_type='SITE' AND p.active AND sl.active AND sl.effective_from<=?::date AND (sl.effective_to IS NULL OR sl.effective_to>=?::date)
          ), slots AS (SELECT * FROM supplier_slots UNION ALL SELECT * FROM site_slots)
          SELECT sl.requirement_id,r.requirement_code,sl.scope_type,sl.site_id,sl.active,sl.effective_from,sl.effective_to,sl.recorded_at,
                 a.assessment_id,a.business_id,a.status,a.assessed_at,a.effective_from,a.expires_on,a.recorded_at,a.status_recorded_at,
                 cc.check_id,cc.result,cc.checked_at,cc.recorded_at,
                 NULL::varchar,NULL::timestamptz,NULL::timestamptz,FALSE,sl.mandatory,sl.critical,sl.applicability_id,ev.item_states::text
          FROM slots sl JOIN compliance_requirement r ON r.id=sl.requirement_id
          LEFT JOIN LATERAL (
            SELECT ah.assessment_id,aa.assessment_id business_id,sh.new_status status,ah.assessed_at,ah.effective_from,ah.expires_on,ah.recorded_at,sh.recorded_at status_recorded_at
            FROM compliance_assessment_scoring_history ah JOIN compliance_assessment aa ON aa.id=ah.assessment_id
            LEFT JOIN LATERAL (SELECT h.new_status,h.recorded_at FROM compliance_assessment_status_history h WHERE h.assessment_id=ah.assessment_id AND h.recorded_at<=? ORDER BY h.recorded_at DESC,h.id DESC LIMIT 1) sh ON TRUE
            WHERE ah.supplier_id=? AND ah.site_id IS NOT DISTINCT FROM sl.site_id AND ah.recorded_at<=? AND ah.assessed_at<=?
            ORDER BY ah.assessed_at DESC,ah.recorded_at DESC,ah.assessment_id DESC,ah.id DESC LIMIT 1
          ) a ON TRUE
          LEFT JOIN LATERAL (
            SELECT ch.check_id,ch.result,ch.checked_at,ch.recorded_at FROM compliance_check_scoring_history ch
            WHERE ch.assessment_id=a.assessment_id AND ch.requirement_id=sl.requirement_id AND ch.recorded_at<=? AND ch.checked_at<=?
            ORDER BY ch.checked_at DESC,ch.recorded_at DESC,ch.check_id DESC,ch.id DESC LIMIT 1
          ) cc ON TRUE
          LEFT JOIN LATERAL (
            SELECT COALESCE(jsonb_agg(jsonb_build_object('itemId',i.id,'itemKey',i.item_key,'eventId',latest.id,'eventKey',latest.event_key,'eventType',latest.event_type,'recordedAt',latest.recorded_at,'availability',COALESCE(latest.event_type,CASE WHEN i.legacy_untracked THEN 'LEGACY_AVAILABILITY_UNKNOWN' ELSE 'MISSING' END),'legacyUntracked',i.legacy_untracked,'reference',i.evidence_reference) ORDER BY i.id),'[]'::jsonb) item_states
            FROM compliance_evidence_item i
            LEFT JOIN LATERAL (SELECT e.id,e.event_key,e.event_type,e.recorded_at FROM compliance_evidence_event e WHERE e.evidence_item_id=i.id AND e.recorded_at<=? ORDER BY e.recorded_at DESC,e.id DESC LIMIT 1) latest ON TRUE
            WHERE i.created_at<=? AND (i.assessment_id=a.assessment_id OR i.check_id=cc.check_id)
          ) ev ON TRUE
          ORDER BY sl.requirement_id,sl.scope_type,sl.site_id NULLS FIRST
          """,(rs,n)->new ComplianceScoringCalculator.Observation(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getObject(4,Long.class),rs.getBoolean(5),rs.getDate(6).toLocalDate(),rs.getDate(7)==null?null:rs.getDate(7).toLocalDate(),rs.getObject(8,java.time.OffsetDateTime.class).toLocalDateTime(),rs.getObject(9,Long.class),rs.getString(10),rs.getString(11),rs.getObject(12,java.time.OffsetDateTime.class)==null?null:rs.getObject(12,java.time.OffsetDateTime.class).toLocalDateTime(),rs.getDate(13)==null?null:rs.getDate(13).toLocalDate(),rs.getDate(14)==null?null:rs.getDate(14).toLocalDate(),rs.getObject(15,java.time.OffsetDateTime.class)==null?null:rs.getObject(15,java.time.OffsetDateTime.class).toLocalDateTime(),rs.getObject(16,java.time.OffsetDateTime.class)==null?null:rs.getObject(16,java.time.OffsetDateTime.class).toLocalDateTime(),rs.getObject(17,Long.class),rs.getString(18),rs.getObject(19,java.time.OffsetDateTime.class)==null?null:rs.getObject(19,java.time.OffsetDateTime.class).toLocalDateTime(),rs.getObject(20,java.time.OffsetDateTime.class)==null?null:rs.getObject(20,java.time.OffsetDateTime.class).toLocalDateTime(),null,null,null,false,rs.getBoolean(24),rs.getBoolean(25),rs.getObject(26,Long.class),evidenceStates(rs.getString(27))),
            TimestampCompat.of(runAsOf),runAsOf.toLocalDate(),runAsOf.toLocalDate(),supplier.id,TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),supplier.id,TimestampCompat.of(runAsOf),runAsOf.toLocalDate(),runAsOf.toLocalDate(),TimestampCompat.of(runAsOf),supplier.id,TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf),TimestampCompat.of(runAsOf));
        return new ComplianceScoringCalculator().calculate(observations,runAsOf,runWindowStart.toLocalDate(),complianceMin);
    }
    private List<ComplianceScoringCalculator.EvidenceItemState> evidenceStates(String raw){try{if(raw==null)return List.of();List<ComplianceScoringCalculator.EvidenceItemState> states=new ArrayList<>();for(JsonNode n:json.readTree(raw)){states.add(new ComplianceScoringCalculator.EvidenceItemState(n.path("itemId").asLong(),n.path("itemKey").asText(),n.path("eventId").isNull()?null:n.path("eventId").asLong(),n.path("eventKey").isNull()?null:n.path("eventKey").asText(),n.path("eventType").isNull()?null:n.path("eventType").asText(),n.path("recordedAt").isNull()?null:java.time.OffsetDateTime.parse(n.path("recordedAt").asText()).toLocalDateTime(),n.path("availability").asText(),n.path("legacyUntracked").asBoolean(),n.path("reference").asText()));}return List.copyOf(states);}catch(Exception ex){throw new IllegalStateException("Invalid compliance evidence item state JSON",ex);}}
    private DisruptionResult disruption(Supplier supplier){
      List<DisruptionScoringCalculator.Signal> signals=db.query("""
          select id,risk_id,source,source_reference,severity,starts_at,ends_at,recorded_at
          from risk_signal where supplier_id=? and recorded_at<=?
          order by source,source_reference nulls last,risk_id,id
          """,(rs,row)->new DisruptionScoringCalculator.Signal(rs.getLong("id"),rs.getString("risk_id"),
          rs.getString("source"),rs.getString("source_reference"),rs.getString("severity"),
          rs.getObject("starts_at",java.time.OffsetDateTime.class).toLocalDateTime(),
          rs.getObject("ends_at",java.time.OffsetDateTime.class)==null?null:rs.getObject("ends_at",java.time.OffsetDateTime.class).toLocalDateTime(),
          rs.getObject("recorded_at",java.time.OffsetDateTime.class).toLocalDateTime()),supplier.id,TimestampCompat.of(runAsOf));
      DisruptionScoringCalculator.Result result=new DisruptionScoringCalculator().calculate(signals,runWindowStart,runAsOf,exposureScale,90);
      Map<String,Object> metrics=new LinkedHashMap<>();metrics.put("totalExposure",result.exposure());metrics.put("exposureScale",result.exposureScale());
      metrics.put("eligibleSignalCount",result.eligibleObservations());metrics.put("excludedSignalCount",result.excludedObservations());
      long invalidCount=result.evidence().stream().filter(e->e.exclusionReason()!=null&&(e.exclusionReason().startsWith("INVALID_")||e.exclusionReason().startsWith("MISSING_"))).count();
      long afterCutoffCount=result.evidence().stream().filter(e->"AFTER_AS_OF".equals(e.exclusionReason())).count();
      Map<String,Long> exclusions=new TreeMap<>();result.evidence().stream().map(DisruptionScoringCalculator.Evidence::exclusionReason)
          .filter(Objects::nonNull).forEach(reason->exclusions.merge(reason,1L,Long::sum));
      metrics.put("excludedInvalidObservationCount",invalidCount);metrics.put("excludedAfterCutoffObservationCount",afterCutoffCount);
      metrics.put("exclusionReasonCounts",exclusions);
      metrics.put("severityWeights",Map.of("INFO",bd("0.25"),"LOW",bd("0.5"),"MEDIUM",bd("1"),"HIGH",bd("2"),"CRITICAL",bd("4")));
      metrics.put("recencyHalfLifeDays",90);
      Metric metric=new Metric(result.score(),result.exposure(),result.exposureScale(),result.observations(),
          result.eligibleObservations(),result.excludedObservations(),result.status(),result.reason(),metrics);
      return new DisruptionResult(metric,result);
    }
    private Metric aggregate(Supplier s,String component,String sql){return aggregate(s,component,sql,0,null,null,false);}
    private Metric aggregate(Supplier s,String component,String sql,long min,BigDecimal minQty,String unused,boolean special){
      // Stable historical cutoff: event dates and record creation dates are both bounded by the persisted run window.
      // Every source query uses the immutable cutoff captured from this score-run snapshot.
      LocalDateTime asOf=runAsOf, from=runWindowStart; List<Object> args=new ArrayList<>();
      if(component.equals("DELIVERY")){for(int i=0;i<3;i++)args.add(asOf.toLocalDate());for(int i=0;i<4;i++)args.add(TimestampCompat.of(asOf));args.add(s.id);args.add(TimestampCompat.of(asOf));}
      else if(component.equals("QUALITY")){args.addAll(qualityQueryArguments(s.id,from,asOf));}
      else if(component.equals("COST")){args.add(s.id);args.add(from.toLocalDate());args.add(asOf.toLocalDate());args.add(TimestampCompat.of(asOf));}
      else if(component.equals("CAPACITY")){args.add(TimestampCompat.of(asOf));args.add(s.id);args.add(TimestampCompat.of(asOf));args.add(from.toLocalDate());args.add(asOf.toLocalDate());}
      else if(component.equals("COMPLIANCE")){args.add(s.id);args.add(asOf.toLocalDate());args.add(from.toLocalDate());}
      else {args.add(TimestampCompat.of(asOf));args.add(TimestampCompat.of(asOf));args.add(TimestampCompat.of(from));args.add(TimestampCompat.of(asOf));args.add(s.id);args.add(TimestampCompat.of(asOf));args.add(TimestampCompat.of(asOf));args.add(TimestampCompat.of(asOf));args.add(TimestampCompat.of(from));}
      List<Object> values=db.query(sql,rs->{if(!rs.next())return List.of();List<Object> columns=new ArrayList<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)columns.add(rs.getObject(i));return columns;},args.toArray());
      long obs=((Number)values.get(0)).longValue(),eligible=obs,excluded=0;BigDecimal num=values.size()>1?new BigDecimal(values.get(values.size()-2).toString()):BigDecimal.ZERO,den=values.size()>2?new BigDecimal(values.get(values.size()-1).toString()):BigDecimal.ZERO;
      String status="AVAILABLE",reason=null;BigDecimal result=null;
      if(component.equals("DELIVERY")){eligible=((Number)values.get(1)).longValue();num=new BigDecimal(values.get(2).toString());den=BigDecimal.valueOf(eligible);obs=((Number)values.get(0)).longValue();}
      else if(component.equals("QUALITY")){obs=((Number)values.get(0)).longValue();eligible=((Number)values.get(1)).longValue();num=new BigDecimal(values.get(2).toString());den=new BigDecimal(values.get(3).toString());}
      else if(component.equals("COST")){obs=((Number)values.get(0)).longValue();eligible=((Number)values.get(1)).longValue();num=new BigDecimal(values.get(2).toString());den=new BigDecimal(values.get(3).toString());}
      else if(component.equals("CAPACITY")){obs=((Number)values.get(0)).longValue();eligible=((Number)values.get(1)).longValue();num=new BigDecimal(values.get(2).toString());den=new BigDecimal(values.get(3).toString());}
      else if(component.equals("COMPLIANCE")){obs=((Number)values.get(0)).longValue();eligible=((Number)values.get(1)).longValue();num=new BigDecimal(values.get(2).toString());den=BigDecimal.valueOf(eligible);}
      else {obs=((Number)values.get(0)).longValue();eligible=obs;den=new BigDecimal(exposureScale.toString());num=new BigDecimal(values.get(1).toString());}
      long minimum=switch(component){case "DELIVERY"->deliveryMin;case "QUALITY"->inspectionMin;case "COST"->costMin;case "CAPACITY"->capacityMin;case "COMPLIANCE"->complianceMin;default->0;};
      if(component.equals("DISRUPTION")){result=BigDecimal.valueOf(100).multiply(BigDecimal.ONE.subtract(num.divide(exposureScale,8,RoundingMode.HALF_UP))).max(BigDecimal.ZERO);if(obs==0){status="AVAILABLE";reason="No risk signals found in available source data";}}
      else if(eligible<minimum||(component.equals("QUALITY")&&den.compareTo(qualityMin)<0)){status=obs==0?"MISSING_DATA":"INSUFFICIENT_OBSERVATIONS";reason="Eligible observations below configured minimum ("+minimum+")";}
      else if(den.signum()==0){status="MISSING_DATA";reason="Zero eligible denominator";}
      else if(component.equals("COST")){result=num.divide(den,8,RoundingMode.HALF_UP);}
      else result=num.multiply(BigDecimal.valueOf(100)).divide(den,8,RoundingMode.HALF_UP);
      if(result!=null)result=result.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).setScale(2,RoundingMode.HALF_UP);
      Map<String,Object> extra=new LinkedHashMap<>();extra.put("numerator",num);extra.put("denominator",den);extra.put("eligibleObservations",eligible);extra.put("excludedObservations",excluded);
      if(component.equals("QUALITY")){extra.put("inspectedQuantity",den);extra.put("acceptedQuantity",num);extra.put("rejectedQuantity",values.size()>4?values.get(4):BigDecimal.ZERO);extra.put("acceptanceRate",den.signum()==0?null:num.multiply(BigDecimal.valueOf(100)).divide(den,2,RoundingMode.HALF_UP));extra.put("rejectionRate",den.signum()==0?null:new BigDecimal(values.get(4).toString()).multiply(BigDecimal.valueOf(100)).divide(den,2,RoundingMode.HALF_UP));}
      if(component.equals("DELIVERY")){extra.put("overdueOutstandingQuantity",values.get(3));extra.put("cancelledLineCount",values.get(5));}
      if(component.equals("COMPLIANCE"))extra.put("criticalViolation",values.size()>3&&((Number)values.get(3)).longValue()>0);
      return new Metric(result,num,den,obs,eligible,excluded,status,reason,extra);
    }
    static List<Object> qualityQueryArguments(long supplierId,LocalDateTime windowStart,LocalDateTime asOf){
      return List.of(supplierId,TimestampCompat.of(windowStart),TimestampCompat.of(asOf),TimestampCompat.of(asOf),
          TimestampCompat.of(asOf),TimestampCompat.of(asOf),TimestampCompat.of(asOf));
    }
    public Map<String,Object> run(String id){return one("select run_id as \"runId\",run_status as status,algorithm_version as \"algorithmVersion\",as_of as \"asOf\",window_start as \"windowStart\",started_at as \"startedAt\",completed_at as \"completedAt\",failure_reason as \"failureReason\",duration_millis as \"durationMillis\" from score_run where run_id=?",id);}
    public Map<String,Object> summary(String id){var m=run(id);Long pk=pk(id);m.put("totalSuppliers",db.queryForObject("select count(*) from supplier_score where score_run_id=?",Long.class,pk));m.put("validScores",db.queryForObject("select count(*) from supplier_score where score_run_id=? and overall_score is not null",Long.class,pk));m.put("insufficientData",db.queryForObject("select count(*) from supplier_score where score_run_id=? and overall_score is null",Long.class,pk));m.put("averageScore",db.queryForObject("select avg(overall_score) from supplier_score where score_run_id=?",BigDecimal.class,pk));m.put("classifications",db.query("select classification,count(*) count from supplier_score where score_run_id=? group by classification order by classification",(r,n)->Map.of("classification",r.getString(1),"count",r.getLong(2)),pk));m.put("componentStatusCounts",db.query("select c.component,c.component_status,count(*) from supplier_score_component c join supplier_score s on s.id=c.supplier_score_id where s.score_run_id=? group by c.component,c.component_status order by c.component,c.component_status",(r,n)->Map.of("component",r.getString(1),"status",r.getString(2),"count",r.getLong(3)),pk));m.put("criticalComplianceCount",db.queryForObject("select count(*) from supplier_score where score_run_id=? and critical_compliance_violation",Long.class,pk));return m;}
    public Map<String,Object> runs(int page,int size){int p=Math.max(0,page),n=Math.min(100,Math.max(1,size));return page("select run_id as \"runId\",run_status as status,algorithm_version as \"algorithmVersion\",as_of as \"asOf\",started_at as \"startedAt\",completed_at as \"completedAt\" from score_run order by created_at desc limit ? offset ?",p,n,n,p*n,"select count(*) from score_run");}
    public Map<String,Object> scores(String id,int page,int size,String cls){return scores(id,page,size,cls,null,null,null,null,null,"score");}
    public Map<String,Object> scores(String id,int page,int size,String cls,BigDecimal minimum,BigDecimal maximum,String componentStatus,Boolean critical,String supplierQuery,String sort){
        if(page<0||size<1||minimum!=null&&(minimum.signum()<0||minimum.compareTo(BigDecimal.valueOf(100))>0)||maximum!=null&&(maximum.signum()<0||maximum.compareTo(BigDecimal.valueOf(100))>0)||minimum!=null&&maximum!=null&&minimum.compareTo(maximum)>0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid score filter or pagination values");
        if(componentStatus!=null&&!componentStatus.isBlank()&&!Set.of("AVAILABLE","INSUFFICIENT_OBSERVATIONS","MISSING_DATA","NOT_APPLICABLE","EXCLUDED_INVALID_DATA").contains(componentStatus))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported component status");
        int p=page,n=Math.min(100,size);long runPk=pk(id);StringBuilder where=new StringBuilder(" where ss.score_run_id=?");List<Object> args=new ArrayList<>();args.add(runPk);
        if(cls!=null&&!cls.isBlank()){where.append(" and ss.classification=?");args.add(cls);}
        if(minimum!=null){where.append(" and ss.overall_score>=?");args.add(minimum);}
        if(maximum!=null){where.append(" and ss.overall_score<=?");args.add(maximum);}
        if(critical!=null){where.append(" and ss.critical_compliance_violation=?");args.add(critical);}
        if(componentStatus!=null&&!componentStatus.isBlank()){where.append(" and exists(select 1 from supplier_score_component fc where fc.supplier_score_id=ss.id and fc.component_status=?)");args.add(componentStatus);}
        if(supplierQuery!=null&&!supplierQuery.isBlank()){where.append(" and (lower(s.supplier_id) like ? or lower(s.name) like ?)");args.add("%"+supplierQuery.toLowerCase(Locale.ROOT)+"%");args.add("%"+supplierQuery.toLowerCase(Locale.ROOT)+"%");}
        String order=switch(Objects.toString(sort,"score")){case "score"->"ss.overall_score desc nulls last,s.supplier_id";case "scoreAsc"->"ss.overall_score asc nulls last,s.supplier_id";case "supplierId"->"s.supplier_id";case "name"->"s.name,s.supplier_id";case "coverage"->"ss.coverage desc,s.supplier_id";case "classification"->"ss.classification,s.supplier_id";default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported score sort");};
        String base="select ss.score_id as \"scoreId\",s.supplier_id as \"supplierId\",s.name,ss.overall_score as score,ss.coverage,ss.classification,ss.critical_compliance_violation as \"criticalComplianceViolation\",ss.explanation from supplier_score ss join supplier s on s.id=ss.supplier_id"+where;
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(n);pageArgs.add((long)p*n);var data=db.queryForList(base+" order by "+order+" limit ? offset ?",pageArgs.toArray());Long total=db.queryForObject("select count(*) from supplier_score ss join supplier s on s.id=ss.supplier_id"+where,Long.class,args.toArray());
        return Map.of("content",data,"page",p,"size",n,"totalElements",total,"totalPages",(total+n-1)/n);
    }
    public Map<String,Object> score(String id,String supplier){Map<String,Object> m=one("select ss.score_id as \"scoreId\",s.supplier_id as \"supplierId\",s.name,ss.overall_score as score,ss.coverage,ss.classification,ss.critical_compliance_violation as \"criticalComplianceViolation\",ss.explanation from supplier_score ss join supplier s on s.id=ss.supplier_id join score_run r on r.id=ss.score_run_id where r.run_id=? and s.supplier_id=?",id,supplier);m.put("components",components(id,supplier));return m;}
    public List<Map<String,Object>> components(String id,String supplier){return db.queryForList("select c.component,c.component_score as score,c.weight,c.numerator,c.denominator,c.observation_count as \"observationCount\",c.eligible_observation_count as \"eligibleObservationCount\",c.excluded_observation_count as \"excludedObservationCount\",c.component_status as status,c.missing_reason as \"missingReason\",c.explanation,c.metrics from supplier_score_component c join supplier_score s on s.id=c.supplier_score_id join score_run r on r.id=s.score_run_id join supplier p on p.id=s.supplier_id where r.run_id=? and p.supplier_id=? order by c.component",id,supplier);}
    public Map<String,Object> evidence(String id,String supplier,String component,Boolean included,String sourceType,String sort,int page,int size){
        if(page<0||size<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"page must be nonnegative and size must be positive");
        int p=page,n=Math.min(100,size);long runPk=requireRun(id);
        List<Long> supplierRows=db.query("select id from supplier where supplier_id=?",(rs,row)->rs.getLong(1),supplier);
        if(supplierRows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Supplier was not found");
        long supplierPk=supplierRows.getFirst();
        Long scorePk=db.query("select ss.id from supplier_score ss where ss.score_run_id=? and ss.supplier_id=?",rs->rs.next()?rs.getLong(1):null,runPk,supplierPk);
        if(scorePk==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Supplier score was not found for this run");
        String c=component==null?null:component.toUpperCase(Locale.ROOT);
        if(c!=null&&!COMPONENTS.contains(c))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown score component: "+component);
        String source=sourceType==null?null:sourceType.toUpperCase(Locale.ROOT);
        if(source!=null&&!Set.of("PO_LINE","INSPECTION_LINE","INVOICE_LINE","CAPACITY_COMMITMENT","RECEIPT_LINE","COMPLIANCE_CHECK","RISK_SIGNAL").contains(source))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown source entity type: "+sourceType);
        String[] sortParts=sort==null?new String[0]:sort.split(",",-1);String field=sortParts.length>0?sortParts[0]:"";String direction=sortParts.length>1?sortParts[1].toLowerCase(Locale.ROOT):"asc";
        String order=switch(field){case "evaluatedAt"->"e.evaluated_at";case "sourceBusinessId"->"e.source_business_id";case "contribution"->"e.contribution";case "sourceEntityType"->"e.source_entity_type";default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported evidence sort field");};
        if(!direction.equals("asc")&&!direction.equals("desc"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Evidence sort direction must be asc or desc");
        String where="e.score_run_id=? AND e.supplier_id=? AND (CAST(? AS varchar) IS NULL OR e.component=CAST(? AS varchar)) AND (CAST(? AS boolean) IS NULL OR e.included=CAST(? AS boolean)) AND (CAST(? AS varchar) IS NULL OR e.source_entity_type=CAST(? AS varchar))";
        Object[] filterArgs={runPk,supplierPk,c,c,included,included,source,source};
        String select="select r.run_id,s.supplier_id,e.component,e.source_entity_type,e.source_business_id,e.source_record_id,e.included,e.contribution,e.exclusion_reason,e.evaluated_at,e.details::text from score_evidence e join score_run r on r.id=e.score_run_id join supplier s on s.id=e.supplier_id where "+where;
        List<ScoreEvidenceResponse> content=db.query(select+" order by "+order+" "+direction+",e.id asc limit ? offset ?",(rs,row)->{
            JsonNode details;try{details=json.readTree(rs.getString(11));}catch(Exception ex){throw new IllegalStateException("Stored score evidence details are invalid JSON",ex);}
            return ScoreEvidenceResponse.from(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getBoolean(7),rs.getBigDecimal(8),rs.getString(9),rs.getObject(10,java.time.OffsetDateTime.class),details);
        },append(filterArgs,n,p*n));
        Long total=db.queryForObject("select count(*) from score_evidence e where "+where,Long.class,filterArgs);
        return Map.of("content",content,"page",p,"size",n,"totalElements",total,"totalPages",(total+n-1)/n);
    }
    private Object[] append(Object[] args,Object... tail){Object[] all=Arrays.copyOf(args,args.length+tail.length);System.arraycopy(tail,0,all,args.length,tail.length);return all;}
    private long requireRun(String runId){List<Long> rows=db.query("select id from score_run where run_id=?",(rs,row)->rs.getLong(1),runId);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Score run was not found");return rows.getFirst();}
    public Map<String,Object> history(String supplier,int page,int size){int p=Math.max(0,page),n=Math.min(100,Math.max(1,size));return page("select r.run_id as \"runId\",r.algorithm_version as \"algorithmVersion\",r.as_of as \"asOf\",ss.overall_score as score,ss.coverage,ss.classification from supplier_score ss join supplier s on s.id=ss.supplier_id join score_run r on r.id=ss.score_run_id where s.supplier_id=? and r.run_status='COMPLETED' order by r.as_of desc limit ? offset ?",supplier,p,n,n,p*n,"select count(*) from supplier_score ss join supplier s on s.id=ss.supplier_id join score_run r on r.id=ss.score_run_id where s.supplier_id=? and r.run_status='COMPLETED'");}
    private Map<String,Object> one(String q,Object... a){var x=db.queryForList(q,a);if(x.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found");return new LinkedHashMap<>(x.getFirst());}
    private Map<String,Object> page(String q,int page,int size,int lim,int off,String count){var rows=db.queryForList(q,lim,off);long total=db.queryForObject(count,Long.class);return Map.of("content",rows,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size);}
    private Map<String,Object> page(String q,Object filter,int page,int size,int lim,int off,String count){var rows=db.queryForList(q,filter,lim,off);long total=db.queryForObject(count,Long.class,filter);return Map.of("content",rows,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size);}
    private static final class TimestampCompat {static java.sql.Timestamp of(LocalDateTime v){return java.sql.Timestamp.valueOf(v);}static java.sql.Timestamp of(Instant v){return java.sql.Timestamp.from(v);}}
}
