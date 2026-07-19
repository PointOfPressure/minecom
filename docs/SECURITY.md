# Security Policy

Minecom is a vanilla-parity Minecraft server built on
[Minestom](https://github.com/Minestom/Minestom). Because it accepts
untrusted network input from the public internet, we take security reports
seriously and aim to respond quickly.

This policy covers **minecom's own code**. Vulnerabilities in Minestom, the
JVM, or Mojang's protocol are handled through coordinated disclosure with the
relevant upstream project (see [Scope](#scope) and
[Upstream coordination](#upstream-coordination)).

## Supported versions

Minecom ships tagged releases and tracks a specific Minestom build per
release (currently `2026.07.12-26.2`, declared in `pom.xml`). Security fixes
land on the latest tagged release; there is no long-term-support branch.

| Version | Supported |
|---|---|
| Latest tagged release | ✅ Security fixes |
| Older tags | ❌ Upgrade to latest |
| `main` (unreleased) | ✅ Best effort |

If you run minecom, run the latest tag. When a fix requires bumping the
pinned Minestom version, the release notes will say so.

## Reporting a vulnerability

**Do not open a public GitHub issue for a security vulnerability.** Public
issues are for non-sensitive bugs only.

Report privately through **either** of:

1. **GitHub private security advisories** (preferred) — on the
   `PointOfPressure/minecom` repository, use
   *Security → Advisories → Report a vulnerability*. This opens a private
   thread visible only to the maintainer and you, and supports coordinated
   disclosure and CVE assignment.
2. **Email** — `s4yerh4rris0n@gmail.com` with a subject beginning
   `[minecom-security]`. If you want to encrypt, ask in a first plaintext
   message with no sensitive detail and we will exchange a key.

Please include:

- The minecom version/tag (or commit) and the pinned Minestom version.
- What the issue is and the impact (crash, resource exhaustion, data loss,
  auth bypass, RCE, …).
- The minimum steps or conditions to reproduce. A description of the
  mechanism is enough — **you do not need to send a weaponized exploit**,
  and please do not attach anything designed to harm a live third party.
- Whether you have already told anyone else (including Minestom or Mojang).

### What to expect

- **Acknowledgement within 72 hours** that we received the report.
- An initial assessment (is it in scope, rough severity) within **7 days**.
- Regular updates while we work a fix; we will tell you our planned
  disclosure date and coordinate it with you.
- **Credit** in the advisory and release notes if you want it (or a
  strictly anonymous handling if you prefer).

We are a small, single-maintainer project — timelines are targets made in
good faith, not an SLA. If a report is complex or depends on an upstream
fix, we will say so and keep you informed rather than go quiet.

### Safe-harbor

We will not pursue or support any action against researchers who:

- test **only against their own instance** of minecom (never against a
  server they do not own or have written permission to test),
- avoid privacy violations, data destruction, and service degradation for
  other users,
- give us reasonable time to fix before public disclosure, and
- do not exfiltrate more data than needed to demonstrate the issue.

Good-faith research under these terms is welcome, and we will treat you as
a collaborator, not an adversary.

## Scope

**In scope** — report these:

- Remote crashes, hangs, or resource exhaustion (CPU, memory, threads,
  file descriptors, disk) reachable from an unauthenticated network
  connection to minecom.
- Malformed- or oversized-packet handling gaps in minecom's own listeners,
  event handlers, or persistence paths.
- Logic flaws in minecom code that let a client corrupt world/player data,
  escape intended limits, or affect other players' sessions.
- Authentication/authorization gaps in any deployment configuration minecom
  documents as supported.

**Out of scope / handled elsewhere:**

- Vulnerabilities in **Minestom**, the **JVM**, or **Mojang's client or
  protocol** — we will forward or co-report these upstream (see below), but
  the fix is theirs. Report Minestom-only issues to Minestom directly if you
  can; tell us too so we can pin a fixed build.
- Issues that require operating-system, hosting-panel, or network
  misconfiguration outside minecom's control (though we welcome
  documentation fixes for our deployment guidance).
- **Offline-mode deployments run intentionally without a proxy or
  authentication.** Offline mode has no player authentication by design;
  running it exposed to the public internet is an operator choice, not a
  minecom vulnerability. See `docs/SECURITY-HARDENING.md` for the supported
  hardened deployment shapes.
- Social engineering, physical attacks, and anything targeting the
  maintainer rather than the software.

## Upstream coordination

Minecom sits on top of Minestom, which sits on top of Mojang's protocol. A
single network-facing weakness can belong to any of those layers, and some
belong to more than one.

- When a report is **minecom's** to fix, we fix it here and release.
- When it is **Minestom's**, we coordinate privately with the Minestom
  maintainers first, ship a local mitigation in minecom if one is possible
  (e.g. a stricter listener-layer bound, a tuned `ServerFlag`, or a
  deployment recommendation) so minecom users are protected while the
  upstream fix lands, and pin the fixed Minestom build once it releases.
- When it is **Mojang's** protocol-level issue, we defer to Mojang's
  process and mitigate locally where we can.

Minecom is committed to **responsible, coordinated disclosure**: we will not
publish the details of an unfixed upstream vulnerability, and we ask
reporters to extend the same courtesy while a coordinated fix is in
progress. Our current threat analysis and the specific mitigations we
maintain against protocol-level resource-exhaustion attacks are documented
in `docs/SECURITY-HARDENING.md`.
