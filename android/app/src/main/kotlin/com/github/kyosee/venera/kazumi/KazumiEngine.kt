package io.github.kyosee.venera.kazumi

import android.content.Context
import com.ryanheise.audioservice.AudioServicePlugin
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.embedding.engine.FlutterJNI

/**
 * Nagisaland suite：番剧分区（Kazumi）的 Flutter 引擎。
 *
 * audio_service 插件按 [AudioServicePlugin.getFlutterEngineId]（默认 "audio_service_engine"）在 FlutterEngineCache 里找
 * 「宿主引擎」：找不到会自己新建一个跑默认入口（漫画 VeneraX）的引擎，并把番剧标成 wrongEngineDetected，
 * 之后番剧调用后台播放服务全部报错（换集时释放播放器失败 → 卡死）。所以番剧引擎必须用这个 id 放进缓存。
 *
 * 漫画界面也注册了 audio_service：它先于番剧挂到界面上时同样会去建那个错误引擎。漫画界面创建前调用 [reserve]，
 * 先在这个 id 下放一个不跑 Dart、不注册插件的占位引擎；番剧引擎真正创建时替换并销毁它。
 */
object KazumiEngine {
    @JvmStatic
    val ID: String get() = AudioServicePlugin.getFlutterEngineId()

    private var placeholder: FlutterEngine? = null

    /** 缓存里的是不是占位引擎（还没真正建番剧引擎） */
    @JvmStatic
    fun isPlaceholder(e: FlutterEngine) = e === placeholder

    @JvmStatic
    @Synchronized
    fun reserve(context: Context) {
        io.github.kyosee.venera.suite.SuiteEngines.initLoader(context)
        if (FlutterEngineCache.getInstance().get(ID) != null) return
        val p = FlutterEngine(context.applicationContext, null as FlutterLoader?, FlutterJNI(), null as Array<String>?, false)
        placeholder = p
        FlutterEngineCache.getInstance().put(ID, p)
    }

    @JvmStatic
    @Synchronized
    fun get(context: Context): FlutterEngine {
        val cache = FlutterEngineCache.getInstance()
        val cached = cache.get(ID)
        if (cached != null && cached !== placeholder) return cached
        placeholder?.let {
            cache.remove(ID)
            try { it.destroy() } catch (_: Throwable) {}
            placeholder = null
        }
        // Nagisaland：从共享的 FlutterEngineGroup 建（见 SuiteEngines）
        val engine = io.github.kyosee.venera.suite.SuiteEngines.create(context, "kazumiMain", "番剧")
        cache.put(ID, engine)
        return engine
    }
}
