package app.hunter.nav

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UpdateCheckerTest {

    private fun release(tag: String = "v0.3.0-b2", extra: String = "", assets: String = ASSET) =
        """{"tag_name":"$tag","name":"Rumo","body":"- **Corrige**","draft":false,"prerelease":false$extra,"assets":[$assets]}"""

    private class FakeHttp(private val body: String) : Http {
        override suspend fun get(url: String) = body
        override suspend fun postJson(url: String, body: String) = error("não usado")
    }

    @Test
    fun `interpreta tag, versao, codigo, notas e apk`() {
        val u = assertNotNull(UpdateChecker.parse(release()))
        assertEquals("v0.3.0-b2", u.tag)
        assertEquals("0.3.0", u.versionName)
        assertEquals(2, u.build)
        assertEquals(102, u.versionCode)
        assertEquals("- **Corrige**", u.notes)
        assertEquals("https://github.com/x/releases/download/v0.3.0-b2/Rumo-v0.3.0-b2.apk", u.apkUrl)
        assertEquals(1234L, u.sizeBytes)
    }

    @Test
    fun `ignora rascunho, pre-lancamento, sem apk e tag fora do padrao`() {
        assertNull(UpdateChecker.parse(release(extra = "").replace("\"draft\":false", "\"draft\":true")))
        assertNull(UpdateChecker.parse(release().replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(UpdateChecker.parse(release(assets = """{"name":"notas.txt","browser_download_url":"u","size":1}""")))
        assertNull(UpdateChecker.parse(release(tag = "v1.0")))
        assertNull(UpdateChecker.parse("não é json"))
        assertNull(UpdateChecker.parse("""{"message":"Not Found"}"""))
    }

    @Test
    fun `so avisa quando a release e mais nova que a instalada`() = runTest {
        val c = UpdateChecker(FakeHttp(release(tag = "v0.3.0-b5")))
        assertNotNull(c.check(installedCode = 104))
        assertNull(c.check(installedCode = 105), "mesma versão")
        assertNull(c.check(installedCode = 130), "instalada é mais nova")
    }

    private companion object {
        const val ASSET = """{"name":"Rumo-v0.3.0-b2.apk","browser_download_url":"https://github.com/x/releases/download/v0.3.0-b2/Rumo-v0.3.0-b2.apk","size":1234}"""
    }
}
