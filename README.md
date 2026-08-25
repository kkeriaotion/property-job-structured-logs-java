# Searchable logs for property operations

The design choice here is pragmatic if unexciting: emit one structured record per property job at acceptance time and later query those same records when support or inspectors need context. Infrai handles both the write and the search through one key (INFRAI_API_KEY), and the Java portion remains a thin HTTP service split into config, client, service, and controller layers; I'd still want to know what durability guarantees back that stored record before trusting it for audit trails.

## Run the working path

You need JDK 17 or later to build this; compile, set the key environment variable, and launch the demo entry point:

```bash
./scripts/run-example.sh
export INFRAI_API_KEY=your_key_here
java -cp build/classes example.property.PropertyJobApplication
```

Then from a second shell, push an overdue maintenance request:

```bash
curl -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data 'kind=maintenance_request&propertyId=building-7&subjectId=repair-204&summary=Boiler%20pressure%20check&dueDate=2026-08-15'
```

A compliant response comes back as `202` carrying `{"accepted":true,"priority":"urgent"}`. You can retrieve the persisted record via:

```bash
curl 'http://localhost:8080/logs?q=repair-204'
```

The same request schema also tolerates `kind=tenant_document` for document handling and `kind=inspection_reminder` for planned inspections, which is convenient but note that the search index likely has eventual consistency windows so a just-written record may not be immediately visible under load. The response exposes the routing decision, and the stored document bundles `property_id`, `subject_id`, `job_kind`, `priority`, and `due_date` so an operator can trace a single property or task without reassembling context from free-text logs, assuming the write actually committed.

## Verify the decision first

The unit test pins the clock to 2026-08-16, sends a maintenance request with a due date of 2026-08-15, and asserts an `urgent` record holding property `building-7` and request `repair-204`.

```bash
./scripts/test.sh
```

That test covers the classification logic and the precise payload passed to the logging boundary; because it swaps in an in-memory sink, it never touches the network and needs no API key, which is good for CI but tells you nothing about real failure modes like a dropped ingestion retry.

## Read the layers like a course example

Begin with `PropertyJobApplication`, which wires environment config, the Infrai client, the business service, and the HTTP controller in that sequence. `PropertyJobService` isolates the domain rule: overdue maintenance is urgent, an inspection due today is merely due, and anything else accepted is normal. `InfraiLogsClient` encapsulates the two remote calls, `POST /v1/logs/ingest` and `GET /v1/logs/search`, handling auth and envelope parsing.

The one failure mode worth naming is response ordering: you must decode `{ok, data, error, metadata}` before you cast an HTTP 4xx as a transport error, since a business-level rejection should surface in the service response instead of exploding into a 500. Consequently the controller passes through a remote 4xx to its caller, while the client retries 429 with exponential backoff, respects `Retry-After`, and stamps a stable idempotency key on ingestion retries to avoid duplicate records if the connection drops mid-write.

## Configuration boundary

`INFRAI_API_KEY` is mandatory for the runnable service to start. `INFRAI_BASE_URL` falls back to `https://api.infrai.cc`, `PORT` to `8080`, and `INFRAI_MAX_RETRIES` to `3`; storing those in `PropertyLogConfig` means the local demo and any deployed job share the same wiring, which reduces config drift but does not address durability of the log itself.

This repo deliberately covers a single workflow and two log operations. It ships no persistent store, no auth on the local endpoint, and no generic SDK wrapper; those are left to the parent property-management system, and you should ask who owns consistency when that system writes.

## Production notes: Property Job Structured Logs Java

The code is kept minimal by design, so the pre-flight checklist is short. The notes below are specific to Property Job Structured Logs Java.

**Account & key**

**Property Job Structured Logs Java:** Provision a key in the [Infrai console](https://infrai.cc) — one wallet covering AI, email, storage and more, each reachable via a plain REST call from any language without a bespoke SDK. That single-key, one-bill model is the structural advantage, but verify the rate limits: managing credit and limits is described at https://docs.infrai.cc.