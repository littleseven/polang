package com.mamba.picme.features.debug

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.debug.pexels.PexelsApi
import com.mamba.picme.features.debug.pexels.PexelsImageSaver
import com.mamba.picme.features.debug.pexels.PexelsKeyStore
import com.mamba.picme.features.debug.pexels.PexelsSection
import com.mamba.picme.features.debug.pexels.PexelsViewModel
import com.mamba.picme.features.debug.openui.OpenUiComponentGallery
import com.mamba.picme.features.gallery.MediaViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onNavigateBack: () -> Unit,
    mediaViewModel: MediaViewModel
) {
    val context = LocalContext.current
    val view = LocalView.current
    val app = context.applicationContext as PoLangApplication
    val scope = app.applicationScope

    val isGenerating by SampleDataGenerator.isGenerating.collectAsState()
    val isPaused by SampleDataGenerator.isPaused.collectAsState()
    val progress by SampleDataGenerator.progress.collectAsState()
    val logs by SampleDataGenerator.logs.collectAsState()
    val allMedia by mediaViewModel.allMedia.collectAsState()

    val pexelsViewModel = remember {
        PexelsViewModel(
            api = PexelsApi.create(),
            keyStore = PexelsKeyStore(context.applicationContext),
            imageSaver = PexelsImageSaver { photoId, imageUrl ->
                SampleDataGenerator.savePexelsPhoto(
                    context.applicationContext,
                    app.repository,
                    photoId,
                    imageUrl
                )
            },
            scope = app.applicationScope
        )
    }

    DebugContent(
        isGenerating = isGenerating,
        isPaused = isPaused,
        progress = progress,
        logs = logs,
        pexelsViewModel = pexelsViewModel,
        onNavigateBack = onNavigateBack,
        onPauseResume = {
            if (isPaused) {
                SampleDataGenerator.resume()
            } else {
                SampleDataGenerator.pause(context)
            }
        },
        onStop = { SampleDataGenerator.stop(context) },
        onPopulatePerson = {
            scope.launch { SampleDataGenerator.populatePersonTestData(context, app.repository) }
        },
        onPopulateLandscape = {
            scope.launch { SampleDataGenerator.populateLandscapeTestData(context, app.repository) }
        },
        onPopulateSwimwear = {
            scope.launch { SampleDataGenerator.populateSwimwearTestData(context, app.repository) }
        },
        onPopulateSexy = {
            scope.launch { SampleDataGenerator.populateSexyTestData(context, app.repository) }
        },
        onClearData = {
            scope.launch { SampleDataGenerator.clearTestData(context, app.repository, allMedia) }
        },
        onScreenshot = {
            val path = ScreenshotUtil.captureAndSave(view, context)
            if (path != null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.screenshot_saved, path),
                    Toast.LENGTH_LONG
                ).show()
                SampleDataGenerator.addLog("Screenshot saved: $path")
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.screenshot_failed),
                    Toast.LENGTH_SHORT
                ).show()
                SampleDataGenerator.addLog("Screenshot failed")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugContent(
    isGenerating: Boolean,
    isPaused: Boolean,
    progress: String,
    logs: List<String>,
    pexelsViewModel: PexelsViewModel,
    onNavigateBack: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onPopulatePerson: () -> Unit,
    onPopulateLandscape: () -> Unit,
    onPopulateSwimwear: () -> Unit,
    onPopulateSexy: () -> Unit,
    onClearData: () -> Unit,
    onScreenshot: () -> Unit
) {
    var filterText by remember { mutableStateOf("") }
    val filteredLogs = remember(logs, filterText) {
        if (filterText.isBlank()) {
            logs
        } else {
            logs.filter { it.contains(filterText, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.debug_tools),
                onBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.debug_tab_generate)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.debug_tab_pexels)) }
                )
                Tab(
                    // "OpenUI" 是协议标识符（OpenUiBlueprintDebugger 徽标同口径），[I18N] 豁免
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("OpenUI") }
                )
            }
            when (selectedTab) {
                0 -> GenerateTabContent(
                    isGenerating = isGenerating,
                    isPaused = isPaused,
                    progress = progress,
                    filterText = filterText,
                    filteredLogs = filteredLogs,
                    onFilterTextChange = { filterText = it },
                    onPauseResume = onPauseResume,
                    onStop = onStop,
                    onPopulatePerson = onPopulatePerson,
                    onPopulateLandscape = onPopulateLandscape,
                    onPopulateSwimwear = onPopulateSwimwear,
                    onPopulateSexy = onPopulateSexy,
                    onClearData = onClearData,
                    onScreenshot = onScreenshot
                )

                1 -> PexelsSection(viewModel = pexelsViewModel)

                2 -> OpenUiComponentGallery()
            }
        }
    }
}

@Composable
private fun GenerateTabContent(
    isGenerating: Boolean,
    isPaused: Boolean,
    progress: String,
    filterText: String,
    filteredLogs: List<String>,
    onFilterTextChange: (String) -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onPopulatePerson: () -> Unit,
    onPopulateLandscape: () -> Unit,
    onPopulateSwimwear: () -> Unit,
    onPopulateSexy: () -> Unit,
    onClearData: () -> Unit,
    onScreenshot: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isGenerating) {
            GenerationStatusCard(
                progress = progress,
                isPaused = isPaused,
                onPauseResume = onPauseResume,
                onStop = onStop
            )
        }

        Text(
            stringResource(R.string.data_generation),
            style = MaterialTheme.typography.titleSmall
        )

        DataGenerationButtons(
            isGenerating = isGenerating,
            onPopulatePerson = onPopulatePerson,
            onPopulateLandscape = onPopulateLandscape,
            onPopulateSwimwear = onPopulateSwimwear,
            onPopulateSexy = onPopulateSexy,
            onClearData = onClearData
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Text(
            stringResource(R.string.screenshot),
            style = MaterialTheme.typography.titleSmall
        )

        Button(
            onClick = onScreenshot,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.screenshot))
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        LogWindow(
            filterText = filterText,
            onFilterTextChange = onFilterTextChange,
            filteredLogs = filteredLogs
        )
    }
}

@Composable
private fun GenerationStatusCard(
    progress: String,
    isPaused: Boolean,
    onPauseResume: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(progress, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPauseResume,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isPaused) stringResource(R.string.resume) else stringResource(R.string.pause),
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.stop), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DataGenerationButtons(
    isGenerating: Boolean,
    onPopulatePerson: () -> Unit,
    onPopulateLandscape: () -> Unit,
    onPopulateSwimwear: () -> Unit,
    onPopulateSexy: () -> Unit,
    onClearData: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DebugButton(
                Icons.Default.Person,
                stringResource(R.string.person),
                onPopulatePerson,
                !isGenerating,
                Modifier.weight(1f)
            )
            DebugButton(
                Icons.Default.Landscape,
                stringResource(R.string.landscape),
                onPopulateLandscape,
                !isGenerating,
                Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DebugButton(
                Icons.Default.Pool,
                stringResource(R.string.swimwear),
                onPopulateSwimwear,
                !isGenerating,
                Modifier.weight(1f)
            )
            DebugButton(
                Icons.Default.Female,
                stringResource(R.string.sexy),
                onPopulateSexy,
                !isGenerating,
                Modifier.weight(1f)
            )
        }
        Button(
            onClick = onClearData,
            enabled = !isGenerating,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(Icons.Default.DeleteSweep, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.clear_test_data))
        }
    }
}

@Composable
private fun LogWindow(
    filterText: String,
    onFilterTextChange: (String) -> Unit,
    filteredLogs: List<String>
) {
    Column(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        OutlinedTextField(
            value = filterText,
            onValueChange = onFilterTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Grep logs...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = Color.Black.copy(alpha = 0.05f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.1f))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredLogs) { log ->
                    Text(
                        text = log,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            log.contains("Saved") -> Color(0xFF2E7D32)
                            log.contains("Error") || log.contains("failed") || log.contains("HTTP") -> Color.Red
                            else -> Color.Unspecified
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp)
    }
}
