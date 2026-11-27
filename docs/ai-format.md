# JNote JSON format v1

This is the one contract for writing notes into JNote from outside the IDE. AI agents, the Claude Code skill, web AIs given the copied instructions, and people editing an export all write this format. JNote reads it through import (from a file, the clipboard, or the inbox), and every import passes a preview that a person accepts or cancels. Nothing writes to the database file directly.

JNote never calls an AI. It only reads files and the clipboard that you or your agent give it.

## Document

```json
{
  "format_version": 1,
  "notes": [ ... ]
}
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `format_version` | integer | yes | Always `1` for this spec. Any other value rejects the whole document. |
| `notes` | array of note objects | yes | May be empty. |

A document that is not valid JSON, is not an object, or lacks either field is rejected as a whole and nothing is imported.

Write standard JSON and output exactly one object. The parser rejects comments, trailing commas, single quotes, unquoted keys or strings, and anything after the closing `}`. It happens to tolerate a few other deviations (a raw line break inside a string, `TRUE` in upper case), but do not rely on them.

## Note

| Field | Type | Required | Meaning |
|---|---|---|---|
| `uid` | string | no | Stable identity of the note. Leave it out to create a new note. Copy it from an export or from `v_notes_export` to update that note. |
| `kind` | `"CODE"` or `"FREE"` | yes | `CODE` is anchored to lines of a file and needs `anchor`. `FREE` (a query, a memo) must not have `anchor`. Upper case only. |
| `title` | string | yes | Not blank. Shown in the list. |
| `body` | string | no | Plain text, no Markdown rendering. Default empty. |
| `tags` | array of strings | no | Tag names. Surrounding spaces are trimmed and duplicates are merged. A blank name or a name containing a comma is invalid. Unknown tags are registered automatically. |
| `anchor` | object | for `CODE` | See below. |
| `ai_source` | string | no | Who wrote the note, free text such as `"Claude"`, `"ChatGPT"`, `"Gemini"`. |
| `source_url` | string | no | Link to the conversation or page the note came from. |
| `prompt` | string | no | The question or prompt that produced the note. It is searchable. |

## Anchor

| Field | Type | Required | Meaning |
|---|---|---|---|
| `repo_url` | string | one of `repo_url` / `project_path` | The git origin URL as git prints it (`git remote get-url origin`). Any remote URL with a host and a path works: `git@github.com:org/repo.git`, `https://github.com/org/repo`, `ssh://git@host:2222/org/repo.git`. JNote normalizes it into its project key, so every clone and worktree of the repository sees the note. A local path origin (`/srv/repos/shop.git`, `file://...`) is not a remote URL and makes the item invalid; use `project_path` then. |
| `project_path` | string | one of `repo_url` / `project_path` | The project key itself, used verbatim. Use it in two cases: when you copy a note from `v_notes_export` (put its `project` column here), and when the project has no remote origin (put the absolute path of the folder opened in the IDE here). For a new note in a repository with a remote origin, use `repo_url`. |
| `path` | string | yes | File path relative to the project root, with `/` separators, such as `src/main/kotlin/App.kt`. Absolute paths and `..` segments are invalid. |
| `start_line` | integer | yes | First line, 1-based. |
| `end_line` | integer | yes | Last line, inclusive, at least `start_line`. |
| `class_name` | string | no | Enclosing class or file name. Helps JNote find the code again after the file moves. |
| `snapshot` | string | no | The exact text of the lines `start_line` to `end_line`. JNote uses it to relocate the note when lines shift, so copy it verbatim. |

Exactly one of `repo_url` and `project_path` must be present. Line numbers are plain integers: `12`, not `12.0` or `"12"`.

The project root is the folder opened in the IDE. When the IDE opens the repository root (the usual case), `path` is relative to the repository root.

## Export-only fields

Export also writes these for people reading the file. Import ignores them.

| Field | Meaning |
|---|---|
| `status` | `OK`, `APPROX` (approximate location), `ORPHAN` (code not found), or `NEEDS_RELOCATE`. A new code note always starts as `OK`. |
| `created_at`, `updated_at` | ISO-8601 UTC times. JNote sets them itself. |

Export writes `repo_url` as `https://` plus the project key, such as `https://github.com/org/repo`. JNote keeps only the normalized key, so this is an equivalent spelling of the repository, not necessarily the origin URL you cloned with. When that spelling would not read back as the same key (a project without a remote origin, or an unusual key), export writes `project_path` instead.

## Null and unknown fields

- A field whose value is `null` counts as absent.
- Unknown fields are ignored at every level, so a document from a newer minor revision still imports. A change in meaning gets a new `format_version`.

## Import rules

- **Create or update by `uid`.** An item whose `uid` matches an existing note updates that note. An item with an unknown `uid` creates a note with that `uid`. An item without `uid` creates a note with a new `uid`.
- **Always send the whole note. Fields you leave out are cleared.** An update replaces every field with the item's values: a missing `body` becomes empty, missing `tags` remove all tags, missing `ai_source`, `source_url`, or `prompt` become empty. To change one field, start from the full note (an export or a row of `v_notes_export`) and send all of it back.
- An update that keeps the same project, `path`, `start_line`, and `end_line` keeps the note's location status. An update that changes any of them marks the location `OK`. Changing only `class_name` or `snapshot` keeps the status.
- **Import never deletes notes.** Leaving a note out of a document does nothing to it.
- **Invalid items are skipped, valid ones are imported.** JNote reports each skipped item's position, `uid`, field, and problem (missing, wrong type, or invalid value) in one notification.
- Two items with the same `uid` in one document are both skipped.
- Nothing is written until a person reviews the preview (new notes, updated notes, and their titles) and clicks Apply. A backup `jnote.pre-import.bak` is made right before applying.

## Where to put a document

- **Inbox (agents).** Save the document as a `.json` file in `~/.jnote/inbox/`. When the JNote Tool Window gains focus, JNote offers to review waiting files and moves processed files to `~/.jnote/inbox/done/`. Use a new file name per batch, such as `2026-11-27-retry-notes.json`.
- **Clipboard (web AIs).** Copy the JSON the AI produced and import it from the clipboard.
- **File.** Import any `.json` file from Tools > JNote.

## Reading notes

Agents read notes through the read-only SQLite view `v_notes_export` in `~/.jnote/jnote.db` (or the path set in Settings > Tools > JNote), for example `sqlite3 -readonly ~/.jnote/jnote.db "SELECT uid, title, tags FROM v_notes_export"`. Its columns are `uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, status, title, body, ai_source, source_url, prompt, created_at, updated_at, tags` (`tags` comma-separated, `project` is the normalized key, times in epoch milliseconds). The view keeps these names across versions. Never write to the database.

### Turning a view row into a note

A row is flat, a note is not. To send a row back as an update:

| Note field | From the row |
|---|---|
| `uid`, `kind`, `title`, `body`, `ai_source`, `source_url`, `prompt` | the column of the same name (`NULL` may be left out) |
| `tags` | `tags` split at commas; `NULL` means `[]` |
| `anchor.project_path` | `project` (CODE only) |
| `anchor.path` | `rel_path` |
| `anchor.start_line`, `anchor.end_line`, `anchor.class_name`, `anchor.snapshot` | `start_line`, `end_line`, `class_name`, `snapshot` |

A FREE row has `NULL` anchor columns and gets no `anchor`. `status`, `created_at`, and `updated_at` are not needed.

## Examples

### A free note

```json
{
  "format_version": 1,
  "notes": [
    {
      "kind": "FREE",
      "title": "Find orders stuck in PENDING",
      "body": "SELECT id, created_at FROM orders\nWHERE status = 'PENDING' AND created_at < now() - interval '1 hour';",
      "tags": ["sql", "orders"],
      "ai_source": "ChatGPT"
    }
  ]
}
```

### A code note written by an agent

```json
{
  "format_version": 1,
  "notes": [
    {
      "kind": "CODE",
      "title": "Why retry backs off exponentially",
      "body": "The payment gateway rate-limits bursts; linear retries got the account throttled in the 2026-10 incident.",
      "tags": ["payments", "retry"],
      "anchor": {
        "repo_url": "git@github.com:acme/shop.git",
        "path": "src/main/kotlin/shop/payment/Retry.kt",
        "start_line": 42,
        "end_line": 45,
        "class_name": "Retry",
        "snapshot": "fun delayFor(attempt: Int): Duration {\n    val base = 200.milliseconds\n    return base * 2.0.pow(attempt).toInt()\n}"
      },
      "ai_source": "Claude",
      "prompt": "Why does Retry use exponential backoff?"
    }
  ]
}
```

### An update to an exported note

Start from the exported note, change what you need, and send the whole note back with its `uid`. Here the tags gain `"incident"`; every other field is sent unchanged.

```json
{
  "format_version": 1,
  "notes": [
    {
      "uid": "0b6f7f0e-8a43-4d7e-9a39-2f0c5d1f6a10",
      "kind": "CODE",
      "title": "Why retry backs off exponentially",
      "body": "The payment gateway rate-limits bursts; linear retries got the account throttled in the 2026-10 incident.",
      "tags": ["incident", "payments", "retry"],
      "anchor": {
        "repo_url": "https://github.com/acme/shop",
        "path": "src/main/kotlin/shop/payment/Retry.kt",
        "start_line": 42,
        "end_line": 45,
        "class_name": "Retry",
        "snapshot": "fun delayFor(attempt: Int): Duration {\n    val base = 200.milliseconds\n    return base * 2.0.pow(attempt).toInt()\n}"
      },
      "ai_source": "Claude",
      "prompt": "Why does Retry use exponential backoff?",
      "status": "OK",
      "created_at": "2026-11-20T08:15:00Z",
      "updated_at": "2026-11-21T10:02:41Z"
    }
  ]
}
```

### Retagging a note read from `v_notes_export`

The row `uid = 0b6f7f0e-...`, `kind = CODE`, `project = github.com/acme/shop`, `tags = payments,retry` (other columns as in the example above) becomes the note below, with `"incident"` added. Every column is carried over, because fields left out are cleared.

```json
{
  "format_version": 1,
  "notes": [
    {
      "uid": "0b6f7f0e-8a43-4d7e-9a39-2f0c5d1f6a10",
      "kind": "CODE",
      "title": "Why retry backs off exponentially",
      "body": "The payment gateway rate-limits bursts; linear retries got the account throttled in the 2026-10 incident.",
      "tags": ["incident", "payments", "retry"],
      "anchor": {
        "project_path": "github.com/acme/shop",
        "path": "src/main/kotlin/shop/payment/Retry.kt",
        "start_line": 42,
        "end_line": 45,
        "class_name": "Retry",
        "snapshot": "fun delayFor(attempt: Int): Duration {\n    val base = 200.milliseconds\n    return base * 2.0.pow(attempt).toInt()\n}"
      },
      "ai_source": "Claude",
      "prompt": "Why does Retry use exponential backoff?"
    }
  ]
}
```

## Prompt examples

### For a web AI (ChatGPT, Gemini)

> Summarize what we found as JNote notes. Output only one JSON object in JNote JSON format v1, with no text before or after it:
> `{"format_version": 1, "notes": [...]}`. Each note has `kind` ("FREE" for notes not tied to code, "CODE" for notes about specific lines), `title`, `body` (plain text), `tags` (array of short names without commas), and `ai_source` set to your name. A CODE note also has `anchor` with `repo_url` (the git origin URL I give you), `path` relative to the project root I give you (the folder I open in my IDE), `start_line`, `end_line` (1-based integers), and `snapshot` (the exact text of those lines). To change an existing note, include its `uid` and always send the whole note; fields you leave out are cleared. Use strict JSON: double quotes, no comments, no trailing commas.

### For a coding agent with a shell

> Save the following as a JNote note. Run `git remote get-url origin` for `repo_url`, use the file path relative to the folder opened in the IDE (usually the repository root), and copy the exact text of the lines into `snapshot`. Write one JNote JSON format v1 document (see docs/ai-format.md) to `~/.jnote/inbox/<date>-<topic>.json` and do not touch `~/.jnote/jnote.db`. When updating an existing note, read it from `v_notes_export` first and always send the whole note; fields you leave out are cleared.

## Validation reference

When an item is skipped, the notification names the field and one of these problems.

| Field | Missing | Wrong type | Invalid |
|---|---|---|---|
| (the item) | | not an object | |
| `uid` | | not a string | blank |
| `kind` | absent | not a string | not `CODE` or `FREE` |
| `title` | absent | not a string | blank |
| `body`, `ai_source`, `source_url`, `prompt` | | not a string | |
| `tags` | | not an array of strings | blank name or comma |
| `anchor` | absent on `CODE` | not an object | present on `FREE` |
| `anchor.repo_url` | neither it nor `project_path` | not a string | does not look like a git URL with host and path |
| `anchor.project_path` | | not a string | blank, or given together with `repo_url` |
| `anchor.path` | absent | not a string | blank, absolute, or contains `..` |
| `anchor.start_line`, `anchor.end_line` | absent | not an integer | below 1, or `end_line` before `start_line` |
| `anchor.class_name`, `anchor.snapshot` | | not a string | |

## Versioning

`format_version` changes only when an existing field changes meaning or a new field becomes required. Adding an optional field keeps version 1; readers ignore fields they do not know. JNote rejects a document with a version it does not support, so update the plugin before importing a newer document.
