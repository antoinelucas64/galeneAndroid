package fr.antoineassocies.galene.ui.components

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.model.ChatMessage
import fr.antoineassocies.galene.domain.model.Participant
import java.util.Date

/** Same sender within this delay: the bubble goes under the previous one, without a header. */
private const val GROUPING_MS = 60_000L

/**
 * Full-screen chat panel, as in Meet or Zoom on a phone: message list that follows new
 * messages, input field at the bottom above the keyboard. The call goes on underneath.
 *
 * Above the field, "To: …" picks between the whole group and one participant (private
 * message); tapping a private message received sets its sender as the recipient.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    selfId: String,
    canSend: Boolean,
    others: List<Participant>,
    recipient: Participant?,
    onSelectRecipient: (String?) -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var draft by rememberSaveable { mutableStateOf("") }
    val send = {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }

    val listState = rememberLazyListState()
    // Follow new messages, unless the user scrolled up to read older ones
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= messages.lastIndex - 1 || messages.last().source == selfId) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                ) {
                    RecipientPicker(others, recipient, onSelectRecipient, Modifier.padding(start = 12.dp, top = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (recipient == null) stringResource(R.string.chat_hint)
                                    else stringResource(R.string.chat_hint_private, recipient.username)
                                )
                            },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            enabled = canSend,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send,
                            ),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                        )
                        IconButton(onClick = send, enabled = canSend && draft.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.chat_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(messages) { i, message ->
                    // Reply privately: only to someone still in the group
                    val replyTo = message.source.takeIf { id ->
                        message.isPrivate && id != selfId && others.any { it.id == id }
                    }
                    ChatItem(
                        message, previous = messages.getOrNull(i - 1), mine = message.source == selfId,
                        onClick = replyTo?.let { { onSelectRecipient(it) } },
                    )
                }
            }
        }
    }
}

/** "To: Everyone ▾" — the whole group or one participant. */
@Composable
private fun RecipientPicker(
    others: List<Participant>,
    recipient: Participant?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val everyone = stringResource(R.string.chat_to_everyone)
    Box(modifier) {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(
                stringResource(R.string.chat_to, recipient?.username ?: everyone),
                style = MaterialTheme.typography.labelLarge,
                color = if (recipient != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(everyone) },
                leadingIcon = { Icon(Icons.Default.Groups, contentDescription = null) },
                trailingIcon = { if (recipient == null) Icon(Icons.Default.Check, contentDescription = null) },
                onClick = { expanded = false; onSelect(null) },
            )
            others.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.username) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    trailingIcon = { if (p.id == recipient?.id) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { expanded = false; onSelect(p.id) },
                )
            }
        }
    }
}

@Composable
private fun ChatItem(message: ChatMessage, previous: ChatMessage?, mine: Boolean, onClick: (() -> Unit)?) {
    if (message.kind == ChatMessage.Kind.NOTICE) {
        Notice(message)
        return
    }
    val name = if (mine) stringResource(R.string.label_me) else message.username ?: "?"
    val showHeader = previous == null || previous.kind == ChatMessage.Kind.NOTICE ||
        previous.source != message.source || message.time - previous.time > GROUPING_MS ||
        previous.isPrivate != message.isPrivate || previous.to != message.to

    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (showHeader) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append(name)
                        if (message.isPrivate) append(" · ").append(
                            message.to?.let { stringResource(R.string.chat_private_to, it) }
                                ?: stringResource(R.string.chat_private)
                        )
                        append(" · ").append(formatTime(message.time))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when {
                    mine -> MaterialTheme.colorScheme.primaryContainer
                    message.isPrivate -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            ) {
                val me = message.kind == ChatMessage.Kind.ME
                Text(
                    text = linkified(if (me) "* $name ${message.text}" else message.text),
                    fontStyle = if (me) FontStyle.Italic else FontStyle.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Server or operator information, centred like a system line. */
@Composable
private fun Notice(message: ChatMessage) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                text = linkified(listOfNotNull(message.username, message.text).joinToString(" : ")),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Last message received with the chat closed, over the videos for a few seconds, as
 * Meet and Zoom do. A tap opens the chat.
 */
@Composable
fun ChatPreview(message: ChatMessage?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Keeps the text during the exit animation (plain holder: no state written while composing)
    val last = remember { arrayOfNulls<ChatMessage>(1) }
    if (message != null) last[0] = message
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val m = last[0] ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(12.dp).widthIn(max = 420.dp).clickable(onClick = onClick),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    m.username ?: stringResource(R.string.chat_server),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(m.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun formatTime(time: Long): String {
    val context = LocalContext.current
    return remember(time) { DateFormat.getTimeFormat(context).format(Date(time)) }
}

private val URL_REGEX = Regex("""https?://[^\s<>"]*[^\s<>".,;:!?)\]'"]""")

/** Makes the web addresses of a message clickable (opened in the browser). */
@Composable
private fun linkified(text: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, color) {
        val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            var last = 0
            URL_REGEX.findAll(text).forEach { match ->
                append(text.substring(last, match.range.first))
                withLink(LinkAnnotation.Url(match.value, style)) { append(match.value) }
                last = match.range.last + 1
            }
            append(text.substring(last))
        }
    }
}
