package io.github.driflux.jnote.settings

import io.github.driflux.jnote.db.DatabaseCopier
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Path

/** 계획대로 복사와 전환을 수행한다. 블로킹이므로 백그라운드 스레드에서만 부른다. */
object DbPathSwitcher {
    fun execute(
        plan: DbPathChange.Plan,
        service: DatabaseService,
        settings: DbPathSettings,
        checkCancelled: () -> Unit,
    ) {
        when (plan) {
            is DbPathChange.Plan.CopyThenSwitch -> {
                val (source, database) = service.current()
                DatabaseCopier.copy(source, plan.target, database, checkCancelled)
                switch(plan.target, service, settings, checkCancelled)
            }
            is DbPathChange.Plan.SwitchToExisting -> switch(plan.target, service, settings, checkCancelled)
            DbPathChange.Plan.NoChange, is DbPathChange.Plan.Invalid ->
                throw IllegalArgumentException("nothing to execute for $plan")
        }
    }

    private fun switch(target: Path, service: DatabaseService, settings: DbPathSettings, checkCancelled: () -> Unit) {
        checkCancelled()
        try {
            service.switchTo(target)
        } finally {
            // 옛 DB를 닫다 실패해도 전환은 이미 끝났으므로, 재시작 후에도 새 경로를 쓰도록 저장한다
            if (service.currentPath() == target) settings.store(target)
        }
    }
}
