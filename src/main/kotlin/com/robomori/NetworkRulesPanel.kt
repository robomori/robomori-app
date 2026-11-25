package com.robomori

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.Icons
import com.robomori.iconography.PencilSquare
import com.robomori.iconography.Trash
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

/** Opens the rules, saying how many are on. */
@Composable
internal fun NetworkRulesButton(rules: NetworkRulesState) {
    val count = rules.enabledCount
    val label = when {
        !rules.allEnabled && rules.rules.isNotEmpty() -> "Rules (Off)"
        count == 0 -> "Rules"
        else -> "Rules ($count)"
    }
    Button(onClick = rules::openList, tag = "NetworkRules") { Label(label) }
}

/** The mark on a request a rule changed. */
@Composable
internal fun NetworkRuleMark() {
    Icon(Icons.PencilSquare, tint = RobomoriTheme.colors.keyIcon, modifier = Modifier.size(12.dp).testTag("NetworkRuleMark"))
}

/** What [this] does, in a line, such as "Mock 500 · GET api.example.com/users". */
internal val NetworkRule.summary: String get() {
    val target = listOfNotNull(method, url).joinToString(" ")
    val change = when (val action = action) {
        is NetworkRuleAction.Mock -> "Mock ${action.status}"
        is NetworkRuleAction.Modify -> listOfNotNull(
            action.status?.let { "Status $it" },
            "Headers".takeIf { action.setHeaders.isNotEmpty() || action.removeHeaders.isNotEmpty() },
            when (action.body) {
                null -> null
                is NetworkBodyEdit.Replace -> "Body"
                is NetworkBodyEdit.Substitute -> "Replace “${action.body.find}”"
            },
        ).ifEmpty { listOf("Unchanged") }.joinToString(", ", prefix = "Modify ")
    }
    return "$change · $target"
}

/** The rules list and editor; [exchanges] are the requests listed, which the editor shows a rule matching. */
@Composable
internal fun NetworkRulesDialogs(rules: NetworkRulesState, exchanges: List<NetworkExchange>) {
    AppDialog(rules.dialog, onClose = rules::close, dismissOnClickOutside = false) { dialog ->
        when (dialog) {
            NetworkRulesDialog.RuleList -> RuleListDialog(rules)
            is NetworkRulesDialog.Edit -> RuleEditor(rules, dialog.draft, exchanges)
        }
    }
}

@Composable
private fun RuleListDialog(rules: NetworkRulesState) {
    DialogScaffold("Rules", "NetworkRulesDialog", Modifier.width(560.dp), footer = {
        Button(onClick = rules::newRule, tag = "NewNetworkRule") { Label("New Rule…") }
        Spacer(Modifier.weight(1f))
        Button(onClick = rules::close, style = ButtonStyle.Primary, tag = "NetworkRulesDone") { Label("Done") }
    }) {
        SwitchRow("Use Rules", rules.allEnabled, "NetworkRulesOn", rules::useRules, icon = Icons.PencilSquare)
        Label("Rules change the responses apps get on every device, before they reach the app. Right-click a request to make one from it.",
            color = RobomoriTheme.colors.textSecondary)
        if (rules.rules.isEmpty()) Label("No rules yet.", color = RobomoriTheme.colors.textSecondary)
        else Hint("The first rule that matches a request changes it. Right-click a rule to duplicate it or move it up or down.")
        Column {
            rules.rules.forEachIndexed { index, rule ->
                if (index > 0) Rule()
                ContextMenuArea(items = { listOfNotNull(
                    ContextMenuItem("Edit…") { rules.edit(rule) },
                    ContextMenuItem("Duplicate") { rules.duplicate(rule.id) },
                    ContextMenuItem("Move Up") { rules.move(rule.id, -1) }.takeIf { index > 0 },
                    ContextMenuItem("Move Down") { rules.move(rule.id, 1) }.takeIf { index < rules.rules.lastIndex },
                    ContextMenuItem("Delete") { rules.delete(rule.id) },
                ) }) {
                    Row(Modifier.padding(vertical = 6.dp).testTag("NetworkRule:${rule.id}"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CheckboxRow(rule.summary, rule.enabled, { rules.setEnabled(rule.id, it) }, Modifier.weight(1f),
                            tag = "NetworkRuleEnabled:${rule.id}", enabled = rules.allEnabled)
                        Button(onClick = { rules.edit(rule) }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                            contentDescription = "Edit rule", tag = "EditNetworkRule:${rule.id}") { Icon(Icons.PencilSquare) }
                        Button(onClick = { rules.delete(rule.id) }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                            contentDescription = "Delete rule", tag = "DeleteNetworkRule:${rule.id}") { Icon(Icons.Trash) }
                    }
                }
            }
        }
    }
}

private val ruleMethods = listOf(null, "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

@Composable
private fun RuleEditor(rules: NetworkRulesState, draft: NetworkRuleDraft, exchanges: List<NetworkExchange>) {
    fun update(change: NetworkRuleDraft.() -> NetworkRuleDraft) = rules.updateDraft(draft.change())
    val problem = draft.problem
    DialogScaffold(if (draft.id == null) "New Rule" else "Edit Rule", "NetworkRuleEditor", Modifier.width(560.dp), footer = {
        problem?.let { Label(it.message, Modifier.weight(1f), color = RobomoriTheme.colors.danger) } ?: Spacer(Modifier.weight(1f))
        Button(onClick = rules::cancelEdit, tag = "CancelNetworkRule") { Label("Cancel") }
        Button(onClick = rules::saveDraft, style = ButtonStyle.Primary, enabled = problem == null, tag = "SaveNetworkRule") { Label("Save") }
    }) {
        Section("Requests") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Select(draft.method, ruleMethods, { update { copy(method = it) } }, label = { it ?: "Any" }, tag = "NetworkRuleMethod",
                    modifier = Modifier.width(130.dp))
                DraftField(draft.url, { update { copy(url = it) } }, "NetworkRuleUrl", "api.example.com/v1/users*", Modifier.weight(1f))
            }
            Hint("* stands for any text. Without a ?, any query matches.")
            MatchPreview(draft, exchanges)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToggleButton(draft.kind == NetworkRuleKind.Mock, { update { copy(kind = NetworkRuleKind.Mock) } }, size = ButtonSize.Small,
                tag = "NetworkRuleKind-Mock") { Label("Mock Response") }
            ToggleButton(draft.kind == NetworkRuleKind.Modify, { update { copy(kind = NetworkRuleKind.Modify) } }, size = ButtonSize.Small,
                tag = "NetworkRuleKind-Modify") { Label("Modify Response") }
        }
        when (draft.kind) {
            NetworkRuleKind.Mock -> {
                Hint("Answers with this response, without contacting the server.")
                Section("Status") { DraftField(draft.mockStatus, { update { copy(mockStatus = it) } }, "NetworkRuleMockStatus", "200", Modifier.width(100.dp)) }
                Section("Headers") { DraftTextArea(draft.mockHeaders, { update { copy(mockHeaders = it) } }, "NetworkRuleMockHeaders", "Name: value", 4) }
                Section("Body") { DraftTextArea(draft.mockBody, { update { copy(mockBody = it) } }, "NetworkRuleMockBody", null, 10) }
            }
            NetworkRuleKind.Modify -> {
                Hint("Changes the server's response before it reaches the app.")
                Section("Status") { DraftField(draft.modifyStatus, { update { copy(modifyStatus = it) } }, "NetworkRuleModifyStatus", "Keep", Modifier.width(100.dp)) }
                Section("Add or Replace Headers") {
                    DraftTextArea(draft.setHeaders, { update { copy(setHeaders = it) } }, "NetworkRuleSetHeaders", "Name: value", 3)
                }
                Section("Remove Headers") {
                    DraftField(draft.removeHeaders, { update { copy(removeHeaders = it) } }, "NetworkRuleRemoveHeaders", "Names, separated by commas", Modifier.fillMaxWidth())
                }
                Section("Body") {
                    Select(draft.bodyEdit, NetworkBodyEditKind.entries, { update { copy(bodyEdit = it) } }, label = { it.title },
                        tag = "NetworkRuleBodyEdit", modifier = Modifier.width(200.dp))
                    when (draft.bodyEdit) {
                        NetworkBodyEditKind.Keep -> {}
                        NetworkBodyEditKind.Replace ->
                            DraftTextArea(draft.replacementBody, { update { copy(replacementBody = it) } }, "NetworkRuleReplacementBody", null, 10)
                        NetworkBodyEditKind.Substitute -> {
                            DraftField(draft.find, { update { copy(find = it) } }, "NetworkRuleFind", "Find", Modifier.fillMaxWidth())
                            DraftField(draft.replacement, { update { copy(replacement = it) } }, "NetworkRuleReplacement", "Replace with", Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** Which listed requests the rule being edited matches, as Proxyman's rule tester shows, so a pattern can be checked as it's typed. */
@Composable
private fun MatchPreview(draft: NetworkRuleDraft, exchanges: List<NetworkExchange>) {
    if (draft.url.isBlank()) return
    val matched = remember(draft.url, draft.method, exchanges) {
        val rule = NetworkRule("", draft.url, NetworkRuleAction.Mock(200, emptyList(), ""), draft.method)
        exchanges.filter { it.method != "CONNECT" && rule.matches(it.method, it.url) }.distinctBy { it.method to it.url }
    }
    Column(Modifier.testTag("NetworkRuleMatches"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Hint(when (matched.size) {
            0 -> "Matches none of the requests listed."
            1 -> "Matches 1 of the requests listed:"
            else -> "Matches ${matched.size} of the requests listed, such as:"
        })
        for (exchange in matched.take(3)) {
            Text("${exchange.method} ${exchange.url}", style = RobomoriTheme.typography.codeSmall, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

private val NetworkBodyEditKind.title get() = when (this) {
    NetworkBodyEditKind.Keep -> "Keep"
    NetworkBodyEditKind.Replace -> "Replace"
    NetworkBodyEditKind.Substitute -> "Find and Replace"
}

private val NetworkRuleProblem.message get() = when (this) {
    NetworkRuleProblem.MissingUrl -> "Enter the requests' server and path."
    NetworkRuleProblem.InvalidStatus -> "Enter a status code from 100 to 599."
    NetworkRuleProblem.InvalidHeader -> "Write each header as Name: value."
    NetworkRuleProblem.MissingFind -> "Enter the text to find."
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(title, color = RobomoriTheme.colors.textSecondary)
        content()
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = RobomoriTheme.colors.textSecondary, style = RobomoriTheme.typography.small)

/** A field that starts at [value] and passes on what is typed. */
@Composable
private fun DraftField(value: String, onChange: (String) -> Unit, tag: String, placeholder: String?, modifier: Modifier) {
    val state = rememberSynced(value, onChange)
    Field(state, tag, modifier, placeholder)
}

/** Several lines of text, such as headers or a body, in code type. */
@Composable
private fun DraftTextArea(value: String, onChange: (String) -> Unit, tag: String, placeholder: String?, lines: Int) {
    val state = rememberSynced(value, onChange)
    TextArea(state, Modifier.fillMaxWidth().heightIn(min = (lines * 18 + 12).dp, max = 320.dp).testTag(tag),
        textStyle = RobomoriTheme.typography.codeSmall,
        placeholder = placeholder?.let { { Text(it, color = RobomoriTheme.colors.textSecondary, style = RobomoriTheme.typography.codeSmall) } })
}

@Composable
private fun rememberSynced(value: String, onChange: (String) -> Unit): TextFieldState {
    val state = rememberTextFieldState(value)
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { latest(it) } }
    return state
}
