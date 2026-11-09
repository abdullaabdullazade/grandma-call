package az.zeng

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ui {
    private val palette = intArrayOf(
        0xFF00A884.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFF4511E.toInt(),
        0xFF6D4C41.toInt(), 0xFF00897B.toInt(), 0xFFD81B60.toInt(), 0xFF3949AB.toInt(),
    )

    /** Round coloured avatar with the first letter, colour stable per user id. */
    fun avatar(v: TextView, id: String, name: String) {
        v.text = name.trim().take(1).uppercase()
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette[Math.floorMod(id.hashCode(), palette.size)])
        }
    }

    /** Grey circle with a people glyph for group calls in the log. */
    fun groupAvatar(v: TextView) {
        v.text = "👥"
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF8696A0.toInt()) }
    }

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** 0:42 / 12:05 / 1:02:33 */
    fun clock(sec: Long): String {
        val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "3 dəq 12 san", "1 saat 4 dəq" */
    fun duration(sec: Long): String {
        val (uh, um, us) = Triple(L.s(R.string.u_h), L.s(R.string.u_m), L.s(R.string.u_s))
        if (sec <= 0) return "0 $us"
        val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
        return listOfNotNull(
            if (h > 0) "$h $uh" else null,
            if (m > 0) "$m $um" else null,
            if (h == 0L && s > 0) "$s $us" else null,
        ).joinToString(" ")
    }

    private val time = SimpleDateFormat("HH:mm", Locale.US)
    private val date = SimpleDateFormat("dd.MM.yyyy", Locale.US)

    /** "Bu gün 14:05", "Dünən 09:12", "03.11.2026 18:40" */
    fun whenText(unix: Long): String {
        val ms = unix * 1000
        val t = time.format(Date(ms))
        return when {
            DateUtils.isToday(ms) -> "${L.s(R.string.today)} $t"
            DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "${L.s(R.string.yesterday)} $t"
            else -> "${date.format(Date(ms))} $t"
        }
    }

    fun endText(reason: String) = L.s(when (reason) {
        "busy" -> R.string.end_busy
        "declined" -> R.string.end_declined
        "unavailable", "offline" -> R.string.end_unavailable
        "no_answer" -> R.string.end_no_answer
        "lost" -> R.string.end_lost
        "answered_elsewhere" -> R.string.end_elsewhere
        "failed" -> R.string.end_failed
        else -> R.string.end_default
    })

    fun statusText(status: String) = when (status) {
        "missed" -> L.s(R.string.st_missed)
        "declined" -> L.s(R.string.st_declined)
        "busy" -> L.s(R.string.st_busy)
        "unavailable" -> L.s(R.string.st_unavailable)
        else -> null
    }
}
