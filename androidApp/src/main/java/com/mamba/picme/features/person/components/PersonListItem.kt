package com.mamba.picme.features.person.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.repository.RelationDisplayItem
import com.mamba.picme.features.common.isFamilyRelation
import com.mamba.picme.features.common.personRelationLabelRes
import com.mamba.picme.features.person.PersonCover

@Composable
fun PersonListItem(
    person: PersonEntity,
    cover: PersonCover?,
    relation: RelationDisplayItem?,
    photoCount: Int = person.faceCount,
    isEditingName: Boolean,
    onCoverClick: () -> Unit,
    onNameClick: () -> Unit,
    onNameSave: (String) -> Unit,
    onNameCancel: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clickable(onClick = onCoverClick)
            ) {
                CoverThumbnail(
                    cover = cover,
                    contentDescription = person.name ?: stringResource(R.string.people_default_name, person.personId)
                )
                CountBadge(
                    photoCount = photoCount,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // 第一行：名字（编辑态为行内输入框）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    NameBlock(
                        person = person,
                        isEditingName = isEditingName,
                        onNameClick = onNameClick,
                        onNameSave = onNameSave,
                        onNameCancel = onNameCancel,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 第二行（常驻）：关系 chip 居左（无关系不占位），详情按钮固定右下角
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    val hasRelation = person.isSelf || relation != null
                    if (hasRelation) {
                        RelationChip(
                            relation = relation,
                            isSelf = person.isSelf,
                            onClick = onInfoClick
                        )
                    } else {
                        // 无关系：描边引导胶囊填空（点击进详情页设置关系）
                        AddRelationChip(onClick = onInfoClick)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.person_info_title),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 张数角标：封面右下角半透明胶囊（紧凑式 48P，对齐 Ardot 设计稿） */
@Composable
private fun CountBadge(
    photoCount: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = pluralStringResource(R.plurals.people_photos_count, photoCount, photoCount),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun NameBlock(
    person: PersonEntity,
    isEditingName: Boolean,
    onNameClick: () -> Unit,
    onNameSave: (String) -> Unit,
    onNameCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isEditingName) {
        NameEditor(
            initialName = person.name.orEmpty(),
            onSave = onNameSave,
            onCancel = onNameCancel,
            modifier = modifier
        )
    } else {
        val hasName = !person.name.isNullOrBlank()
        Text(
            text = if (hasName) person.name!! else stringResource(R.string.person_edit_name_hint),
            // 未命名时用主题色引导用户点击命名（对齐 Ardot People 页设计稿）
            color = if (hasName) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.clickable(onClick = onNameClick)
        )
    }
}

@Composable
private fun RelationChip(
    relation: RelationDisplayItem?,
    isSelf: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when {
        isSelf -> stringResource(R.string.person_is_self)
        relation?.customLabel != null -> relation.customLabel
        relation != null -> stringResource(personRelationLabelRes(relation.predicate))
        else -> stringResource(R.string.person_relation_none)
    }
    // 配色分组（对齐 Ardot People 页设计稿）：我=primaryContainer，家庭=secondaryContainer，
    // 社会=tertiaryContainer，自定义/其他=surfaceContainerHighest
    val scheme = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when {
        isSelf -> scheme.primaryContainer to scheme.onPrimaryContainer
        relation?.customLabel != null -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        relation?.predicate?.isFamilyRelation() == true -> scheme.secondaryContainer to scheme.onSecondaryContainer
        relation != null -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 无关系时的描边引导胶囊（+ Add relation），点击进详情页设置 */
@Composable
private fun AddRelationChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = stringResource(R.string.person_relation_add),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun CoverThumbnail(
    cover: PersonCover?,
    contentDescription: String
) {
    val alignment = remember(cover?.faceFocusY) {
        faceAwareVerticalAlignment(cover?.faceFocusY)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        val uri = cover?.coverUri
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // 未命名/无封面：人脸图标占位（对齐 Ardot People 页设计稿 unnamed 卡片）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Face,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}

@Composable
private fun NameEditor(
    initialName: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(initialName) { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    innerTextField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
        )
        IconButton(
            onClick = { onSave(text) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.save),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
