package app.plamotranslate.conversation

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.widget.Toast

/**
 * 会話ログの書き出し（操作の欄の「ログ」）。共有はほかのアプリ（メモ、メール、議事録を作るアプリなど）へテキストで送り、
 * 保存は保存先を選んでもらってMarkdownのファイルとして書く。中身の組み立ては [ConversationLog]。
 * 保存先を選ぶ画面の結果を受け取るため、Activityを作るとき（表示より前）に作る。
 */
internal class ConversationLogExport(
    private val activity: ComponentActivity,
    private val turns: () -> List<Turn>,
) {
    /** 保存先を選んでもらってから書くので、その間ログの中身を持っておく。 */
    private var pending = ""
    private val createDocument = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            activity.contentResolver.openOutputStream(uri)!!.use { it.write(pending.toByteArray(Charsets.UTF_8)) }
        }.onSuccess {
            Toast.makeText(activity, "会話ログを保存しました", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(activity, "保存できませんでした：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 共有画面からほかのアプリへテキストで送る。 */
    fun share() {
        val turns = turns()
        if (turns.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, ConversationLog.fileName(turns).removeSuffix(".md"))
            .putExtra(Intent.EXTRA_TEXT, ConversationLog.format(turns))
        activity.startActivity(Intent.createChooser(send, "会話ログを送る"))
    }

    /** 保存先を選んでもらい、Markdownのファイルとして書く。 */
    fun save() {
        val turns = turns()
        if (turns.isEmpty()) return
        pending = ConversationLog.format(turns)
        createDocument.launch(ConversationLog.fileName(turns))
    }
}
