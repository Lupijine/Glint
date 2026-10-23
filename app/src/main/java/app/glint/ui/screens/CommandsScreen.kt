package app.glint.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.glint.ui.theme.GlassAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glint.R
import app.glint.engine.CommandLine
import app.glint.engine.CommandStore
import app.glint.engine.CommandTemplate
import app.glint.ui.EmptyCard
import app.glint.ui.FieldLabel
import app.glint.ui.GlassPill
import app.glint.ui.GlassTextField
import app.glint.ui.SecondaryText
import app.glint.ui.icons.GlintIcons
import app.glint.ui.theme.glassSurface

private const val OptionsGuide = "https://github.com/yt-dlp/yt-dlp#usage-and-options"

/** Saved yt-dlp commands: create, edit, delete, and run one on a link. */
@Composable
fun CommandsScreen(bottomInset: Dp, onRun: (CommandTemplate) -> Unit) {
    val templates by CommandStore.templates.collectAsStateWithLifecycle()
    // The id being edited; a fresh id means a new template.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Page(
        title = stringResource(R.string.tab_commands),
        bottomInset = bottomInset,
        actions = {
            GlassPill(
                text = stringResource(R.string.new_command),
                icon = GlintIcons.Plus,
                onClick = { editingId = CommandStore.newId() },
            )
        },
    ) {
        item(key = "intro") {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.commands_intro),
                    color = SecondaryText,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
                Text(
                    text = stringResource(R.string.commands_guide),
                    modifier =
                        Modifier.clickable(role = Role.Button) {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(OptionsGuide))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: ActivityNotFoundException) {}
                        },
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (templates.isEmpty()) {
            item(key = "empty") {
                EmptyCard(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    icon = GlintIcons.Commands,
                    title = stringResource(R.string.empty_commands_title),
                    body = stringResource(R.string.empty_commands_body),
                )
            }
        } else {
            items(templates, key = { it.id }) { template ->
                CommandCard(
                    template = template,
                    onEdit = { editingId = template.id },
                    onRun = { onRun(template) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }

    editingId?.let { id ->
        CommandEditor(
            existing = templates.firstOrNull { it.id == id },
            id = id,
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun CommandCard(
    template: CommandTemplate,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.edit_command), onClick = onEdit)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = template.name.ifBlank { stringResource(R.string.untitled_command) },
            color = Color.White,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = template.args,
            color = SecondaryText,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassPill(text = stringResource(R.string.edit_command), onClick = onEdit)
            Spacer(Modifier.width(8.dp))
            GlassPill(
                text = stringResource(R.string.run_command),
                icon = GlintIcons.Download,
                tint = MaterialTheme.colorScheme.primary,
                onClick = onRun,
            )
        }
    }
}

@Composable
private fun CommandEditor(existing: CommandTemplate?, id: String, onDismiss: () -> Unit) {
    var name by rememberSaveable(id) { mutableStateOf(existing?.name.orEmpty()) }
    var args by rememberSaveable(id) { mutableStateOf(existing?.args.orEmpty()) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    val parsed = remember(args) { CommandLine.split(args) }
    val canSave = name.isNotBlank() && parsed.isNotEmpty()

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_command_title)) },
            text = { Text(stringResource(R.string.delete_command_body, existing.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        CommandStore.delete(existing.id)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error)
                    }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.new_command else R.string.edit_command))
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FieldLabel(stringResource(R.string.command_name))
                GlassTextField(value = name, onValueChange = { name = it }, placeholder = "MP3 320k")
                FieldLabel(stringResource(R.string.command_args))
                GlassTextField(
                    value = args,
                    onValueChange = { args = it },
                    placeholder = "-x --audio-format mp3",
                    singleLine = false,
                    monospace = true,
                )
                Text(
                    text = stringResource(R.string.command_args_hint),
                    color = SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                if (existing != null) {
                    GlassPill(
                        text = stringResource(R.string.delete_command),
                        icon = GlintIcons.Trash,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    CommandStore.save(CommandTemplate(id = id, name = name.trim(), args = args.trim()))
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
