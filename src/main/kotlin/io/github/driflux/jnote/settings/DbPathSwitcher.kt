package io.github.driflux.jnote.settings

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.DatabaseCopier
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Path
import java.util.concurrent.ExecutionException

/** 계획대로 복사와 전환을 수행한다. 블로킹이므로 백그라운드 스레드에서만 부른다. */
object DbPathSwitcher {
    /** 이미 있는 파일을 JNote DB로 열 수 없어 전환하지 않았다. */
    class UnusableTarget(val target: Path, cause: Throwable) : Exception(cause.message, cause)

    fun execute(
        plan: DbPathChange.Plan,
        service: DatabaseService,
        settings: DbPathSettings,
        checkCancelled: () -> Unit,
    ) {
        val (target: Path, retired: Database?) = when (plan) {
            is DbPathChange.Plan.CopyThenSwitch -> plan.target to swap(service) { current ->
                DatabaseCopier.copy(current.path, plan.target, current.database, checkCancelled) {
                    service.replace(current, plan.target)
                }
            }
            is DbPathChange.Plan.SwitchToExisting -> {
                requireUsable(plan.target)
                checkCancelled()
                plan.target to swap(service) { current -> service.replace(current, plan.target) }
            }
            DbPathChange.Plan.NoChange, is DbPathChange.Plan.Invalid ->
                throw IllegalArgumentException("nothing to execute for $plan")
        }
        // 경로는 이미 바뀌었으므로, 옛 DB를 닫다 실패해도 재시작 뒤 새 경로를 쓰도록 먼저 저장한다
        settings.store(target)
        retired?.close()
    }

    // 설정에 남긴 뒤에 실패하면 다음 사용과 재시작마다 같은 오류가 나므로, 바꾸기 전에 한 번 열어 마이그레이션까지 해 본다
    private fun requireUsable(target: Path) {
        val probe = Database(target)
        try {
            probe.submit { }.get()
        } catch (e: ExecutionException) {
            throw UnusableTarget(target, e.cause ?: e)
        } finally {
            probe.close()
        }
    }

    // 상태를 읽은 뒤 DB가 처음 쓰여 만들어졌다면 replace가 거절하므로, 그 DB 스레드에서 다시 하도록 새로 읽어 되풀이한다
    private tailrec fun swap(service: DatabaseService, attempt: (DatabaseService.Current) -> Boolean): Database? {
        val current = service.current()
        return if (attempt(current)) current.database else swap(service, attempt)
    }
}
