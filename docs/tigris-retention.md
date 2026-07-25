# Tigris retention

Every push to `main` publishes a full snapshot — ~273 module/target coordinates × 25
files, about 6,800 objects a merge. Nothing removed them, so the Maven tree reached at
least 124,300 version directories (~3.1M objects) and `ListObjectsV2` over it grew slow
enough to break the publish itself ([#1702](https://github.com/tractat-us/kuilt/issues/1702)).

Retention is enforced by **S3 object-lifecycle rules on the bucket**, not by a cleanup
job. The rules live in [`.github/tigris-lifecycle.json`](../.github/tigris-lifecycle.json).

## Why lifecycle rules and not a prune job

A prune job has to enumerate before it can delete, and enumeration is precisely what is
broken here: objects exist in this bucket that no listing returns
([#1704](https://github.com/tractat-us/kuilt/issues/1704) — `0.7.0-dev.945` HEADs at 200
with 2,380 bytes yet never appears in a listing of its own prefix). **You cannot delete
what you cannot enumerate**, so a prune job leaves those objects as permanent orphans.
Lifecycle expiry runs server-side off each object's last-modified time, so it reclaims
them regardless. It is also retroactive, needs no runner, and is a JSON file instead of a
script.

## Scope — read this before widening a prefix

`s3://buildcache/` is shared. Its top-level prefixes:

| Prefix | Contents |
|---|---|
| `maven/us/tractat/kuilt/` | this project's artifacts — **in scope** |
| `maven/us/tractat/{fgn,hanab,logico}/` | **sibling projects — NOT in scope** |
| `cache/` | Gradle remote build cache — in scope |
| `buildcache/`, `maven-sanity/` | bring-up probes, negligible |

The kuilt rule is deliberately scoped to `maven/us/tractat/kuilt/` and **not** `maven/`.
A rule on `maven/` would expire fgn, hanab-kt and logico artifacts too — and consumers
resolve `us.tractat.hanab:*` from this bucket. Those projects have the same unbounded
growth and want their own rules; that is their call, not a kuilt one.

Note that a bare `maven` prefix (no trailing slash) would also match `maven-sanity/`.
Keep the trailing slash.

## What 30 days means

Snapshots are ephemeral by convention; releases are not affected because **no release
lives here** — `v0.7.x` tags publish to Maven Central, and the historical `0.2.x`/`0.3.x`
lines are archived on GitHub Packages. Everything under this prefix is a redundant copy
or a dev snapshot.

The practical constraint is consumer pins. A consumer pinning a snapshot older than 30
days will stop resolving and must bump. At the current merge rate 30 days settles the
tree around 2.5M objects.

Build-cache entries are not re-written on a hit, so a hot entry still expires at 30 days
and is rebuilt once. That is the intended trade.

## Applying a change

```bash
aws --endpoint-url=https://fly.storage.tigris.dev s3api put-bucket-lifecycle-configuration \
  --bucket buildcache --lifecycle-configuration file://.github/tigris-lifecycle.json

# verify
aws --endpoint-url=https://fly.storage.tigris.dev s3api get-bucket-lifecycle-configuration \
  --bucket buildcache
```

Credentials are the same `S3_BUILD_CACHE_*` pair `publish.yml` uses. Tigris caps a bucket
at **10 rules** total and supports **prefix filters only** — no tag or size filters, which
is why the policy cannot say "expire only `*-dev.*`" while snapshots and the legacy
release lines share one flat tree. First deletions land within a few minutes of applying,
or up to ~20 minutes if the sweep just finished.
