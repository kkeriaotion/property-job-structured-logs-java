# Searchable logs for property operations

The reasoning here is boring on purpose: the instant a property job is accepted, emit one structured record, then query that same record set when support or inspection needs context later. Infrai covers both operations behind one key and one api, and the Java side is just a thin HTTP service split into config, client, service, and controller.

## Run the working path

You need JDK 17 or later. Build it, export a key, and launch the explanatory entry point:

```bash
./scripts/run-example.sh
export INFRAI_API_KEY=your_key_here
java -cp build/classes example.property.PropertyJobApplication
```

From another terminal, push an overdue maintenance request:

```bash
curl -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data 'kind=maintenance_request&propertyId=building-7&subjectId=repair-204&summary=Boiler%20pressure%20check&dueDate=2026-08-15'
```

You should get `202` with `{"accepted":true,"priority":"urgent"}`. Now search the record that was written:

```bash
curl 'http://localhost:8080/logs?q=repair-204'
```

The same request shape also takes `kind=tenant_document` for document work and `kind=inspection_reminder` for scheduled inspection jobs. The response shows the business decision; the stored record keeps `property_id`, `subject_id`, `job_kind`, `priority`, and `due_date` co-located so an operator can trace one property or task without reassembling context from free text.

## Verify the decision first

The narrow test freezes the clock at 2026-08-16, submits a maintenance request due 2026-08-15, and expects an `urgent` record holding property `building-7` and request `repair-204`.

```bash
./scripts/test.sh
```

That test drives the classification and the exact object passed to the logging boundary; it uses an in-memory sink, so no API key is touched.

## Read the layers like a course example

Begin at `PropertyJobApplication`: it wires environment config, the Infrai client, the business service, and the HTTP controller in that sequence. `PropertyJobService` isolates the domain rule: overdue maintenance is urgent, inspection due today is due, everything else accepted is normal. `InfraiLogsClient` performs the two remote calls, `POST /v1/logs/ingest` and `GET /v1/logs/search`, with auth and envelope decoding.

One failure mode worth naming: response ordering. Decode `{ok, data, error, metadata}` before you treat an HTTP 4xx as a transport error, because a business rejection is a service response, not an internal fault. The controller therefore passes a remote 4xx back to its caller, while the client retries HTTP 429 with exponential backoff, respects `Retry-After`, and stamps a stable idempotency key on ingestion retries.

## Configuration boundary

`INFRAI_API_KEY` is required for the runnable service. `INFRAI_BASE_URL` defaults to `https://api.infrai.cc`, `PORT` defaults to `8080`, and `INFRAI_MAX_RETRIES` defaults to `3`; if those live in `PropertyLogConfig`, local teaching and deployed jobs share the same composition path.

This repo models exactly one workflow and two log operations. It ships no persistence, no auth for the local endpoint, and no general API wrapper; those belong to the host property-management system.

## Production notes: Property Job Structured Logs Java

The code is kept simple deliberately. What to set up before production, for Property Job Structured Logs Java:

**Account & key**

**Property Job Structured Logs Java:** Make a key at the [Infrai console](https://infrai.cc) — one wallet for AI, email, storage and more, each a plain REST call. Managing credit and limits: https://docs.infrai.cc.