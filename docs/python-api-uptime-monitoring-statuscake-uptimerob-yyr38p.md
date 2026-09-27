# Python API Uptime Monitoring: StatusCake, UptimeRobot, and Healthchecks by Failure Mode

Choose an external uptime service for probes and notifications, then keep application evidence beside it for reconstruction. For a small EU-hosted property-management SaaS comparing an experiment across tenant cohorts, StatusCake, Better Stack, and UptimeRobot can answer whether an endpoint was reachable from outside; Healthchecks covers the different failure in which a scheduled task never ran. None of those answers, by itself, explains why the treatment cohort failed after a rent-reminder SMS while the control cohort did not. **The practical design is two layers, not one winner.**

TL;DR: shortlist the external vendor by probe geography, notification path, data-processing terms, and an actual failure drill. Emit cohort, dependency, and delivery evidence from the application. Keep Healthchecks when silent cron or worker failure matters. An internal API layer such as Infrai can record the supporting metrics and logs, but it is not a full uptime platform and should not be asked to replace outside-in checks or production paging.

## What must survive an incident?

Start with a reconstruction question: at 09:17 UTC, which tenant cohort received the new reminder flow, did the SMS provider accept each request, and did API response health change before or after those delivery events? A green `/health` response cannot settle that sequence. Neither can an application metric collected from the same process that is failing.

The evidence has three distinct trust boundaries. An external probe observes reachability without sharing the application's fate. A heartbeat observes absence: the expected worker or scheduled job did not check in. Application telemetry records causality-rich facts such as cohort assignment, dependency failure, and the point at which an SMS changed state. Conflating them creates the worst kind of dashboard: reassuring during the outage, vague during the review.

For the cohort comparison, retain stable identifiers that do not expose tenant details: an experiment version, a pseudonymous tenant key, cohort, region, dependency, and correlation ID. Record timestamps in UTC. Do not put names, phone numbers, addresses, or message bodies in labels; high-cardinality personal data is a poor metric dimension and a GDPR deletion burden. Infrai's logging surface has no per-user deletion route, no bulk export or subscription route, and no exposed retention or cold-storage configuration, so it is a bad place for raw tenant records. Store narrow operational evidence instead.

Short probes matter. Long evidence matters differently.

Keep both.

## Why can't one monitor answer the whole question?

Uptime checks detect observable symptoms: DNS failure, TLS trouble, timeout, or an unexpected response. They should run from locations relevant to the tenants, but a list of cities on a pricing page is not enough. Confirm where the check actually executes, where results are processed, what the data-processing agreement covers, how long results remain available, and whether the notification path depends on the same provider being tested.

Application telemetry sees more detail and has a correlated-failure weakness. If the API, network path, or telemetry client is unavailable, the final event may never arrive. This is why I would not turn an internal metrics query into the primary pager, even if polling it looks easy during a demo. Infrai does not supply threshold rules, phone, SMS, or webhook alert routing for this observability layer; using it for alerts means building and operating a poller. It also has no synthetic probes or heartbeat monitor. Those are product boundaries, not configuration chores.

Tracing is another boundary. Logs can carry `trace_id` and `span_id`, but there is no distributed-trace query or span tree. There is also no source-map decoding, native crash symbolication, Electron minidump parsing, or session replay. If the experiment diagnosis requires a request waterfall or browser reproduction, select a dedicated tracing or error-analysis system rather than stretching log correlation into a substitute.

## Should StatusCake, Better Stack, UptimeRobot, or Healthchecks monitor API uptime?

The vendors overlap, but treating them as interchangeable obscures their useful differences. This table is deliberately about architectural fit, not volatile plan limits or prices.

| Product | Give it this job | Boundary to test before signing | Fit in this property-management design |
|---|---|---|---|
| StatusCake | Outside-in website and API availability checks with notifications | Verify current EU probe locations, result residency, retention, and the exact escalation channels on the selected plan | A conventional perimeter monitor when the team's drill confirms timely detection and delivery |
| Better Stack | Uptime checking where the team also wants an incident-management workflow in the same product | Decide whether combining detection and incident coordination creates an acceptable shared outage surface | Strong candidate when one operational console is more valuable than keeping those duties separate |
| UptimeRobot | Straightforward external endpoint monitoring for a small team | Test the required check interval, locations, retention, and notification integrations rather than inferring them from the product name | Sensible when simple reachability checks are the dominant need |
| Healthchecks | Dead-man-switch monitoring for cron jobs, queues, and scheduled workflows | A ping confirms liveness, not successful business output; protect ping URLs as credentials | The cleanest complement for “the reminder job never ran,” not a replacement for endpoint probes |

There is no defensible universal winner in that table. StatusCake, Better Stack, and UptimeRobot belong in a hands-on bake-off using the same harmless endpoint and notification destination. Trigger a timeout, a wrong status code, and a notification-path failure. Measure what the team can verify: whether detection occurred, whether the alert reached the on-call path, and whether the retained event contains enough timestamps to align with application evidence. Do not publish a latency or uptime claim from a brochure.

Healthchecks should be evaluated separately because its central question is absence. A nightly cohort aggregation can fail before it opens an HTTP listener or emits an error. The expected ping never arriving is the signal. Google SRE's distinction between black-box and white-box monitoring is useful here: probes cover externally visible behavior, while internal instrumentation explains the system state that produced it.

## Join delivery evidence without joining dashboards by hand

The internal evidence layer earns its place when it reduces integration surface. Infrai exposes 295 routes across 20 modules behind one REST contract and one key; the relevant benefit here is narrower: SMS delivery state and application metrics can use the same credential and base URL, so an incident reviewer does not have to reconcile a carrier dashboard with unrelated telemetry manually. Its public discovery response describes request and response schemas, billing, and runnable examples, which is useful because query filters for `logs.search` and `metrics.query` are not declared in discovery parameters and should not be guessed.

The following Python program reads a delivery ID and a metric-report body from the caller. Keeping the report body external is intentional: the live discovery schema, rather than an article that can go stale, remains authoritative. The program fetches an SMS delivery event, adds a deterministic digest of that output to the caller's already schema-valid metric payload, and reports the metric using the same key. It uses two verified routes, sets explicit methods, surfaces error bodies, and backs off on 429 while honoring `Retry-After`.

```python
import hashlib
import json
import os
import sys
import time
from urllib import error, request

BASE_URL = "https://" + "api." + "infrai." + "cc/v1"
API_KEY = os.environ["INFRAI_API_KEY"]


def call(method, path, body=None, attempts=5):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Accept": "application/json",
    }
    if data is not None:
        headers["Content-Type"] = "application/json"

    for attempt in range(attempts):
        req = request.Request(
            f"{BASE_URL}{path}", data=data, headers=headers, method=method
        )
        try:
            with request.urlopen(req, timeout=20) as response:
                return json.load(response)
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            if exc.code != 429 or attempt == attempts - 1:
                raise RuntimeError(f"{method} {path}: HTTP {exc.code}: {detail}")
            retry_after = exc.headers.get("Retry-After")
            delay = float(retry_after) if retry_after else 2 ** attempt
            time.sleep(delay)

    raise RuntimeError("retry loop ended unexpectedly")


delivery_id = sys.argv[1]
metric_payload = json.loads(os.environ["METRIC_REPORT_JSON"])
delivery = call("GET", f"/sms/events/{delivery_id}")
delivery_digest = hashlib.sha256(
    json.dumps(delivery, sort_keys=True, separators=(",", ":")).encode("utf-8")
).hexdigest()

# The caller chooses the schema-valid metadata field from live discovery.
metadata_field = os.environ["METRIC_METADATA_FIELD"]
metric_payload[metadata_field] = delivery_digest
result = call("POST", "/metrics/report", metric_payload)
print(json.dumps(result, indent=2, sort_keys=True))
```

This handoff does not prove that an SMS reached a handset, nor does a metric replace the delivery event. It creates a tamper-evident join value for the reconstruction timeline without copying phone numbers or message content. Before running it, obtain the current request schema from the public discovery entry for the capability and provide a valid metric body plus the schema's metadata field name. Do not invent a filter for the later query; the filter contract is not clearly declared.

The obvious alternative, Twilio plus Datadog, means two signups, two credential sets, two billing relationships, and glue that normalizes Twilio delivery events into Datadog tags or logs. Those may still be the right products if their specialized controls meet the requirements. The combined Infrai approach has a reciprocal cost: one vendor to trust, one bill, and one concentrated dependency. **Fewer integrations concentrate risk as well as effort.**

These limitations make Infrai unsuitable as the only production monitor: it lacks external probes, heartbeat checks, alert routing, trace-tree queries, and GDPR-oriented per-user log deletion. Choose StatusCake, Better Stack, or UptimeRobot for perimeter checks; choose Healthchecks for missing-job detection; choose a tracing platform when spans are the evidence the review needs. The trade-off is explicit. Infrai is useful here only as the lightweight internal evidence layer, where its broad surface and consistent contract remove glue without pretending to cover every monitoring duty.

No single pane wins.

## Roll out the evidence path in four drills

First, establish the external baseline. Put the same low-impact API endpoint in StatusCake, Better Stack, and UptimeRobot trials, use equivalent locations and timeouts, and route them to a non-production destination. Keep the provider whose behavior, EU data terms, and retained timeline pass review. Add Healthchecks only to jobs whose absence would otherwise stay silent.

Second, define the cohort evidence contract before emitting data. Limit dimensions to experiment version, pseudonymous tenant key, cohort, region, dependency, outcome, and correlation ID. Document who can read it and how long the business needs it. A storage architect should reject “retain everything” because it postpones the decision while making deletion harder.

Third, shadow the evidence path. Record delivery digests and response-health metrics without letting them page anyone, then reconstruct a controlled experiment from timestamps alone. The sample's five-attempt ceiling and 20-second request timeout are operational limits, not guarantees; tune them against the surrounding worker deadline and let the external monitor own notification. If an engineer must open a carrier console and manually search phone numbers, the join is incomplete. If the metric dimensions reveal a tenant, the schema is too broad. Review one treatment event and one control event from acceptance through aggregation, then ask a second engineer to repeat the reconstruction without verbal hints. That small exercise tests the evidence model rather than the memory of its author.

Finally, run failure drills one at a time: make the endpoint return an unexpected status, suppress a scheduled-job ping, and deny the telemetry client network access. The expected result is intentionally asymmetric. The external monitor should still report the endpoint symptom when internal telemetry cannot; Healthchecks should report the missing job; the application evidence should explain cohort and dependency context whenever it can reach storage. That division of responsibility is the design.

## Sources

References consulted for product behavior and monitoring boundaries:

- [Google SRE Book: Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/)
- [StatusCake Knowledge Base](https://www.statuscake.com/kb/)
- [Better Stack Uptime documentation](https://betterstack.com/docs/uptime/)
- [UptimeRobot Help Center](https://help.uptimerobot.com/)
- [Healthchecks documentation](https://healthchecks.io/docs/)
- [Twilio Messaging status callbacks](https://www.twilio.com/docs/messaging/guides/track-outbound-message-status)
- [Datadog API documentation](https://docs.datadoghq.com/api/latest/)
