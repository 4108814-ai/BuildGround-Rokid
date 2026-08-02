package com.anezium.rokidbus.plugin.assistant

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The saved conversations, newest first.
 *
 * Everything the assistant heard and answered lives on this phone only, so this screen
 * is both the record and the eraser: tap a thread to read it back, delete one, or clear
 * the lot. Destructive actions always go through a confirm — a thread is the only copy.
 */
class AssistantConversationsActivity : Activity() {
    private val threadStore by lazy { AssistantThreadStore(applicationContext) }
    private val authStore by lazy { CodexAuthStore(applicationContext) }

    private lateinit var listColumn: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        renderThreads()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AssistantConversationsActivity,
                    if (authStore.keepPhotosInConversations()) {
                        "Saved on this phone only, never uploaded -- including the photos " +
                            "the assistant took. Delete a conversation to erase them."
                    } else {
                        "Saved on this phone only, never uploaded. Photos the assistant took " +
                            "are not kept -- a turn that used the camera is only marked."
                    },
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantConversationsActivity, 18))
            addView(listColumn, NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@AssistantConversationsActivity,
                    NexusPluginIcons.drawableFor("chat"),
                    "Conversations",
                    "Assistant history",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@AssistantConversationsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderThreads()
    }

    /** The store is a file read; never let it land on the frame that is drawing. */
    private fun renderThreads() {
        Thread {
            val threads = runCatching { threadStore.threads() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showThreads(threads)
            }
        }.start()
    }

    private fun showThreads(threads: List<AssistantThread>) {
        listColumn.removeAllViews()
        if (threads.isEmpty()) {
            listColumn.addView(
                NexusUi.card(this).apply {
                    addView(NexusUi.rowSub(this@AssistantConversationsActivity, EMPTY_HINT))
                },
                NexusUi.block(),
            )
            return
        }

        threads.forEachIndexed { index, thread ->
            if (index > 0) listColumn.addView(BusTheme.gap(this, 10))
            listColumn.addView(
                NexusUi.navCard(
                    this,
                    thread.title,
                    threadMeta(thread),
                ) { showThread(thread.id) },
                NexusUi.block(),
            )
        }

        listColumn.addView(BusTheme.gap(this, 22))
        listColumn.addView(
            NexusUi.outlinePillButton(this, "Delete all conversations").apply {
                NexusUi.stylePillAsDanger(this@AssistantConversationsActivity, this)
                setOnClickListener { confirmDeleteAll(threads.size) }
            },
            NexusUi.block(),
        )
    }

    private fun threadMeta(thread: AssistantThread): String {
        val turns = thread.messages.count { it.role == "user" }
        val questions = if (turns == 1) "1 question" else "$turns questions"
        val photos = thread.messages.count { it.hadPhoto }
        val photoPart = when (photos) {
            0 -> ""
            1 -> " · 1 photo"
            else -> " · $photos photos"
        }
        return "$questions$photoPart · ${relativeTime(thread.updatedAtMs)}"
    }

    private fun relativeTime(timestampMs: Long): String =
        DateUtils.getRelativeTimeSpanString(
            timestampMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()

    private fun showThread(threadId: String) {
        Thread {
            val thread = runCatching { threadStore.thread(threadId) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (thread == null) renderThreads() else showThreadDialog(thread)
            }
        }.start()
    }

    private fun showThreadDialog(thread: AssistantThread) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            thread.messages.forEachIndexed { index, message ->
                if (index > 0) addView(BusTheme.gap(this@AssistantConversationsActivity, 12))
                addView(
                    NexusUi.metaLabel(
                        this@AssistantConversationsActivity,
                        speakerLabel(message),
                        if (message.role == "user") NexusUi.INK3 else NexusUi.GREEN,
                    ),
                    NexusUi.block(),
                )
                addView(BusTheme.gap(this@AssistantConversationsActivity, 4))
                addView(
                    NexusUi.cardBody(this@AssistantConversationsActivity, message.text),
                    NexusUi.block(),
                )
                message.photoPath?.let { path ->
                    addView(BusTheme.gap(this@AssistantConversationsActivity, 8))
                    addView(thumbnailSlot(path), NexusUi.block())
                }
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantConversationsActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                16,
            )
            setPadding(
                NexusUi.dp(this@AssistantConversationsActivity, 18),
                NexusUi.dp(this@AssistantConversationsActivity, 18),
                NexusUi.dp(this@AssistantConversationsActivity, 18),
                NexusUi.dp(this@AssistantConversationsActivity, 14),
            )
            addView(NexusUi.cardTitle(this@AssistantConversationsActivity, thread.title))
            addView(BusTheme.gap(this@AssistantConversationsActivity, 2))
            addView(
                NexusUi.rowSub(
                    this@AssistantConversationsActivity,
                    threadMeta(thread),
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantConversationsActivity, 14))
            addView(
                ScrollView(this@AssistantConversationsActivity).apply {
                    isVerticalScrollBarEnabled = false
                    addView(transcript)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(BusTheme.gap(this@AssistantConversationsActivity, 14))
            addView(
                LinearLayout(this@AssistantConversationsActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantConversationsActivity, "Close").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(
                            this@AssistantConversationsActivity,
                            "Delete",
                            danger = true,
                        ).apply {
                            setOnClickListener {
                                dialog.dismiss()
                                confirmDeleteThread(thread)
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.7f).toInt(),
        )
    }

    private fun speakerLabel(message: AssistantThreadMessage): String {
        val speaker = if (message.role == "user") "YOU" else "ASSISTANT"
        return if (message.hadPhoto) "$speaker · PHOTO" else speaker
    }

    /**
     * The photo behind a turn, decoded off the UI thread and dropped into a slot
     * of fixed height so the transcript does not jump as the images land. A photo
     * that has since been erased simply leaves its slot empty: the turn keeps the
     * PHOTO marker on its speaker label, which stays the honest record.
     */
    private fun thumbnailSlot(photoPath: String): LinearLayout {
        val slot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            background = NexusUi.bordered(
                this@AssistantConversationsActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                10,
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                NexusUi.dp(this@AssistantConversationsActivity, THUMBNAIL_HEIGHT_DP),
            )
        }
        Thread {
            val bitmap = runCatching {
                threadStore.photoBytes(photoPath)?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || bitmap == null) return@runOnUiThread
                slot.addView(
                    ImageView(this).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }.start()
        return slot
    }

    private fun confirmDeleteThread(thread: AssistantThread) {
        confirm(
            title = "Delete this conversation",
            body = "\"${thread.title}\" is removed from this phone. This cannot be undone.",
            confirmLabel = "Delete",
        ) {
            mutate({ threadStore.deleteThread(thread.id) }, "Conversation deleted.")
        }
    }

    private fun confirmDeleteAll(count: Int) {
        val what = if (count == 1) "1 conversation" else "$count conversations"
        confirm(
            title = "Delete all conversations",
            body = "All $what are removed from this phone. This cannot be undone.",
            confirmLabel = "Delete all",
        ) {
            mutate({ threadStore.deleteAll() }, "Conversations deleted.")
        }
    }

    private fun mutate(
        change: () -> Unit,
        confirmation: String,
    ) {
        Thread {
            runCatching(change)
            val threads = runCatching { threadStore.threads() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showThreads(threads)
                toast(confirmation)
            }
        }.start()
    }

    private fun confirm(
        title: String,
        body: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantConversationsActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                16,
            )
            setPadding(
                NexusUi.dp(this@AssistantConversationsActivity, 18),
                NexusUi.dp(this@AssistantConversationsActivity, 18),
                NexusUi.dp(this@AssistantConversationsActivity, 18),
                NexusUi.dp(this@AssistantConversationsActivity, 14),
            )
            addView(NexusUi.cardTitle(this@AssistantConversationsActivity, title))
            addView(BusTheme.gap(this@AssistantConversationsActivity, 6))
            addView(
                NexusUi.cardBody(this@AssistantConversationsActivity, body),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantConversationsActivity, 14))
            addView(
                LinearLayout(this@AssistantConversationsActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantConversationsActivity, "Cancel").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(
                            this@AssistantConversationsActivity,
                            confirmLabel,
                            danger = true,
                        ).apply {
                            setOnClickListener {
                                dialog.dismiss()
                                onConfirm()
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val EMPTY_HINT =
            "No conversations yet. Ask the glasses something and it shows up here."
        const val THUMBNAIL_HEIGHT_DP = 160
    }
}
