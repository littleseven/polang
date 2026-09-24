package com.mamba.picme.features.person

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FilterListOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.features.common.avatar.AvatarCaptureController
import com.mamba.picme.features.common.avatar.AvatarCaptureOrigin
import com.mamba.picme.features.common.avatar.AvatarCaptureTarget
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.main.MAIN_PAGE_PEOPLE
import com.mamba.picme.features.main.MainFloatingBottomBar
import com.mamba.picme.features.person.components.PersonInfoScreen
import com.mamba.picme.features.person.components.PersonListItem
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.launch

/**
 * 「人物」页：双列网格展示全部人脸聚类。
 * 支持行内改名、展开式 Bottom Sheet 编辑关系/「我」标记、底部 Sheet 选择封面。
 * 2026-09-06 升根页：去顶栏返回箭头、挂悬浮底 bar（人物项高亮），系统返回经
 * MainPagerHost BackHandler 回相册页。
 */
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onNavigateToGallery: (Long) -> Unit,
    /** 人物编辑页相机角标：登记 pending 头像拍摄后切到相机页（Pager 页 0） */
    onNavigateToCamera: () -> Unit = {},
    /** 底 bar 页切换出口（相册/整理/聊天/回忆；人物为本页，选中项空操作） */
    onSwitchMainPage: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.reconcileAndLoad() }

    var scoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val persons by viewModel.persons.collectAsState()
    val covers by viewModel.covers.collectAsState()
    val relations by viewModel.relations.collectAsState()
    val photoCounts by viewModel.photoCounts.collectAsState()
    val editingPersonId by viewModel.editingPersonId.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showAll by viewModel.showAll.collectAsState()
    val totalPersonCount by viewModel.totalPersonCount.collectAsState()

    var infoTarget by remember { mutableStateOf<PersonEntity?>(null) }
    var infoPhotos by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }

    // 顶栏副标题统计：已显示人物数 · 这些人物的照片总数（对齐 Ardot People 页设计稿）
    val totalShownPhotos = persons.sumOf { photoCounts[it.personId] ?: it.faceCount }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(showAll, persons.size, totalPersonCount) {
        val hidden = totalPersonCount - persons.size
        if (hidden > 0 && !showAll) {
            snackbarHostState.showSnackbar(
                context.getString(R.string.people_filter_hidden_hint, hidden)
            )
        }
    }

    LaunchedEffect(infoTarget) {
        val target = infoTarget
        infoPhotos = if (target != null) {
            viewModel.loadPhotosByPerson(target.personId)
        } else {
            emptyList()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.people_title),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.people_stats_format,
                                pluralStringResource(
                                    R.plurals.people_people_count,
                                    persons.size,
                                    persons.size
                                ),
                                pluralStringResource(
                                    R.plurals.people_photos_count_full,
                                    totalShownPhotos,
                                    totalShownPhotos
                                )
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 显示全部 / 隐藏单张未命名单人分组
                    AppTopBarAction(
                        icon = if (showAll) Icons.Outlined.FilterListOff else Icons.Outlined.FilterList,
                        contentDescription = stringResource(
                            if (showAll) R.string.people_filter_hide_singletons else R.string.people_filter_show_all
                        ),
                        onClick = { viewModel.toggleShowAll() }
                    )

                    // 手动触发：跑一轮（300 张）eDifFIQA 打分 + 刷新封面，完成后 reload 看效果
                    if (scoring) {
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        AppTopBarAction(
                            icon = Icons.Outlined.AutoAwesome,
                            contentDescription = stringResource(R.string.people_rescore),
                            onClick = {
                                scope.launch {
                                    scoring = true
                                    try {
                                        val app = context.applicationContext as? PoLangApplication
                                        app?.container?.aestheticScoreWorker?.runOnce(300)
                                        viewModel.reconcileAndLoad()
                                    } finally {
                                        scoring = false
                                    }
                                }
                            }
                        )
                    }
                    // 重新聚类：仅重提已有人脸 embedding（对齐路径）+ 全量重聚类（保名），后台 FGS 运行
                    AppTopBarAction(
                        icon = Icons.Outlined.Autorenew,
                        contentDescription = stringResource(R.string.people_recluster),
                        onClick = {
                            context.startForegroundService(TagGenerationService.intentReembedFaces(context))
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.people_recluster_started))
                            }
                        }
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                // 底部 96dp：悬浮底 bar 遮挡预留（对齐 Memory 页做法）
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = persons,
                    key = { person -> person.personId }
                ) { person ->
                    PersonListItem(
                        person = person,
                        cover = covers[person.personId],
                        relation = relations[person.personId],
                        photoCount = photoCounts[person.personId] ?: person.faceCount,
                        isEditingName = editingPersonId == person.personId,
                        onCoverClick = { onNavigateToGallery(person.personId) },
                        onNameClick = { viewModel.startEditing(person.personId) },
                        onNameSave = { name -> viewModel.updateName(person.personId, name) },
                        onNameCancel = { viewModel.stopEditing() },
                        onInfoClick = { infoTarget = person }
                    )
                }
            }
        }
    }

    // 悬浮底 bar：人物项 selected 高亮（2026-09-06 升根页，对齐相册/整理/回忆）
    MainFloatingBottomBar(
        selectedMainPage = MAIN_PAGE_PEOPLE,
        onSwitchPage = onSwitchMainPage,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding(),
    )

    infoTarget?.let { person ->
        PersonInfoScreen(
            person = person,
            relation = relations[person.personId],
            cover = covers[person.personId],
            photos = infoPhotos,
            onSave = { relation, customLabel, isSelf ->
                viewModel.updatePersonInfo(person.personId, relation, customLabel, isSelf)
            },
            onNavigateBack = { infoTarget = null },
            onCaptureAvatar = {
                AvatarCaptureController.begin(
                    AvatarCaptureTarget.Person(person.personId),
                    AvatarCaptureOrigin.PEOPLE_PAGE
                )
                onNavigateToCamera()
            },
            onUpdateCover = { photo ->
                viewModel.updateCover(person.personId, photo.id)
            },
            onUpdateName = { name ->
                viewModel.updateName(person.personId, name)
            },
            onRescore = {
                val app = context.applicationContext as? PoLangApplication
                app?.container?.aestheticScoreWorker?.runOnceForPerson(person.personId)
                viewModel.reconcileAndLoad()
            }
        )
    }
    }
}
