package tv.nova.player.ui

import android.app.AlertDialog
import android.app.Dialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import tv.nova.player.R
import tv.nova.player.data.Playlist
import tv.nova.player.data.Store
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class PlaylistSetup(
    private val activity: HomeActivity,
    private val store: Store,
    private val onActivate: (Playlist) -> Unit,
    private val onEmpty: () -> Unit,
    private val onRefresh: (Playlist) -> Unit,
) {
    private var dialog: Dialog? = null
    private var list: ListView? = null
    private var details: TextView? = null
    private var qr: ImageView? = null
    private var pairingUrl: TextView? = null
    private var selectedIndex = 0
    private var server: PairingServer? = null

    fun show(prefillUrl: String? = null) {
        dismiss()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        content += text("Playlists", 24, true)
        val playlistList = ListView(activity).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190))
        }
        list = playlistList
        content += playlistList
        val info = text("", 14, false)
        details = info
        content += info
        content += row(
            button("Add URL") { showEditor(null) },
            button("Edit") { selected()?.let { showEditor(it) } },
            button("Delete") { confirmDelete() },
            button("Switch") { selected()?.let { store.setActive(it.id); onActivate(it); refreshList() } },
            button("Reload") { selected()?.let(onRefresh) },
        )
        content += row(
            button("Built-in presets") { showPresets() },
            button("Close") { dismiss() },
        )
        val pairingRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val image = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(174), dp(174))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Phone pairing QR code"
        }
        qr = image
        pairingRow += image
        val urlLabel = text("Starting phone pairing…\nThe server runs only while this setup screen is open.", 15, false).apply {
            setPadding(dp(16), dp(12), 0, 0)
        }
        pairingUrl = urlLabel
        pairingRow += urlLabel
        content += pairingRow
        val scroll = ScrollView(activity).apply { addView(content) }
        val newDialog = Dialog(activity).apply {
            setContentView(scroll)
            setOnShowListener { startPairing() }
            setOnDismissListener {
                server?.stop()
                server = null
            }
        }
        dialog = newDialog
        newDialog.show()
        newDialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        refreshList()
        if (!prefillUrl.isNullOrBlank()) showEditor(null, prefillUrl)
    }

    fun refreshList() {
        val values = store.playlists()
        val activeId = store.activePlaylistId()
        selectedIndex = selectedIndex.coerceIn(0, (values.size - 1).coerceAtLeast(0))
        list?.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_single_choice, values.map { item ->
            val marker = if (item.id == activeId) "● " else ""
            "$marker${item.name} — ${formatTime(item.lastLoaded)}"
        })
        list?.setItemChecked(values.indexOfFirst { it.id == activeId }.coerceAtLeast(0), values.isNotEmpty())
        list?.setOnItemClickListener { _, _, position, _ -> selectedIndex = position }
        details?.text = if (values.isEmpty()) {
            "No playlists. Add a URL, choose a preset, or scan the QR code with your phone."
        } else {
            "${values.size} saved • Active: ${store.activePlaylist()?.name ?: "none"}"
        }
    }

    fun dismiss() {
        server?.stop()
        server = null
        dialog?.dismiss()
        dialog = null
    }

    private fun showEditor(existing: Playlist?, prefillUrl: String? = null) {
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val name = input("Playlist name", existing?.name ?: if (prefillUrl != null) "Pushed playlist" else "My playlist")
        val url = input("M3U / M3U8 URL", existing?.url ?: prefillUrl.orEmpty(), InputType.TYPE_TEXT_VARIATION_URI)
        val userAgent = input("User-Agent (optional)", existing?.userAgent.orEmpty())
        val referer = input("Referer (optional)", existing?.referer.orEmpty(), InputType.TYPE_TEXT_VARIATION_URI)
        listOf("Name" to name, "Playlist URL" to url, "User-Agent" to userAgent, "Referer" to referer).forEach { (label, field) ->
            fields += text(label, 14, false)
            fields += field
        }
        val scroll = ScrollView(activity).apply { addView(fields) }
        val editor = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "Add playlist" else "Edit playlist")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .show()
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val playlistUrl = url.text.toString().trim()
            if (!playlistUrl.startsWith("http://", true) && !playlistUrl.startsWith("https://", true)) {
                url.error = "Enter an http:// or https:// URL"
                url.requestFocus()
                return@setOnClickListener
            }
            if (existing != null && existing.url != playlistUrl) store.cacheFile(existing).delete()
            val playlist = Playlist(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.text.toString().trim().ifBlank { "My playlist" },
                url = playlistUrl,
                userAgent = userAgent.text.toString().trim().ifBlank { null },
                referer = referer.text.toString().trim().ifBlank { null },
                lastLoaded = existing?.takeIf { it.url == playlistUrl }?.lastLoaded ?: 0L,
            )
            store.savePlaylist(playlist)
            store.setActive(playlist.id)
            editor.dismiss()
            refreshList()
            onActivate(playlist)
        }
    }

    private fun showPresets() {
        AlertDialog.Builder(activity)
            .setTitle("Optional iptv-org presets")
            .setItems(Playlist.presets.map { it.name }.toTypedArray()) { _, index ->
                val preset = Playlist.presets[index].copy(id = UUID.randomUUID().toString())
                store.savePlaylist(preset)
                store.setActive(preset.id)
                refreshList()
                onActivate(preset)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        val playlist = selected() ?: return
        val wasActive = playlist.id == store.activePlaylistId()
        AlertDialog.Builder(activity)
            .setTitle("Delete ${playlist.name}?")
            .setMessage("This removes the playlist and its local catalogue cache. Favourites are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.deletePlaylist(playlist.id)
                refreshList()
                if (wasActive) store.activePlaylist()?.let(onActivate) ?: onEmpty()
            }
            .show()
    }

    private fun startPairing() {
        val pairing = PairingServer { payload ->
            activity.runOnUiThread {
                val playlist = Playlist(
                    name = payload.name,
                    url = payload.url,
                    userAgent = payload.userAgent,
                    referer = payload.referer,
                )
                store.savePlaylist(playlist)
                store.setActive(playlist.id)
                refreshList()
                onActivate(playlist)
            }
        }
        server = pairing
        val url = pairing.start()
        pairingUrl?.text = if (url == null) {
            "Phone pairing is unavailable. Check Wi-Fi/LAN and port 8765, or type the URL on the TV."
        } else {
            "Scan with your phone, or open:\n$url\n\nOnly this temporary pairing session can add a playlist. The server stops when setup closes."
        }
        qr?.setImageBitmap(url?.let { runCatching { QrCode.render(it) }.getOrNull() })
    }

    private fun selected(): Playlist? = store.playlists().getOrNull(selectedIndex)
    private fun formatTime(value: Long): String = if (value == 0L) "not loaded yet" else
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

    private fun text(value: String, size: Int, bold: Boolean): TextView = TextView(activity).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(ContextCompat.getColor(activity, if (bold) R.color.text_primary else R.color.text_secondary))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun input(hint: String, value: String, variation: Int = InputType.TYPE_TEXT_VARIATION_NORMAL): EditText = EditText(activity).apply {
        this.hint = hint
        setText(value)
        setSelection(value.length)
        inputType = InputType.TYPE_CLASS_TEXT or variation
        setSingleLine(true)
        setBackgroundResource(R.drawable.bg_search)
        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        setHintTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(8) }
    }

    private fun button(label: String, action: () -> Unit): Button = Button(activity).apply {
        text = label
        isFocusable = true
        setOnClickListener { action() }
        setBackgroundResource(R.drawable.bg_button)
        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private operator fun LinearLayout.plusAssign(view: View) { addView(view) }
}
