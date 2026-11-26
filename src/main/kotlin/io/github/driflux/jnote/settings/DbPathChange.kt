package io.github.driflux.jnote.settings

import java.nio.file.Files
import java.nio.file.Path

/** DB 경로를 바꿀 때 무엇을 할지 정하는 순수 함수. 파일을 읽기만 하고 아무것도 바꾸지 않는다. */
object DbPathChange {
    enum class Reason(val messageKey: String) {
        NOT_ABSOLUTE("settings.dbPath.invalid.notAbsolute"),
        PARENT_NOT_CREATABLE("settings.dbPath.invalid.parentNotCreatable"),
        IS_DIRECTORY("settings.dbPath.invalid.isDirectory"),
        NOT_WRITABLE("settings.dbPath.invalid.notWritable"),
    }

    sealed interface Plan {
        data object NoChange : Plan

        /** 새 경로에 파일이 없다: 현재 DB를 복사한 뒤 전환한다. */
        data class CopyThenSwitch(val target: Path) : Plan

        /** 새 경로에 파일이 이미 있다: 확인을 받고 그 DB로 전환한다(병합하지 않는다). */
        data class SwitchToExisting(val target: Path) : Plan

        data class Invalid(val reason: Reason) : Plan
    }

    fun plan(current: Path, requested: Path): Plan {
        if (!requested.isAbsolute) return Plan.Invalid(Reason.NOT_ABSOLUTE)
        val target = requested.normalize()
        if (Files.isDirectory(target)) return Plan.Invalid(Reason.IS_DIRECTORY)
        if (canonical(current) == canonical(target)) return Plan.NoChange

        if (Files.exists(target)) {
            return if (Files.isRegularFile(target) && Files.isWritable(target)) {
                Plan.SwitchToExisting(target)
            } else {
                Plan.Invalid(Reason.NOT_WRITABLE)
            }
        }
        val ancestor = nearestExisting(target)
        return when {
            ancestor == null || !Files.isDirectory(ancestor) -> Plan.Invalid(Reason.PARENT_NOT_CREATABLE)
            !Files.isWritable(ancestor) ->
                Plan.Invalid(if (ancestor == target.parent) Reason.NOT_WRITABLE else Reason.PARENT_NOT_CREATABLE)
            else -> Plan.CopyThenSwitch(target)
        }
    }

    private fun nearestExisting(path: Path): Path? {
        var p: Path? = path
        while (p != null && !Files.exists(p)) p = p.parent
        return p
    }

    // 심볼릭 링크와 대소문자 무시 파일시스템에서도 같은 파일을 같게 보도록, 존재하는 상위 경로만 실제 경로로 푼다
    private fun canonical(path: Path): Path {
        val abs = path.toAbsolutePath().normalize()
        val existing = nearestExisting(abs) ?: return abs
        return existing.toRealPath().resolve(existing.relativize(abs)).normalize()
    }
}
