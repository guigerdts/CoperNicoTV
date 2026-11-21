package com.copernicotv.core.ota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicacionesGitHubTest {
    @Test
    fun `parsea releases con etiqueta borrador preliminar y activos`() {
        val json =
            """
            [
              {
                "tag_name": "v0.2.0",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.2.0",
                "assets": [
                  {
                    "name": "app-release.apk",
                    "browser_download_url": "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/app-release.apk"
                  },
                  {
                    "name": "update.json",
                    "browser_download_url": "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/update.json"
                  }
                ]
              }
            ]
            """.trimIndent()

        val publicaciones = PublicacionesGitHub.parse(json)

        assertEquals(1, publicaciones?.size)
        val publicacion = requireNotNull(publicaciones).single()
        assertEquals("v0.2.0", publicacion.etiqueta)
        assertEquals(false, publicacion.borrador)
        assertEquals(false, publicacion.preliminar)
        assertEquals(
            "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/update.json",
            publicacion.activo(PublicacionesGitHub.NOMBRE_MANIFIESTO)?.urlDescarga,
        )
    }

    @Test
    fun `marca preliminar y borrador como vienen de la API`() {
        val json =
            """
            [
              {"tag_name":"v0.3.0-beta.1","draft":false,"prerelease":true,
               "html_url":"https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.3.0-beta.1","assets":[]}
            ]
            """.trimIndent()

        val publicacion = PublicacionesGitHub.parse(json)?.single()

        assertEquals(true, publicacion?.preliminar)
        assertEquals(false, publicacion?.borrador)
        assertNull(publicacion?.activo(PublicacionesGitHub.NOMBRE_MANIFIESTO))
    }

    @Test
    fun `un release sin activos queda con lista vacia`() {
        val json =
            """
            [
              {"tag_name":"v0.1.0","draft":true,"prerelease":false,
               "html_url":"https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.1.0"}
            ]
            """.trimIndent()

        val publicacion = PublicacionesGitHub.parse(json)?.single()

        assertEquals(true, publicacion?.activos?.isEmpty())
        assertEquals(true, publicacion?.borrador)
    }

    @Test
    fun `omite elementos invalidos y conserva los validos`() {
        val json =
            """
            [
              {"draft":false,"prerelease":false,"html_url":"https://github.com/x","assets":[]},
              {"tag_name":"v0.2.0","draft":false,"prerelease":false,
               "html_url":"https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.2.0","assets":[]},
              {"tag_name":"v0.3.0",
               "assets":[{"name":"update.json"}]}
            ]
            """.trimIndent()

        val publicaciones = PublicacionesGitHub.parse(json)

        assertEquals(listOf("v0.2.0"), publicaciones?.map { it.etiqueta })
    }

    @Test
    fun `documento que no es lista devuelve null`() {
        assertNull(PublicacionesGitHub.parse("""{"message":"Not Found"}"""))
        assertNull(PublicacionesGitHub.parse("no es json"))
        assertNull(PublicacionesGitHub.parse(""))
    }

    @Test
    fun `documento mayor al tope devuelve null`() {
        val enorme = "[${" ".repeat(PublicacionesGitHub.MAX_BYTES + 1)}]"

        assertNull(PublicacionesGitHub.parse(enorme))
    }
}
