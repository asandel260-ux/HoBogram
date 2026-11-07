package com.botgram.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botgram.app.data.Repo
import com.botgram.app.data.friendly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bulk-add chats: the Bot API cannot list a bot's chats, so you give it the ids it should look up. */
@Composable
fun ImportChatsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var summary by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                } catch (e: Exception) {
                    ""
                }
            }
            text = (text + "\n" + content).trim()
        }
    }

    val refs = Repo.parseChatRefs(text)

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Import chats") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (summary == null) {
                    Text(
                        "Telegram never tells a bot which chats it is in, so list the ones you want: chat IDs " +
                            "(e.g. -1001234567890), @usernames or t.me links, separated by spaces, commas or new lines. " +
                            "You can get IDs from your server logs or database. Each one is looked up and its membership checked.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, enabled = !running,
                        label = { Text("Chat IDs / @usernames") }, minLines = 4, maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { picker.launch("text/*") }, enabled = !running) { Text("Load from text / CSV file") }
                    Text("${refs.size} chats recognised", style = MaterialTheme.typography.labelMedium)
                    if (running) {
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("$done / $total", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Text(summary!!, modifier = Modifier.heightIn(max = 360.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (summary == null) {
                TextButton(
                    enabled = !running && refs.isNotEmpty(),
                    onClick = {
                        running = true; total = refs.size; done = 0
                        scope.launch {
                            val r = try {
                                Repo.verifyChats(refs) { d, t -> done = d; total = t }
                            } catch (e: Exception) {
                                null.also { summary = "Import stopped: " + e.friendly() }
                            }
                            if (r != null) {
                                summary = buildString {
                                    append("Added/updated ${r.ok} chats")
                                    if (r.inactive > 0) append(" (${r.inactive} where the bot is no longer a member)")
                                    append(".")
                                    if (r.failed.isNotEmpty()) {
                                        append("\n\nCould not add ${r.failed.size}:\n")
                                        append(r.failed.take(30).joinToString("\n"))
                                    }
                                }
                            }
                            running = false
                        }
                    },
                ) { Text("Import") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = { if (summary == null) TextButton(onClick = onDismiss, enabled = !running) { Text("Cancel") } },
    )
}
