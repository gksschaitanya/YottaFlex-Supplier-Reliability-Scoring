package com.yottaflex.reliability.supplier.application;

import com.yottaflex.reliability.supplier.api.SupplierListResponse;
import com.yottaflex.reliability.supplier.api.SupplierListResponse.SupplierSummary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class SupplierQueryService {
    private static final String SUPPLIER_LIST_SQL = """
            SELECT s.supplier_id, s.name, s.status,
                   latest.overall_score AS score,
                   latest.classification,
                   latest.run_id AS score_run_id
            FROM supplier s
            LEFT JOIN LATERAL (
                SELECT ss.overall_score, ss.classification, sr.run_id
                FROM supplier_score ss
                JOIN score_run sr ON sr.id = ss.score_run_id
                WHERE ss.supplier_id = s.id AND sr.run_status = 'COMPLETED'
                ORDER BY sr.completed_at DESC, sr.as_of DESC, ss.id DESC
                LIMIT 1
            ) latest ON TRUE
            ORDER BY s.name, s.supplier_id
            LIMIT ? OFFSET ?
            """;

    private final JdbcTemplate jdbc;

    public SupplierQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SupplierListResponse list(int page, int requestedSize) {
        if (page < 0 || requestedSize < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid supplier pagination values");
        }
        int size = Math.min(requestedSize, 100);
        long total = jdbc.queryForObject("SELECT count(*) FROM supplier", Long.class);
        List<SupplierSummary> suppliers = jdbc.query(SUPPLIER_LIST_SQL, (rs, row) -> new SupplierSummary(
                rs.getString("supplier_id"), rs.getString("name"), rs.getString("status"),
                rs.getBigDecimal("score"), rs.getString("classification"), rs.getString("score_run_id")),
                size, (long) page * size);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new SupplierListResponse(suppliers, page, size, total, totalPages, page == 0,
                totalPages == 0 || page >= totalPages - 1);
    }
}
