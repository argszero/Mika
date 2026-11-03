package com.wangzan101.mika.core.child

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class ChildPhase { Running, Completed, Interrupted, Unknown }

@Serializable data class ChildRecord(val id: String, val parentId: String, val phase: ChildPhase,
    val result: String? = null, val atMs: Long)

interface ChildRunStore {
    fun save(record: ChildRecord)
    fun get(id: String): ChildRecord?
    fun all(): List<ChildRecord>
}

class MemoryChildRunStore : ChildRunStore {
    private val records = linkedMapOf<String, ChildRecord>()
    @Synchronized override fun save(record: ChildRecord) { records[record.id] = record }
    @Synchronized override fun get(id: String) = records[id]
    @Synchronized override fun all() = records.values.toList()
}

/** 子 Run 的结论。[text] 是子任务自己交出来的整份原文,不在这里按长度截:要不要截、截多少,由调用方按自己的上限决定(见 `spawn_agent` 的 `maxResultChars`)。 */
data class ChildResult(val id: RunId, val phase: ChildPhase, val text: String)

fun interface ChildRunHost { suspend fun spawn(spec: RunSpec, format: String?): ChildResult }

/** 子 Run 先保存结论再交给父 Run；父 Run 消失时保留 Unknown 记录，不自动重跑。 */
class ChildAgentRunner(
    private val create: (RunSpec) -> AgentLoop,
    private val store: ChildRunStore,
    private val ownerAlive: (RunId) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : ChildRunHost {
    private val active = HashMap<RunId, MutableSet<AgentLoop>>()

    override suspend fun spawn(spec: RunSpec, format: String?): ChildResult {
        require(spec.parent != null && spec.provenance.origin == com.wangzan101.mika.core.run.RunOrigin.Child)
        val parent = spec.parent
        val agent = create(spec)
        synchronized(active) { active.getOrPut(parent) { mutableSetOf() } += agent }
        store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Running, atMs = clock()))
        try {
            val end = agent.run(spec, RunInbox())
            val raw = when (end) {
                is TaskState.Succeeded -> end.answer
                is TaskState.Failed -> end.reason
                TaskState.Cancelled -> INTERRUPTED
                else -> "no result"
            }
            val phase = if (end == TaskState.Cancelled) ChildPhase.Interrupted else ChildPhase.Completed
            // 校验的是子任务自己交出来的那份,不是落盘时截过的那份:截过的 JSON 必然解析不了,
            // 原来那句「没通过校验」其实只是被这里先砍了一刀。
            val checked = if (format == "json" && runCatching { Json.parseToJsonElement(raw) }.isFailure)
                "$raw\n(the child run's JSON did not pass validation; the original text is kept)" else raw
            // 交给父 Run 的是原文,截多少由父 Run 按自己的上限决定(见 SpawnAgent 的 maxResultChars);
            // 落盘的那份才按这里的上限截——账本每个子 Run 都整份重写,不能留成任意长。
            val stored = checked.take(MAX_RESULT_CHARS)
            store.save(ChildRecord(spec.id.value, parent.value, phase, stored, clock()))
            if (!ownerAlive(parent)) {
                store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Unknown, stored, clock()))
                return ChildResult(spec.id, ChildPhase.Unknown, checked)
            }
            return ChildResult(spec.id, phase, checked)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Interrupted, INTERRUPTED, clock())) }
            throw e
        } finally {
            synchronized(active) { active[parent]?.remove(agent); if (active[parent]?.isEmpty() == true) active.remove(parent) }
        }
    }

    fun cancelAll(parent: RunId) { synchronized(active) { active[parent]?.toList() }.orEmpty().forEach(AgentLoop::cancel) }

    companion object {
        /** 落盘的子任务结果留多少字。交给父 Run 的那份不截:父 Run 按自己的 maxResultChars 截(见 SpawnAgent)。 */
        const val MAX_RESULT_CHARS = 4000
        private const val INTERRUPTED = "interrupted"
    }
}
