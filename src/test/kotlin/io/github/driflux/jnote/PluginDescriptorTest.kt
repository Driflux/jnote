package io.github.driflux.jnote

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginDescriptorTest : BasePlatformTestCase() {
    fun testPluginDescriptorIsRegistered() {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("io.github.driflux.jnote"))
        assertNotNull("plugin descriptor not registered", descriptor)
        assertEquals("JNote", descriptor!!.name)
        assertEquals("Driflux", descriptor.vendor)
    }
}
