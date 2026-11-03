package com.yottaflex.reliability.scoring.application;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.*;

@Service
public class ScoringExportService {
    public record Export(String contentType, String filename, Object body) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public ScoringExportService(JdbcTemplate db,ObjectMapper json) { this.db=db;this.json=json; }

    public Export export(String runId,String report,String format,int page,int size) {
        if(page<0||size<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"page must be nonnegative and size positive");
        int p=page,n=Math.min(size,10000);long offset=(long)p*n;
        List<Long> runIds=db.query("select id from score_run where run_id=?",(rs,row)->rs.getLong(1),runId);
        if(runIds.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Score run was not found");
        long runPk=runIds.getFirst();
        String key=Objects.toString(report,"scores").toLowerCase(Locale.ROOT);
        if(key.equals("run")){
            if(!Objects.toString(format,"json").equalsIgnoreCase("json"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"run report is available as JSON");
            Map<String,Object> metadata=db.queryForMap("select run_id as \"runId\",run_status as status,algorithm_version as \"algorithmVersion\",as_of as \"asOf\",window_start as \"windowStart\",window_end as \"windowEnd\",configuration_snapshot::text as configuration from score_run where id=?",runPk);
            try { metadata.put("configuration",json.readTree(Objects.toString(metadata.get("configuration"),"{}"))); }
            catch(JsonProcessingException ex){throw new IllegalStateException("Stored scoring configuration is invalid JSON",ex);}
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("suppliers",db.queryForObject("select count(*) from supplier_score where score_run_id=?",Long.class,runPk));
            summary.put("validScores",db.queryForObject("select count(*) from supplier_score where score_run_id=? and overall_score is not null",Long.class,runPk));
            summary.put("insufficientData",db.queryForObject("select count(*) from supplier_score where score_run_id=? and overall_score is null",Long.class,runPk));
            summary.put("classificationCounts",db.query("select classification,count(*) from supplier_score where score_run_id=? group by classification order by classification",(rs,row)->Map.of("classification",rs.getString(1),"count",rs.getLong(2)),runPk));
            summary.put("componentStatusCounts",db.query("select c.component,c.component_status,count(*) from supplier_score_component c join supplier_score s on s.id=c.supplier_score_id where s.score_run_id=? group by c.component,c.component_status order by c.component,c.component_status",(rs,row)->Map.of("component",rs.getString(1),"status",rs.getString(2),"count",rs.getLong(3)),runPk));
            summary.put("missingComponentCount",db.queryForObject("select count(*) from supplier_score_component c join supplier_score s on s.id=c.supplier_score_id where s.score_run_id=? and c.component_status='MISSING_DATA'",Long.class,runPk));
            summary.put("sourceObservationCounts",db.query("select s.supplier_id,o.source_type,o.eligible_count,o.excluded_after_cutoff_count,o.excluded_invalid_count from score_run_source_observation_count o join supplier s on s.id=o.supplier_id where o.score_run_id=? order by s.supplier_id,o.source_type",(rs,row)->Map.of("supplierId",rs.getString(1),"sourceType",rs.getString(2),"eligibleCount",rs.getLong(3),"excludedAfterCutoffCount",rs.getLong(4),"excludedInvalidCount",rs.getLong(5)),runPk));
            summary.put("criticalComplianceCount",db.queryForObject("select count(*) from supplier_score where score_run_id=? and critical_compliance_violation",Long.class,runPk));
            return new Export("application/json","yottaflex-"+runId.replaceAll("[^A-Za-z0-9_-]","_")+"-run.json",Map.of("run",metadata,"summary",summary));
        }
        String sql=switch(key){
            case "scores" -> "select r.run_id as run_id,s.supplier_id,s.name,ss.overall_score,ss.classification,ss.coverage,ss.critical_compliance_violation,ss.explanation from supplier_score ss join supplier s on s.id=ss.supplier_id join score_run r on r.id=ss.score_run_id where ss.score_run_id=? order by s.supplier_id";
            case "components" -> "select r.run_id as run_id,s.supplier_id,s.name,c.component,c.component_score,c.weight,c.component_status,c.observation_count,c.eligible_observation_count,c.excluded_observation_count,c.numerator,c.denominator,c.missing_reason,c.explanation from supplier_score_component c join supplier_score ss on ss.id=c.supplier_score_id join supplier s on s.id=ss.supplier_id join score_run r on r.id=ss.score_run_id where ss.score_run_id=? order by s.supplier_id,c.component";
            case "metrics" -> "select r.run_id as run_id,s.supplier_id,s.name,c.component,c.component_status,c.numerator,c.denominator,c.observation_count,c.eligible_observation_count,c.excluded_observation_count,c.metrics::text as metrics,c.explanation from supplier_score_component c join supplier_score ss on ss.id=c.supplier_score_id join supplier s on s.id=ss.supplier_id join score_run r on r.id=ss.score_run_id where ss.score_run_id=? order by s.supplier_id,c.component";
            case "evidence","exclusions" -> "select r.run_id as run_id,s.supplier_id,e.component,e.source_entity_type,e.source_business_id,e.source_record_id,e.included,e.contribution,e.exclusion_reason,e.evaluated_at,e.details::text as details from score_evidence e join score_run r on r.id=e.score_run_id join supplier s on s.id=e.supplier_id where e.score_run_id=?"+(key.equals("exclusions")?" and not e.included":"")+" order by s.supplier_id,e.component,e.source_entity_type,e.source_record_id";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"report must be scores, components, metrics, evidence, or exclusions");
        };
        long total=db.queryForObject("select count(*) from ("+sql+") report_rows",Long.class,runPk);
        List<Map<String,Object>> rows=db.queryForList(sql+" limit ? offset ?",runPk,n,offset);
        String normalized=Objects.toString(format,"json").toLowerCase(Locale.ROOT);
        String safeRunId=runId.replaceAll("[^A-Za-z0-9_-]","_");
        if(normalized.equals("csv"))return new Export("text/csv;charset=UTF-8","yottaflex-"+safeRunId+"-"+key+"-page-"+p+".csv",toCsv(rows,columns(key)));
        if(!normalized.equals("json"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"format must be json or csv");
        Map<String,Object> envelope=new LinkedHashMap<>();envelope.put("runId",runId);envelope.put("report",key);envelope.put("page",p);envelope.put("size",n);envelope.put("totalElements",total);envelope.put("totalPages",(total+n-1)/n);envelope.put("content",rows);
        return new Export("application/json","yottaflex-"+safeRunId+"-"+key+"-page-"+p+".json",envelope);
    }

    public static String toCsv(List<Map<String,Object>> rows,List<String> emptyHeaders) {
        List<String> columns=rows.isEmpty()?emptyHeaders:new ArrayList<>(rows.getFirst().keySet());StringBuilder out=new StringBuilder();
        out.append(String.join(",",columns.stream().map(ScoringExportService::cell).toList())).append("\r\n");
        for(Map<String,Object> row:rows){
            for(int i=0;i<columns.size();i++){if(i>0)out.append(',');Object value=row.get(columns.get(i));out.append(cell(value));}
            out.append("\r\n");
        }
        return out.toString();
    }
    private static List<String> columns(String report){return switch(report){
        case "scores"->List.of("run_id","supplier_id","name","overall_score","classification","coverage","critical_compliance_violation","explanation");
        case "components"->List.of("run_id","supplier_id","name","component","component_score","weight","component_status","observation_count","eligible_observation_count","excluded_observation_count","numerator","denominator","missing_reason","explanation");
        case "metrics"->List.of("run_id","supplier_id","name","component","component_status","numerator","denominator","observation_count","eligible_observation_count","excluded_observation_count","metrics","explanation");
        default->List.of("run_id","supplier_id","component","source_entity_type","source_business_id","source_record_id","included","contribution","exclusion_reason","evaluated_at","details");
    };}
    private static String cell(Object value){
        if(value==null)return "";
        String text=value instanceof java.time.temporal.TemporalAccessor?value.toString():String.valueOf(value);
        if(value instanceof String&&!text.isEmpty()&&"=+-@".indexOf(text.charAt(0))>=0)text="'"+text;
        return "\""+text.replace("\"","\"\"").replace("\r"," ").replace("\n"," ")+"\"";
    }
}
