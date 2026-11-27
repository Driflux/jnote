---
name: jnote
description: Save notes about code or anything else into the JNote IntelliJ plugin. Use when the user asks to save, remember, or note something "in JNote" or "as a note", or to keep an explanation of some code lines for later.
---

# JNote

JNote is an IntelliJ plugin that keeps personal notes, anchored to code lines or free, in a local SQLite file. You never write to that file. You write a JSON document into the inbox, and the user reviews it in the IDE before anything is saved.

The format is JNote JSON format v1. Its full rules are in `${CLAUDE_PLUGIN_ROOT}/skills/jnote/ai-format.md` (a copy of `docs/ai-format.md` in the JNote repository). Read it before your first write in a session, and follow it over anything below.

## Rules

- Write only new files in `~/.jnote/inbox/`. Never create, change, or delete `~/.jnote/jnote.db` or any other file under `~/.jnote/`.
- Never overwrite an inbox file, and never let a half-written `.json` file appear there. Publish it as in Capture step 4, never with a tool that writes the final name directly.
- Output strict JSON: double quotes, no comments, no trailing commas.

## Capture

Save something from the conversation as one or more notes.

1. Choose the kind. A note about specific lines of a file is `CODE`. Anything else (a query, a command, a decision, a memo) is `FREE`.
2. For a `CODE` note, collect the anchor from the files, not from memory:
   - First fix the project root: the folder the user opens in the IDE. It is usually the top of the repository you are working in (`git rev-parse --show-toplevel` from the working directory). For a file inside a nested repository or submodule, the project root is still the outer folder the IDE opens, not the nested repository; ask if unsure.
   - `repo_url`: run `git -C <project root> config --get remote.origin.url`, not `git remote get-url origin`: JNote reads the URL as configured, and the second command applies `url.*.insteadOf` rewrites, which would anchor the note to a different project. Drop any user name, password, or token from an `https://user:token@host/...` URL. If there is no origin, or it is a local path, leave `repo_url` out and set `project_path` to the absolute path of the project root.
   - `path`: the file path relative to the project root, with `/` separators.
   - `start_line`, `end_line`: 1-based, inclusive, plain integers.
   - `snapshot`: the exact text of those lines, read from the file (for example `sed -n '<start>,<end>p' <file>`). Copy it verbatim, keeping indentation.
   - `class_name`: the enclosing class, or the file name without extension when there is none.
3. Write the note: a short `title`; `body` in plain text (no Markdown rendering in JNote); `tags` as short names without commas; `ai_source` set to `"Claude"`; `prompt` set to the user's request that led to the note. Leave `uid` out, because these are new notes.
4. Save one document per request as `~/.jnote/inbox/<YYYY-MM-DD>-<short-topic>.json`. JNote reads only `*.json` files there, so write the document under a hidden temporary name first, check it, then publish it with `ln`, which fails instead of replacing an existing file:
   ```sh
   inbox=~/.jnote/inbox; mkdir -p "$inbox"
   tmp=$(mktemp "$inbox/.jnote-XXXXXX")
   cat > "$tmp" <<'EOF'
   { ...the document... }
   EOF
   { python3 -m json.tool "$tmp" || jq empty "$tmp"; } > /dev/null 2>&1 &&
     ln "$tmp" "$inbox/2026-11-27-retry-backoff.json" && rm "$tmp"
   ```
   If neither `python3` nor `jq` is installed, the check fails and nothing is published; tell the user instead of publishing unchecked. If the check fails, fix the temporary file and run the last command again; never publish a document that does not parse.
   If `ln` fails because the name exists, publish the same temporary file again with `-2`, `-3`, ... before `.json`, then remove the temporary file.
5. Tell the user the file name and that JNote shows it for review when its Tool Window gains focus. Nothing is saved until they click Apply there.

### Example

```json
{
  "format_version": 1,
  "notes": [
    {
      "kind": "CODE",
      "title": "Why retry backs off exponentially",
      "body": "The payment gateway rate-limits bursts; linear retries got the account throttled.",
      "tags": ["payments", "retry"],
      "anchor": {
        "repo_url": "git@github.com:acme/shop.git",
        "path": "src/main/kotlin/shop/payment/Retry.kt",
        "start_line": 42,
        "end_line": 43,
        "class_name": "Retry",
        "snapshot": "fun delayFor(attempt: Int): Duration =\n    200.milliseconds * 2.0.pow(attempt).toInt()"
      },
      "ai_source": "Claude",
      "prompt": "Save why Retry uses exponential backoff"
    },
    {
      "kind": "FREE",
      "title": "Find orders stuck in PENDING",
      "body": "SELECT id FROM orders WHERE status = 'PENDING' AND created_at < now() - interval '1 hour';",
      "tags": ["sql", "orders"],
      "ai_source": "Claude",
      "prompt": "Note this query in JNote"
    }
  ]
}
```
