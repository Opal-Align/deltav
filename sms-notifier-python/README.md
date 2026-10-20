# SMS / Email Notifier

Sends templated SMS and email messages to patients listed in a CSV, via Azure Communication
Services (ACS). Each patient row is matched to a practice (`practice_id` + `client_id`) looked up
in `DB_Metadata.dbo.Metadata_ClientPractice`, which supplies the practice name and the sending
number/address. Every send attempt is logged to a CSV so re-running the same input never
double-sends to someone already messaged successfully.

## 1. Prerequisites

- Python 3.9+
- [Microsoft ODBC Driver 18 for SQL Server](https://learn.microsoft.com/en-us/sql/connect/odbc/download-odbc-driver-for-sql-server) installed on your machine (required by `pyodbc`)
- Network access to the SQL Server hosting `DB_Metadata`
- An Azure Communication Services resource connection string (SMS and/or Email, both use the same resource)

## 2. Setup

Run once, from this directory (`sms-notifier-python/`):

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Create a `.env` file (copy `.env.example`) with your real values:

```bash
cp .env.example .env
```

```
SQL_CONNECTION_STRING=Driver={ODBC Driver 18 for SQL Server};Server=tcp:<server>,<port>;Database=DB_Metadata;UID=<user>;PWD=<password>;Encrypt=no;TrustServerCertificate=yes;
AZURE_COMMUNICATION_CONNECTION_STRING=endpoint=https://<resource-name>.communication.azure.com/;accesskey=<key>
```

`.env` is gitignored — never commit it. Both scripts load it automatically on startup.

Every time you open a new terminal to run these scripts, re-activate the venv first:

```bash
cd sms-notifier-python
source .venv/bin/activate
```

## 3. CSV input format

Column order doesn't matter — columns are matched by name (case-insensitive), and extra columns
in the file are simply ignored.

**SMS** (`send_sms.py`) requires: `first_name, last_name, phone_number, practice_id, client_id`
**Email** (`send_email.py`) requires: `first_name, last_name, email, practice_id, client_id`

Phone numbers are auto-normalized regardless of format — `(202) 555-1234`, `202-555-1234`,
`+12025551234`, `2025551234` all work. Blank, `null`, `none`, `n/a` phone/email values are skipped
automatically (logged as a warning, not an error).

See `patients_sample.csv` / `patients_email_sample.csv` for examples.

## 4. Running

Always preview with `--dry-run` first — it loads everything and prints what *would* be sent
without actually sending or writing to the sent-log:

```bash
python3 send_sms.py --csv your_patients.csv --dry-run
python3 send_email.py --csv your_patients.csv --dry-run
```

When the preview looks right, drop `--dry-run` to actually send:

```bash
python3 send_sms.py --csv your_patients.csv
python3 send_email.py --csv your_patients.csv
```

### What happens on a real run

1. Connects to `DB_Metadata` and loads the practice table.
2. For each CSV row, resolves `practice_id`/`client_id` against that table; skips (with a logged
   reason) any row whose practice doesn't exist, is inactive, has a `client_id` mismatch, or has no
   usable sending number/address (`collect_phone_number` for SMS, `email_sender_id` for email).
3. Filters out anyone already logged as a successful send (see **Sent-log tracking** below).
4. Sends to everyone remaining, pausing periodically to avoid overwhelming carriers/ACS (see
   **Batching** below).
5. Prints a final `Sent: X, Failed: Y, Total: Z` summary.

### Useful flags (same on both scripts)

| Flag | Default | Purpose |
|---|---|---|
| `--csv <path>` | *(required)* | Input CSV |
| `--dry-run` | off | Preview only, no send, no log write |
| `--batch-size <n>` | 50 | Pause after every N messages |
| `--batch-pause-seconds <s>` | 5.0 | How long to pause between batches |
| `--log-file <path>` | `sent_log.csv` / `sent_email_log.csv` | Where sent/failed outcomes are recorded |
| `--resend` | off | Ignore the log and message everyone in the CSV again |

Example with custom pacing:

```bash
python3 send_sms.py --csv your_patients.csv --batch-size 10 --batch-pause-seconds 10
```

## 5. Sent-log tracking (avoiding duplicate sends)

Every attempt (success or failure) is appended to the log file as it happens — so if the script is
interrupted partway through, progress is not lost. On the next run, any `(practice_id, phone/email)`
already logged with `status=success` is automatically skipped. Only successes are skipped — failed
attempts are retried automatically on the next run.

To deliberately message everyone again regardless of the log, add `--resend`.

The log files (`sent_log.csv`, `sent_email_log.csv`) contain patient names, numbers, and emails —
they are gitignored and must never be committed.

## 6. Checking results

Each log has a matching stats script that prints per-practice success/failed counts:

```bash
python3 sms_stats.py --log-file sent_log.csv
python3 email_stats.py --log-file sent_email_log.csv
```

Example output:

```
Practice ID  Practice Name                             Success   Failed    Total
--------------------------------------------------------------------------------
1012         ADA Wesley Chapel                              42        1       43
1015         Advanced Dental - Lakeland                     12        0       12
--------------------------------------------------------------------------------
TOTAL                                                       54        1       55
```

If the log file doesn't exist yet, it just prints `No rows found in <path>` — no error.

## 7. Editing the message

- SMS text: edit `MESSAGE_TEMPLATE` at the top of `send_sms.py`.
- Email subject/body: edit `EMAIL_SUBJECT_TEMPLATE` / `EMAIL_BODY_TEMPLATE` at the top of `send_email.py`.

Both templates can reference `{first_name}`, `{last_name}`, and `{practice_name}`.

## 8. Troubleshooting

- **`SQL_CONNECTION_STRING environment variable is required`** — `.env` is missing or not in the
  current directory; confirm you're running from `sms-notifier-python/` and `.env` exists.
- **ODBC driver errors on connect** — install ODBC Driver 18 for SQL Server for your OS.
- **`AZURE_COMMUNICATION_CONNECTION_STRING environment variable is required`** — same as above, for
  the ACS key.
- **Row skipped: `client_id mismatch`** — the CSV's `practice_id`/`client_id` pair doesn't match
  what's in `Metadata_ClientPractice`; double-check the source data.
- **Row skipped: `no usable collect_phone_number` / `no usable email_sender_id`** — that practice's
  metadata row doesn't have a valid sending number/address configured; nothing can be sent for it
  until that's fixed in the database.
- **Email send fails even though dry-run looked fine** — the `email_sender_id` address must be a
  verified sender on a domain provisioned in the ACS resource's Email channel; a connection string
  alone isn't enough if Email hasn't been set up on that resource.
