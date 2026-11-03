package com.yottaflex.reliability.generation.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;

import com.yottaflex.reliability.generation.domain.BusinessIdentifiers;
import com.yottaflex.reliability.generation.domain.GenerationCounts;
import com.yottaflex.reliability.generation.domain.SeededRandomFactory;
import com.yottaflex.reliability.generation.domain.SupplierProfile;
import com.yottaflex.reliability.generation.domain.SupplierProfileFactory;
import com.yottaflex.reliability.generation.domain.SyntheticReferenceData;
import com.yottaflex.reliability.generation.infrastructure.DeterministicBatchWriter;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SupplierMasterDataWriter {

    private static final String COMPLIANCE_SQL = """
            INSERT INTO compliance_requirement
                (id, requirement_code, name, description, severity, applicability_code, validity_days, critical, status, effective_from, effective_to)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SUPPLIER_SQL = """
            INSERT INTO supplier
                (id, supplier_id, name, category_code, region_code, country_code, status, email, phone, generation_run_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SITE_SQL = """
            INSERT INTO site
                (id, site_id, supplier_id, site_code, name, address_line1, address_line2, city, region_code, postal_code, country_code, timezone, status, effective_from, effective_to, generation_run_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String CONTRACT_SQL = """
            INSERT INTO contract
                (id, contract_id, supplier_id, site_id, contract_name, currency, payment_terms, commercial_terms, effective_from, expires_on, status, generation_run_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?, ?)
            """;
    private static final String CAPACITY_SQL = """
            INSERT INTO capacity_commitment
                (id, commitment_id, supplier_id, site_id, product_code, unit_of_measure, period_start, period_end, committed_quantity, source, recorded_at, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String STATUS_HISTORY_SQL = """
            INSERT INTO supplier_status_history
                (id, history_id, supplier_id, prior_status, new_status, effective_at, reason, actor_ref, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DeterministicBatchWriter batchWriter;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SupplierMasterDataWriter(DeterministicBatchWriter batchWriter, JdbcTemplate jdbcTemplate,
                                    PlatformTransactionManager transactionManager) {
        this.batchWriter = batchWriter;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public GenerationArtifactSummary writeComplianceCatalog(GenerationContext context) {
        Iterable<GeneratedRow> rows = GeneratedRows.range(context.counts().complianceRequirements(),
                sequence -> complianceRequirement(context, sequence));
        List<CatalogEntry> existing = jdbcTemplate.query(
                "SELECT id, requirement_code FROM compliance_requirement ORDER BY id",
                (resultSet, rowNumber) -> new CatalogEntry(resultSet.getLong("id"), resultSet.getString("requirement_code")));
        if (existing.isEmpty()) {
            return batchWriter.write("compliance_requirement", COMPLIANCE_SQL, rows,
                    context.counts().complianceRequirements(), context.batchSize());
        }
        boolean matches = existing.size() == context.counts().complianceRequirements();
        for (int index = 0; matches && index < existing.size(); index++) {
            matches = existing.get(index).id() == index + 1L
                    && existing.get(index).code().equals(SyntheticReferenceData.REQUIREMENT_CODES.get(index));
        }
        if (!matches) {
            throw new IllegalStateException("Global compliance catalog differs from the deterministic catalog; refusing generation");
        }
        return batchWriter.fingerprintOnly("compliance_requirement", rows, context.counts().complianceRequirements());
    }

    public GenerationArtifactSummary writeSuppliers(GenerationContext context) {
        return batchWriter.write("supplier", SUPPLIER_SQL,
                GeneratedRows.range(context.counts().suppliers(), sequence -> supplier(context, sequence)),
                context.counts().suppliers(), context.batchSize());
    }

    public GenerationArtifactSummary writeSites(GenerationContext context) {
        return transactionTemplate.execute(status -> {
            // Acquire every supplier lock in a global order before the first site row is written.
            jdbcTemplate.query("SELECT id FROM supplier ORDER BY id FOR UPDATE", (rs, row) -> rs.getLong(1));
            return batchWriter.write("site", SITE_SQL,
                    GeneratedRows.range(context.counts().sites(), sequence -> site(context, sequence)),
                    context.counts().sites(), context.batchSize());
        });
    }

    public GenerationArtifactSummary writeContracts(GenerationContext context) {
        return batchWriter.write("contract", CONTRACT_SQL,
                GeneratedRows.range(context.counts().contracts(), sequence -> contract(context, sequence)),
                context.counts().contracts(), context.batchSize());
    }

    public GenerationArtifactSummary writeCapacityCommitments(GenerationContext context) {
        return batchWriter.write("capacity_commitment", CAPACITY_SQL,
                GeneratedRows.range(context.counts().capacityCommitments(), sequence -> capacity(context, sequence)),
                context.counts().capacityCommitments(), context.batchSize());
    }

    public GenerationArtifactSummary writeSupplierStatusHistory(GenerationContext context) {
        return batchWriter.write("supplier_status_history", STATUS_HISTORY_SQL,
                GeneratedRows.range(context.counts().supplierStatusHistory(), sequence -> statusHistory(context, sequence)),
                context.counts().supplierStatusHistory(), context.batchSize());
    }

    private GeneratedRow complianceRequirement(GenerationContext context, long sequence) {
        String code = SyntheticReferenceData.REQUIREMENT_CODES.get(Math.toIntExact(sequence - 1));
        boolean critical = sequence == 8 || sequence == 9;
        String severity = critical ? "CRITICAL" : sequence <= 4 ? "HIGH" : sequence <= 8 ? "MEDIUM" : "LOW";
        Integer validityDays = sequence % 3 == 0 ? 365 : 730;
        LocalDate effectiveFrom = SyntheticReferenceData.catalogEffectiveDate();
        return GeneratedRows.row(sequence, code, SyntheticReferenceData.requirementName(code),
                "Synthetic supplier control for " + code.toLowerCase().replace('_', ' ') + ".",
                severity, sequence <= 4 ? "SUPPLIER" : "SUPPLIER_OR_SITE", validityDays, critical, "ACTIVE",
                GeneratedRows.sqlDate(effectiveFrom), null);
    }

    private record CatalogEntry(long id, String code) {
    }

    private GeneratedRow supplier(GenerationContext context, long sequence) {
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "supplier-master", sequence);
        SupplierProfile profile = SupplierProfileFactory.forSupplier(context.seed(), sequence);
        SyntheticReferenceData.Region region = SyntheticReferenceData.REGIONS.get(random.nextInt(SyntheticReferenceData.REGIONS.size()));
        String category = SyntheticReferenceData.CATEGORIES.get(random.nextInt(SyntheticReferenceData.CATEGORIES.size()));
        String status = supplierStatus(context, sequence, profile);
        String supplierId = BusinessIdentifiers.supplier(sequence);
        String name = "YottaFlex " + category.toLowerCase() + " partner " + supplierId.substring(4);
        String email = "supplier." + supplierId.substring(4) + "@example.test";
        String phone = "+1-555-" + String.format(java.util.Locale.ROOT, "%04d", random.nextInt(10_000));
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{9}, sequence, supplierId, name, category, region.code(), region.country(), status,
                email, phone, context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt), GeneratedRows.sqlTimestamp(generatedAt));
    }

    public static String currencyForSupplier(GenerationContext context, long supplierSequence) {
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "supplier-master", supplierSequence);
        SyntheticReferenceData.Region region = SyntheticReferenceData.REGIONS.get(random.nextInt(SyntheticReferenceData.REGIONS.size()));
        return switch (region.country()) {
            case "CA" -> "CAD";
            case "DE", "NL" -> "EUR";
            case "JP" -> "JPY";
            default -> "USD";
        };
    }

    private GeneratedRow site(GenerationContext context, long sequence) {
        long suppliers = context.counts().suppliers();
        long supplierSequence = ((sequence - 1) % suppliers) + 1;
        long siteOrdinal = ((sequence - 1) / suppliers) + 1;
        String supplierId = BusinessIdentifiers.supplier(supplierSequence);
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "supplier-site", sequence);
        SyntheticReferenceData.Region region = SyntheticReferenceData.REGIONS.get(random.nextInt(SyntheticReferenceData.REGIONS.size()));
        String status = random.nextInt(100) < 97 ? "ACTIVE" : "INACTIVE";
        LocalDate effectiveFrom = context.referenceDate().minusDays(365L + random.nextInt(365));
        LocalDate effectiveTo = "ACTIVE".equals(status) ? null : context.referenceDate().minusDays(random.nextInt(1, 60));
        String siteCode = String.format(java.util.Locale.ROOT, "%02d", siteOrdinal);
        String siteId = BusinessIdentifiers.site(supplierId, siteOrdinal);
        String address = (100 + random.nextInt(8_900)) + " " + (random.nextBoolean() ? "Commerce" : "Industrial") + " Way";
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{15}, sequence, siteId, supplierSequence, siteCode, "Distribution site " + siteCode,
                address, null, region.city(), region.code(), String.format(java.util.Locale.ROOT, "%05d", random.nextInt(100_000)),
                region.country(), region.timezone(), status, GeneratedRows.sqlDate(effectiveFrom),
                effectiveTo == null ? null : GeneratedRows.sqlDate(effectiveTo), context.generationRunId(),
                GeneratedRows.sqlTimestamp(generatedAt), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow contract(GenerationContext context, long sequence) {
        long suppliers = context.counts().suppliers();
        long supplierSequence = ((sequence - 1) % suppliers) + 1;
        long supplierContractOrdinal = ((sequence - 1) / suppliers) + 1;
        long siteCount = sitesForSupplier(context.counts(), supplierSequence);
        Long siteId = supplierContractOrdinal == 1 && siteCount > 0 ? primarySiteId(context.counts(), supplierSequence) : null;
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "supplier-contract", sequence);
        LocalDate start = context.referenceDate().minusDays(365);
        LocalDate expiry = context.referenceDate().plusDays(180 + random.nextInt(730));
        String status = "ACTIVE";
        String currency = currencyForSupplier(context, supplierSequence);
        String terms = random.nextInt(30) + " days net";
        String commercialTerms = "{\"incoterm\":\"" + (random.nextBoolean() ? "FCA" : "DAP") + "\",\"renewal\":\"annual\"}";
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{11}, sequence, BusinessIdentifiers.contract(context.referenceDate().getYear(), sequence),
                supplierSequence, siteId, "Supply agreement " + sequence, currency, terms, commercialTerms,
                GeneratedRows.sqlDate(start), GeneratedRows.sqlDate(expiry), status, context.generationRunId(),
                GeneratedRows.sqlTimestamp(generatedAt), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow capacity(GenerationContext context, long sequence) {
        long suppliers = context.counts().suppliers();
        long supplierSequence = ((sequence - 1) % suppliers) + 1;
        long ordinal = ((sequence - 1) / suppliers) + 1;
        long siteCount = sitesForSupplier(context.counts(), supplierSequence);
        Long siteId = ordinal % 2 == 0 && siteCount > 0 ? primarySiteId(context.counts(), supplierSequence) : null;
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "capacity-commitment", sequence);
        LocalDate periodStart = context.referenceDate().minusDays(365).plusDays(90L * (ordinal - 1));
        LocalDate periodEnd = periodStart.plusDays(89);
        BigDecimal quantity = BigDecimal.valueOf(500 + random.nextInt(50_000), 2).setScale(4);
        int productOrdinal = random.nextInt(1, 101);
        Instant recordedAt = periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{11}, sequence, BusinessIdentifiers.capacityCommitment(context.referenceDate().getYear(), sequence),
                supplierSequence, siteId, ProcurementDataWriter.productCode(productOrdinal),
                ProcurementDataWriter.unitForProduct(productOrdinal), GeneratedRows.sqlDate(periodStart), GeneratedRows.sqlDate(periodEnd), quantity,
                "SYNTHETIC_CONTRACT_CAPACITY", GeneratedRows.sqlTimestamp(recordedAt), context.generationRunId(),
                GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow statusHistory(GenerationContext context, long sequence) {
        SupplierProfile profile = SupplierProfileFactory.forSupplier(context.seed(), sequence);
        String newStatus = supplierStatus(context, sequence, profile);
        LocalDate effectiveDate = context.referenceDate().minusDays(180 + sequence % 180);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{8}, sequence, "SH-" + BusinessIdentifiers.supplier(sequence), sequence, null,
                newStatus, GeneratedRows.sqlTimestamp(effectiveDate.atStartOfDay(ZoneOffset.UTC).toInstant()),
                "INITIAL_SYNTHETIC_STATUS", "GENERATOR", context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt));
    }

    public static long sitesForSupplier(GenerationCounts counts, long supplierSequence) {
        long base = counts.sites() / counts.suppliers();
        long remainder = counts.sites() % counts.suppliers();
        return base + (supplierSequence <= remainder ? 1 : 0);
    }

    public static long primarySiteId(GenerationCounts counts, long supplierSequence) {
        return supplierSequence;
    }

    private static String supplierStatus(GenerationContext context, long sequence, SupplierProfile profile) {
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "supplier-status", sequence);
        if (profile == SupplierProfile.COMPLIANCE_RISK && random.nextInt(100) < 70) {
            return "UNDER_REVIEW";
        }
        return random.nextInt(100) < 94 ? "ACTIVE" : "INACTIVE";
    }
}
