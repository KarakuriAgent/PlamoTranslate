package app.plamotranslate.conversation

import android.content.Context
import app.plamotranslate.llm.Language

/** 会話翻訳の設定（表示モード・自分の言語）。次に開いたときも同じにする。 */
internal class ConversationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("conversation", Context.MODE_PRIVATE)

    /** 表示モード。保存が無ければ null（画面の既定を使う）。 */
    var viewMode: ViewMode?
        get() = prefs.getString(KEY_VIEW_MODE, null)?.let { name -> ViewMode.entries.firstOrNull { it.name == name } }
        set(mode) = prefs.edit().putString(KEY_VIEW_MODE, mode?.name).apply()

    /**
     * 自分の言語。保存が無ければ null。選べる言語は音声認識モデルで変わるので、選べるかは読む側が確かめる
     * （[ConversationViewModel]。選べなければ日本語に戻して、保存も消す）。
     */
    var self: Language?
        get() = Language.ofCode(prefs.getString(KEY_SELF, null).orEmpty())
        set(language) = prefs.edit().putString(KEY_SELF, language?.code).apply()

    private companion object {
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_SELF = "self"
    }
}
