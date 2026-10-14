package app.ghostly.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.ghostly.core.model.Server
import app.ghostly.ui.countryName
import app.ghostly.ui.title
import app.ghostly.vpn.MainActivity
import app.ghostly.vpn.R
import app.ghostly.i18n.tr

/**
 * The ongoing VPN notification, shared by the Xray and mihomo services: header = core with a
 * running connection timer, body = live speed, expanded = session traffic and what is left of the
 * subscription. The server's country flag is the large icon (the core's logo when there is no flag).
 * With Xray the title is the server ("Финляндия · ⚡ Быстрый"). With Mihomo traffic goes through
 * the selectors (each may point at another country), so the title is the subscription itself.
 * Buttons: «Отключить», «Переподключить» and, with two or more favourites, «Следующий».
 */
internal object VpnNotification {

    const val CHANNEL = "vpn"
    private const val FAILURE_ID = 9  // 7 = Xray service, 8 = mihomo service

    enum class Core(val label: String, val logo: Int) {
        XRAY("Xray", R.drawable.ic_core_xray),
        MIHOMO("Mihomo", R.drawable.ic_core_mihomo),
    }

    class Stats(val up: Long, val down: Long, val upTotal: Long, val downTotal: Long)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("VPN-подключение"), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * [connectedAt] null means still connecting: no timer, [status] as the text and an indeterminate bar.
     * [subscriptionLine] is [app.ghostly.ui.Format.subscriptionLine]; [canSwitch] shows «Следующий».
     */
    fun build(
        context: Context,
        core: Core,
        server: String,
        subscription: String?,
        subscriptionLine: String?,
        connectedAt: Long?,
        stats: Stats?,
        stop: PendingIntent,
        canSwitch: Boolean,
        status: String? = null,
    ): Notification {
        val speed = stats?.let { "↓ ${speed(it.down)}   ↑ ${speed(it.up)}" }
        val text = status ?: speed ?: tr("Подключено")
        val bySubscription = core == Core.MIHOMO && !subscription.isNullOrBlank()
        val name = Server(id = "", name = server, protocol = "").title()
        val nameFlag = name.flag
        val title = when {
            bySubscription -> subscription!!
            server.isBlank() -> context.getString(R.string.app_name)
            else -> listOfNotNull(nameFlag?.let { countryName(it) }, name.title, name.subtitle).joinToString(" · ")
        }
        val flag = nameFlag?.takeIf { !bySubscription }
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ghost)
            .setColor(ACCENT)
            .setLargeIcon(flag?.let { flagBadge(context, it) } ?: coreBadge(context, core))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(core.label)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (connectedAt != null) {
            b.setWhen(connectedAt).setShowWhen(true).setUsesChronometer(true)
            val lines = listOfNotNull(
                text,
                stats?.let { tr("За сессию ↓ {0}  ·  ↑ {1}", bytes(it.downTotal), bytes(it.upTotal)) },
                subscriptionLine?.takeIf { !bySubscription || it != subscription },
            )
            b.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            b.addAction(0, tr("Отключить"), stop)
            b.addAction(0, tr("Переподключить"), NotificationActions.intent(context, NotificationActions.RECONNECT))
            if (canSwitch) b.addAction(0, tr("Следующий"), NotificationActions.intent(context, NotificationActions.NEXT))
        } else {
            // Connecting: a thin running bar instead of a frozen line; stopping stays possible.
            b.setProgress(0, 0, true)
            b.addAction(0, tr("Отменить"), stop)
        }
        return b.build()
    }

    /**
     * The tunnel went down on its own and the app gave up retrying: a quiet note in the shade with
     * «Подключить», so the user sees it is off without opening the app. Gone once the VPN is up or
     * turned off on purpose.
     */
    fun showFailure(context: Context, server: String?, reason: String) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ghost)
            .setColor(ACCENT)
            .setContentTitle(tr("VPN отключён"))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(reason, server).joinToString("\n")))
            .setContentIntent(openApp(context))
            .addAction(0, tr("Подключить"), NotificationActions.intent(context, NotificationActions.CONNECT))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(FAILURE_ID, n) }
    }

    fun clearFailure(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(FAILURE_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val ACCENT = 0xFFA88DFF.toInt()
    private val badges = HashMap<String, Bitmap>()

    private fun badgeSize(context: Context) = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(96)

    /** The app's violet gradient disc every badge sits on. */
    private fun disc(size: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                0xFF6A4BDB.toInt(), 0xFFA88DFF.toInt(), android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
        return bmp to canvas
    }

    /** The core's logo in white on the disc, drawn once per core. */
    private fun coreBadge(context: Context, core: Core): Bitmap = synchronized(badges) {
        badges.getOrPut(core.name) {
            val size = badgeSize(context)
            val (bmp, canvas) = disc(size)
            ContextCompat.getDrawable(context, core.logo)?.mutate()?.let { d ->
                val inset = (size * 0.22f).toInt()
                d.setBounds(inset, inset, size - inset, size - inset)
                d.draw(canvas)
            }
            bmp
        }
    }

    /** The server's flag emoji centred on the disc, drawn once per country. */
    private fun flagBadge(context: Context, flag: String): Bitmap = synchronized(badges) {
        badges.getOrPut(flag) {
            val size = badgeSize(context)
            val (bmp, canvas) = disc(size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 0.5f; textAlign = Paint.Align.CENTER }
            val fm = paint.fontMetrics
            canvas.drawText(flag, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint)
            bmp
        }
    }

    fun speed(bps: Long): String = when {
        bps < 1024 -> tr("{0} Б/с", bps)
        bps < 1024 * 1024 -> tr("{0} КБ/с", bps / 1024)
        else -> String.format(java.util.Locale.US, tr("%.1f МБ/с"), bps / 1048576.0)
    }

    fun bytes(n: Long): String = when {
        n < 1024 -> tr("{0} Б", n)
        n < 1024 * 1024 -> tr("{0} КБ", n / 1024)
        n < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, tr("%.1f МБ"), n / 1048576.0)
        else -> String.format(java.util.Locale.US, tr("%.2f ГБ"), n / 1073741824.0)
    }
}
