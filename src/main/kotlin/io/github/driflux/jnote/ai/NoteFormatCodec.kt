package io.github.driflux.jnote.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.project.RepoUrl
import io.github.driflux.jnote.ui.CodeTargets
import java.io.IOException
import java.io.StringReader
import java.io.Writer
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant

object NoteFormatCodec {
    const val FORMAT_VERSION = 1

    fun encode(notes: List<ExportedNote>, out: Writer) {
        val json = JsonWriter(out).apply {
            setIndent("  ")
            serializeNulls = false
        }
        json.beginObject()
        json.name("format_version").value(FORMAT_VERSION.toLong())
        json.name("notes").beginArray()
        for (exported in notes) writeNote(json, exported)
        json.endArray()
        json.endObject()
        json.flush()
    }

    fun decode(text: String): DecodeResult {
        val root = parseStrict(text) ?: return DecodeResult.Rejected(DocumentError.MALFORMED_JSON)
        if (!root.isJsonObject) return DecodeResult.Rejected(DocumentError.NOT_AN_OBJECT)
        val document = root.asJsonObject
        val version = document.get("format_version")
            ?.takeUnless { it.isJsonNull }
            ?: return DecodeResult.Rejected(DocumentError.MISSING_FORMAT_VERSION)
        if (intOrNull(version) != FORMAT_VERSION) return DecodeResult.Rejected(DocumentError.UNSUPPORTED_FORMAT_VERSION)
        val items = document.get("notes")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return DecodeResult.Rejected(DocumentError.MISSING_NOTES)
        val parsed = mutableListOf<IndexedValue<FormatNote>>()
        val skipped = mutableListOf<SkippedItem>()
        items.forEachIndexed { index, item ->
            try {
                parsed += IndexedValue(index, readNote(item))
            } catch (e: ItemProblem) {
                skipped += SkippedItem(index, rawUid(item), e.field, e.problem)
            }
        }
        val duplicated = items.mapNotNull(::rawUid).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val (dropped, kept) = parsed.partition { it.value.uid in duplicated }
        skipped += dropped.map { SkippedItem(it.index, it.value.uid, "uid", FieldProblem.DUPLICATE) }
        return DecodeResult.Decoded(kept.map { it.value }, skipped.sortedBy { it.index })
    }

    private fun rawUid(item: JsonElement): String? =
        (item as? JsonObject)?.get("uid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun writeNote(json: JsonWriter, exported: ExportedNote) {
        val note = exported.note
        json.beginObject()
        json.name("uid").value(note.uid)
        json.name("kind").value(note.kind.name)
        json.name("title").value(note.title)
        json.name("body").value(note.body)
        json.name("tags").beginArray()
        note.tags.forEach { json.value(it) }
        json.endArray()
        note.anchor?.let { anchor ->
            json.name("anchor").beginObject()
            val asUrl = "https://" + anchor.project
            if (RepoUrl.normalize(asUrl) == anchor.project) json.name("repo_url").value(asUrl)
            else json.name("project_path").value(anchor.project)
            json.name("path").value(anchor.relPath)
            json.name("start_line").value(anchor.startLine.toLong())
            json.name("end_line").value(anchor.endLine.toLong())
            json.name("class_name").value(anchor.className)
            json.name("snapshot").value(anchor.snapshot)
            json.endObject()
        }
        json.name("ai_source").value(note.aiSource)
        json.name("source_url").value(note.sourceUrl)
        json.name("prompt").value(note.prompt)
        json.name("status").value(exported.status.name)
        json.name("created_at").value(Instant.ofEpochMilli(exported.createdAt).toString())
        json.name("updated_at").value(Instant.ofEpochMilli(exported.updatedAt).toString())
        json.endObject()
    }

    private fun readNote(item: JsonElement): FormatNote {
        if (!item.isJsonObject) throw ItemProblem("note", FieldProblem.WRONG_TYPE)
        val obj = item.asJsonObject
        val uid = optionalString(obj, "uid")
        if (uid != null && uid.isBlank()) throw ItemProblem("uid", FieldProblem.INVALID)
        val kind = when (requiredString(obj, "kind")) {
            NoteKind.CODE.name -> NoteKind.CODE
            NoteKind.FREE.name -> NoteKind.FREE
            else -> throw ItemProblem("kind", FieldProblem.INVALID)
        }
        val title = requiredString(obj, "title")
        if (title.isBlank()) throw ItemProblem("title", FieldProblem.INVALID)
        val anchorElement = obj.get("anchor")?.takeUnless { it.isJsonNull }
        val anchor = when {
            kind == NoteKind.CODE && anchorElement == null -> throw ItemProblem("anchor", FieldProblem.MISSING)
            kind == NoteKind.FREE && anchorElement != null -> throw ItemProblem("anchor", FieldProblem.INVALID)
            anchorElement == null -> null
            else -> readAnchor(anchorElement)
        }
        return FormatNote(
            uid = uid,
            title = title,
            body = optionalString(obj, "body") ?: "",
            tags = readTags(obj),
            anchor = anchor,
            aiSource = optionalString(obj, "ai_source"),
            sourceUrl = optionalString(obj, "source_url"),
            prompt = optionalString(obj, "prompt"),
        )
    }

    private fun readAnchor(element: JsonElement): FormatAnchor {
        if (!element.isJsonObject) throw ItemProblem("anchor", FieldProblem.WRONG_TYPE)
        val obj = element.asJsonObject
        val repoUrl = optionalString(obj, "repo_url", "anchor.")
        val projectPath = optionalString(obj, "project_path", "anchor.")
        val project = when {
            repoUrl != null && projectPath != null -> throw ItemProblem("anchor.project_path", FieldProblem.INVALID)
            repoUrl != null -> RepoUrl.normalize(repoUrl) ?: throw ItemProblem("anchor.repo_url", FieldProblem.INVALID)
            projectPath != null -> projectPath.takeUnless { it.isBlank() } ?: throw ItemProblem("anchor.project_path", FieldProblem.INVALID)
            else -> throw ItemProblem("anchor.repo_url", FieldProblem.MISSING)
        }
        val relPath = requiredString(obj, "path", "anchor.")
        if (!CodeTargets.isSafeRelPath(relPath) || !isParsablePath(relPath)) throw ItemProblem("anchor.path", FieldProblem.INVALID)
        val startLine = requiredLine(obj, "start_line")
        val endLine = requiredLine(obj, "end_line")
        if (endLine < startLine) throw ItemProblem("anchor.end_line", FieldProblem.INVALID)
        return FormatAnchor(
            project = project,
            relPath = relPath,
            startLine = startLine,
            endLine = endLine,
            className = optionalString(obj, "class_name", "anchor."),
            snapshot = optionalString(obj, "snapshot", "anchor."),
        )
    }

    private fun readTags(obj: JsonObject): List<String> {
        val element = obj.get("tags")?.takeUnless { it.isJsonNull } ?: return emptyList()
        if (!element.isJsonArray) throw ItemProblem("tags", FieldProblem.WRONG_TYPE)
        return (element as JsonArray).map { tag ->
            if (!tag.isJsonPrimitive || !tag.asJsonPrimitive.isString) throw ItemProblem("tags", FieldProblem.WRONG_TYPE)
            val name = tag.asString.trim()
            if (name.isEmpty() || ',' in name) throw ItemProblem("tags", FieldProblem.INVALID)
            name
        }.distinct()
    }

    private fun requiredLine(obj: JsonObject, name: String): Int {
        val element = obj.get(name)?.takeUnless { it.isJsonNull } ?: throw ItemProblem("anchor.$name", FieldProblem.MISSING)
        val line = intOrNull(element) ?: throw ItemProblem("anchor.$name", FieldProblem.WRONG_TYPE)
        if (line < 1) throw ItemProblem("anchor.$name", FieldProblem.INVALID)
        return line
    }

    private fun requiredString(obj: JsonObject, name: String, prefix: String = ""): String =
        optionalString(obj, name, prefix) ?: throw ItemProblem(prefix + name, FieldProblem.MISSING)

    private fun optionalString(obj: JsonObject, name: String, prefix: String = ""): String? {
        val element = obj.get(name)?.takeUnless { it.isJsonNull } ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) throw ItemProblem(prefix + name, FieldProblem.WRONG_TYPE)
        return element.asString
    }

    private fun intOrNull(element: JsonElement): Int? =
        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asString.toIntOrNull() else null

    private fun isParsablePath(relPath: String): Boolean = try {
        Path.of(relPath)
        true
    } catch (e: InvalidPathException) {
        false
    }

    private fun parseStrict(text: String): JsonElement? = try {
        val reader = JsonReader(StringReader(text)).apply { isLenient = false }
        Gson().getAdapter(JsonElement::class.java).read(reader).takeIf { reader.peek() == JsonToken.END_DOCUMENT }
    } catch (e: IOException) {
        null
    } catch (e: JsonParseException) {
        null
    } catch (e: IllegalStateException) {
        null
    } catch (e: NumberFormatException) {
        null
    }

    private class ItemProblem(val field: String, val problem: FieldProblem) : Exception(null, null, false, false)
}
