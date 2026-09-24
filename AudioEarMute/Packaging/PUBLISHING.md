# Publishing AudioEarMute to the Microsoft Store

## How hard is this, honestly

**Low difficulty, mostly waiting.** Roughly half a day of actual work, then up to three
business days of certification.

The two things that used to make this painful are gone:

- **The registration fee is gone.** Microsoft removed it for both individual and company
  accounts. It used to be $19 for individuals and $99 for companies. You must start at
  <https://storedeveloper.microsoft.com> — other entry points still show the old paid flow.
- **Code signing is free and automatic.** The Store re-signs your package with its own
  certificate. No certificate authority, no $200/year EV certificate, no SmartScreen
  reputation problem.

What is actually left to do:

| Step | Effort | Blocking on |
|---|---|---|
| Developer account + identity verification | 20 min | Government ID and a selfie |
| Reserve the app name | 2 min | Name being free |
| Build the MSIX | 10 min | .NET SDK and Windows SDK installed |
| Screenshots | 30 min | You, running the app |
| Listing text, age rating, pricing | 30 min | Already drafted, see `StoreListing.md` |
| Certification | 1–3 business days | Microsoft |

The genuinely annoying parts, in order:

1. **Identity verification.** Individual accounts need a government-issued ID and a selfie
   taken on a phone. Nothing you can prepare in advance.
2. **Toolchain install.** This machine has neither the .NET SDK nor the Windows SDK, so
   nothing can be packaged until both are installed. See below.
3. **Screenshots.** Fiddly rather than hard. Certification does look at them.

There is no separate technical review to pass beyond the automated checks. This app is
unusually easy to certify: no network, no accounts, no data collection, no age-sensitive
content, no in-app purchases.

### The easier alternative, and why not to take it

Since June 2021 the Store also accepts plain `.exe` and `.msi` installers. You just give
Partner Center a URL to an offline installer, and skip MSIX entirely.

It is less work up front but worse afterwards:

- You have to host the installer yourself, at a URL that stays stable
- The Store does not sign it, so users get a SmartScreen warning on an unsigned binary
- No automatic updates through the Store
- You would have to write an installer, which this project does not have

Given that MSIX packaging is a single script here, the MSIX path is the better trade.

---

## Prerequisites

Neither SDK is installed on this machine. Both are needed:

```bash
winget install Microsoft.DotNet.SDK.8
```

```bash
winget install Microsoft.WindowsSDK.10.0.26100
```

The Windows SDK is only needed for `makeappx.exe` and `signtool.exe`. If the installer
offers component selection, "MSIX Packaging Tools" and "Windows SDK Signing Tools" are
enough.

Open a fresh terminal afterwards so `dotnet` lands on `PATH`.

---

## Step 1 — Developer account

1. Go to <https://storedeveloper.microsoft.com> — **not** Partner Center directly, or you
   get the legacy paid flow.
2. Click **Get started for free**, choose **Individual developer**.
3. Sign in with a personal Microsoft account. A work account will not work for individual
   accounts.
4. Complete identity verification: government-issued ID plus a selfie, captured on a
   phone in good light with the original document.
5. Wait for the dashboard. It can take about five minutes to appear.

Pick **Individual** unless you are publishing as a registered business. Switching from
Individual to Company later is not supported — you would have to create a new account.

---

## Step 2 — Reserve the name

1. Open <https://aka.ms/submitwindowsapp>
2. **New product** → **MSIX app**
3. Enter `AudioEarMute`, click **Check availability**, then **Reserve product name**

Reserving is free and holds the name for three months.

---

## Step 3 — Get your package identity

In Partner Center: your app → **Product management** → **Product identity**.

Copy three values:

- **Package/Identity/Name** — looks like `12345Greg.AudioEarMute`
- **Package/Identity/Publisher** — looks like `CN=A1B2C3D4-1234-5678-9ABC-DEF012345678`
- **Package/Properties/PublisherDisplayName** — your publisher display name

These must match exactly or the upload is rejected.

---

### This app's identity

Reserved on 31 July 2026. These are not secrets — they end up inside every shipped
package — so they are recorded here to save a trip to Partner Center.

| Field | Value |
|---|---|
| `Package/Identity/Name` | `GGVP.AudioEarMute` |
| `Package/Identity/Publisher` | `CN=A8E7E4DD-34EE-4119-BEF0-5D1F26B464CE` |
| `Package/Properties/PublisherDisplayName` | `GGVP` |
| Package Family Name | `GGVP.AudioEarMute_98jkmyw2pfpfg` |
| Store ID | `9N640PVFCZSL` |

---

## Step 4 — Build the package

```bash
powershell -ExecutionPolicy Bypass -File Packaging\Build-Msix.ps1 -IdentityName "GGVP.AudioEarMute" -IdentityPublisher "CN=A8E7E4DD-34EE-4119-BEF0-5D1F26B464CE" -PublisherDisplayName "GGVP"
```

The script runs the unit tests, regenerates the assets, publishes self-contained for
win-x64, substitutes the identity into the manifest and packs `Packaging\out\AudioEarMute.msix`.

It refuses to package if the tests fail. That is intentional.

### Trying the package before submitting

```bash
powershell -ExecutionPolicy Bypass -File Packaging\Build-Msix.ps1 -IdentityName "..." -IdentityPublisher "..." -PublisherDisplayName "..." -SignForLocalTesting
```

This adds a self-signed signature and prints the one elevated command needed to trust the
test certificate. Install it, confirm the app appears in Settings → Apps → Startup, then
rebuild **without** `-SignForLocalTesting` for the real submission.

Worth checking in the installed package specifically:

- The tray menu shows "Start at logon — manage in Windows Settings" rather than a
  checkbox. That confirms `PackageInfo.IsPackaged` detection works.
- The app appears under Settings → Apps → Startup, switched off by default.
- Diagnostics still reports the verdict line.

---

## Step 5 — Complete the submission

Fill each tab. `StoreListing.md` has the copy ready to paste.

| Tab | What to do |
|---|---|
| **Pricing and availability** | Free. All markets. No trial. |
| **Properties** | Category *Utilities & tools*. Privacy policy URL required — publish `PrivacyPolicy.md` somewhere public first, e.g. a GitHub Pages page or a Gist. |
| **Age ratings** | IARC questionnaire. Answer no to everything: no violence, no user interaction, no data collection, no purchases. Result will be the lowest rating. |
| **Packages** | Upload `Packaging\out\AudioEarMute.msix`. |
| **Store listings** | Paste from `StoreListing.md`, upload screenshots. |
| **Submission options** | Paste the certification notes from `StoreListing.md`. |

Then **Submit for certification**.

---

## Step 6 — After submission

Certification takes up to three business days. If it passes, the listing appears within
about 15 minutes.

If it fails, Partner Center says which test failed and you resubmit. The common causes
for a utility like this are a privacy policy URL that does not resolve, and screenshots
below the minimum resolution.

---

## What changed in the app for the Store

The app now detects whether it runs packaged and behaves differently:

| | Unpackaged build | Store build |
|---|---|---|
| Start at logon | Task Scheduler task the app creates | `windows.startupTask` in the manifest |
| Who owns the switch | The tray menu checkbox | Windows Settings → Apps → Startup |
| Registry writes | `Run` key cleanup only | None |

Detection is `PackageInfo.IsPackaged`, which calls `GetCurrentPackageFullName`. That is a
plain P/Invoke, so the project keeps its `net8.0-windows` target and needs no Windows SDK
reference just to answer the question.

This split matters for certification. A packaged app writing to `HKCU\...\Run` or shelling
out to `schtasks.exe` gets its writes virtualised at best, and flagged at worst.

---

## Files in this folder

| File | Purpose |
|---|---|
| `AppxManifest.xml` | MSIX manifest with identity placeholders |
| `Generate-Assets.ps1` | Draws all 29 logo PNGs in code |
| `Build-Msix.ps1` | Test → publish → stage → pack |
| `StoreListing.md` | Listing copy, search terms, screenshot plan, certification notes |
| `PrivacyPolicy.md` | Privacy policy to publish at a public URL |
| `Assets/` | Generated PNGs — regenerate rather than edit |
