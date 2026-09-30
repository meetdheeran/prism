package com.meetdheeran.prism.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that stand between the model and an unwanted send. They must not depend on the model. */
class AgentPolicyTest {
    private val own = "com.meetdheeran.prism"

    @Test fun `banking payment and password apps are blocked by package or name`() {
        listOf(
            "com.paypal.android.p2pmobile" to "PayPal",
            "com.google.android.apps.nbu.paisa.user" to "Google Pay",
            "com.phonepe.app" to "PhonePe",
            "com.x8bit.bitwarden" to "Bitwarden",
            "com.example.mybankapp" to "My Bank",
            "ae.somebank.retail" to "Emirates Islamic Banking",
            "com.example.wallet" to "Wallet",
            "com.google.android.apps.authenticator2" to "Authenticator",
        ).forEach { (pkg, label) -> assertNotNull("$label should be blocked", AgentPolicy.blockReason(pkg, label, own)) }
    }

    @Test fun `everyday apps are allowed`() {
        listOf(
            "us.zoom.videomeetings" to "Zoom",
            "com.google.android.gm" to "Gmail",
            "com.whatsapp" to "WhatsApp",
            "com.instagram.android" to "Instagram",
            "com.spotify.music" to "Spotify",
            "com.android.settings" to "Settings",
            "com.facebook.katana" to "Facebook",
            "com.google.android.youtube" to "YouTube",
            "com.google.android.apps.maps" to "Maps",
        ).forEach { (pkg, label) -> assertNull("$label should be allowed", AgentPolicy.blockReason(pkg, label, own)) }
    }

    @Test fun `send delete pay and friends need the user's OK`() {
        listOf("Send", "send message", "Post", "Delete chat", "Pay now", "Place order", "Confirm", "Allow", "Call", "Share", "Submit", "Uninstall")
            .forEach { assertTrue("\"$it\" should need confirmation", AgentPolicy.looksIrreversible(it, null)) }
        assertTrue("icon-only send button found by its id", AgentPolicy.looksIrreversible("", "btn_send"))
    }

    @Test fun `navigation taps run without asking`() {
        listOf("Chat", "Team Chat", "Search", "Anushka Thakur", "Back", "Contacts", "Reply", "Forward", "Facebook", "Settings", "Compose", "Message")
            .forEach { assertFalse("\"$it\" should not need confirmation", AgentPolicy.looksIrreversible(it, null)) }
    }

    @Test fun `enter in a chat box asks, enter in a search box doesn't`() {
        assertTrue(AgentPolicy.submitNeedsConfirm("Message", "chat_input"))
        assertTrue(AgentPolicy.submitNeedsConfirm("", null))
        assertFalse(AgentPolicy.submitNeedsConfirm("Search", null))
        assertFalse(AgentPolicy.submitNeedsConfirm("", "search_src_text"))
        assertEquals(false, AgentPolicy.submitNeedsConfirm("Search or type URL", null))
    }
}
