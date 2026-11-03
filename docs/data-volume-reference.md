# Data Volume Reference

The fixed reference date is `2026-10-22`. The business event window is the 365 days ending on that date. These counts are deterministic and are checked before a run can complete.

| Entity/table | STARTER | FULL | Notes |
|---|---:|---:|---|
| Supplier | 25 | 1,000 | Required FULL baseline |
| Site | 43 | 1,750 | Deterministic distribution across suppliers |
| Contract | 50 | 2,000 | Two per supplier |
| CapacityCommitment | 100 | 4,000 | Four per supplier |
| PurchaseOrder | 120 | 10,000 | Required FULL baseline |
| POLine | 300 | 25,000 | Two per PO plus one for a fixed subset |
| Shipment | 180 | 15,000 | Two for a fixed subset of POs, otherwise one |
| ShipmentLine | 300 | 25,000 | Split shipment allocation reconciles to PO lines |
| DeliveryEvent | 360 | 30,000 | Dispatch and delivery per shipment |
| Receipt | 240 | 20,000 | Extra partial receipts for a fixed subset |
| ReceiptLine | 300 | 25,000 | One row for each shipment line |
| Inspection | 240 | 20,000 | One per receipt |
| InspectionLine | 300 | 25,000 | One row per receipt line |
| Invoice | 144 | 12,000 | Base PO invoices plus deterministic extras |
| InvoiceLine | 180 | 15,000 | Matched to PO lines |
| RiskSignal | 100 | 4,000 | Four per supplier |
| ComplianceRequirement | 12 | 12 | Global catalog; created once or verified/reused |
| ComplianceAssessment | 75 | 3,000 | Three per supplier |
| ComplianceCheck | 300 | 12,000 | Four selected catalog requirements per assessment |
| SupplierStatusHistory | 25 | 1,000 | Initial history event per supplier |

The four mandatory FULL counts are exactly 1,000 suppliers, 10,000 purchase orders, 15,000 shipments, and 20,000 inspections. Supporting FULL counts are fixed values within the approved ranges. STARTER count configuration changes supplier, PO, and shipment totals; related entity counts are derived deterministically by the same formulas as FULL.
