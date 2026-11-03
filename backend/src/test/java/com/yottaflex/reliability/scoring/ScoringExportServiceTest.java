package com.yottaflex.reliability.scoring;

import com.yottaflex.reliability.scoring.application.ScoringExportService;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ScoringExportServiceTest {
    @Test void csvQuotesCommasAndQuotesAndProtectsFormulaCells() {
        Map<String,Object> row=new LinkedHashMap<>();row.put("supplier_id","=HYPERLINK(\"x\")");row.put("explanation","late, \"revised\"");
        assertThat(ScoringExportService.toCsv(List.of(row),List.of())).isEqualTo("\"supplier_id\",\"explanation\"\r\n\"'=HYPERLINK(\"\"x\"\")\",\"late, \"\"revised\"\"\"\r\n");
    }

    @Test void emptyCsvRetainsStableColumnHeader() {
        assertThat(ScoringExportService.toCsv(List.of(),List.of("run_id","score"))).isEqualTo("\"run_id\",\"score\"\r\n");
    }
}
