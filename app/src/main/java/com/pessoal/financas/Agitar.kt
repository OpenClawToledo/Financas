package com.pessoal.financas

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.sqrt

/** Carrega a moeda antes de qualquer tela, notificação ou serviço. */
class AppFinancas : Application() {
    override fun onCreate() {
        super.onCreate()
        Formato.codigo = Ajustes.moeda(this)
        Migracao.executar(this)
    }
}

/** Liga ou desliga o serviço que escuta o gesto de agitar, e o vigia que o religa. */
object Agitar {
    fun sincronizar(ctx: Context) {
        val i = Intent(ctx, ServicoAgitar::class.java)
        if (Ajustes.agitarAtivo(ctx)) {
            try { ctx.startForegroundService(i) } catch (e: Exception) { /* o sistema pode recusar em segundo plano */ }
            agendarVigia(ctx)
        } else {
            ctx.stopService(i)
            cancelarVigia(ctx)
            if (Ajustes.notifAtiva(ctx)) Notificacao.mostrar(ctx)
        }
    }

    private fun piVigia(ctx: Context) = android.app.PendingIntent.getBroadcast(
        ctx, 50, Intent(ctx, ReceptorVigia::class.java),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    /** A cada 15 minutos confirma que o serviço está vivo (o HyperOS pode encerrá-lo). */
    fun agendarVigia(ctx: Context) {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 15 * 60_000L, 15 * 60_000L, piVigia(ctx))
    }

    private fun cancelarVigia(ctx: Context) = ctx.getSystemService(android.app.AlarmManager::class.java).cancel(piVigia(ctx))

    /** Religa daqui a pouco: usado quando o app é removido da lista de recentes. */
    fun religarEmBreve(ctx: Context) {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        val pi = android.app.PendingIntent.getBroadcast(ctx, 51, Intent(ctx, ReceptorVigia::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val quando = android.os.SystemClock.elapsedRealtime() + 1500
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, pi)
        else am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, pi)
    }

    /** O Android só deixa religar em segundo plano se o app estiver sem restrição de bateria. */
    fun bateriaLivre(ctx: Context): Boolean =
        ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
}

class ReceptorVigia : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Ajustes.agitarAtivo(ctx)) Agitar.sincronizar(ctx)
        Atualizador.verificarEmSegundoPlano(ctx)
    }
}

/**
 * Serviço em primeiro plano: mantém a notificação fixa e escuta o acelerômetro.
 * Três picos de força em menos de 1 segundo = agitada intencional → abre o popup.
 */
class ServicoAgitar : Service(), SensorEventListener {
    private var sensores: SensorManager? = null
    private var limiar = 2.4f
    private val picos = ArrayDeque<Long>()
    private var ultimoDisparo = 0L
    private val relogio = android.os.Handler(android.os.Looper.getMainLooper())
    private val tique = object : Runnable {
        override fun run() {
            Nuvem.agendar(this@ServicoAgitar, 0)
            relogio.postDelayed(this, 15 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notificacao.construir(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notificacao.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notificacao.ID, n)
        }
        limiar = when (Ajustes.sensibilidade(this)) { 0 -> 3.0f; 2 -> 2.0f; else -> 2.4f }
        if (sensores == null) {
            sensores = getSystemService(SensorManager::class.java)
            sensores?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensores?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            // enquanto o serviço estiver ativo, sincroniza a cada 15 minutos (novidades do casal)
            relogio.postDelayed(tique, 15 * 60_000L)
        }
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0] / SensorManager.GRAVITY_EARTH
        val y = e.values[1] / SensorManager.GRAVITY_EARTH
        val z = e.values[2] / SensorManager.GRAVITY_EARTH
        val forca = sqrt(x * x + y * y + z * z)
        if (forca < limiar) return
        val t = System.currentTimeMillis()
        if (picos.isEmpty() || t - picos.last() > 150) picos.addLast(t)
        while (picos.isNotEmpty() && t - picos.first() > 1000) picos.removeFirst()
        if (picos.size >= 3 && t - ultimoDisparo > 3000) {
            ultimoDisparo = t
            picos.clear()
            vibrar()
            Notificacao.popup(this)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun vibrar() {
        try {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }

    /** Deslizou o app para fora dos recentes: pede para ser religado logo em seguida. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Ajustes.agitarAtivo(this)) Agitar.religarEmBreve(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (Ajustes.agitarAtivo(this)) Agitar.religarEmBreve(this)
        relogio.removeCallbacks(tique)
        sensores?.unregisterListener(this)
        sensores = null
        super.onDestroy()
    }
}
