package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncGroupPolicyTest {
    /** What the ROM handed back when we last created a group, plus the SSID we had asked for. */
    private val ours = listOf("DIRECT-xy-Android_9f3a", "DIRECT-NS-ab3d9k")

    private fun classify(networkName: String?) = MediaSyncGroupPolicy.classify(networkName, ours)

    @Test
    fun `the framework-generated name we recorded on creation is ours`() {
        // This ROM rejects configured creation, so our own group carries a DIRECT-xy- name no
        // prefix rule could ever recognise. The recorded credentials are the only proof we have.
        assertEquals(MediaSyncGroupOwnership.OURS, classify("DIRECT-xy-Android_9f3a"))
    }

    @Test
    fun `the SSID we asked for is ours on ROMs that honour it`() {
        assertEquals(MediaSyncGroupOwnership.OURS, classify("DIRECT-NS-ab3d9k"))
        assertEquals(MediaSyncGroupOwnership.OURS, classify("DIRECT-NS-anyother"))
    }

    @Test
    fun `an identically-shaped stranger is not ours`() {
        // Same DIRECT-xy- shape as our recorded group, different suffix: not proof, not ours.
        assertEquals(MediaSyncGroupOwnership.FOREIGN, classify("DIRECT-xy-Android_0000"))
    }

    @Test
    fun `a recognisable camera group is classified as the camera's`() {
        assertEquals(MediaSyncGroupOwnership.CAMERA_LINK, classify("DIRECT-RN-1a2b3c"))
    }

    @Test
    fun `blank and unknown names are foreign`() {
        listOf("", null, "AndroidShare_9182").forEach { name ->
            assertEquals("classify($name)", MediaSyncGroupOwnership.FOREIGN, classify(name))
        }
    }

    @Test
    fun `nothing that is not ours is ever removed`() {
        // The decisive rule after the first device run: on this ROM the camera's parked group is
        // indistinguishable from any stranger's, so "foreign" must never mean "removable".
        listOf(MediaSyncGroupOwnership.CAMERA_LINK, MediaSyncGroupOwnership.FOREIGN).forEach {
            assertEquals(MediaSyncGroupAction.DEFER, MediaSyncGroupPolicy.action(it, usable = true))
            assertEquals(MediaSyncGroupAction.DEFER, MediaSyncGroupPolicy.action(it, usable = false))
        }
    }

    @Test
    fun `our own group is reused when usable and rebuilt when not`() {
        assertEquals(
            MediaSyncGroupAction.REUSE,
            MediaSyncGroupPolicy.action(MediaSyncGroupOwnership.OURS, usable = true),
        )
        assertEquals(
            MediaSyncGroupAction.REBUILD,
            MediaSyncGroupPolicy.action(MediaSyncGroupOwnership.OURS, usable = false),
        )
    }

    @Test
    fun `with nothing recorded yet only the requested prefix counts as ours`() {
        assertEquals(
            MediaSyncGroupOwnership.OURS,
            MediaSyncGroupPolicy.classify("DIRECT-NS-ab3d9k", emptyList()),
        )
        assertEquals(
            MediaSyncGroupOwnership.FOREIGN,
            MediaSyncGroupPolicy.classify("DIRECT-xy-Android_9f3a", emptyList()),
        )
        assertEquals(
            MediaSyncGroupOwnership.FOREIGN,
            MediaSyncGroupPolicy.classify("DIRECT-xy-Android_9f3a", listOf("")),
        )
    }

    @Test
    fun `the two prefixes stay distinct`() {
        assertEquals("DIRECT-NS-", MediaSyncP2pProfileStore.NETWORK_NAME_PREFIX)
        assertEquals("DIRECT-RN-", MediaSyncGroupPolicy.CAMERA_NETWORK_NAME_PREFIX)
    }
}
