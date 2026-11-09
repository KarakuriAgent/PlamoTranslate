package app.plamotranslate.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.ReadAloud
import app.plamotranslate.llm.Language

/*
 * 会話翻訳のカード（docs/04_会話翻訳.md「画面」）。対面のカード・1画面のカード・話している最中のカード。
 */

/** 対面で、そのカードを読む人。相手の面のカードは逆さに出し、相手の言語を上に出す。 */
internal enum class Reader { ME, PARTNER }

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
internal fun FaceCard(turn: Turn, reader: Reader) {
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
internal fun SharedCard(turn: Turn) {
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
internal fun LiveCard(live: LiveSpeech, reader: Reader?) {
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
