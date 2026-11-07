package com.botgram.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botgram.app.data.Repo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastScreen(onBack: () -> Unit) {
    val sess by Repo.session.collectAsState()
    val session = sess ?: return
    val chats by session.store.chats.collectAsState()
    val parse by Repo.parseMode.collectAsState()
    val eligible = remember(chats) { chats.filter { !it.left }.sortedBy { it.title.lowercase() } }
    val selected = remember { mutableStateMapOf<Long, Boolean>() }
    var text by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ids = eligible.filter { selected[it.id] == true }.map { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Broadcast") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("Message") },
                minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(), enabled = !running,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ids.size} of ${eligible.size} chats selected", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { eligible.forEach { selected[it.id] = true } }, enabled = !running) { Text("All") }
                TextButton(onClick = { selected.clear() }, enabled = !running) { Text("None") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(eligible, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !running) { selected[c.id] = selected[c.id] != true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected[c.id] == true, onCheckedChange = { selected[c.id] = it }, enabled = !running)
                        Text("${c.title} · ${c.type}")
                    }
                }
            }
            if (running || finished) {
                LinearProgressIndicator(
                    progress = { if (ids.isEmpty()) 0f else done.toFloat() / ids.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    (if (finished) "Finished: " else "Sending: ") + "$done sent, $failed failed",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                enabled = !running && text.isNotBlank() && ids.isNotEmpty(),
                onClick = {
                    running = true; finished = false; done = 0; failed = 0
                    val snapshot = ids.toList()
                    scope.launch {
                        Repo.broadcast(snapshot, text.trim(), parse) { d, f -> done = d; failed = f }
                        running = false
                        finished = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send to ${ids.size} chats") }
            Text(
                "Telegram limits bots to about 30 messages per second overall and 1 per second per chat. Messages are paced automatically.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
