package app.plamotranslate.conversation

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.AppTheme
import app.plamotranslate.ModelState
import app.plamotranslate.ModelSwitch
import app.plamotranslate.ReadAloud
import app.plamotranslate.llm.Language

/**
 * 会話翻訳の画面（docs/04_会話翻訳.md「画面」）。
 * 対面：上下2分割で、上半分は相手向けに逆さに出す。端末を2人の間に置いて使う。
 * 1画面：2人で同じ一覧を見る。発話ごとに原文と訳を並べる。
 * 会話中は画面を点けたままにする。
 */
class ConversationActivity : ComponentActivity() {
    private val vm: ConversationViewModel by viewModels()
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening()
    }

    /** 保存先を選んでもらってから書くので、その間ログの中身を持っておく。 */
    private var logToSave = ""
    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write(logToSave.toByteArray(Charsets.UTF_8)) }
        }.onSuccess {
            Toast.makeText(this, "会話ログを保存しました", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "保存できませんでした：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val model by vm.model.collectAsStateWithLifecycle()
                ConversationScreen(
                    state = state,
                    model = model,
                    actions = ConversationActions(
                        start = ::startListening,
                        stop = vm::stop,
                        partnerMode = vm::setMode,
                        self = vm::setSelf,
                        viewMode = vm::setViewMode,
                        clear = vm::clear,
                        retry = vm::loadAsr,
                        shareLog = ::shareLog,
                        saveLog = ::saveLog,
                    ),
                )
                // デバッグ版だけ：adb から音声ファイルを流して確かめる（docs/04_会話翻訳.md「動作確認」）
                val wav = intent.getStringExtra(EXTRA_WAV)
                // 実際に話すときと同じく、翻訳モデルの準備が済んでから流す（途中訳も確かめるため）
                val ready = state.asr == AsrState.Ready && model is ModelState.Ready
                LaunchedEffect(ready, wav) {
                    if (wav != null && ready && isDebuggable()) vm.startFromWav(wav)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 画面を離れたらマイクと読み上げを止める（フォアグラウンドサービスはまだ無い）
        vm.stop()
        ReadAloud.stop()
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        vm.start()
    }

    /** 共有画面からほかのアプリ（メモ、メール、議事録を作るアプリなど）へテキストで送る。 */
    private fun shareLog() {
        val turns = vm.state.value.turns
        if (turns.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, ConversationLog.fileName(turns).removeSuffix(".md"))
            .putExtra(Intent.EXTRA_TEXT, ConversationLog.format(turns))
        startActivity(Intent.createChooser(send, "会話ログを送る"))
    }

    /** 保存先を選んでもらい、Markdownのファイルとして書く。 */
    private fun saveLog() {
        val turns = vm.state.value.turns
        if (turns.isEmpty()) return
        logToSave = ConversationLog.format(turns)
        saveLog.launch(ConversationLog.fileName(turns))
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        const val EXTRA_WAV = "wav"
    }
}

private class ConversationActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val partnerMode: (PartnerMode) -> Unit,
    val self: (Language) -> Unit,
    val viewMode: (ViewMode) -> Unit,
    val clear: () -> Unit,
    val retry: () -> Unit,
    val shareLog: () -> Unit,
    val saveLog: () -> Unit,
)

@Composable
private fun ConversationScreen(state: ConversationUiState, model: ModelState, actions: ConversationActions) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.viewMode) {
                ViewMode.FACE_TO_FACE -> {
                    // 相手向け：相手の言語を大きく、逆さに出す
                    PartnerHalf(state, Modifier.weight(1f).fillMaxWidth())
                    Controls(state, model, actions)
                    // 自分向け：自分の言語を大きく出す
                    FollowingList(state, Modifier.weight(1f).fillMaxWidth()) {
                        items(state.turns, key = { it.id }) { FaceCard(it, reader = Reader.ME) }
                        state.live?.let { item(key = LIVE_KEY) { LiveCard(it, Reader.ME) } }
                    }
                }
                ViewMode.SHARED -> {
                    FollowingList(state, Modifier.weight(1f).fillMaxWidth()) {
                        items(state.turns, key = { it.id }) { SharedCard(it) }
                        state.live?.let { item(key = LIVE_KEY) { LiveCard(it, reader = null) } }
                    }
                    Controls(state, model, actions)
                }
            }
        }
    }
}

private const val LIVE_KEY = "live"

/**
 * 相手向けの半分。一覧ごと180°回すと、指を離したときの勢い（フリング）の向きがずれて急に止まる
 * （2026-11-06、作者の指摘「手を離した瞬間にブレーキがかかる」）。一覧は回さず、各カードだけを回し、
 * 並びは下から上へ（reverseLayout）にする。相手から見ると上から下へ並び、指の動きと画面の動きが一致する。
 */
@Composable
private fun PartnerHalf(state: ConversationUiState, modifier: Modifier) {
    // 背景は自分側と同じ地の色にする。別の色にすると、カードの色と混ざって見分けにくかった（2026-11-06、作者の指摘）
    FollowingList(state, modifier, reverse = true) {
        items(state.turns, key = { it.id }) { Box(Modifier.rotate(180f)) { FaceCard(it, reader = Reader.PARTNER) } }
        state.live?.let { item(key = LIVE_KEY) { Box(Modifier.rotate(180f)) { LiveCard(it, Reader.PARTNER) } } }
    }
}

/**
 * 新しい発話が来たら末尾へ送る一覧。作者が読み返すために末尾から離れている間は動かさない
 * （訳が1トークン届くたびに末尾へ戻すと、スクロールの途中で引き戻される）。
 */
@Composable
private fun FollowingList(
    state: ConversationUiState,
    modifier: Modifier,
    reverse: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // スクロールが止まった時点で末尾にいれば、以後も新しい発話を追う
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    val count = state.turns.size + if (state.live != null) 1 else 0
    LaunchedEffect(count, state.turns.lastOrNull()?.translation?.length, state.live?.text, state.live?.translation) {
        if (follow && count > 0) listState.scrollToEnd(count)
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        reverseLayout = reverse,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private suspend fun LazyListState.scrollToEnd(count: Int) {
    // 最後のカードが伸びた分も見えるよう、最後のカードの末尾まで送る（大きすぎる値は末尾で止まる）
    scrollToItem(count - 1, scrollOffset = Int.MAX_VALUE / 4)
}

@Composable
private fun Controls(state: ConversationUiState, model: ModelState, actions: ConversationActions) {
    // 操作欄は地の色と分け、上下の線で会話の一覧と区切る（対面では2人の面の境目にもなる）
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val ready = state.asr == AsrState.Ready && model is ModelState.Ready
            if (state.listening) {
                OutlinedButton(onClick = actions.stop) { Text("停止") }
            } else {
                Button(onClick = actions.start, enabled = ready) { Text("開始") }
            }
            LevelMeter(state, Modifier.weight(1f))
            ViewMode.entries.forEach { mode ->
                FilterChip(selected = state.viewMode == mode, onClick = { actions.viewMode(mode) }, label = { Text(mode.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelfMenu(state.self, actions.self)
            PartnerMenu(state.mode, state.self, actions.partnerMode)
            LogMenu(enabled = state.turns.isNotEmpty(), actions)
            Spacer(Modifier.weight(1f))
            // 翻訳モデルをその場で切り替える（2026-11-06、作者の要望）
            ModelSwitch()
        }
        val status = when {
            state.asr is AsrState.Missing -> "音声認識のモデルがありません。トップ画面に戻ると出る「モデルの準備」でダウンロードしてください"
            state.asr is AsrState.Failed -> state.asr.message
            state.asr == AsrState.Loading -> "音声認識を準備中…"
            model is ModelState.Missing -> "翻訳モデルがありません。トップ画面に戻ると出る「モデルの準備」か、設定の画面でダウンロードしてください"
            model is ModelState.Failed -> model.message
            model is ModelState.Loading -> "翻訳モデルを準備中…"
            state.error != null -> state.error
            state.live != null -> "聞き取り中…（話し終わると訳が確定します）"
            state.listening -> "聞いています（${state.self.label} ⇄ ${state.partner.label}）"
            else -> "「開始」を押して話してください（${state.self.label} ⇄ ${state.partner.label}）"
        }
        Text(status, style = MaterialTheme.typography.labelMedium)
        if (state.asr is AsrState.Missing || state.asr is AsrState.Failed) {
            TextButton(onClick = actions.retry) { Text("読み直す") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 自分の言語の選択（2026-11-08、作者の要望で日本語以外も選べるようにした）。
 * 選べるのは音声認識が聞き分けられる言語だけ（[CONVERSATION_LANGUAGES]）。
 */
@Composable
private fun SelfMenu(self: Language, onSelect: (Language) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = MenuButtonPadding) { Text("自分：${PartnerMode(self).short()} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CONVERSATION_LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(if (lang == self) "✓ ${lang.conversationLabel}" else lang.conversationLabel) },
                    onClick = { open = false; onSelect(lang) },
                )
            }
        }
    }
}

/**
 * 相手の言語の選択。中国語を足して5つになり1行に並ばないので、チップからメニューにした（2026-11-08）。
 * 自分と同じ言語は出さない（[PartnerMode.choices]）。
 */
@Composable
private fun PartnerMenu(mode: PartnerMode, self: Language, onSelect: (PartnerMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = MenuButtonPadding) { Text("相手：${mode.short()} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PartnerMode.choices(self).forEach { m ->
                val label = m.label(self)
                DropdownMenuItem(
                    text = { Text(if (m == mode) "✓ $label" else label) },
                    onClick = { open = false; onSelect(m) },
                )
            }
        }
    }
}

/** 自分・相手・ログ・翻訳モデルを1行に並べるので、ボタンの左右の余白を詰める。 */
private val MenuButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

/** 会話ログの共有・保存と、画面の発話の消去。 */
@Composable
private fun LogMenu(enabled: Boolean, actions: ConversationActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("ログ ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("共有（ほかのアプリへ送る）") }, onClick = { open = false; actions.shareLog() })
            DropdownMenuItem(text = { Text("ファイルに保存") }, onClick = { open = false; actions.saveLog() })
            DropdownMenuItem(text = { Text("画面の発話を消去") }, onClick = { open = false; actions.clear() })
        }
    }
}

/** マイクの音量。話していると判定している間は色を変える。 */
@Composable
private fun LevelMeter(state: ConversationUiState, modifier: Modifier) {
    val level by animateFloatAsState(if (state.listening) state.level else 0f, label = "level")
    val color = if (state.live != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier.height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(level).background(color))
    }
}


private enum class Reader { ME, PARTNER }

/**
 * 会話のカードの文字の大きさ（2026-11-06、作者の指摘で見直し）。2人が注視して読むものなので、
 * 原文も訳文もしっかり大きくする。特に原文は、話した人が「自分の言葉がどう聞き取られたか」を確かめる大事な情報で、
 * 補足扱いの小さな文字では読めなかった。かかった時間などの内部の情報だけを小さくする。
 */
private object ConversationType {
    /** 読む人の言語で書かれた方（対面）、訳文（1画面）。 */
    val main = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)

    /** もう一方の言語（対面）、原文（1画面）。 */
    val sub = TextStyle(fontSize = 20.sp, lineHeight = 27.sp)
}

/** 誰の発話かで色を決める。対面の上下どちらの半分でも、同じ人の発話は同じ色にする。 */
@Composable
private fun speakerColors(mine: Boolean): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return if (mine) c.primaryContainer to c.onPrimaryContainer else c.tertiaryContainer to c.onTertiaryContainer
}

/** カードの見出し。相手向けの面では相手が日本語を読めないので、言語の略号だけにする。 */
private fun cardLabel(direction: Direction, reader: Reader?): String = when (reader) {
    Reader.PARTNER -> "${direction.source.code.substringBefore('-').uppercase()} → ${direction.target.code.substringBefore('-').uppercase()}"
    else -> "${if (direction.mine) "自分" else "相手"}・${direction.source.label} → ${direction.target.label}"
}

/** 対面で、その面を読む人の言語。自分の面は自分が話した・訳された言語、相手の面は相手のもの。 */
private fun readerLanguage(d: Direction, reader: Reader): Language =
    if (d.mine == (reader == Reader.ME)) d.source else d.target

/** 対面のカード。読む人の言語で書かれた方を上に大きく、もう一方をその下に出す。 */
@Composable
private fun FaceCard(turn: Turn, reader: Reader) {
    val d = turn.direction
    val readerLanguage = readerLanguage(d, reader)
    val originalFirst = d.source == readerLanguage
    val translation = turn.translation.ifEmpty { "…" }
    UtteranceCard(
        turn = turn,
        translationIsMain = !originalFirst,
        label = cardLabel(d, reader),
        mine = d.mine,
        right = d.mine == (reader == Reader.ME),
        main = if (originalFirst) turn.original else translation,
        mainPending = !originalFirst && !turn.translated,
        sub = if (originalFirst) translation else turn.original,
        subPending = originalFirst && !turn.translated,
        stats = if (reader == Reader.ME) turn.stats else "",
    )
}

/** 1画面のカード。原文の下に訳を大きく出す。 */
@Composable
private fun SharedCard(turn: Turn) {
    UtteranceCard(
        turn = turn,
        translationIsMain = true,
        label = cardLabel(turn.direction, null),
        mine = turn.direction.mine,
        right = turn.direction.mine,
        main = turn.translation.ifEmpty { "…" },
        mainPending = !turn.translated,
        sub = turn.original,
        subPending = false,
        stats = turn.stats,
        subFirst = true,
    )
}

/**
 * 確定したカード。見出し（誰が・何語から何語へ）、本文2つ、かかった時間を縦に並べる。
 * 色は話した人ごとに分け、背景と混ざらないよう地の色との差がはっきりした組み合わせにする。
 * 訳文をタップすると、訳した先の言語で読み上げる（2026-11-08、作者の要望。[ReadAloud]）。読んでいる間は見出しに🔊を出し、
 * もう一度タップすると止める。
 */
@Composable
private fun UtteranceCard(
    turn: Turn,
    translationIsMain: Boolean,
    label: String,
    mine: Boolean,
    right: Boolean,
    main: String,
    mainPending: Boolean,
    sub: String,
    subPending: Boolean,
    stats: String,
    subFirst: Boolean = false,
) {
    val (container, content) = speakerColors(mine)
    val pendingColor = content.copy(alpha = 0.45f)
    val context = LocalContext.current
    val speaking by ReadAloud.speaking.collectAsStateWithLifecycle()
    val key = "turn-${turn.id}"
    val speak = Modifier.clickable(enabled = turn.translated && turn.translation.isNotBlank()) {
        ReadAloud.toggle(context, key, turn.translation, turn.direction.target)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = container,
            contentColor = content,
        ) {
            SelectionContainer {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (speaking == key) "🔊 $label" else label,
                        style = MaterialTheme.typography.labelMedium,
                        color = content.copy(alpha = 0.7f),
                    )
                    val mainText = @Composable {
                        Text(
                            main,
                            style = ConversationType.main,
                            color = if (mainPending) pendingColor else content,
                            modifier = if (translationIsMain) speak else Modifier,
                        )
                    }
                    val subText = @Composable {
                        if (sub.isNotEmpty()) {
                            Text(
                                sub,
                                style = ConversationType.sub,
                                color = if (subPending) pendingColor else content.copy(alpha = 0.85f),
                                modifier = if (translationIsMain) Modifier else speak,
                            )
                        }
                    }
                    if (subFirst) { subText(); mainText() } else { mainText(); subText() }
                    if (stats.isNotEmpty()) {
                        Text(stats, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.55f))
                    }
                }
            }
        }
    }
}

/**
 * 話している最中の聞き取りと途中訳。2人が注視して読むものなので、確定したカードと同じ大きさ・濃さで出し、
 * 枠線で囲って「話し中（まだ変わる）」ことを見出しで示す。話し終わると確定したカードに置き換わる。
 */
@Composable
private fun LiveCard(live: LiveSpeech, reader: Reader?) {
    val d = live.direction
    val c = MaterialTheme.colorScheme
    val right = when {
        d == null -> reader != Reader.PARTNER
        reader == null -> d.mine
        else -> d.mine == (reader == Reader.ME)
    }
    val readerLanguage = if (d == null || reader == null) null else readerLanguage(d, reader)
    val original = live.text.ifEmpty { "…" }
    val translation = live.translation.ifEmpty { "…" }
    // 1画面は確定したカードと同じく「原文の下に訳」。対面は読む人の言語を上に
    val originalFirst = reader == null || d == null || d.source == readerLanguage
    val label = if (reader == Reader.PARTNER) "● LIVE" else "● 話し中"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = c.surfaceContainerHigh,
            contentColor = c.onSurface,
            border = BorderStroke(2.dp, c.primary),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = c.primary)
                if (reader == null) {
                    Text(original, style = ConversationType.sub)
                    Text(translation, style = ConversationType.main)
                } else {
                    Text(if (originalFirst) original else translation, style = ConversationType.main)
                    Text(if (originalFirst) translation else original, style = ConversationType.sub, color = c.onSurfaceVariant)
                }
            }
        }
    }
}
