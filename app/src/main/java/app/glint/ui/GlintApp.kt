package app.glint.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.glint.engine.DownloadManager
import app.glint.engine.Links
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.glint.R
import app.glint.ui.icons.GlintIcons
import app.glint.ui.screens.CommandsScreen
import app.glint.ui.screens.DownloadsScreen
import app.glint.ui.screens.LibraryScreen
import app.glint.ui.screens.SettingsScreen
import app.glint.ui.theme.GlassBackdrop
import app.glint.ui.theme.GlassScene
import app.glint.ui.theme.LocalLiveGlassBackdrop
import app.glint.ui.theme.rememberGlassSceneState
import app.glint.ui.theme.liquidGlass

enum class Tab(
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    Downloads(R.string.tab_downloads, GlintIcons.Download),
    Library(R.string.tab_library, GlintIcons.Library),
    Commands(R.string.tab_commands, GlintIcons.Commands),
    Settings(R.string.tab_settings, GlintIcons.Settings),
}

private val IslandHeight = 66.dp

/**
 * The whole app: pages over the theme's artwork, a floating island, and the new download sheet.
 * Links shared from other apps open in [app.glint.ShareActivity] instead, over that app.
 */
@Composable
fun GlintApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.Downloads) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetUrl by rememberSaveable { mutableStateOf("") }
    // Each opening starts a fresh sheet, even when one is already showing.
    var sheetSession by rememberSaveable { mutableIntStateOf(0) }
    // A saved command to run, when the sheet was opened from the Commands tab.
    var sheetCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    // The copied link the + button last put in the sheet, so the same link is not offered twice.
    var offeredLink by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openSheet(url: String, commandId: String? = null) {
        sheetUrl = url
        sheetCommandId = commandId
        sheetSession++
        sheetOpen = true
    }

    fun clipboardLink(): String {
        val text = clipboard.getText()?.text.orEmpty()
        return Links.first(text) ?: text.trim()
    }

    /** A link copied since the last time, for the + button; otherwise the sheet opens empty. */
    fun newCopiedLink(): String {
        val link = Links.first(clipboard.getText()?.text.orEmpty())
        if (link == null || link == offeredLink) return ""
        offeredLink = link
        return link
    }

    BackHandler(enabled = sheetOpen) { sheetOpen = false }
    BackHandler(enabled = !sheetOpen && tab != Tab.Downloads) { tab = Tab.Downloads }

    GlassBackdrop(modifier = Modifier.fillMaxSize()) {
        val bottomInset = IslandHeight + 20.dp
        val scene = rememberGlassSceneState()

        GlassScene(state = scene, modifier = Modifier.fillMaxSize()) {
            when (tab) {
                Tab.Downloads -> DownloadsScreen(bottomInset)
                Tab.Library -> LibraryScreen(bottomInset)
                Tab.Commands -> CommandsScreen(bottomInset, onRun = { openSheet(clipboardLink(), it.id) })
                Tab.Settings -> SettingsScreen(bottomInset)
            }
        }

        CompositionLocalProvider(LocalLiveGlassBackdrop provides scene) {
            val addInteraction = remember { MutableInteractionSource() }
            Row(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Island(modifier = Modifier.weight(1f), selected = tab, onSelect = { tab = it })
                Box(
                    modifier =
                        Modifier.size(IslandHeight)
                            .liquidGlass(
                                CircleShape,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                interactionSource = addInteraction,
                            )
                            .clickable(interactionSource = addInteraction, indication = null, role = Role.Button) {
                                openSheet(newCopiedLink())
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = GlintIcons.Plus,
                        contentDescription = stringResource(R.string.new_download),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = sheetOpen,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(160)),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val closeLabel = stringResource(R.string.close)
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .background(Color(0x78040509))
                                .semantics { contentDescription = closeLabel }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    sheetOpen = false
                                })
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        key(sheetSession) {
                            NewDownloadSheet(
                                initialUrl = sheetUrl,
                                initialCommandId = sheetCommandId,
                                onClose = { sheetOpen = false },
                                onDownload = { requests ->
                                    askForNotifications()
                                    DownloadManager.enqueue(requests)
                                    sheetOpen = false
                                    tab = Tab.Downloads
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Island(modifier: Modifier, selected: Tab, onSelect: (Tab) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .height(IslandHeight)
                .liquidGlass(CircleShape, interactionSource = interaction)
                .padding(5.dp)
                .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            val container by
                animateColorAsState(
                    if (isSelected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                    label = "tab",
                )
            val rim by animateColorAsState(
                if (isSelected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                label = "tab rim",
            )
            val content by animateColorAsState(
                if (isSelected) Color.White else SecondaryText,
                label = "tab content",
            )
            Column(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(listOf(container, container.copy(alpha = container.alpha * 0.3f))))
                        .border(0.5.dp, rim, CircleShape)
                        .selectable(
                            selected = isSelected,
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(tab) },
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(tab.label),
                    color = content,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
