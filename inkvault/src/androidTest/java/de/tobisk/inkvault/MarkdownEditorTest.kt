package de.tobisk.inkvault

import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import androidx.test.platform.app.InstrumentationRegistry
import de.tobisk.inkvault.ui.FormattedMarkdownEditor
import io.noties.markwon.core.spans.EmphasisSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import org.junit.Assert.*
import org.junit.Test

class MarkdownEditorTest {
    @Test fun formattingPreservesSourceAndUpdatesAfterEdits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val formatter = FormattedMarkdownEditor.create(instrumentation.targetContext)
            val source = "# Heading\n\n**Bold** and *italic* with [[Note|link]]\n\n```\n**literal**\n```\n"
            val text = SpannableStringBuilder(source)
            formatter.process(text)
            assertEquals(source, text.toString())
            assertTrue(text.getSpans(0, text.length, RelativeSizeSpan::class.java).isNotEmpty())
            assertEquals(1, text.getSpans(0, text.length, StrongEmphasisSpan::class.java).size)
            assertEquals(1, text.getSpans(0, text.length, EmphasisSpan::class.java).size)
            text.replace(0, text.length, "Plain text")
            formatter.process(text)
            assertEquals("Plain text", text.toString())
            assertTrue(text.getSpans(0, text.length, StrongEmphasisSpan::class.java).isEmpty())
            assertTrue(text.getSpans(0, text.length, RelativeSizeSpan::class.java).isEmpty())
        }
    }
}
