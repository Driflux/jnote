package io.github.driflux.jnote.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * 프로젝트 키를 처음 요청받을 때 한 번 계산해 캐시한다.
 * plugin.xml에 등록하지 않는 라이트 서비스라 쓰기 전에는 아무 비용도 없다.
 */
@Service(Service.Level.PROJECT)
class ProjectKeyService(project: Project) {
    private val cache = CachedProjectKey(project.basePath, project.name, ProjectKeyResolver::resolve)

    /** 파일을 읽으므로 EDT 밖에서 호출한다. */
    fun resolve(): ProjectKey {
        assertNotOnEdt()
        return cache.get()
    }

    private fun assertNotOnEdt() {
        val app = ApplicationManager.getApplication() ?: return
        // 운영 중에는 던지지 않고, 테스트에서만 EDT 호출을 즉시 드러낸다
        check(!(app.isUnitTestMode && app.isDispatchThread)) { "ProjectKeyService.resolve() must be called off the EDT" }
    }
}

/** 해석 결과를 스레드 안전하게 한 번만 계산하는 캐시. 테스트에서 해석 횟수를 셀 수 있게 분리했다. */
internal class CachedProjectKey(
    basePath: String?,
    fallbackName: String,
    resolver: (Path) -> ProjectKey,
) {
    private val value: ProjectKey by lazy {
        // 기본 프로젝트처럼 경로가 없으면 읽을 곳이 없으므로 이름으로 대체한다
        if (basePath == null) ProjectKey(fallbackName, fallbackName) else resolver(Path.of(basePath))
    }

    fun get(): ProjectKey = value
}
