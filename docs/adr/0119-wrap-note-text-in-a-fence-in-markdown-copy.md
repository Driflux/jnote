# 0119. Wrap the note text in a fence in the Markdown copy

- Status: Proposed
- Date: 2026-11-27
- Issue: #119
- PR: TBD

## Context
"Copy as Markdown" (ADR 0019, intent.md section 10.4) writes each selected note as a heading, the location, the snippet, and the note text, and joins the notes with a blank line. The snippet sits in a fenced block that is one backtick longer than its longest backtick run, but the note text was added as it is. Note text in JNote is plain text with no Markdown rendering (intent.md, "Note text is plain text"), so people write whatever they like in it, code included.

CommonMark (0.31.2, section 4.5) runs a fenced code block "until a closing code fence of the same type as the code block began with (backticks or tildes), and with at least as many backticks or tildes as the opening code fence", and when no closing fence is found the block runs to the end of the document (examples 126 and 127). A note text with an unclosed ``` therefore turned every later note's heading, location, and snippet into code once the copy was pasted into an AI chat. The same happens without a fence: an HTML block that starts with `<!--` ends only at a line that contains `-->`, and one that starts with `<pre` ends only at `</pre>` (section 4.6, conditions 1 and 2). The fault is that plain text was handed to a Markdown reader as Markdown, not one particular character.

## Decision
- **Every non-empty note text goes in a fenced block.** The fence is backticks, one longer than the longest backtick run in the text and at least three, the same rule as the snippet. No line of the text can hold that many backticks in a row, so no line can close the block, and the next note's `## ` heading always starts outside it. An empty or blank note text is still left out.
- **One fence rule.** `codeBlock(text, language)` builds both the snippet block and the note text block, so the fence length is decided in one place. The file extension, which picks the snippet's info string, moved to its own function.
- **Info string `text`.** It is the usual name for "no highlighting", so a chat that colors code blocks shows the note text plainly. With an empty info string some renderers guess a language and color prose as code (a guess, not checked per chat). `text` also tells the reader that this block is the note, not code from the file.
- **Tildes need no handling.** A closing fence must be of the same type as the opening one (section 4.5), so a `~~~` line inside a backtick fence is content (example 122). A text that mixes `~~~` and ``` is covered by the backtick count alone.
- **Changed output.** Every copy with a note text now shows it in a `text` block. Before, it was a plain paragraph. intent.md section 10.4 still holds: the copy has the title, location, snippet, and note text. This replaces the "then the note text" part of the Markdown shape in ADR 0019.

## Alternatives considered
- **Escape Markdown in the note text** (backslash before `, ~, <, #, and the rest). The AI then reads text that is not what the user wrote, full of backslashes in exactly the code-heavy notes this is about, and the escape table has to cover every block start in the spec. Rejected.
- **Close a dangling fence after each note text.** It needs a fence tracker (type, length, up to three spaces of indent) and still leaves `<!--` and `<pre` open. The text is also still read as Markdown, which JNote never does with it. Rejected.
- **Wrap only when the text could open a block that runs past it.** Prose would stay a plain paragraph, which reads more naturally in a chat. But the check has to repeat the CommonMark block-start rules, a missed case fails silently in someone's chat, and the copy gets two shapes. One rule that always holds was preferred over nicer prose. Rejected.
- **Indented code block (four spaces per line).** No fence to size, but every line of the text changes when a user copies it back out, and it cannot carry the `text` info string. The fence code already exists. Rejected.
- **A tilde fence for the note text.** Same safety with its own length rule, so two fence rules instead of one. Rejected.

## Consequences
- Pros
  - No note text can change how the notes after it read, whatever it contains.
  - The snippet and the note text share one fence rule.
  - The reader, human or AI, sees exactly where each note's text starts and ends.
- Cons and what we accept
  - Prose note text shows as a code block: a fixed-width font, and in some chats long lines may scroll instead of wrap (a guess, not checked).
  - Two tests that pinned the old shape were updated (`NoteCopyTest.markdownHasTitleLocationSnippetAndBodyPerNote`, `ToolWindowCopyTest.testCopyAsMarkdownCopiesTitleLocationSnippetAndBody`).
- Follow-up work
  - The title is still written as it is after `## `. JSON import accepts a title with a line break (`NoteFormatCodec` only rejects a blank one), so a title such as `x` followed by a line `` ``` `` would open a fence the same way. That is a separate issue.
