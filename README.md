# Enterprise Integration Architecture: Zero-Trust Event-Driven 3PL Inventory Sync to SAP S/4HANA (Clean Core)

![SAP Integration Suite](https://img.shields.io/badge/SAP%20Integration%20Suite-Cloud%20Integration-0070F2?style=for-the-badge&logo=sap)
![SAP S/4HANA](https://img.shields.io/badge/SAP%20S%2F4HANA-OData%20V4%20Clean%20Core-E35205?style=for-the-badge&logo=sap)
![Security](https://img.shields.io/badge/Security-Zero--Trust%20SHA--256-2ea44f?style=for-the-badge)
![Compliance](https://img.shields.io/badge/Compliance-GxP%20%7C%20GDPR%20Privacy--by--Design-blue?style=for-the-badge)
![Architecture](https://img.shields.io/badge/Architecture-Event--Driven%20Decoupled-purple?style=for-the-badge)

## Executive Summary

This enterprise integration project demonstrates an end-to-end, mission-critical B2B inventory synchronization pipeline between a Global Third-Party Logistics carrier (3PL – DHL Life Sciences) and **SAP S/4HANA Cloud / Private Edition**, implemented for the **MedTech & Biopharmaceutical** sector.

The architecture enforces SAP's **Clean Core** paradigm and strict **GxP / EU Annex 11 / GDPR Privacy-by-Design** regulatory standards. By replacing monolithic synchronous point-to-point connections with a decoupled **Event-Driven Architecture (EDA)**, the system achieves zero downtime vulnerability, cryptographic audit immutability via **SHA-256 payload hashing**, in-flight commercial/PII data obfuscation, and persistent **idempotency controls** preventing dual-posting anomalies in clinical stock records.

---

## Architecture Overview

### High-Level Topology

```plaintext
[3PL Logistics Partner (DHL Life Sciences)]
       │
       │ HTTPS / REST (OData V2 / JSON Payload)
       ▼
[SAP Integration Suite (Cloud Integration Runtime)]
  ├── iFlow 1: IF_Inbound_3PL_Event_Publisher (Producer)
  │     ├── Channel: Inbound HTTPS (/medtech/3pl/inbound/v1)
  │     ├── Security Engine: Groovy Zero-Trust & GDPR Scrubber
  │     │     ├── PII & Commercial Data Masking ('***-**-4821', '***CONFIDENTIAL***')
  │     │     ├── Cryptographic SHA-256 Hashing of TransactionID
  │     │     └── MPL Audit Header Extraction (CorrelationID) & Payload Log Suppression
  │     ├── Data Store: Idempotent Ledger (MedTech_Processed_Events - 30-Day Retention)
  │     └── Channel: Outbound ProcessDirect (/medtech/inventory/consumer)
  │
  └── iFlow 2: IF_EventMesh_to_S4HANA_CleanCore_Consumer (Consumer)
        ├── Channel: Inbound ProcessDirect (/medtech/inventory/consumer)
        ├── Content-Based Routing Matrix:
        │     ├── MovementType == '101' ──► Goods Receipt Routing
        │     └── MovementType == '601' ──► Goods Issue / Dispatch Routing
        ├── Outbound Clean Core Channel: OData V4 Adapter (Create / POST)
        └── Exception Subprocess:
              ├── Incident JSON Payload Constructor
              ├── Dynamic MPL Message ID & Stack Trace Extraction
              └── Dead Letter Routing & IT Ops Dispatch
       │
       │ Reverse Invoke Tunnel (TLS 1.3)
       ▼
[SAP Cloud Connector]
  ├── Subaccount Binding & Resource Whitelisting
  └── Principal Propagation Engine (Short-Lived X.509 Client Certificates)
       │
       │ HTTPS / OData V4 Service
       ▼
[SAP S/4HANA Enterprise Core (Clean Core Architecture)]
  └── Service: /sap/opu/odata4/sap/api_materialdocument/srvd_a2x/sap/materialdocument/0001
      (Standard Clean Core Material Document Posting - Goods Movement 101/601)
```

---

## Technical Specifications

| Architectural Component | Technical Implementation | Enterprise Justification & Governance |
| :--- | :--- | :--- |
| **Inbound Ingestion** | HTTPS REST Endpoint (`/medtech/3pl/inbound/v1`) | Standardized B2B JSON ingestion layer; decoupled perimeter security. |
| **Zero-Trust Engine** | Custom Groovy Script (`GDPR_ZeroTrust_Scrubber.groovy`) | Executes SHA-256 cryptographic hashing on payload IDs and dynamically obfuscates sensitive pricing/PII fields before log persistence. |
| **Data Privacy & GxP** | Memory-Level Payload Scrubbing | Precludes sensitive pharma pricing and patient delivery data from leaking into Message Processing Logs (MPL). |
| **Idempotency Control** | Data Store (`MedTech_Processed_Events`) | Prevents duplicate physical stock postings by validating SHA-256 hashes against an encrypted 30-day transactional registry. |
| **Broker Abstraction** | ProcessDirect Virtual Pipeline | Decouples producer from consumer pipelines with zero serialization latency; fully prepared for SAP Event Mesh AMQP 1.0 transition. |
| **Transactional Core** | OData V4 Adapter (`API_MATERIAL_DOCUMENT_SRV`) | Strictly abides by Clean Core guidelines; completely bypasses deprecated RFC/BAPI wrappers and custom ABAP Z-tables. |
| **Hybrid Connectivity** | SAP Cloud Connector (TLS 1.3) | Secure reverse-invoke tunnel into private ERP subnets with zero public inbound firewall ports exposed. |
| **Audit Traceability** | Principal Propagation (X.509) | Propagates genuine caller credentials from BTP Identity Authentication directly to SAP ERP transaction logs (`SY-UNAME`). |
| **Fault Tolerance** | Automated Exception Subprocess | Intercepts ERP communication failures, extracts internal MPL Log IDs, and formats structured incident alerts for IT Operations. |

---

## Security, Zero-Trust & Regulatory Compliance

### 1. Cryptographic Pseudonymization & GDPR Scrubbing
Pharma supply chains mandate strict adherence to patient privacy and trade secret governance. Upstream payloads contain confidential unit valuations and recipient facility records. The integrated Groovy engine sanitizes inbound messages in-flight:
* **Identifier Hashing:** Generates an irreversible `SHA-256` digest of `TransactionID` or `DeliveryDocument`, propagated as `TransactionHash` and `X-Correlation-ID`.
* **PII & Value Obfuscation:** Replaces commercial prices with `***CONFIDENTIAL***` and client identities with `***REDACTED***`.
* **Tracking Truncation:** Retains solely the terminal 4 digits of parcel identifiers (e.g., `***-**-4821`).

### 2. Idempotent Processing (Anti-Duplication Ledger)
To prevent network retransmission anomalies from generating phantom warehouse receipts, each incoming transaction hash is recorded into an encrypted SAP CPI Data Store (`MedTech_Processed_Events`). Duplicate submissions within the 30-day retention window are intercepted and rejected prior to ERP commitment.

### 3. Clean Core OData V4 Consumption
Rather than relying on legacy IDoc structures (`DELVRY03`, `MBGMCR01`) or direct database modifications, all transactional operations target SAP S/4HANA's native OData V4 Material Document API:
```http
POST /sap/opu/odata4/sap/api_materialdocument/srvd_a2x/sap/materialdocument/0001/MaterialDocument HTTP/1.1
Content-Type: application/json
```

---

## Repository Structure

```plaintext
├── src/
│   ├── GDPR_ZeroTrust_Scrubber.groovy       # Production Groovy script for Zero-Trust hashing & scrubbing
│   ├── sample_3pl_inbound.json              # Canonical 3PL inbound shipment telemetry payload
│   └── incident_payload_template.json       # Clean Core exception subprocess structured incident schema
├── docs/
│   └── architecture-overview.md             # Technical design specification & Clean Core compliance matrix
└── README.md                                # Enterprise architectural documentation
```

---

## Author

**Željko Popov**  
Integration Architect  
*SAP Integration Suite Specialist | Cloud Architecture & Clean Core*
