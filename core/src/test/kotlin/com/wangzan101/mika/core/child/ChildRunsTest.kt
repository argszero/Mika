package com.wangzan101.mika.core.child

import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildRunsTest {
    @Test fun spawnReturnsBoundedObservationAndPersistsChildFirst() = runBlocking {
        val parent = RunSpec(RunId("parent"), "goal", "thread")
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ spec ->
            AgentLoop(FakeModel(answerReply("子结论")),
                toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec), "BASE", confirmations = null)
        }, store, ownerAlive = { store.all().single().phase == ChildPhase.Completed })
        val tools = toolDispatcher(FakeScreen(), runSpec = parent, childHost = runner)
        val result = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"只查一条事实"}"""), ActionPass())
        val record = store.all().single()
        assertEquals(ChildPhase.Completed, record.phase)
        assertEquals("parent", record.parentId)
        assertEquals("子结论", record.result)
        assertEquals(Trust.Untrusted, result.observations.single().trust)
        assertEquals("子结论", result.observations.single().text)
    }

    @Test fun childRestrictionsCannotBeBypassedByRunSpecFlags() = runBlocking {
        val spec = RunSpec(RunId("child"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Present),
            capabilities = null, readOnly = false, parent = RunId("parent"))
        val tools = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec,
            childHost = ChildRunHost { _, _ -> error("must not spawn") }, scheduleTrigger = { Scheduled(true) })
        val names = tools.definitions().map { it.name }
        assertTrue("web_read_page" in names)
        assertFalse(names.any { it.startsWith("phone_") || it == "web_interact" || it == "write_file" ||
            it == "spawn_agent" || it == "memory_save" || it == "schedule_task" ||
            it == "ask_user" || it == "hand_to_user" || it == "request_confirmation" })
        assertEquals("unknown_tool", tools.execute(Part.ToolCall("w", "web_interact", "{}"), ActionPass())
            .content["error"].toString().trim('"'))
    }

    @Test fun absentOwnerLeavesUnknownResultWithoutRerun() = runBlocking {
        val store = MemoryChildRunStore()
        val spec = RunSpec(RunId("c"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended), parent = RunId("p"))
        val runner = ChildAgentRunner({ s -> AgentLoop(FakeModel(answerReply("answer")),
            toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = s), "BASE", confirmations = null) }, store,
            ownerAlive = { false })
        assertEquals(ChildPhase.Unknown, runner.spawn(spec, null).phase)
        assertEquals(ChildPhase.Unknown, store.get("c")?.phase)
    }

    /**
     * 子任务结论比落盘上限长:交给父 Run 的是整份原文,落盘的那份才截到 [ChildAgentRunner.MAX_RESULT_CHARS]。
     * 这条必须走真 [ChildAgentRunner]——用假 host 原样返回长文本,跨层的截断顺序就测不出来。
     */
    @Test fun childAnswerLongerThanTheStoredCapReachesTheParentWhole() = runBlocking {
        val store = MemoryChildRunStore()
        val long = "长".repeat(6_000)
        val runner = ChildAgentRunner({ s -> AgentLoop(FakeModel(answerReply(long)),
            toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = s), "BASE", confirmations = null) }, store)
        val spec = RunSpec(RunId("c"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended), parent = RunId("p"))
        val result = runner.spawn(spec, null)
        assertEquals(long.length, result.text.length)
        assertEquals(ChildAgentRunner.MAX_RESULT_CHARS, store.get("c")?.result?.length)
    }

    /** 同上,一路走到 `spawn_agent`:`maxResultChars` 超过落盘上限也拿得到,`truncated` 和原长都是真的。 */
    @Test fun askingForMoreThanTheStoredCapDeliversItAndReportsTheRealOriginalLength() = runBlocking {
        val store = MemoryChildRunStore()
        val long = "x".repeat(9_000)
        val runner = ChildAgentRunner({ s -> AgentLoop(FakeModel(answerReply(long)),
            toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = s), "BASE", confirmations = null) }, store)
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = runner)
        val content = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"读一页并整理","maxResultChars":8000}"""), ActionPass()).content
        assertEquals(8_000, resultLength(content))
        assertEquals("true", content["truncated"].toString())
        assertEquals(long.length.toString(), content["fullLength"].toString())
        // 账本里那份照旧有上限
        assertEquals(ChildAgentRunner.MAX_RESULT_CHARS, store.all().single().result?.length)
    }

    /** 原文比父 Run 的上限短:整份交出去,不写 truncated,也不写原长。 */
    @Test fun childAnswerUnderTheLimitComesBackWholeWithoutTheFlags() = runBlocking {
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ s -> AgentLoop(FakeModel(answerReply("短结论")),
            toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = s), "BASE", confirmations = null) }, store)
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = runner)
        val content = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"查一条"}"""), ActionPass()).content
        assertEquals("短结论".length, resultLength(content))
        assertEquals(null, content["truncated"])
        assertEquals(null, content["fullLength"])
    }

    private fun spawnWith(args: String, text: String = "x".repeat(20_000)): Pair<RunSpec, kotlinx.serialization.json.JsonObject> = runBlocking {
        var seen: RunSpec? = null
        val host = ChildRunHost { spec, _ -> seen = spec; ChildResult(spec.id, ChildPhase.Completed, text) }
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = host)
        val r = tools.execute(Part.ToolCall("s", "spawn_agent", args), ActionPass())
        seen!! to r.content
    }

    private fun resultLength(content: kotlinx.serialization.json.JsonObject) =
        (content["result"] as kotlinx.serialization.json.JsonPrimitive).content.removePrefix("<untrusted>\n").removeSuffix("\n</untrusted>").length

    @Test fun defaultBudgetsStayAsBefore() {
        val (spec, content) = spawnWith("""{"goal":"查一条"}""")
        assertEquals(15, spec.stepBudget)
        assertTrue(spec.readOnly)
        assertEquals(setOf("web", "sandbox"), spec.capabilities)
        assertEquals(4000, resultLength(content))
        assertEquals("true", content["truncated"].toString())
    }

    @Test fun modelChosenBudgetsAreClampedToTheCaps() {
        val (spec, content) = spawnWith("""{"goal":"查三家","maxSteps":30,"maxResultChars":6000}""")
        assertEquals(30, spec.stepBudget)
        assertEquals(6000, resultLength(content))
        val (high, highContent) = spawnWith("""{"goal":"查三家","maxSteps":500,"maxResultChars":100000}""")
        assertEquals(40, high.stepBudget)
        assertEquals(8000, resultLength(highContent))
        val (low, lowContent) = spawnWith("""{"goal":"查三家","maxSteps":0,"maxResultChars":10}""")
        assertEquals(1, low.stepBudget)
        assertEquals(500, resultLength(lowContent))
        val (_, short) = spawnWith("""{"goal":"查三家"}""", text = "短结论")
        assertEquals(null, short["truncated"])
    }

    @Test fun nonIntegerBudgetIsRejected() = runBlocking {
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"),
            childHost = ChildRunHost { _, _ -> error("must not spawn") })
        val r = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"g","maxSteps":2.5}"""), ActionPass())
        assertEquals("invalid_arguments", r.content["error"].toString().trim('"'))
    }
}
