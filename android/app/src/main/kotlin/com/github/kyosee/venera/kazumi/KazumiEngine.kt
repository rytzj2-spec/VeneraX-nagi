package io.github.kyosee.venera.kazumi

import android.content.Context
import android.util.Log
import com.ryanheise.audioservice.AudioServicePlugin
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor

/**
 * Nagisaland suite：番剧分区（Kazumi）的 Flutter 引擎。
 *
 * audio_service 插件按 [AudioServicePlugin.getFlutterEngineId]（默认 "audio_service_engine"）在
 * FlutterEngineCache 里找宿主引擎；找不到就自己新建一个跑**默认入口**（也就是漫画分区 VeneraX）的引擎，
 * 并把番剧引擎标成 wrongEngineDetected —— 之后 audio_service 的调用全部抛错，换集时释放播放器失败卡死。
 * 所以：
 * - 番剧引擎用插件约定的 id 缓存；
 * - 漫画分区（它的引擎也注册了 audio_service 插件）在 super.onCreate 之前调用 [reserve]，
 *   先放一个不跑 Dart、不注册插件的占位引擎，免得插件替我们建一个跑漫画入口的引擎；
 *   番剧引擎真正创建时替换并销毁占位引擎。
 */
object KazumiEngine {
    private const val TAG = "KazumiEngine"

    val ID: String
        get() = AudioServicePlugin.getFlutterEngineId()

    private var placeholder: FlutterEngine? = null

    @JvmStatic
    @Synchronized
    fun reserve(context: Context) {
        val cache = FlutterEngineCache.getInstance()
        if (cache.contains(ID)) return
        try {
            val engine = FlutterEngine(context.applicationContext, null, false)
            placeholder = engine
            cache.put(ID, engine)
        } catch (e: Exception) {
            Log.w(TAG, "reserve failed", e)
        }
    }

    @JvmStatic
    @Synchronized
    fun get(context: Context): FlutterEngine {
        val cache = FlutterEngineCache.getInstance()
        cache.get(ID)?.let { if (it !== placeholder) return it }
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(context.applicationContext)
        loader.ensureInitializationComplete(context.applicationContext, null)
        val engine = FlutterEngine(context.applicationContext)
        engine.navigationChannel.setInitialRoute("/")
        engine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "kazumiMain")
        )
        cache.put(ID, engine)
        placeholder?.let { old ->
            placeholder = null
            try {
                old.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "destroy placeholder failed", e)
            }
        }
        return engine
    }
}
