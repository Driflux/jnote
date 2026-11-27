# 0119. Wrap the note text in a fence in the Markdown copy

- Status: Proposed
- Date: 2026-11-27
- Issue: #119
- PR: TBD

## Context
"Copy as Markdown" (ADR 0019, intent.md section 10.4) writes each selected note as a heading, the location, the snippet, and the note text, and joins the notes with a blank line. The snippet sits in a fenced block that is one backtick longer than its longest backtick run, but the note text was added as it is. Note text in JNote is plain text with no Markdown rendering (intent.md, "Note text is plain text"), so people write whatever they like in it, code included.

CommonMark (0.31.2, section 4.5) runs a fenced code block "until a closing code fence of the same type as the code block began with (backticks or tildes), and with at least as many backticks or tildes as the opening code fence", and when no closing fence is found the block runs to the end of the document (examples 126 and 127). A note text with an unclosed ``` therefore turned every later note's heading, location, and snippet into code once the copy was pasted into an AI chat. The same happens without a fence: an HTML block that starts with `<!--` ends only at a line that contains `-->`, and one that starts with `<pre>` ends only at a line that contains `</pre>` (section 4.6, conditions 1 and 2). More generally the note text could be read as any Markdown block, not only a paragraph. The fault is that plain text was handed to a Markdown reader as Markdown, not one particular character.

The title and the location path have the same exposure. JSON import accepts a title with a line break (`NoteFormatCodec` only rejects a blank one) and a path is stored as given, so a title such as `x` followed by a line `` ``` `` opened a fence on its second line. A path with a line break did the same even inside the inline code span, because a fence line that stands on its own line is a block start before it is span content.

## Decision
- **Every non-empty note text goes in a fenced block.** The fence is backticks, one longer than the longest backtick run in the text and at least three, the same rule as the snippet. No line of the text can hold that many backticks in a row, so no line can close the block. An empty or blank note text is still left out.
- **The title and the location are written on one line.** In the Markdown copy every line break in the title and in the location path (CRLF, LF, or CR) becomes one space. Nothing else is changed. With the note text fenced and these two on one line, the next note's `## ` heading always starts outside any block. The stored note and the JSON copy keep the text as it is.
- **The note text keeps its last line break.** A note text that ends with a line break (LF, CRLF, or CR) goes into the block as it is, so trailing blank lines are kept. A note text that does not gets one LF before the closing fence, because the fence must start its own line. The snippet keeps its earlier output: trailing LFs removed, then one LF.
- **One fence rule.** `codeBlock(lines, language)` builds both the snippet block and the note text block, so the fence length is decided in one place. The file extension, which picks the snippet's info string, moved to its own function.
- **Info string `text`.** CommonMark does not say what an info string does. `text` is a common name for "no highlighting", and many renderers show such a block plainly, but that depends on the renderer. With an empty info string some renderers guess a language and color prose as code (a guess, not checked per chat). The info string does not mark the block as the note: a snippet from a `.text` file gets the same one. The reader tells them apart by order, since the note text always comes after the location and the snippet.
- **Tildes need no handling.** A closing fence must be of the same type as the opening one (section 4.5), so a `~~~` line inside a backtick fence is content (example 122). A text that mixes `~~~` and ``` is covered by the backtick count alone.
- **Changed output.** Every copy with a note text now shows it in a `text` block. Before, it was a plain paragraph. intent.md section 10.4 still holds: the copy has the title, location, snippet, and note text. This refines the "then the note text" part of the Markdown shape in ADR 0019. ADR 0019 is not edited, because ADRs accumulate (CONTRIBUTE.md).

## Alternatives considered
- **Escape Markdown in the note text** (backslash before `, ~, <, #, and the rest). The AI then reads text that is not what the user wrote, full of backslashes in exactly the code-heavy notes this is about, and the escape table has to cover every block start in the spec. Rejected.
- **Close a dangling fence after each note text.** It needs a fence tracker (type, length, up to three spaces of indent) and still leaves `<!--` and `<pre>` open. The text is also still read as Markdown, which JNote never does with it. Rejected.
- **Wrap only when the text could open a block that runs past it.** Prose would stay a plain paragraph, which reads more naturally in a chat. But the check has to repeat the CommonMark block-start rules, a missed case fails silently in someone's chat, and the copy gets two shapes. One rule that always holds was preferred over nicer prose. Rejected.
- **Indented code block (four spaces per line).** No fence to size. In the Markdown source every line of the text gains four spaces, and an AI usually reads that source, not a rendering. A renderer strips the four spaces again, so the rendered text matches, but the raw paste does not. It also cannot carry an info string, and a blank first or last line needs care. The fence code already exists. Rejected.
- **A tilde fence for the note text.** Same safety with its own length rule, so two fence rules instead of one. Rejected.
- **Reject line breaks in titles at JSON import.** It changes the import contract of format v1 (ADR 0016) and does nothing for notes already stored or paths with a line break. The copy is where the text meets Markdown, so the fix goes there. Rejected.
- **Split the title and path fix into its own issue.** Same output, same symptom, and a one-line rule in the same function. A separate PR would leave the "next heading starts outside any block" promise half true in between. Rejected.

## Consequences
- Pros
  - No note text, title, or path can change how the notes after it read, whatever it contains.
  - The snippet and the note text share one fence rule.
  - The reader, human or AI, sees exactly where each note's text starts and ends, and the text keeps its trailing line breaks.
- Cons and what we accept
  - Prose note text shows as a code block: a fixed-width font, and in some chats long lines may scroll instead of wrap (a guess, not checked).
  - A title or path with a line break is copied on one line, so the Markdown copy does not show where the break was. The JSON copy keeps it.
  - Two tests that pinned the old shape were updated (`NoteCopyTest.markdownHasTitleLocationSnippetAndBodyPerNote`, `ToolWindowCopyTest.testCopyAsMarkdownCopiesTitleLocationSnippetAndBody`).
- Follow-up work
  - None from this issue.
