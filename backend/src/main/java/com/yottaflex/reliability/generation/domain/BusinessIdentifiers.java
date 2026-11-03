package com.yottaflex.reliability.generation.domain;

import java.util.Locale;
import java.util.Objects;

public final class BusinessIdentifiers {

    private BusinessIdentifiers() {
    }

    public static String supplier(long sequence) {
        return "SUP-" + padded(sequence, 6, 999_999, "supplier");
    }

    public static String site(String supplierId, long sequence) {
        Objects.requireNonNull(supplierId, "supplierId");
        if (!supplierId.matches("SUP-[0-9]{6}")) {
            throw new IllegalArgumentException("Invalid supplier business identifier");
        }
        return "SITE-" + supplierId + "-" + padded(sequence, 2, 99, "site");
    }

    public static String contract(int year, long sequence) {
        return yearPrefix("CON", year) + padded(sequence, 6, 999_999, "contract");
    }

    public static String purchaseOrder(int year, long sequence) {
        return yearPrefix("PO", year) + padded(sequence, 6, 999_999, "purchase order");
    }

    public static String shipment(int year, long sequence) {
        return yearPrefix("SHP", year) + padded(sequence, 6, 999_999, "shipment");
    }

    public static String receipt(int year, long sequence) {
        return yearPrefix("RCV", year) + padded(sequence, 6, 999_999, "receipt");
    }

    public static String inspection(int year, long sequence) {
        return yearPrefix("INS", year) + padded(sequence, 6, 999_999, "inspection");
    }

    public static String invoice(int year, long sequence) {
        return yearPrefix("INV", year) + padded(sequence, 6, 999_999, "invoice");
    }

    public static String capacityCommitment(int year, long sequence) {
        return yearPrefix("CAP", year) + padded(sequence, 6, 999_999, "capacity commitment");
    }

    public static String complianceAssessment(int year, long sequence) {
        return yearPrefix("CMP", year) + padded(sequence, 6, 999_999, "compliance assessment");
    }

    public static String riskSignal(String supplierId, int year, long sequence) {
        Objects.requireNonNull(supplierId, "supplierId");
        if (!supplierId.matches("SUP-[0-9]{6}")) {
            throw new IllegalArgumentException("Invalid supplier business identifier");
        }
        return "RSK-" + supplierId + "-" + year(year) + "-" + padded(sequence, 2, 99, "supplier/year risk signal");
    }

    public static String generationRun(int year, long sequence) {
        return yearPrefix("GEN", year) + padded(sequence, 6, 999_999, "generation run");
    }

    private static String yearPrefix(String prefix, int year) {
        return prefix + "-" + year(year) + "-";
    }

    private static String year(int year) {
        if (year < 1000 || year > 9999) {
            throw new IllegalArgumentException("Business year must be a four-digit year");
        }
        return String.format(Locale.ROOT, "%04d", year);
    }

    private static String padded(long sequence, int width, long maximum, String label) {
        if (sequence < 1 || sequence > maximum) {
            throw new IllegalArgumentException(label + " sequence exceeds supported range: " + sequence);
        }
        return String.format(Locale.ROOT, "%0" + width + "d", sequence);
    }
}
