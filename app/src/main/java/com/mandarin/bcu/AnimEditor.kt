package com.mandarin.bcu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mandarin.bcu.androidutil.StaticStore
import com.mandarin.bcu.androidutil.fakeandroid.CVGraphics
import com.mandarin.bcu.androidutil.io.AContext
import com.mandarin.bcu.androidutil.io.DefineItf
import common.CommonStatic
import common.pack.Source
import common.pack.UserProfile
import common.system.P
import common.util.anim.AnimCE
import common.util.anim.AnimU
import common.util.anim.EAnimD
import common.util.anim.MaAnim
import common.util.anim.Part

/**
 * [Editor] Animation editor, part 1: preview each move, scrub frames, and edit
 * existing keyframes (frame, value, easing) and track looping.
 * Opened from the enemy / form editor with the same extras as EnemyEditor.
 */
class AnimEditor : AppCompatActivity() {

    private lateinit var anim: AnimCE
    private lateinit var types: Array<AnimU.UType>

    /** Edited copies of each move, by its position in anim.anims. Only these get saved. */
    private val working = HashMap<Int, MaAnim>()

    private var animIndex = 0
    private var trackIndex = 0
    private var changed = false
    private var filling = false
    private var textColor = 0

    private lateinit var preview: AnimView
    private lateinit var playButton: Button
    private lateinit var seek: SeekBar
    private lateinit var frameText: TextView
    private lateinit var trackSpinner: Spinner
    private lateinit var trackAdapter: ArrayAdapter<String>
    private lateinit var loopField: EditText
    private lateinit var keysBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = getSharedPreferences(StaticStore.CONFIG, Context.MODE_PRIVATE)

        if (!shared.getBoolean("theme", false)) {
            setTheme(R.style.AppTheme_night)
        } else {
            setTheme(R.style.AppTheme_day)
        }

        DefineItf.check(this)
        AContext.check()
        (CommonStatic.ctx as AContext).updateActivity(this)

        val a = findAnim()

        if (a == null) {
            StaticStore.showShortMessage(this, R.string.editor_enemy_missing)
            finish()
            return
        }

        anim = a

        try {
            anim.check()
            types = anim.types
        } catch (e: Exception) {
            Log.e("AnimEditor", "Failed to load animation", e)
            StaticStore.showShortMessage(this, R.string.editor_import_fail)
            finish()
            return
        }

        if (anim.anims.isEmpty()) {
            StaticStore.showShortMessage(this, R.string.editor_an_none)
            finish()
            return
        }

        textColor = StaticStore.getAttributeColor(this, R.attr.TextPrimary)

        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })
    }

    override fun onPause() {
        super.onPause()

        if (::preview.isInitialized)
            preview.playing = false
    }

    private fun findAnim(): AnimCE? {
        val pack = intent.getStringExtra(EnemyEditor.EXTRA_PACK)?.let { UserProfile.getUserPack(it) } ?: return null

        if (!pack.editable)
            return null

        val a = if (intent.hasExtra(EnemyEditor.EXTRA_UNIT)) {
            val u = pack.units.getRaw(intent.getIntExtra(EnemyEditor.EXTRA_UNIT, -1)) ?: return null
            val fi = intent.getIntExtra(EnemyEditor.EXTRA_FORM, -1)
            if (fi !in u.forms.indices) return null
            u.forms[fi].anim
        } else {
            pack.enemies.getRaw(intent.getIntExtra(EnemyEditor.EXTRA_INDEX, -1))?.anim
        }

        val ce = a as? AnimCE ?: return null

        return if (ce.id.pack == pack.sid) ce else null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** The move being edited (a working copy, made the first time it's opened). */
    private fun ma(): MaAnim = working.getOrPut(animIndex) { anim.anims[animIndex].clone() }

    private fun track(): Part? = ma().parts.getOrNull(trackIndex)

    private fun animName(i: Int): String {
        val t = types.getOrNull(i) ?: return "#$i"
        return t.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun modName(m: Int): String {
        return getString(when (m) {
            0 -> R.string.editor_an_m_parent
            1 -> R.string.editor_an_m_id
            2 -> R.string.editor_an_m_image
            3 -> R.string.editor_an_m_layer
            4 -> R.string.editor_an_m_x
            5 -> R.string.editor_an_m_y
            6 -> R.string.editor_an_m_pivx
            7 -> R.string.editor_an_m_pivy
            8 -> R.string.editor_an_m_scale
            9 -> R.string.editor_an_m_scalex
            10 -> R.string.editor_an_m_scaley
            11 -> R.string.editor_an_m_angle
            12 -> R.string.editor_an_m_opacity
            13 -> R.string.editor_an_m_hflip
            14 -> R.string.editor_an_m_vflip
            else -> R.string.editor_an_m_other
        }).replace("_", m.toString())
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(StaticStore.getAttributeColor(this, R.attr.backgroundPrimary))

        preview = AnimView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Playback controls
        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.setPadding(dp(8), 0, dp(8), 0)

        playButton = Button(this)
        playButton.text = "▶"
        playButton.setOnClickListener {
            preview.playing = !preview.playing
            playButton.text = if (preview.playing) "⏸" else "▶"
            preview.invalidate()
        }
        controls.addView(playButton, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))

        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) preview.setFrame(progress.toFloat())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                preview.playing = false
                playButton.text = "▶"
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        controls.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        frameText = TextView(this)
        frameText.setTextColor(textColor)
        controls.addView(frameText, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(controls)

        val scroll = ScrollView(this)
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(4), dp(12), dp(8))
        scroll.addView(panel)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        label(panel, R.string.editor_an_move)
        val animSpinner = Spinner(this)
        animSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, anim.anims.indices.map { animName(it) })
        animSpinner.onItemSelectedListener = itemListener { pos ->
            if (pos != animIndex) {
                animIndex = pos
                trackIndex = 0
                refreshTracks()
                preview.rebuild(0f)
            }
        }
        panel.addView(animSpinner)

        label(panel, R.string.editor_an_track)
        trackAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ArrayList<String>())
        trackSpinner = Spinner(this)
        trackSpinner.adapter = trackAdapter
        trackSpinner.onItemSelectedListener = itemListener { pos ->
            if (pos != trackIndex) {
                trackIndex = pos
                showTrack()
            }
        }
        panel.addView(trackSpinner)

        label(panel, R.string.editor_an_loop)
        loopField = EditText(this)
        loopField.setSingleLine()
        loopField.setTextColor(textColor)
        loopField.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        loopField.addTextChangedListener(watcher {
            if (!filling) {
                val t = track() ?: return@watcher
                loopField.text.toString().toIntOrNull()?.takeIf { it != t.ints[2] && (it == -1 || it >= 1) }?.let {
                    t.ints[2] = it
                    edited()
                }
            }
        })
        panel.addView(loopField)

        val keysHeader = TextView(this)
        keysHeader.text = getString(R.string.editor_an_keys)
        keysHeader.setTypeface(null, android.graphics.Typeface.BOLD)
        keysHeader.setTextColor(textColor)
        keysHeader.setPadding(0, dp(12), 0, dp(4))
        panel.addView(keysHeader)

        val keysNote = TextView(this)
        keysNote.text = getString(R.string.editor_an_keys_note)
        keysNote.setTextColor(textColor)
        keysNote.alpha = 0.7f
        panel.addView(keysNote)

        keysBox = LinearLayout(this)
        keysBox.orientation = LinearLayout.VERTICAL
        panel.addView(keysBox)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.setPadding(0, dp(16), 0, 0)

        val cancel = Button(this)
        cancel.text = getString(R.string.main_file_cancel)
        cancel.setOnClickListener { confirmLeave() }

        val save = Button(this)
        save.text = getString(R.string.editor_save)
        save.setOnClickListener { save() }

        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(buttons)

        setContentView(root)

        refreshTracks()
        preview.rebuild(0f)
    }

    private fun label(panel: LinearLayout, res: Int) {
        val l = TextView(this)
        l.text = getString(res)
        l.setTextColor(textColor)
        l.setPadding(0, dp(6), 0, 0)
        panel.addView(l)
    }

    private fun watcher(onAfter: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onAfter()
    }

    private fun itemListener(onPick: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun trackLabel(i: Int, p: Part): String {
        val piece = p.ints[0]
        val pieceName = anim.mamodel.strs0.getOrNull(piece)?.trim().orEmpty()
        val who = getString(R.string.editor_mm_piece).replace("_", piece.toString()) + if (pieceName.isEmpty()) "" else " – $pieceName"
        return "${i + 1}. $who · ${modName(p.ints[1])} (${p.n})"
    }

    private fun refreshTracks() {
        val m = ma()

        trackAdapter.clear()
        trackAdapter.addAll(m.parts.mapIndexed { i, p -> trackLabel(i, p) })
        trackAdapter.notifyDataSetChanged()

        trackIndex = trackIndex.coerceIn(0, maxOf(0, m.parts.size - 1))

        if (trackSpinner.selectedItemPosition != trackIndex)
            trackSpinner.setSelection(trackIndex)

        showTrack()
    }

    /** Fill in the loop field and keyframe rows for the selected track. */
    private fun showTrack() {
        keysBox.removeAllViews()

        val t = track()

        filling = true
        loopField.setText(t?.ints?.get(2)?.toString() ?: "")
        loopField.isEnabled = t != null
        filling = false

        if (t == null) {
            val none = TextView(this)
            none.text = getString(R.string.editor_an_no_tracks)
            none.setTextColor(textColor)
            keysBox.addView(none)
            return
        }

        val easings = resources.getStringArray(R.array.editor_an_easings).toList()

        for (k in 0 until t.n) {
            // Each row keeps a reference to its keyframe, so re-sorting doesn't mix rows up
            val key = t.moves[k]

            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(8), dp(6), dp(8), dp(6))
            card.setBackgroundResource(R.drawable.cell_shape)

            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(4), 0, dp(4))

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            // Frames are shown as written in the file (track offset removed)
            numberCell(row, getString(R.string.editor_an_frame), (key[0] - t.off).toString()) { v ->
                if (v >= 0 && v + t.off != key[0]) {
                    key[0] = v + t.off
                    edited()
                }
            }

            numberCell(row, getString(R.string.editor_an_value), key[1].toString()) { v ->
                if (v != key[1]) {
                    key[1] = v
                    edited()
                }
            }

            card.addView(row)

            val row2 = LinearLayout(this)
            row2.orientation = LinearLayout.HORIZONTAL

            val easeSpinner = Spinner(this)
            easeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, easings)
            easeSpinner.setSelection(key[2].coerceIn(0, easings.size - 1))
            easeSpinner.onItemSelectedListener = itemListener { pos ->
                if (pos != key[2]) {
                    key[2] = pos
                    edited()
                }
            }
            row2.addView(easeSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            numberCell(row2, getString(R.string.editor_an_param), key[3].toString()) { v ->
                if (v != key[3]) {
                    key[3] = v
                    edited()
                }
            }

            card.addView(row2)

            val jump = Button(this)
            jump.text = getString(R.string.editor_an_jump)
            jump.isAllCaps = false
            jump.setOnClickListener {
                preview.playing = false
                playButton.text = "▶"
                preview.setFrame((key[0] - t.off).toFloat())
            }
            card.addView(jump)

            keysBox.addView(card, lp)
        }
    }

    private fun numberCell(row: LinearLayout, label: String, value: String, onValue: (Int) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        val l = TextView(this)
        l.text = label
        l.setTextColor(textColor)
        l.alpha = 0.8f
        box.addView(l)

        val et = EditText(this)
        et.setSingleLine()
        et.setTextColor(textColor)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        et.setText(value)
        et.addTextChangedListener(watcher {
            if (!filling) et.text.toString().toIntOrNull()?.let(onValue)
        })
        box.addView(et)

        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** After any change: keep keyframes in order, recompute lengths, and redraw at the same frame. */
    private fun edited() {
        changed = true

        val m = ma()

        for (p in m.parts) {
            java.util.Arrays.sort(p.moves) { a, b -> a[0].compareTo(b[0]) }
            p.validate()
        }

        m.validate()

        preview.rebuild(preview.frame)
    }

    private fun save() {
        try {
            for ((i, m) in working) {
                m.validate()
                anim.anims[i] = m
            }

            Source.SourceAnimSaver(anim.id, anim).saveData()
        } catch (e: Exception) {
            Log.e("AnimEditor", "Failed to save", e)
            StaticStore.showShortMessage(this, R.string.editor_save_fail)
            return
        }

        PackManagement.needReload = true
        changed = false

        StaticStore.showShortMessage(this, R.string.editor_saved)
        finish()
    }

    private fun confirmLeave() {
        if (!changed) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.editor_discard_title)
            .setMessage(R.string.editor_an_discard_msg)
            .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.main_file_cancel, null)
            .show()
    }

    /** Live preview of the move, drawn with BCU's own code. Two fingers pan and zoom. */
    @SuppressLint("ViewConstructor")
    private inner class AnimView(context: Context) : View(context) {

        private val colorPaint = Paint()
        private val bitmapPaint = Paint()
        private val night = !context.getSharedPreferences(StaticStore.CONFIG, Context.MODE_PRIVATE).getBoolean("theme", false)
        private val cv = CVGraphics(Canvas(), colorPaint, bitmapPaint, night)
        private val axisPaint = Paint().apply { color = Color.argb(90, 255, 64, 64); strokeWidth = 2f }

        private var eanim: EAnimD<AnimU.UType>? = null

        var frame = 0f
            private set

        var playing = false
            set(v) {
                field = v
                if (v) invalidate()
            }

        private var size = 1f
        private var offX = 0f
        private var offY = 0f
        private var lastX = 0f
        private var lastY = 0f

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                size = (size * d.scaleFactor).coerceIn(0.1f, 10f)
                invalidate()
                return true
            }
        })

        init {
            colorPaint.isFilterBitmap = true
        }

        private fun maxFrame(): Int = maxOf(1, ma().len)

        fun rebuild(at: Float) {
            try {
                eanim = EAnimD(anim, anim.mamodel, ma(), types.getOrElse(animIndex) { AnimU.UType.WALK })
            } catch (e: Exception) {
                Log.e("AnimEditor", "Preview failed", e)
                eanim = null
            }

            seek.max = maxFrame()
            setFrame(at)
        }

        fun setFrame(f: Float) {
            frame = f.coerceIn(0f, maxFrame().toFloat())

            try {
                eanim?.setTime(frame)
            } catch (e: Exception) {
                Log.e("AnimEditor", "setTime failed", e)
            }

            seek.progress = frame.toInt()
            frameText.text = getString(R.string.editor_an_frame_of).replace("_", "${frame.toInt()}/${maxFrame()}")

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f + offX
            val cy = height * 2f / 3f + offY

            canvas.drawLine(0f, cy, width.toFloat(), cy, axisPaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), axisPaint)

            val e = eanim ?: return

            try {
                cv.setCanvas(canvas)
                val p = P.newP(cx, cy)
                e.draw(cv, p, size)
                P.delete(p)
            } catch (ex: Exception) {
                Log.e("AnimEditor", "Draw failed", ex)
            }

            if (playing) {
                // About 30 frames per second, looping
                postDelayed({
                    if (playing) {
                        val next = frame + 1
                        setFrame(if (next > maxFrame()) 0f else next)
                    }
                }, 33)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaler.onTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x
                    lastY = ev.y
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastX = (ev.getX(0) + ev.getX(1)) / 2
                    lastY = (ev.getY(0) + ev.getY(1)) / 2
                }

                MotionEvent.ACTION_MOVE -> {
                    val mx = if (ev.pointerCount >= 2) (ev.getX(0) + ev.getX(1)) / 2 else ev.x
                    val my = if (ev.pointerCount >= 2) (ev.getY(0) + ev.getY(1)) / 2 else ev.y
                    offX += mx - lastX
                    offY += my - lastY
                    lastX = mx
                    lastY = my
                    invalidate()
                }
            }

            return true
        }
    }
}
