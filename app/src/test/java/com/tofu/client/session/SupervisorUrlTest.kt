package com.tofu.client.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for the supervisor URL derivation (no device needed). */
class SupervisorUrlTest {

    @Test
    fun derives_supervisor_base_from_proxy_url() {
        val su = SupervisorUrl.fromServerUrl(
            "https://5665bc99-vscode-zw05.mlp.sankuai.com/proxy/15000/",
        )!!
        assertEquals(
            "https://5665bc99-vscode-zw05.mlp.sankuai.com/proxy/15001", su.base,
        )
        assertEquals(
            "https://5665bc99-vscode-zw05.mlp.sankuai.com", su.origin,
        )
    }

    @Test
    fun derives_from_bare_host() {
        val su = SupervisorUrl.fromServerUrl("http://localhost:15000/")!!
        assertEquals("http://localhost:15000/proxy/15001", su.base)
        assertEquals("http://localhost:15000", su.origin)
    }

    @Test
    fun invalid_url_returns_null() {
        assertNull(SupervisorUrl.fromServerUrl("not a url"))
        assertNull(SupervisorUrl.fromServerUrl(""))
    }

    @Test
    fun status_endpoint_encodes_project_path_query() {
        val su = SupervisorUrl("https://h.example/proxy/15001", "https://h.example")
        val url = SupervisorUrl.endpoint(su, SupervisorUrl.STATUS, "/home/dev/chatui")
        assertEquals(
            "https://h.example/proxy/15001/status?projectPath=%2Fhome%2Fdev%2Fchatui", url,
        )
    }

    @Test
    fun post_endpoint_has_no_query() {
        val su = SupervisorUrl("https://h.example/proxy/15001", "https://h.example")
        assertEquals(
            "https://h.example/proxy/15001/start",
            SupervisorUrl.endpoint(su, SupervisorUrl.START, null),
        )
        assertEquals(
            "https://h.example/proxy/15001/stop",
            SupervisorUrl.endpoint(su, SupervisorUrl.STOP, null),
        )
    }
}
