package com.loom.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loom.studio.pipeline.LlmPipeline

/** Run screen body for LLMs: streaming chat, fully offline. */
@Composable
fun ChatBody(p: LlmPipeline, modifier: Modifier = Modifier) {
    val msgs by p.messages.collectAsState()
    val streaming by p.streaming.collectAsState()
    val generating by p.generating.collectAsState()
    val status by p.status.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, streaming.length / 40) {
        val n = msgs.size + (if (streaming.isNotEmpty()) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    Column(modifier.imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs.size) { i -> Bubble(msgs[i].role == "user", msgs[i].text) }
            if (streaming.isNotEmpty()) item { Bubble(false, streaming + " ▍") }
        }
        Text(status, color = Color(0xFF8A94A6), fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(4000) },
                modifier = Modifier.weight(1f), placeholder = { Text("Message (offline)") }, maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            if (generating) SecondaryButton("Stop") { p.stop() }
            else PrimaryButton("Send", enabled = input.isNotBlank() && p.ready) { p.send(input); input = "" }
        }
        Text("Clear chat", color = Loom.Cyan, fontSize = 12.sp,
            modifier = Modifier.clickable(enabled = !generating) { p.clear() }.padding(6.dp))
    }
}

@Composable
private fun Bubble(user: Boolean, text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            color = if (user) Color.White else Color(0xFFE6EAF0),
            fontSize = 15.sp,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (user) Loom.Blue else Color(0xFF1B2433))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
