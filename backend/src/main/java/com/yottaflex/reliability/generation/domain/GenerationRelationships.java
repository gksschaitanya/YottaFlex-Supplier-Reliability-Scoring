package com.yottaflex.reliability.generation.domain;

import com.yottaflex.reliability.generation.application.GenerationContext;

public final class GenerationRelationships {

    private GenerationRelationships() {
    }

    public static long supplierForPurchaseOrder(GenerationContext context, long purchaseOrderSequence) {
        return ((purchaseOrderSequence - 1) % context.counts().suppliers()) + 1;
    }

    public static long primarySiteId(GenerationContext context, long supplierSequence) {
        return supplierSequence;
    }

    public static long contractForPurchaseOrder(GenerationContext context, long supplierSequence, long purchaseOrderSequence) {
        long supplierContracts = context.counts().contracts() / context.counts().suppliers();
        long ordinal = 1 + purchaseOrderSequence % supplierContracts;
        return (ordinal - 1) * context.counts().suppliers() + supplierSequence;
    }

    public static long purchaseOrderLineCount(GenerationContext context, long purchaseOrderSequence) {
        long additionalLines = context.counts().poLines() - 2 * context.counts().purchaseOrders();
        return 2 + (purchaseOrderSequence <= additionalLines ? 1 : 0);
    }

    public static long purchaseOrderLineStart(GenerationContext context, long purchaseOrderSequence) {
        long additionalLines = context.counts().poLines() - 2 * context.counts().purchaseOrders();
        return 2 * (purchaseOrderSequence - 1) + Math.min(purchaseOrderSequence - 1, additionalLines) + 1;
    }

    public static PurchaseOrderLineReference purchaseOrderLineForSequence(GenerationContext context, long lineSequence) {
        long additionalLines = context.counts().poLines() - 2 * context.counts().purchaseOrders();
        long linesInAdditionalOrders = additionalLines * 3;
        if (lineSequence <= linesInAdditionalOrders) {
            long purchaseOrderSequence = ((lineSequence - 1) / 3) + 1;
            int lineNumber = (int) (((lineSequence - 1) % 3) + 1);
            return new PurchaseOrderLineReference(purchaseOrderSequence, lineNumber);
        }
        long remaining = lineSequence - linesInAdditionalOrders - 1;
        long purchaseOrderSequence = additionalLines + (remaining / 2) + 1;
        int lineNumber = (int) ((remaining % 2) + 1);
        return new PurchaseOrderLineReference(purchaseOrderSequence, lineNumber);
    }

    public static long shipmentPurchaseOrder(GenerationContext context, long shipmentSequence) {
        long additionalShipments = context.counts().shipments() - context.counts().purchaseOrders();
        return shipmentSequence <= 2 * additionalShipments
                ? ((shipmentSequence - 1) / 2) + 1
                : additionalShipments + (shipmentSequence - 2 * additionalShipments);
    }

    public static int shipmentOrdinalForPurchaseOrder(GenerationContext context, long shipmentSequence) {
        long additionalShipments = context.counts().shipments() - context.counts().purchaseOrders();
        return shipmentSequence <= 2 * additionalShipments && shipmentSequence % 2 == 0 ? 2 : 1;
    }

    public static long shipmentSequenceForPurchaseOrder(GenerationContext context, long purchaseOrderSequence, int shipmentOrdinal) {
        long additionalShipments = context.counts().shipments() - context.counts().purchaseOrders();
        if (purchaseOrderSequence <= additionalShipments) {
            if (shipmentOrdinal < 1 || shipmentOrdinal > 2) {
                throw new IllegalArgumentException("This order has exactly two shipments");
            }
            return (purchaseOrderSequence - 1) * 2 + shipmentOrdinal;
        }
        if (shipmentOrdinal != 1) {
            throw new IllegalArgumentException("This order has exactly one shipment");
        }
        return 2 * additionalShipments + purchaseOrderSequence - additionalShipments;
    }

    public static long shipmentLineCount(GenerationContext context, long shipmentSequence) {
        long purchaseOrder = shipmentPurchaseOrder(context, shipmentSequence);
        long poLineCount = purchaseOrderLineCount(context, purchaseOrder);
        long additionalShipments = context.counts().shipments() - context.counts().purchaseOrders();
        if (purchaseOrder <= additionalShipments && shipmentOrdinalForPurchaseOrder(context, shipmentSequence) == 1) {
            return 1;
        }
        if (purchaseOrder <= additionalShipments) {
            return poLineCount - 1;
        }
        return poLineCount;
    }

    public static int purchaseOrderLineOrdinalForShipmentLine(
            GenerationContext context,
            long shipmentSequence,
            int shipmentLineOrdinal) {
        long purchaseOrder = shipmentPurchaseOrder(context, shipmentSequence);
        long additionalShipments = context.counts().shipments() - context.counts().purchaseOrders();
        if (purchaseOrder <= additionalShipments && shipmentOrdinalForPurchaseOrder(context, shipmentSequence) == 2) {
            return shipmentLineOrdinal + 1;
        }
        return shipmentLineOrdinal;
    }

    public static long shipmentLineStart(GenerationContext context, long shipmentSequence) {
        long additionalShipments = context.counts().shipments() - context.counts().purchaseOrders();
        if (shipmentSequence <= 2 * additionalShipments) {
            long priorPurchaseOrders = (shipmentSequence - 1) / 2;
            long start = 3 * priorPurchaseOrders + 1;
            if (shipmentSequence % 2 == 0) {
                start++;
            }
            return start;
        }
        return 3 * additionalShipments + 2 * (shipmentSequence - 2 * additionalShipments - 1) + 1;
    }

    public static ShipmentLineReference shipmentLineForSequence(GenerationContext context, long lineSequence) {
        long additionalShipments = context.counts().shipments() - context.counts().purchaseOrders();
        long doubleShipmentLineCount = 3 * additionalShipments;
        if (lineSequence <= doubleShipmentLineCount) {
            long purchaseOrderSequence = ((lineSequence - 1) / 3) + 1;
            int linePosition = (int) (((lineSequence - 1) % 3) + 1);
            long shipmentSequence = shipmentSequenceForPurchaseOrder(context, purchaseOrderSequence, linePosition == 1 ? 1 : 2);
            int lineOrdinal = linePosition == 1 ? 1 : linePosition - 1;
            return new ShipmentLineReference(shipmentSequence, lineOrdinal);
        }
        long remaining = lineSequence - doubleShipmentLineCount - 1;
        long purchaseOrderSequence = additionalShipments + (remaining / 2) + 1;
        long shipmentSequence = shipmentSequenceForPurchaseOrder(context, purchaseOrderSequence, 1);
        return new ShipmentLineReference(shipmentSequence, (int) ((remaining % 2) + 1));
    }

    public static long receiptForShipment(GenerationContext context, long receiptSequence) {
        long additionalReceipts = context.counts().receipts() - context.counts().shipments();
        if (receiptSequence <= context.counts().shipments()) {
            return receiptSequence;
        }
        return 2 * (receiptSequence - context.counts().shipments());
    }

    public static long receiptForShipmentLine(GenerationContext context, long shipmentSequence, int lineOrdinal) {
        long additionalReceipts = context.counts().receipts() - context.counts().shipments();
        if (shipmentSequence <= 2 * additionalReceipts && shipmentSequence % 2 == 0 && lineOrdinal > 1) {
            return context.counts().shipments() + shipmentSequence / 2;
        }
        return shipmentSequence;
    }

    public static long invoicePurchaseOrder(GenerationContext context, long invoiceSequence) {
        long additionalInvoices = context.counts().invoices() - context.counts().purchaseOrders();
        return invoiceSequence <= context.counts().purchaseOrders()
                ? invoiceSequence
                : invoiceSequence - context.counts().purchaseOrders();
    }

    public static int invoiceLineCount(GenerationContext context, long invoiceSequence) {
        long additionalLines = context.counts().invoiceLines() - context.counts().invoices();
        return 1 + (invoiceSequence <= additionalLines ? 1 : 0);
    }

    public static long invoiceLinePoLine(GenerationContext context, long invoiceSequence, int lineOrdinal) {
        long purchaseOrder = invoicePurchaseOrder(context, invoiceSequence);
        long lineCount = purchaseOrderLineCount(context, purchaseOrder);
        long additionalInvoices = context.counts().invoices() - context.counts().purchaseOrders();
        if (invoiceSequence > context.counts().purchaseOrders()) {
            return purchaseOrderLineStart(context, purchaseOrder) + lineCount - 1;
        }
        if (lineOrdinal > lineCount) {
            throw new IllegalArgumentException("Invoice line ordinal exceeds the PO line count");
        }
        return purchaseOrderLineStart(context, purchaseOrder) + lineOrdinal - 1;
    }

    public record PurchaseOrderLineReference(long purchaseOrderSequence, int lineNumber) {
    }

    public record ShipmentLineReference(long shipmentSequence, int lineOrdinal) {
    }
}
