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
    fun `interpreta tag nova, versao, codigo, notas e apk`() {
        val u = assertNotNull(UpdateChecker.parse(release(tag = "v0.3.1")))
        assertEquals("v0.3.1", u.tag)
        assertEquals("0.3.1", u.versionName)
        assertEquals(301, u.versionCode)
        assertEquals("- **Corrige**", u.notes)
        assertEquals("https://github.com/x/releases/download/v0.3.0-b2/Rumo-v0.3.0-b2.apk", u.apkUrl)
        assertEquals(1234L, u.sizeBytes)
    }

    @Test
    fun `codigo cresce a cada versao e tags antigas continuam valendo`() {
        val codes = listOf("v0.3.0-b2", "v0.3.1", "v0.3.2", "v0.3.10", "v0.4.1", "v1.0.1").map { UpdateChecker.parseTag(it)!!.second }
        assertEquals(listOf(102, 301, 302, 310, 401, 10001), codes)
        assertEquals(codes.sorted(), codes)
        assertEquals("0.3.0", UpdateChecker.parseTag("v0.3.0-b2")!!.first)
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
        val c = UpdateChecker(FakeHttp(release(tag = "v0.3.5")))
        assertNotNull(c.check(installedCode = 304))
        assertNull(c.check(installedCode = 305), "mesma versão")
        assertNull(c.check(installedCode = 330), "instalada é mais nova")
    }

    private companion object {
        const val ASSET = """{"name":"Rumo-v0.3.0-b2.apk","browser_download_url":"https://github.com/x/releases/download/v0.3.0-b2/Rumo-v0.3.0-b2.apk","size":1234}"""
    }
}
