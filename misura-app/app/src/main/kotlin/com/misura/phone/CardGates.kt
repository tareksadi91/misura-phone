package com.misura.phone

import com.misura.core.agent.Say

/** One tool that asks on a card: what its tap does, what the tap did (for the model's next turn), and how a stop voids its open cards. */
internal class GateOwner(val answered: (String, Boolean) -> Unit, val stopped: () -> Unit, val outcome: (String) -> String? = { null })

/**
 * Routes a card tap to the tool that asked, by the card id's prefix: "cal:" calendar, "rt:" routine, "sc:" shortcut, anything
 * else use_app. The Agent's onChoose / chooseNote / onStopped in [MisuraGraph] are exactly these, so a test drives the same routing.
 */
internal class CardGates(private val calendar: GateOwner, private val routine: GateOwner, private val shortcut: GateOwner, private val useApp: GateOwner) {
    private fun owner(gate: String) = when {
        gate.startsWith("cal:") -> calendar
        gate.startsWith("rt:") -> routine
        gate.startsWith("sc:") -> shortcut
        else -> useApp
    }
    /** Only the first choice (Add / Save / Keep / Remove / Go ahead) is a yes. */
    val onChoose: (Say.Question, Int) -> Unit = { q, i -> q.gate?.let { owner(it).answered(it, i == 0) } }
    /** Only routine and shortcut cards report what their tap did; others say nothing. */
    val chooseNote: (Say.Question, Int) -> String? = { q, _ -> q.gate?.takeIf { it.startsWith("rt:") || it.startsWith("sc:") }?.let { owner(it).outcome(it) } }
    val onStopped: () -> Unit = { calendar.stopped(); useApp.stopped(); routine.stopped(); shortcut.stopped() }
}

/** The four owners as [MisuraGraph] wires them: each tool's own answered / stopped (/ outcome). One place, so a test builds exactly this. */
internal fun cardGates(calendar: com.misura.phone.hands.Calendar, routine: com.misura.phone.routine.RoutineTool,
    shortcut: com.misura.phone.shortcut.ShortcutTool, useApp: com.misura.phone.hands.UseApp) =
    CardGates(GateOwner(calendar::answered, calendar::stopped), GateOwner(routine::answered, routine::stopped, routine::outcome),
        GateOwner(shortcut::answered, shortcut::stopped, shortcut::outcome), GateOwner(useApp::answered, useApp::stopped))
