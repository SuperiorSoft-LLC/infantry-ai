package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RUNTIME-LOAD-8: the mail tools over a scripted daemon: the frames they send and the answers they print. */
class MailToolsTest {
    private class FakeCall(
        override val arguments: JsonObject,
        override val clientSessionId: String? = "s-me",
        private val answers: (String, String) -> Frame,
    ) : ToolCall {
        val sent = mutableListOf<Triple<String, String, String?>>()
        override val caller: Caller get() = error("not used")
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame {
            sent += Triple(type, data, sessionId)
            return answers(type, data)
        }
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private val inbox = """[
        {"id":"m-aaa1","threadId":"t1","seq":1,"from":"s-pm","to":"s-me","subject":"hi","body":"first line\nmore","sentAt":10,"state":"READ","readAt":11},
        {"id":"m-aaa2","threadId":"t1","seq":2,"from":"s-pm","to":"s-me","body":"second","sentAt":20,"state":"DELIVERED","deliveredAt":21},
        {"id":"m-bbb3","threadId":"t2","seq":1,"from":"s-pm","to":"s-me","body":"queued","sentAt":30},
        {"id":"m-ccc4","threadId":"t3","seq":1,"from":"s-pm","to":"s-me","body":"bad","sentAt":40,"state":"UNDELIVERABLE","failureReason":"no such agent"}
    ]"""

    private fun mailbox(type: String, data: String): Frame = when (type) {
        "mail_inbox" -> Frame("mails", inbox)
        "mail_read" -> Frame("mail", """{"id":"$data","threadId":"t1","seq":2,"from":"s-pm","to":"s-me","body":"second","sentAt":20,"state":"READ","readAt":99,"replyTo":"m-aaa1"}""")
        "mail_send" -> Frame("mail", """{"id":"m-new","threadId":"t9","seq":1,"from":"s-me","to":"s-pm","body":"x","sentAt":1,"state":"QUEUED"}""")
        else -> Frame("error", "unexpected $type")
    }

    @Test fun inboxListsEveryMessageAsTheCoreToolDidAndFiltersUnread() {
        val call = FakeCall(args("{}"), answers = ::mailbox)
        val all = Json.parseToJsonElement(MailTools.InboxTool.call(call).text).jsonObject
        assertEquals("4", all["total"]!!.jsonPrimitive.content)
        // TOKEN-1: counts + one line per item (no preview unless asked); the newest unread comes first.
        assertTrue((all["items"] as kotlinx.serialization.json.JsonArray)[0].jsonPrimitive.content.startsWith("m-ccc4 | s-pm"))
        assertEquals(Triple("mail_inbox", "s-me", "s-me"), call.sent.single())
        val unread = Json.parseToJsonElement(MailTools.InboxTool.call(FakeCall(args("""{"unreadOnly":true}"""), answers = ::mailbox)).text).jsonObject
        assertEquals("3", unread["shown"]!!.jsonPrimitive.content)
    }

    @Test fun inboxOutsideASessionAsksForTheHumanHandle() {
        val call = FakeCall(args("{}"), clientSessionId = null, answers = ::mailbox)
        MailTools.InboxTool.call(call)
        assertEquals(Triple("mail_inbox", "user", null), call.sent.single())
    }

    @Test fun readMarksADeliveredMessageAndRefusesTheOthersWithTheCoreWording() {
        val ok = MailTools.ReadTool.call(FakeCall(args("""{"messageId":"m-aaa2"}"""), answers = ::mailbox))
        assertFalse(ok.isError, ok.text)
        val body = Json.parseToJsonElement(ok.text).jsonObject
        assertEquals("second", body["body"]!!.jsonPrimitive.content)
        assertEquals("m-aaa1", body["replyTo"]!!.jsonPrimitive.content)
        assertEquals("true", body["read"]!!.jsonPrimitive.content)

        fun refusal(id: String) = MailTools.ReadTool.call(FakeCall(args("""{"messageId":"$id"}"""), answers = ::mailbox)).also { assertTrue(it.isError) }.text
        assertTrue(refusal("m-aaa").contains("ambiguous"))
        assertTrue(refusal("zzz").contains("no such message 'zzz'"))
        assertTrue(refusal("m-ccc4").contains("was never delivered: no such agent"))
        assertTrue(refusal("m-bbb3").contains("has not reached a console yet (state QUEUED)"))
        assertTrue(MailTools.ReadTool.call(FakeCall(args("{}"), answers = ::mailbox)).isError)
    }

    @Test fun messageSendsMailFromTheSessionAndReportsRefusals() {
        val call = FakeCall(args("""{"to":"s-pm","body":"hello","replyTo":"m1"}"""), answers = ::mailbox)
        assertEquals("ok m-new QUEUED", MailTools.MessageTool.call(call).text)
        val (type, data, session) = call.sent.single()
        assertEquals("mail_send", type)
        assertEquals("s-me", session)
        val frame = Json.parseToJsonElement(data).jsonObject
        assertEquals("", frame["from"]!!.jsonPrimitive.content)
        assertEquals("m1", frame["replyTo"]!!.jsonPrimitive.content)

        val human = FakeCall(args("""{"to":"s-pm","body":"hello"}"""), clientSessionId = null, answers = ::mailbox)
        MailTools.MessageTool.call(human)
        assertEquals("user", Json.parseToJsonElement(human.sent.single().second).jsonObject["from"]!!.jsonPrimitive.content)

        val refused = MailTools.MessageTool.call(FakeCall(args("""{"to":"x","body":"b"}"""), answers = { _, _ ->
            Frame("mail_refused", """{"from":"s-me","to":"x","reason":"cross-project mail is refused"}""")
        }))
        assertTrue(refused.isError)
        assertEquals("infantry_message refused: cross-project mail is refused", refused.text)
        assertEquals("infantry_message needs a body", MailTools.MessageTool.call(FakeCall(args("""{"to":"x"}"""), answers = ::mailbox)).text)
    }

    @Test fun aDaemonFailureIsAnErrorResultNotACrash() {
        val dead = FakeCall(args("{}"), answers = { _, _ -> error("mail_inbox got no answer in 45000 ms") })
        val result = MailTools.InboxTool.call(dead)
        assertTrue(result.isError)
        assertTrue(result.text.contains("no answer"))
    }
}
