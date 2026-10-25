package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.core.designsystem.AppSliderTokens
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.domain.chat.a2ui.A2uiTextRole
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * OpenUI 官方组件库全量补全 · 表单批次（M4b，2026-10-25，spec《openui-full-library-design》§3.3）。
 *
 * 12 个表单组件的渲染分支，由 [OpenUiNodeRenderer] 主 dispatch 按 [openUiFormComponentNames]
 * 接线转入。写入约定：官方 name 键控 form state → 本仓 state 变量 `"$" + name`（含 $ 前缀），
 * 交互即 @Set($<name>, 值) 直写（OpenUiCheckBox/OpenUiSwitch 既有口径），他处组件经 $name 绑定读。
 *
 * schema 真相 = shared `OpenUiLibrary`（以它为准）：
 * - Chips.type 默认 multiple、OptionCards.type 默认 single（官方默认值刻意相反，schema 注释口径）；
 * - OptionCard 元素只有 value/title/subtitle（无 icon/image 字段）；
 * - EditableTable columns 无 options（schema 不支持 OBJECT_ARRAY 嵌套 OBJECT_ARRAY，select 列
 *   按 text 单元格渲染）；data[].values 是按列序对齐的 PRIMITIVE_ARRAY（非 key 键控对象）；
 * - Input/TextArea 的 rules 收下不用（spec §7：官方校验对象只收不执行）；
 * - SwitchGroup.variant（clear/card/sunk）收下不用，统一纵向列渲染。
 *
 * 半信任输入防御：prop 缺失/形态不符丢组件或空 Box 占位，绝不抛异常；数组护栏
 * [MAX_FORM_ITEMS] / [MAX_EDITABLE_TABLE_ROWS] / [MAX_EDITABLE_TABLE_COLS]。
 *
 * DatePickerDialog 确认/取消按钮用系统框架串 android.R.string.ok/cancel（框架自带本地化，
 * 本批不新增 values/strings.xml 条目；[I18N] 合规由系统翻译兜底）。
 */

/** 主 dispatch 接线用的表单组件名集合（本文件负责的 12 个）。 */
internal val openUiFormComponentNames: Set<String> = setOf(
    "Form",
    "FormControl",
    "Input",
    "TextArea",
    "Select",
    "DatePicker",
    "CheckBoxGroup",
    "RadioGroup",
    "SwitchGroup",
    "Chips",
    "OptionCards",
    "EditableTable",
)

/** 表单组件数组/子组件列表护栏（LLM 生成面无界，渲染端 take 兜底，主文件 MAX_LIST_CHILDREN 同口径）。 */
private const val MAX_FORM_ITEMS = 50
private const val MAX_EDITABLE_TABLE_ROWS = 20
private const val MAX_EDITABLE_TABLE_COLS = 8

/** EditableTable 定宽横滑模式下的单元格宽（主文件 TABLE_CELL_MIN_WIDTH 88dp 先例）。 */
private val EDIT_CELL_MIN_WIDTH = 96.dp

/** Chips/OptionCards 多选 state 值的分隔符（逗号串编码，spec §3.3）。 */
private const val MULTI_VALUE_SEPARATOR = ","

/** 表单批分发：未知名 → 空 Box 占位（半信任输入防御，不炸整卡）。 */
@Composable
internal fun OpenUiFormNode(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node.type) {
        "Form" -> OpenUiFormGroup(node, state, photoResolver, onAction, modifier)
        "FormControl" -> OpenUiFormControl(node, state, photoResolver, onAction, modifier)
        "Input" -> OpenUiInputField(node, state, onAction, modifier, multiline = false)
        "TextArea" -> OpenUiInputField(node, state, onAction, modifier, multiline = true)
        "Select" -> OpenUiSelectField(node, state, onAction, modifier)
        "DatePicker" -> OpenUiDatePickerField(node, state, onAction, modifier)
        "CheckBoxGroup" -> OpenUiToggleGroup(node, state, onAction, modifier, isSwitch = false)
        "SwitchGroup" -> OpenUiToggleGroup(node, state, onAction, modifier, isSwitch = true)
        "RadioGroup" -> OpenUiRadioGroup(node, state, onAction, modifier)
        "Chips" -> OpenUiChips(node, state, onAction, modifier)
        "OptionCards" -> OpenUiOptionCards(node, state, onAction, modifier)
        "EditableTable" -> OpenUiEditableTable(node, onAction, modifier)
        else -> Box(modifier = modifier)
    }
}

// ── 公共工具 ──────────────────────────────────────────────────────────────

/** 表单写入约定：name → state 变量名（"$" 前缀）。 */
private fun formStateKey(name: String): String = "\$$name"

/** state 值 → 文本形态（Str 直取；Num 整数不带小数点；Bool 回退文本；其他/缺省 → null）。 */
private fun openUiFormText(value: OpenUiValue?): String? = when (value) {
    is OpenUiValue.Str -> value.v
    is OpenUiValue.Num -> if (value.v % 1.0 == 0.0) value.v.toLong().toString() else value.v.toString()
    is OpenUiValue.Bool -> value.v.toString()
    else -> null
}

/** 构造 @Set 动作（target = state 变量名）。 */
private fun formSetAction(stateKey: String, value: OpenUiValue): OpenUiAction =
    OpenUiAction(name = "Set", args = listOf(value), target = stateKey)

/** 逗号串多选值解析（空段剔除）。 */
private fun parseMultiValues(raw: String?): Set<String> =
    raw.orEmpty()
        .split(MULTI_VALUE_SEPARATOR)
        .map { token -> token.trim() }
        .filter { token -> token.isNotEmpty() }
        .toSet()

/** 逗号串多选值 toggle 成员后重拼。 */
private fun toggleMultiValue(raw: String?, member: String): String {
    val members = parseMultiValues(raw)
    val next = if (member in members) members - member else members + member
    return next.joinToString(MULTI_VALUE_SEPARATOR)
}

// ── Form / FormControl ────────────────────────────────────────────────────

/** Form：纯视觉分组 Column（children 递归，Spacing.md 间距）；name 收下不用（无语义作用域）。 */
@Composable
private fun OpenUiFormGroup(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty().take(MAX_FORM_ITEMS)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        for (child in children) {
            if (child is OpenUiValue.ComponentVal) {
                OpenUiNodeRenderer(child, state, photoResolver, onAction)
            } else {
                Box(modifier = Modifier) // null 字面量/被剔除元素：空占位
            }
        }
    }
}

/** FormControl：label（BODY SemiBold）+ input 组件递归 + hint?（CAPTION 弱色）。 */
@Composable
private fun OpenUiFormControl(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = (node.props["label"] as? OpenUiValue.Str)?.v ?: return
    val inputs = (node.props["input"] as? OpenUiValue.Arr)?.v.orEmpty().take(MAX_FORM_ITEMS)
    val hint = (node.props["hint"] as? OpenUiValue.Str)?.v
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY).copy(fontWeight = FontWeight.SemiBold),
        )
        for (child in inputs) {
            if (child is OpenUiValue.ComponentVal) {
                OpenUiNodeRenderer(child, state, photoResolver, onAction)
            } else {
                Box(modifier = Modifier)
            }
        }
        hint?.let { hintText ->
            Text(
                text = hintText,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Input / TextArea ─────────────────────────────────────────────────────

/**
 * Input/TextArea：OutlinedTextField fillMaxWidth。本地 remember(node) 文本态为编辑期真相；
 * 外部 state 值变化且与本地不同时回写本地（相等不赋值，防光标跳动）。提交制（review 🟡9，
 * 每键击 @Set 会整程序重求值+整卡重组）：失焦/IME Done 才 @Set($name, 文本)，与 EditableTable
 * 同口径。Input.type → KeyboardType（password 加掩码）；TextArea 多行 minLines=rows（默认 3）。
 * rules 收下不用（spec §7）。
 */
@Composable
private fun OpenUiInputField(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
    multiline: Boolean,
) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    val stateKey = formStateKey(name)
    val placeholder = (node.props["placeholder"] as? OpenUiValue.Str)?.v
    val external = openUiFormText(state[stateKey])
    var text by remember(node) { mutableStateOf(external.orEmpty()) }
    // 已提交值快照：失焦时与本地文本比对，有变更才发 @Set（避免无编辑失焦也触发重求值）
    var committed by remember(node) { mutableStateOf(external.orEmpty()) }
    val commit: () -> Unit = {
        if (text != committed) {
            committed = text
            onAction(formSetAction(stateKey, OpenUiValue.Str(text)))
        }
    }
    LaunchedEffect(external) {
        if (external != null && external != text) {
            text = external
            committed = external
        }
    }
    val commitModifier = modifier
        .fillMaxWidth()
        .onFocusChanged { focusState -> if (!focusState.isFocused) commit() }
    if (multiline) {
        val rows = ((node.props["rows"] as? OpenUiValue.Num)?.v?.toInt() ?: 3).coerceIn(1, 10)
        OutlinedTextField(
            value = text,
            onValueChange = { newText -> text = newText },
            modifier = commitModifier,
            placeholder = placeholder?.let { hint -> { Text(text = hint) } },
            minLines = rows,
        )
    } else {
        val type = (node.props["type"] as? OpenUiValue.Str)?.v ?: "text"
        val keyboardType = when (type) {
            "email" -> KeyboardType.Email
            "password" -> KeyboardType.Password
            "number" -> KeyboardType.Number
            "url" -> KeyboardType.Uri
            else -> KeyboardType.Text
        }
        OutlinedTextField(
            value = text,
            onValueChange = { newText -> text = newText },
            modifier = commitModifier,
            placeholder = placeholder?.let { hint -> { Text(text = hint) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            visualTransformation = if (type == "password") {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        )
    }
}

// ── Select ────────────────────────────────────────────────────────────────

private data class OpenUiSelectOption(val value: String, val label: String)

/** Select：OutlinedButton（文案 = 当前值匹配项 label ?: placeholder ?: name）+ DropdownMenu；选中 → @Set($name, value)。 */
@Composable
private fun OpenUiSelectField(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    val options = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val value = (obj["value"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        OpenUiSelectOption(value = value, label = label)
    }.take(MAX_FORM_ITEMS)
    if (options.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val stateKey = formStateKey(name)
    val current = (state[stateKey] as? OpenUiValue.Str)?.v
    val placeholder = (node.props["placeholder"] as? OpenUiValue.Str)?.v
    val buttonText = options.firstOrNull { option -> option.value == current }?.label
        ?: placeholder
        ?: name
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = buttonText,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = option.label) },
                    onClick = {
                        onAction(formSetAction(stateKey, OpenUiValue.Str(option.value)))
                        expanded = false
                    },
                )
            }
        }
    }
}

// ── DatePicker ────────────────────────────────────────────────────────────

/** millis（M3 DatePicker 返回 UTC 零点）→ ISO 日期串（minSdk 24 不用 java.time；UTC 格式化防时区回卷一天）。 */
private fun formatIsoDate(millis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(millis))
}

/**
 * DatePicker：按钮（当前值 ?: placeholder ?: "—"）+ M3 DatePickerDialog（single）/
 * DateRangePickerDialog（range）；确认 → @Set($name, "yyyy-MM-dd" / "yyyy-MM-dd..yyyy-MM-dd")。
 * range 仅选起点时终点回落起点。对话框打开态是渲染期 remember 本地态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenUiDatePickerField(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    val mode = (node.props["mode"] as? OpenUiValue.Str)?.v ?: return
    val stateKey = formStateKey(name)
    val current = (state[stateKey] as? OpenUiValue.Str)?.v
    val placeholder = (node.props["placeholder"] as? OpenUiValue.Str)?.v
    var showDialog by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = current ?: placeholder ?: "—",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = openUiIcon("calendarToday") ?: Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
            )
        }
    }
    if (!showDialog) return
    // 确认/取消按钮用系统框架串（框架自带本地化，不新增 values 条目，见文件头 KDoc）
    if (mode == "range") {
        val rangeState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = rangeState.selectedStartDateMillis
                        if (start != null) {
                            val end = rangeState.selectedEndDateMillis ?: start
                            onAction(
                                formSetAction(
                                    stateKey,
                                    OpenUiValue.Str(formatIsoDate(start) + ".." + formatIsoDate(end)),
                                )
                            )
                        }
                        showDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        ) {
            DateRangePicker(state = rangeState)
        }
    } else {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onAction(formSetAction(stateKey, OpenUiValue.Str(formatIsoDate(millis))))
                        }
                        showDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

// ── CheckBoxGroup / SwitchGroup ───────────────────────────────────────────

private data class OpenUiToggleItem(
    val label: String,
    val description: String?,
    val name: String,
    val defaultChecked: Boolean,
)

/**
 * CheckBoxGroup / SwitchGroup：纵向开关列；每项 checked 读 state["$"+item.name]（缺省 fallback
 * defaultChecked ?: false），toggle → @Set($item.name, !checked)。整行 toggleable 扩热区
 * （主文件 OpenUiCheckBox/OpenUiSwitch 同口径），开关本体挂 null 防双击。SwitchGroup.variant
 * 收下不用（统一纵向列）。CheckBoxGroup 的 name prop 收下不用（每项独立键控）。
 */
@Composable
private fun OpenUiToggleGroup(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
    isSwitch: Boolean,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val itemName = (obj["name"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        OpenUiToggleItem(
            label = label,
            description = (obj["description"] as? OpenUiValue.Str)?.v,
            name = itemName,
            defaultChecked = (obj["defaultChecked"] as? OpenUiValue.Bool)?.v ?: false,
        )
    }.take(MAX_FORM_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEach { item ->
            val itemKey = formStateKey(item.name)
            val checked = (state[itemKey] as? OpenUiValue.Bool)?.v ?: item.defaultChecked
            val toggle: (Boolean) -> Unit = { newValue ->
                onAction(formSetAction(itemKey, OpenUiValue.Bool(newValue)))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = if (isSwitch) Role.Switch else Role.Checkbox,
                        onValueChange = toggle,
                    )
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSwitch) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
                        item.description?.let { desc ->
                            Text(
                                text = desc,
                                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 配色镜像主文件 OpenUiSwitch 分支（SwitchDefaults + AppSliderTokens.thumbColor）
                    Switch(
                        checked = checked,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = AppSliderTokens.thumbColor,
                        ),
                    )
                } else {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column {
                        Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
                        item.description?.let { desc ->
                            Text(
                                text = desc,
                                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── RadioGroup ────────────────────────────────────────────────────────────

private data class OpenUiRadioOption(
    val label: String,
    val description: String?,
    val value: String,
)

/** RadioGroup：selected = state[$name] Str（缺省 fallback defaultValue prop）；纵向行 selectable，选中 → @Set($name, value)。 */
@Composable
private fun OpenUiRadioGroup(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        // value schema 必填；防御兜底用 label（半信任输入不丢整项）
        val value = (obj["value"] as? OpenUiValue.Str)?.v ?: label
        OpenUiRadioOption(
            label = label,
            description = (obj["description"] as? OpenUiValue.Str)?.v,
            value = value,
        )
    }.take(MAX_FORM_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val stateKey = formStateKey(name)
    val selected = (state[stateKey] as? OpenUiValue.Str)?.v
        ?: (node.props["defaultValue"] as? OpenUiValue.Str)?.v
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEach { item ->
            val isSelected = item.value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        onAction(formSetAction(stateKey, OpenUiValue.Str(item.value)))
                    }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Column {
                    Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
                    item.description?.let { desc ->
                        Text(
                            text = desc,
                            style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── Chips ─────────────────────────────────────────────────────────────────

private data class OpenUiChipOption(val value: String, val label: String, val icon: String?)

/**
 * Chips：FlowRow + FilterChip。type=single：点选 → @Set($name, value)；multiple（schema 默认）：
 * state 值是逗号串，toggle 成员增删后重拼写回。icon? 经 openUiIcon 作 leadingIcon（16dp）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenUiChips(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val value = (obj["value"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        OpenUiChipOption(value = value, label = label, icon = (obj["icon"] as? OpenUiValue.Str)?.v)
    }.take(MAX_FORM_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val stateKey = formStateKey(name)
    val multiple = (node.props["type"] as? OpenUiValue.Str)?.v != "single"
    val rawSelected = (state[stateKey] as? OpenUiValue.Str)?.v
    val selectedSet = parseMultiValues(rawSelected)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items.forEach { option ->
            val isSelected = if (multiple) option.value in selectedSet else option.value == rawSelected
            FilterChip(
                selected = isSelected,
                onClick = {
                    val next = if (multiple) {
                        toggleMultiValue(rawSelected, option.value)
                    } else {
                        option.value
                    }
                    onAction(formSetAction(stateKey, OpenUiValue.Str(next)))
                },
                label = { Text(text = option.label) },
                leadingIcon = option.icon?.let { iconName ->
                    openUiIcon(iconName)?.let { image ->
                        {
                            Icon(
                                imageVector = image,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
        }
    }
}

// ── OptionCards ───────────────────────────────────────────────────────────

private data class OpenUiOptionCardItem(val value: String, val title: String, val subtitle: String?)

/**
 * OptionCards：FlowRow 可选卡（title BODY SemiBold + subtitle? CAPTION 弱色；schema 元素无
 * icon/image 字段，纯文本卡）；选中态 primary 描边 1.5dp。写入同 Chips：single（schema 默认）
 * 写 $name=值，multiple 写逗号串。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenUiOptionCards(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val value = (obj["value"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val title = (obj["title"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        OpenUiOptionCardItem(value = value, title = title, subtitle = (obj["subtitle"] as? OpenUiValue.Str)?.v)
    }.take(MAX_FORM_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val stateKey = formStateKey(name)
    val multiple = (node.props["type"] as? OpenUiValue.Str)?.v == "multiple"
    val rawSelected = (state[stateKey] as? OpenUiValue.Str)?.v
    val selectedSet = parseMultiValues(rawSelected)
    val selectedBorderColor = MaterialTheme.colorScheme.primary
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items.forEach { item ->
            val isSelected = if (multiple) item.value in selectedSet else item.value == rawSelected
            OutlinedCard(
                border = if (isSelected) {
                    BorderStroke(1.5.dp, selectedBorderColor)
                } else {
                    CardDefaults.outlinedCardBorder()
                },
            ) {
                Column(
                    modifier = Modifier
                        .clickable {
                            val next = if (multiple) {
                                toggleMultiValue(rawSelected, item.value)
                            } else {
                                item.value
                            }
                            onAction(formSetAction(stateKey, OpenUiValue.Str(next)))
                        }
                        .padding(Spacing.md),
                ) {
                    Text(
                        text = item.title,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY)
                            .copy(fontWeight = FontWeight.SemiBold),
                    )
                    item.subtitle?.let { sub ->
                        Text(
                            text = sub,
                            style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── EditableTable ─────────────────────────────────────────────────────────

private data class OpenUiEditColumn(val type: String, val key: String, val header: String)
private data class OpenUiEditRow(val id: String, val cells: List<String>)

/**
 * 重拼全表 JSON（行对象 {id, values:{列key: 单元格值}}；number 列可解析时写数值）。
 * values 键控对象形态对齐官方 EditableTable 数据语义（schema 输入侧是受 prop 类型所限的
 * 按列序数组，输出侧以 key 键控保持自描述）。
 */
private fun buildEditableTableJson(
    columns: List<OpenUiEditColumn>,
    rows: List<OpenUiEditRow>,
    edited: Map<String, String>,
): String {
    val table = JSONArray()
    rows.forEach { row ->
        val rowObj = JSONObject()
        rowObj.put("id", row.id)
        val valuesObj = JSONObject()
        columns.forEachIndexed { colIndex, column ->
            val cellKey = row.id + "|" + column.key
            val raw = edited[cellKey] ?: row.cells.getOrElse(colIndex) { "" }
            val number = if (column.type == "number") raw.toDoubleOrNull() else null
            if (number != null) {
                valuesObj.put(column.key, number)
            } else {
                valuesObj.put(column.key, raw)
            }
        }
        rowObj.put("values", valuesObj)
        table.put(rowObj)
    }
    return table.toString()
}

/**
 * EditableTable：表头行 + 数据行（等宽列，列宽合计超容器宽时横滑，主文件 OpenUiTable 同径）。
 * 单元格 BasicTextField 内联编辑：本地 cell 态 map（key = rowId|colKey）为编辑期真相，IME
 * Done/失焦提交 → 重拼全表 JSON 数组串 → @Set($name, json)。number 列 → KeyboardType.Number；
 * select 列 schema 无 options（不支持嵌套 OBJECT_ARRAY），按 text 单元格渲染。
 * 官方 Save 提交制简化为即写（spec §3.3 口径）。
 */
@Composable
private fun OpenUiEditableTable(
    node: OpenUiValue.ComponentVal,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    val columns = (node.props["columns"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val type = (obj["type"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val key = (obj["key"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val header = (obj["header"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        OpenUiEditColumn(type = type, key = key, header = header)
    }.take(MAX_EDITABLE_TABLE_COLS)
    val rows = (node.props["data"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val id = (obj["id"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        // values 是按列序对齐的 PRIMITIVE_ARRAY（schema 口径；非 key 键控对象）
        val cells = (obj["values"] as? OpenUiValue.Arr)?.v.orEmpty()
            .map { cell -> openUiFormText(cell) ?: "" }
        OpenUiEditRow(id = id, cells = cells)
    }.take(MAX_EDITABLE_TABLE_ROWS)
    if (columns.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val stateKey = formStateKey(name)
    // 本地 cell 编辑态（编辑期真相）；node 相等性在 @Set 后不变（props 与 state 分离），编辑态跨重组保持
    val edited = remember(node) { mutableStateMapOf<String, String>() }
    val focusManager = LocalFocusManager.current
    val cellTextStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)

    fun commitIfDirty() {
        if (edited.isEmpty()) return
        onAction(
            formSetAction(
                stateKey,
                OpenUiValue.Str(buildEditableTableJson(columns, rows, edited)),
            )
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fitsWidth = EDIT_CELL_MIN_WIDTH * columns.size <= maxWidth
        val cellModifier =
            if (fitsWidth) Modifier else Modifier.width(EDIT_CELL_MIN_WIDTH)
        Column(
            modifier = if (fitsWidth) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.horizontalScroll(rememberScrollState())
            },
        ) {
            Row {
                columns.forEach { column ->
                    Text(
                        text = column.header,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (fitsWidth) Modifier.weight(1f) else cellModifier,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))
            rows.forEach { row ->
                Row {
                    columns.forEachIndexed { colIndex, column ->
                        val cellKey = row.id + "|" + column.key
                        val cellText = edited[cellKey] ?: row.cells.getOrElse(colIndex) { "" }
                        BasicTextField(
                            value = cellText,
                            onValueChange = { newText -> edited[cellKey] = newText },
                            textStyle = cellTextStyle,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (column.type == "number") {
                                    KeyboardType.Number
                                } else {
                                    KeyboardType.Text
                                },
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    commitIfDirty()
                                    focusManager.clearFocus()
                                }
                            ),
                            modifier = (if (fitsWidth) Modifier.weight(1f) else cellModifier)
                                .padding(vertical = Spacing.xs)
                                .onFocusChanged { focusState ->
                                    if (!focusState.hasFocus) commitIfDirty()
                                },
                        )
                    }
                }
            }
        }
    }
}
