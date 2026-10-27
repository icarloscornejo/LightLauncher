package com.prototype.lightlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The layer rule, checked on the source text (every token, not only imports):
 * core is plain Kotlin/Java (no Android, nothing else of this app), data never reaches into the UI.
 */
class DependencyRulesTest {

    private val sourceRoot: File = File("src/main/java/com/prototype/lightlauncher")

    private val uiNames: List<String> = listOf(
        "MainActivity", "ClockDream", "AppAdapter", "HomeKeyService", "InputKeyService", "HomeSettingsActivity",
    )

    /** Comments may mention anything; only code counts. */
    private fun stripComments(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\\n]*"), " ")

    private fun coreViolations(source: String): List<String> {
        val code: String = stripComments(source)
        val found: MutableList<String> = mutableListOf()
        Regex("\\b(android|androidx)\\.[A-Za-z]").findAll(code).forEach { found.add(it.value) }
        Regex("com\\.prototype\\.lightlauncher\\.(?!core\\b)[A-Za-z_]+").findAll(code).forEach { found.add(it.value) }
        return found
    }

    private fun dataViolations(source: String): List<String> {
        val code: String = stripComments(source)
        val found: MutableList<String> = mutableListOf()
        Regex("com\\.prototype\\.lightlauncher\\.ui\\b").findAll(code).forEach { found.add(it.value) }
        for (name in uiNames) Regex("\\b$name\\b").findAll(code).forEach { found.add(it.value) }
        return found
    }

    private fun sources(layer: String): List<File> =
        File(sourceRoot, layer).walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    @Test
    fun coreIsPure() {
        val files: List<File> = sources("core")
        assertTrue("core sources not found from ${File(".").absolutePath}", files.isNotEmpty())
        val problems: List<String> = files.flatMap { file -> coreViolations(file.readText()).map { "${file.name}: $it" } }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun dataNeverReachesIntoTheUi() {
        val problems: List<String> = sources("data").flatMap { file -> dataViolations(file.readText()).map { "${file.name}: $it" } }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun plantedViolationsAreCaught() {
        assertTrue(coreViolations("val c = android.graphics.Color.rgb(1, 2, 3)").isNotEmpty())
        assertTrue(coreViolations("import com.prototype.lightlauncher.R").isNotEmpty())
        assertTrue(coreViolations("import com.prototype.lightlauncher.data.AlbumSync").isNotEmpty())
        assertTrue(coreViolations("import androidx.lifecycle.ViewModel").isNotEmpty())
        assertTrue(coreViolations("// android.graphics.Color is fine in a comment\nimport com.prototype.lightlauncher.core.Greeting").isEmpty())
        assertTrue(dataViolations("MainActivity.greetingRes(3)").isNotEmpty())
        assertTrue(dataViolations("import com.prototype.lightlauncher.ui.GlassHighlight").isNotEmpty())
        assertTrue(dataViolations("import com.prototype.lightlauncher.R").isEmpty())
    }
}
