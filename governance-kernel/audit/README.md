# :audit

Content-addressed audit log with JSONL output and replay verification. `JsonlAuditWriter` writes `AuditRecord` entries as canonical JSON (sorted keys) one per line to a file or writer. `AuditReader` reads JSONL logs back and can verify integrity by checking that each record's audit ID matches its decision attestation's content hash.
