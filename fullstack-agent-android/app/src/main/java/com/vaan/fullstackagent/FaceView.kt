package com.vaan.fullstackagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.sin

class FaceView(context: Context) : View(context) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }
    var state = State.IDLE
        set(value) { field = value; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }
    private var t = 0f
    private val tick = object : Runnable { override fun run() { t += .08f; invalidate(); postDelayed(this, 33) } }
    init { post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height / 2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        val base = minOf(width, height) * .22f
        val pulse = when (state) {
            State.IDLE -> 1f + .04f * sin(t)
            State.LISTENING -> 1f + .12f * sin(t * 2f)
            State.THINKING -> 1f + .08f * sin(t * 3f)
            State.SPEAKING -> 1f + .18f * sin(t * 4f)
        }
        c.drawCircle(cx, cy, base * pulse, p)
        p.strokeWidth = 3f
        for (i in 1..3) c.drawCircle(cx, cy, base * (1f + i * .22f) + 8f * sin(t + i), p)
    }
}
