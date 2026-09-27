package com.mamba.picme.features.person.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.mamba.picme.R
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.features.common.PersonRelationPicker
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.features.person.PersonCover

/**
 * 人物信息编辑全屏页（Ardot People 页设计稿落地）。
 *
 * 结构：圆形头像（右下相机角标进相机拍封面，点头像本体打开封面选择）→ 姓名输入框 → 「这是我」胶囊 →
 * Relationship 分组卡片（家庭/社会 chips + 自定义称呼）→ Photos 预览条。
 * 右上角提供「重新打分」「不设置」与「保存」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonInfoScreen(
    person: PersonEntity,
    relation: RelationDisplayItem?,
    cover: PersonCover?,
    photos: List<MediaEntity>,
    onSave: (name: String, relation: RelationPredicate?, customLabel: String, isSelf: Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onUpdateCover: (MediaEntity) -> Unit,
    /** 仅给该人物聚类（重新）打美学/人脸画质分 + 刷新封面；null=不显示该入口。 */
    onRescore: (suspend () -> Unit)? = null,
    /** 相机角标：进相机拍摄该人物封面（2026-08-26 起角标不再打开封面选择 Sheet） */
    onCaptureAvatar: (() -> Unit)? = null
) {
    val editState = remember(person.personId, person.name, person.isSelf, relation) {
        PersonEditState(
            relation = relation?.predicate,
            customLabel = relation?.customLabel.orEmpty(),
            isSelf = person.isSelf,
            name = person.name.orEmpty()
        )
    }
    var showCoverPicker by remember { mutableStateOf(false) }
    val rescoreScope = rememberCoroutineScope()
    var rescoring by remember { mutableStateOf(false) }

    val doSave = {
        val effectiveRelation = if (editState.customLabel.isNotBlank()) {
            RelationPredicate.OTHER
        } else {
            editState.relation
        }
        // 改名并入保存回调单次提交：applyPersonEdit 收口写入，
        // 避免「先独立改名、再拿旧名快照写回」的竞态回退
        onSave(editState.name.trim(), effectiveRelation, editState.customLabel, editState.isSelf)
        onNavigateBack()
    }

    // 系统返回键由顶栏 AppTopBarNavBack 统一注册的 BackHandler 处理（与返回箭头同回调）。

    Scaffold(
        topBar = {
            PersonInfoTopBar(
                onNavigateBack = onNavigateBack,
                onRescore = onRescore,
                rescoring = rescoring,
                onRescoreClick = {
                    rescoreScope.launch {
                        rescoring = true
                        try {
                            onRescore?.invoke()
                        } finally {
                            rescoring = false
                        }
                    }
                },
                onResetRelation = { editState.clearRelation() },
                onSave = doSave
            )
        }
    ) { innerPadding ->
        PersonInfoBody(
            innerPadding = innerPadding,
            person = person,
            cover = cover,
            photos = photos,
            editState = editState,
            onShowCoverPicker = { showCoverPicker = true },
            onCaptureAvatar = onCaptureAvatar
        )
    }

    if (showCoverPicker && photos.isNotEmpty()) {
        PersonCoverPickerSheet(
            photos = photos,
            onSelect = { photo ->
                onUpdateCover(photo)
                showCoverPicker = false
            },
            onDismiss = { showCoverPicker = false }
        )
    }
}

/** 详情页可变编辑态（聚合持有，避免向子组件透传长参数列表） */
private class PersonEditState(
    relation: RelationPredicate?,
    customLabel: String,
    isSelf: Boolean,
    name: String
) {
    var relation by mutableStateOf(relation)
    var customLabel by mutableStateOf(customLabel)
    var isSelf by mutableStateOf(isSelf)
    var name by mutableStateOf(name)

    /** 「不设置」：清空关系与自定义称呼 */
    fun clearRelation() {
        relation = null
        customLabel = ""
    }
}

/** 顶栏：返回 + 标题 + 重新打分（可选）/清空关系/保存 */
@Composable
private fun PersonInfoTopBar(
    onNavigateBack: () -> Unit,
    onRescore: (suspend () -> Unit)?,
    rescoring: Boolean,
    onRescoreClick: () -> Unit,
    onResetRelation: () -> Unit,
    onSave: () -> Unit
) {
    AppTopBar(
        title = {
            Text(text = stringResource(R.string.person_info_title))
        },
        navigationIcon = {
            AppTopBarNavBack(onClick = onNavigateBack)
        },
        actions = {
            // 仅给该聚类（重新）打美学/人脸画质分 + 刷新封面
            if (onRescore != null) {
                IconButton(enabled = !rescoring, onClick = onRescoreClick) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = stringResource(R.string.people_rescore)
                    )
                }
            }
            // 不设置：清空关系与自定义称呼
            IconButton(onClick = onResetRelation) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = stringResource(R.string.person_relation_none)
                )
            }
            // 保存
            IconButton(onClick = onSave) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.save)
                )
            }
        }
    )
}

/** 滚动主体：头像 → 姓名 → 这是我 → 关系卡片 → Photos */
@Composable
private fun PersonInfoBody(
    innerPadding: PaddingValues,
    person: PersonEntity,
    cover: PersonCover?,
    photos: List<MediaEntity>,
    editState: PersonEditState,
    onShowCoverPicker: () -> Unit,
    onCaptureAvatar: (() -> Unit)? = null
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // 头部：圆形头像（本体点击=封面选择 Sheet）+ 相机角标（点击=进相机拍封面）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            AvatarHeader(
                cover = cover,
                contentDescription = person.name ?: stringResource(
                    R.string.people_default_name,
                    person.personId
                ),
                onClick = onShowCoverPicker,
                onCaptureClick = onCaptureAvatar ?: onShowCoverPicker
            )
        }

        // 姓名：标签 + 填充式输入框（常开编辑态，保存时随关系一并提交）
        NameField(
            nameText = editState.name,
            onNameChange = { editState.name = it },
            modifier = Modifier.fillMaxWidth()
        )

        // 「这是我」胶囊开关（居中）
        SelfToggleChip(
            checked = editState.isSelf,
            onToggle = { editState.isSelf = !editState.isSelf },
            modifier = Modifier.fillMaxWidth()
        )

        // Relationship 分组卡片
        RelationCard(
            currentRelation = editState.relation,
            customLabel = editState.customLabel,
            onRelationChange = { editState.relation = it },
            onCustomLabelChange = { editState.customLabel = it },
            modifier = Modifier.fillMaxWidth()
        )

        // Photos 预览条（点击任意缩略图打开封面选择）
        if (photos.isNotEmpty()) {
            PhotosSection(
                photos = photos,
                onThumbClick = onShowCoverPicker,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }
    }
}

/** 圆形头像 + 右下相机角标（人脸感知垂直对齐裁切）；角标=进相机拍封面，头像本体=封面选择 Sheet */
@Composable
private fun AvatarHeader(
    cover: PersonCover?,
    contentDescription: String,
    onClick: () -> Unit,
    onCaptureClick: () -> Unit
) {
    val alignment = remember(cover?.faceFocusY) {
        faceAwareVerticalAlignment(cover?.faceFocusY)
    }
    Box(modifier = Modifier.size(120.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val uri = cover?.coverUri
            if (uri != null) {
                AsyncImage(
                    model = uri,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    alignment = alignment,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Face,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(32.dp)
                .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(onClick = onCaptureClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PhotoCamera,
                contentDescription = stringResource(R.string.avatar_capture_hint),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 姓名：标签 + 填充式输入框（常开编辑态） */
@Composable
private fun NameField(
    nameText: String,
    onNameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.person_name_label).uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(12.dp)
                )
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
        ) {
            BasicTextField(
                value = nameText,
                onValueChange = onNameChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { /* 等用户点保存 */ }),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.padding(vertical = 8.dp)) {
                        if (nameText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.person_edit_name_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            )
            if (nameText.isNotEmpty()) {
                IconButton(
                    onClick = { onNameChange("") },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** 「这是我」胶囊开关（居中） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelfToggleChip(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        FilterChip(
            selected = checked,
            onClick = onToggle,
            label = { Text(stringResource(R.string.person_is_self)) },
            leadingIcon = if (checked) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                null
            }
        )
    }
}

/** Relationship 分组卡片（家庭/社会 chips + 自定义称呼） */
@Composable
private fun RelationCard(
    currentRelation: RelationPredicate?,
    customLabel: String,
    onRelationChange: (RelationPredicate?) -> Unit,
    onCustomLabelChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.person_relation_label),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        PersonRelationPicker(
            selectedPredicate = currentRelation,
            customLabel = customLabel,
            onPredicateChange = onRelationChange,
            onCustomLabelChange = onCustomLabelChange,
            modifier = Modifier.fillMaxWidth(),
            showNoneChip = false,
            showTitle = false
        )
    }
}

/** Photos 预览条（标题 + 张数 + 最多 4 张缩略图，点击打开封面选择） */
@Composable
private fun PhotosSection(
    photos: List<MediaEntity>,
    onThumbClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.person_photos_section),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = pluralStringResource(
                    R.plurals.people_photos_count_full,
                    photos.size,
                    photos.size
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.take(4).forEach { photo ->
                AsyncImage(
                    model = photo.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onThumbClick)
                )
            }
        }
    }
}
