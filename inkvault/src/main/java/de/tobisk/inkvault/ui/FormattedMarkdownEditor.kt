package de.tobisk.inkvault.ui

import android.content.Context
import android.text.Editable
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.editor.AbstractEditHandler
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.PersistedSpans
import io.noties.markwon.editor.handler.EmphasisEditHandler
import io.noties.markwon.editor.handler.StrongEmphasisEditHandler

/** Styles the original Markdown in place; saving never converts rendered text back to source. */
object FormattedMarkdownEditor {
    fun create(context: Context): MarkwonEditor = MarkwonEditor.builder(Markwon.create(context))
        .useEditHandler(StrongEmphasisEditHandler.create())
        .useEditHandler(EmphasisEditHandler())
        .useEditHandler(object : AbstractEditHandler<HeadingSpan>() {
            override fun markdownSpanType(): Class<HeadingSpan> = HeadingSpan::class.java

            override fun configurePersistedSpans(builder: PersistedSpans.Builder) {
                builder.persistSpan(RelativeSizeSpan::class.java) { RelativeSizeSpan(1.3f) }
            }

            override fun handleMarkdownSpan(spans: PersistedSpans, editable: Editable, input: String, span: HeadingSpan, spanStart: Int, spanTextLength: Int) {
                val end = input.indexOf('\n', spanStart).let { if (it < 0) input.length else it }
                editable.setSpan(spans.get(RelativeSizeSpan::class.java), spanStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        })
        .build()
}
