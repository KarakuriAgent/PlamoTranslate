package app.plamotranslate.conversation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import app.plamotranslate.AppTheme
import app.plamotranslate.ModelState
import app.plamotranslate.ReadAloud
import app.plamotranslate.llm.Language
import kotlinx.coroutines.flow.drop

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

    private val logExport = ConversationLogExport(this) { vm.state.value.turns }

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
                        shareLog = logExport::share,
                        saveLog = logExport::save,
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

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        const val EXTRA_WAV = "wav"
    }
}

internal class ConversationActions(
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
 *
 * 対面と1画面を切り替えると、この一覧は作り直される。作り直した一覧は末尾（今話している発話）から始める
 * （作者の指摘「切り替えたタイミングで今話している内容が画面から消える」。以前は先頭から始まり、
 * 先頭にいることを「読み返し中」と取り違えて末尾へ追わなくなっていた）。
 */
@Composable
private fun FollowingList(
    state: ConversationUiState,
    modifier: Modifier,
    reverse: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val count = state.turns.size + if (state.live != null) 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (count - 1).coerceAtLeast(0))
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // スクロールが止まった時点で末尾にいれば、以後も新しい発話を追う。
        // 作った直後の「止まっている」は、まだ並べる前（先頭）の状態なので見ない
        snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
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
