package com.mandarin.bcu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mandarin.bcu.androidutil.StaticStore
import com.mandarin.bcu.androidutil.io.AContext
import com.mandarin.bcu.androidutil.io.DefineItf
import common.CommonStatic
import common.pack.Source
import common.pack.UserProfile
import common.util.anim.AnimCE
import common.util.anim.ImgCut

/**
 * [Editor] Image cut editor: where each body part sits on the sprite sheet.
 * Opened from the enemy / form editor's "Sprite & icons" section with the same extras as EnemyEditor.
 */
class ImgCutEditor : AppCompatActivity() {

    private lateinit var anim: AnimCE

    // Working copy; written back to the animation on Save
    private lateinit var cut: ImgCut
    private var selected = 0
    private var changed = false

    private lateinit var canvasView: CutView
    private lateinit var partSpinner: Spinner
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private lateinit var fx: EditText
    private lateinit var fy: EditText
    private lateinit var fw: EditText
    private lateinit var fh: EditText
    private lateinit var fname: EditText

    // Stops the field listeners from firing while we fill the fields in
    private var filling = false

    private var textColor = 0

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
        } catch (e: Exception) {
            Log.e("ImgCutEditor", "Failed to load animation", e)
            StaticStore.showShortMessage(this, R.string.editor_import_fail)
            finish()
            return
        }

        cut = anim.imgcut.clone()

        if (cut.n == 0) {
            cut.n = 1
            cut.cuts = arrayOf(intArrayOf(0, 0, 1, 1))
            cut.strs = arrayOf("")
        }

        textColor = StaticStore.getAttributeColor(this, R.attr.TextPrimary)

        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })
    }

    /** Same lookup as EnemyEditor: an enemy, or a unit form, in an editable pack. */
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

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(StaticStore.getAttributeColor(this, R.attr.backgroundPrimary))

        // Sprite sheet with part rectangles (takes the top part of the screen)
        val sprite = anim.num?.bimg() as? Bitmap

        canvasView = CutView(this, sprite)
        root.addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Controls below it
        val scroll = ScrollView(this)
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(8), dp(12), dp(8))
        scroll.addView(panel)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val help = TextView(this)
        help.text = getString(R.string.editor_ic_help)
        help.setTextColor(textColor)
        help.alpha = 0.7f
        panel.addView(help)

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ArrayList<String>())
        partSpinner = Spinner(this)
        partSpinner.adapter = spinnerAdapter
        partSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != selected) select(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        panel.addView(partSpinner)

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.HORIZONTAL
        fx = numberField(grid, "X")
        fy = numberField(grid, "Y")
        fw = numberField(grid, getString(R.string.editor_ic_w))
        fh = numberField(grid, getString(R.string.editor_ic_h))
        panel.addView(grid)

        val nameLabel = TextView(this)
        nameLabel.text = getString(R.string.editor_name)
        nameLabel.setTextColor(textColor)
        panel.addView(nameLabel)

        fname = EditText(this)
        fname.setSingleLine()
        fname.setTextColor(textColor)
        fname.addTextChangedListener(simpleWatcher {
            if (!filling) {
                cut.strs[selected] = fname.text.toString().replace(",", " ")
                changed = true
                refreshSpinnerLabels()
            }
        })
        panel.addView(fname)

        val buttons1 = LinearLayout(this)
        buttons1.orientation = LinearLayout.HORIZONTAL

        val add = Button(this)
        add.text = getString(R.string.editor_ic_add)
        add.isAllCaps = false
        add.setOnClickListener { addPart() }

        val remove = Button(this)
        remove.text = getString(R.string.editor_ic_remove)
        remove.isAllCaps = false
        remove.setOnClickListener { removeLastPart() }

        buttons1.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons1.addView(remove, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(buttons1)

        val buttons2 = LinearLayout(this)
        buttons2.orientation = LinearLayout.HORIZONTAL

        val cancel = Button(this)
        cancel.text = getString(R.string.main_file_cancel)
        cancel.setOnClickListener { confirmLeave() }

        val save = Button(this)
        save.text = getString(R.string.editor_save)
        save.setOnClickListener { save() }

        buttons2.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons2.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(buttons2)

        setContentView(root)

        refreshSpinnerLabels()
        select(0)
    }

    private fun simpleWatcher(onAfter: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onAfter()
    }

    private fun numberField(row: LinearLayout, label: String): EditText {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val l = TextView(this)
        l.text = label
        l.setTextColor(textColor)
        col.addView(l)

        val et = EditText(this)
        et.setSingleLine()
        et.setTextColor(textColor)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        et.addTextChangedListener(simpleWatcher { if (!filling) fieldsToRect() })
        col.addView(et)

        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return et
    }

    private fun refreshSpinnerLabels() {
        val labels = (0 until cut.n).map { i ->
            val nm = cut.strs.getOrNull(i)?.trim().orEmpty()
            if (nm.isEmpty()) getString(R.string.editor_ic_part).replace("_", i.toString())
            else getString(R.string.editor_ic_part).replace("_", i.toString()) + " – " + nm
        }

        spinnerAdapter.clear()
        spinnerAdapter.addAll(labels)
        spinnerAdapter.notifyDataSetChanged()

        if (selected < cut.n && partSpinner.selectedItemPosition != selected)
            partSpinner.setSelection(selected)
    }

    private fun select(i: Int) {
        selected = i.coerceIn(0, cut.n - 1)

        if (partSpinner.selectedItemPosition != selected)
            partSpinner.setSelection(selected)

        rectToFields()
        canvasView.invalidate()
    }

    private fun rectToFields() {
        val c = cut.cuts[selected]

        filling = true
        fx.setText(c[0].toString())
        fy.setText(c[1].toString())
        fw.setText(c[2].toString())
        fh.setText(c[3].toString())
        fname.setText(cut.strs.getOrNull(selected) ?: "")
        filling = false
    }

    private fun fieldsToRect() {
        val c = cut.cuts[selected]

        fx.text.toString().toIntOrNull()?.let { c[0] = maxOf(0, it) }
        fy.text.toString().toIntOrNull()?.let { c[1] = maxOf(0, it) }
        fw.text.toString().toIntOrNull()?.let { c[2] = maxOf(1, it) }
        fh.text.toString().toIntOrNull()?.let { c[3] = maxOf(1, it) }

        changed = true
        canvasView.invalidate()
    }

    /** New part: a small square in the top-left, added at the end so existing part numbers don't change. */
    private fun addPart() {
        cut.cuts = cut.cuts + intArrayOf(0, 0, 32, 32)
        cut.strs = cut.strs + ""
        cut.n = cut.cuts.size

        changed = true
        refreshSpinnerLabels()
        select(cut.n - 1)
    }

    /** Only the last part can be removed, and only if the model doesn't use it. */
    private fun removeLastPart() {
        if (cut.n <= 1) {
            StaticStore.showShortMessage(this, R.string.editor_ic_need_one)
            return
        }

        val last = cut.n - 1
        val usedBy = anim.mamodel?.parts?.count { it.size > 2 && it[2] == last } ?: 0

        if (usedBy > 0) {
            StaticStore.showShortMessage(this, getString(R.string.editor_ic_in_use).replace("_", usedBy.toString()))
            return
        }

        cut.cuts = cut.cuts.copyOf(last).requireNoNulls()
        cut.strs = cut.strs.copyOf(last).map { it ?: "" }.toTypedArray()
        cut.n = last

        changed = true
        refreshSpinnerLabels()
        select(minOf(selected, last - 1))
    }

    private fun save() {
        try {
            anim.imgcut.n = cut.n
            anim.imgcut.cuts = Array(cut.n) { cut.cuts[it].clone() }
            anim.imgcut.strs = cut.strs.clone()

            // Re-cut the sprite sheet with the new rectangles, then write the files
            anim.ICedited()
            Source.SourceAnimSaver(anim.id, anim).saveData()
        } catch (e: Exception) {
            Log.e("ImgCutEditor", "Failed to save", e)
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
            .setMessage(R.string.editor_ic_discard_msg)
            .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.main_file_cancel, null)
            .show()
    }

    /**
     * Draws the sprite sheet with every part's rectangle. Tap a part to select it,
     * drag it to move, drag the corner square to resize, drag empty space to pan,
     * pinch to zoom.
     */
    @SuppressLint("ViewConstructor")
    private inner class CutView(context: Context, private val sprite: Bitmap?) : View(context) {

        private var scale = 1f
        private var offX = 0f
        private var offY = 0f
        private var fitted = false

        private val rectPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(160, 255, 255, 255) }
        private val selPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(255, 64, 64) }
        private val handlePaint = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(255, 64, 64) }
        private val checker = Paint().apply { color = Color.rgb(90, 90, 90) }

        private val handleSize get() = dp(22).toFloat()

        private enum class Mode { NONE, MOVE, RESIZE, PAN }

        private var mode = Mode.NONE
        private var lastX = 0f
        private var lastY = 0f

        // Image-space position of the finger when a move/resize started, to avoid rounding drift
        private var startImgX = 0f
        private var startImgY = 0f
        private var startRect = IntArray(4)

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = d.scaleFactor
                val newScale = (scale * f).coerceIn(0.1f, 20f)
                val k = newScale / scale

                // Zoom around the fingers
                offX = d.focusX - (d.focusX - offX) * k
                offY = d.focusY - (d.focusY - offY) * k
                scale = newScale

                invalidate()
                return true
            }
        })

        private fun toImgX(x: Float) = (x - offX) / scale
        private fun toImgY(y: Float) = (y - offY) / scale

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (!fitted && width > 0 && sprite != null) {
                scale = minOf(width.toFloat() / sprite.width, height.toFloat() / sprite.height)
                offX = (width - sprite.width * scale) / 2
                offY = (height - sprite.height * scale) / 2
                fitted = true
            }

            canvas.save()
            canvas.translate(offX, offY)
            canvas.scale(scale, scale)

            if (sprite != null) {
                canvas.drawRect(0f, 0f, sprite.width.toFloat(), sprite.height.toFloat(), checker)
                canvas.drawBitmap(sprite, 0f, 0f, null)
            }

            canvas.restore()

            for (i in 0 until cut.n) {
                val r = screenRect(i)
                canvas.drawRect(r, if (i == selected) selPaint else rectPaint)
            }

            // Resize handle on the selected part's bottom-right corner
            val s = screenRect(selected)
            canvas.drawRect(s.right - handleSize / 2, s.bottom - handleSize / 2, s.right + handleSize / 2, s.bottom + handleSize / 2, handlePaint)
        }

        private fun screenRect(i: Int): RectF {
            val c = cut.cuts[i]
            return RectF(offX + c[0] * scale, offY + c[1] * scale, offX + (c[0] + c[2]) * scale, offY + (c[1] + c[3]) * scale)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)

            if (e.pointerCount > 1) {
                mode = Mode.NONE
                return true
            }

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                    startImgX = toImgX(e.x)
                    startImgY = toImgY(e.y)

                    val s = screenRect(selected)
                    val h = handleSize

                    mode = when {
                        e.x in (s.right - h)..(s.right + h) && e.y in (s.bottom - h)..(s.bottom + h) -> Mode.RESIZE
                        s.contains(e.x, e.y) -> Mode.MOVE
                        else -> {
                            // Tap on another part selects it (smallest one wins when they overlap)
                            val hit = (0 until cut.n).filter { screenRect(it).contains(e.x, e.y) }
                                .minByOrNull { cut.cuts[it][2] * cut.cuts[it][3] }

                            if (hit != null) {
                                select(hit)
                                Mode.MOVE
                            } else {
                                Mode.PAN
                            }
                        }
                    }

                    startRect = cut.cuts[selected].clone()
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = toImgX(e.x) - startImgX
                    val dy = toImgY(e.y) - startImgY
                    val c = cut.cuts[selected]

                    when (mode) {
                        Mode.MOVE -> {
                            c[0] = maxOf(0, startRect[0] + Math.round(dx))
                            c[1] = maxOf(0, startRect[1] + Math.round(dy))
                            changed = true
                            rectToFields()
                        }
                        Mode.RESIZE -> {
                            c[2] = maxOf(1, startRect[2] + Math.round(dx))
                            c[3] = maxOf(1, startRect[3] + Math.round(dy))
                            changed = true
                            rectToFields()
                        }
                        Mode.PAN -> {
                            offX += e.x - lastX
                            offY += e.y - lastY
                        }
                        Mode.NONE -> {}
                    }

                    lastX = e.x
                    lastY = e.y
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
            }

            return true
        }
    }
}
