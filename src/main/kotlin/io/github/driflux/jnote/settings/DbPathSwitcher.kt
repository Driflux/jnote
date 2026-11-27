package io.github.driflux.jnote.settings

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.DatabaseCopier
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Path
import java.util.concurrent.ExecutionException

object DbPathSwitcher {
    class UnusableTarget(val target: Path, cause: Throwable) : Exception(cause.message, cause)

    class OldDatabaseNotClosed(val target: Path, override val cause: Throwable) : Exception(cause.message, cause)

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
        settings.store(target)
        try {
            retired?.close()
        } catch (e: Exception) {
            throw OldDatabaseNotClosed(target, e)
        }
    }

    private fun requireUsable(target: Path) {
        DbPathChange.existingFileProblem(target)?.let { throw UnusableTarget(target, IllegalStateException("not a JNote database: $it")) }
        val probe = Database(target)
        try {
            probe.submit { }.get()
        } catch (e: ExecutionException) {
            throw UnusableTarget(target, e.cause ?: e)
        } finally {
            probe.close()
        }
    }

    private tailrec fun swap(service: DatabaseService, attempt: (DatabaseService.Current) -> Boolean): Database? {
        val current = service.current()
        return if (attempt(current)) current.database else swap(service, attempt)
    }
}
