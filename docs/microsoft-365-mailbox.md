# Collecting from a Microsoft 365 mailbox

Microsoft removed Basic authentication from Exchange Online. No password will open
an IMAP session against a Microsoft 365 mailbox — **not even an app password**, and
app passwords are themselves being retired. A deployment whose DMARC reports arrive
at a Microsoft address therefore cannot use the IMAP path at all.

Those mailboxes are read through **Microsoft Graph** instead: the application
authenticates as itself against Entra ID, and reads the mailbox over HTTPS. There
is no port 993, so a firewall that blocks legacy mail ports — which is most of
them — is not in the way.

> **Google Workspace is different and needs none of this.** Gmail still accepts an
> app password over IMAP. Choose *Gmail, or any IMAP server* in the mailbox form
> and enter `imap.gmail.com`, the address, and an app password.

---

## What you will end up with

Three values to paste into the mailbox form, plus one permission granted once.

| Value | Where it comes from |
|---|---|
| Directory (tenant) ID | Entra ID → Overview |
| Application (client) ID | Your app registration → Overview |
| Client secret | Your app registration → Certificates & secrets |

---

## 1. Register the application

Entra ID → **App registrations** → **New registration**.

- **Name** — something a colleague will recognise in an audit, for example
  `DMARC report collector`.
- **Supported account types** — *Accounts in this organizational directory only*.
- **Redirect URI** — leave empty. The application signs in as itself; there is no
  user to send back anywhere.

From the **Overview** page that follows, copy the **Directory (tenant) ID** and the
**Application (client) ID**.

## 2. Grant permission to read mail

In the registration: **API permissions** → **Add a permission** → **Microsoft
Graph** → **Application permissions** → `Mail.Read`.

Then **Grant admin consent**. Until an administrator does, every collection run
fails with a permissions error.

> **Application** permission, not **Delegated**. Delegated permissions act on
> behalf of a signed-in person, and this runs on a schedule with nobody present.

## 3. Restrict it to the one mailbox

Step 2 grants the application read access to **every mailbox in the tenant**. That
is far more than collecting DMARC reports requires, and it should not be left that
way. Exchange Online scopes it with an *application access policy*.

In Exchange Online PowerShell:

```powershell
Connect-ExchangeOnline

# The group whose mailboxes the application may read. One member: the report mailbox.
New-DistributionGroup -Name "DMARC collector scope" `
  -Alias dmarc-collector-scope `
  -Type Security `
  -Members dmarcreports@yourcompany.com

New-ApplicationAccessPolicy `
  -AppId "<the Application (client) ID>" `
  -PolicyScopeGroupId dmarc-collector-scope@yourcompany.com `
  -AccessRight RestrictAccess `
  -Description "Restricts the DMARC collector to the report mailbox"
```

Check it before moving on — this is the step that either worked or silently did not:

```powershell
Test-ApplicationAccessPolicy -Identity dmarcreports@yourcompany.com -AppId "<client id>"
# AccessCheckResult : Granted

Test-ApplicationAccessPolicy -Identity someone.else@yourcompany.com -AppId "<client id>"
# AccessCheckResult : Denied
```

A policy can take a few minutes to take effect.

## 4. Create a client secret

**Certificates & secrets** → **New client secret**. Copy the **Value** — not the
Secret ID — immediately; the portal never shows it again.

**Note the expiry date.** Entra ID secrets expire, commonly after six or twelve
months, and collection stops the day they do. The mailbox card reports the failure
and names the cause, but nothing renews it for you.

## 5. Configure the mailbox

In the dashboard: **Administration → Mailbox collection → Microsoft 365**, then
enter the mailbox address, the directory ID, the client ID and the secret. Save,
then press **Collect now** rather than waiting for the schedule — a mistake shows
up immediately rather than in fifteen minutes.

---

## When it does not work

The application turns Microsoft's error codes into something actionable, so read
the last-run line on the mailbox card first.

| What you see | What it means |
|---|---|
| *The client secret is wrong or has expired* | `AADSTS7000215`. Usually expiry. Create a new secret and save it here. |
| *No application with this client id exists in that directory* | The client ID and tenant ID belong to different directories, or the ID was mistyped. |
| *That tenant id does not exist* | The **Directory (tenant) ID** is a GUID from the Entra ID overview, not your domain name. |
| *Microsoft accepted the application but refused the mailbox* | HTTP 403. Either admin consent was never granted for `Mail.Read`, or an application access policy excludes this mailbox. Run `Test-ApplicationAccessPolicy`. |
| *No mailbox found at …* | HTTP 404. The address is wrong, or the mailbox has no licence. Shared mailboxes work; unlicensed user accounts do not. |

## What the application does and does not do

It reads. The registration is granted `Mail.Read` and nothing else, so it cannot
send, move, delete or mark anything as read — the mailbox looks untouched to whoever
else uses it.

Each run asks only for messages that carry attachments and arrived since shortly
before the last successful run, newest first, up to two hundred. The window
overlaps by a day on purpose: a duplicate costs nothing because a report already
held is recognised by its id, whereas a gap costs a report nobody will look for
again.

Once an attachment's bytes are in hand they go through exactly the same ingestion
as an IMAP attachment or an uploaded file — the same format sniffing, the same
bounded decompression, the same per-tenant deduplication.
